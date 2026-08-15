/**
 * dsh-nudge — poke the model when a task errors or is interrupted.
 *
 * Listens on the `agent/request-error` waterfall (every terminal model-request
 * failure or abort flows through it). Downstream listeners — the provider
 * retry policy first — get a chance to recover; only when nobody returns
 * `{ kind: 'retry' }` does the turn die, which is exactly the moment this
 * plugin steps in: it queues a follow-up user message so the model explains
 * the failure or resumes from the interruption instead of going silent.
 *
 * Why not followup() directly in the waterfall: DSH's driver treats a
 * terminal request error as the end of the activity — kick() swallows the
 * LlmError, sets the phase idle, and only re-wakes when `wakeRequested` was
 * latched (which happens only for aborts and maintenance, not for plain
 * errors). A followup() issued while the driver is still running therefore
 * parks the message in the inbox forever. This plugin defers the poke until
 * the driver has converged to idle (observed through `agent/status`), then
 * calls followup() — at which point wakeDriver() starts a fresh driver and
 * the nudge message is processed. A short fallback timer covers the case
 * where the idle transition is not observed.
 *
 * Anti-loop guards: one nudge per turn, a consecutive-nudge cap per agent,
 * and no nudge at all for user-initiated cancellation or disposal.
 */

import { randomUUID } from 'node:crypto'

export const name = '@dsh-external/dsh-nudge'

/**
 * Required service: the agents registry. Injecting it places this plugin's
 * listeners on the shared agent context (the same plane llm-retry uses), so
 * the agent-scoped `agent/request-error` and `agent/status` events actually
 * reach this plugin. Without it the listeners register on a context that
 * never sees scoped dispatch, and the plugin silently does nothing.
 */
export const inject = ['agents']

/** Maximum consecutive terminal failures we keep nudging about. */
const MAX_CONSECUTIVE_NUDGES = 3

/** Fallback delay before forcing a pending poke when idle is not observed. */
const POKE_FALLBACK_MS = 1500

/**
 * Create an identified user message exactly like `createUserMessage` would,
 * without importing the dsh-llm package: id, role, text content, and the
 * plain `{ kind: 'user' }` source are all this plugin needs.
 */
function nudgeMessage(text) {
  return {
    id: randomUUID(),
    role: 'user',
    content: [{ type: 'text', text }],
    source: { kind: 'user' },
  }
}

/**
 * Decide whether a cancellation cause deserves a nudge.
 * User-initiated stops and disposal are deliberate; parent interruption and
 * hook-driven cancellation are accidents the model should react to.
 */
function shouldNudgeForCancel(cause) {
  if (cause === undefined) return true // no recorded cause: treat as accidental
  switch (cause.kind) {
    case 'user':
    case 'disposed':
      return false
    case 'parent':
    case 'hook':
      return true
    default:
      return true
  }
}

/**
 * Render the nudge prompt for the failure.
 * @param {object} failure - the LlmFailure ({ message, code, status }).
 * @param {boolean} interrupted - whether the request was aborted mid-flight.
 * @param {unknown} cancelReason - the abort signal's reason, when available.
 */
function renderPrompt(failure, interrupted, cancelReason) {
  const detail = typeof failure?.message === 'string' && failure.message.length > 0
    ? failure.message
    : `code ${failure?.code ?? 'unknown'}`
  if (interrupted) {
    const causeText = cancelReason instanceof Error
      ? cancelReason.message
      : cancelReason === undefined ? 'unknown reason' : String(cancelReason)
    return `[系统通知] 你的上一个回合被中断了（${causeText}）。` +
      `当前任务并未完成。请简要确认进度，然后从中断处继续；如果无法继续，请明确说明卡点。`
  }
  return `[系统通知] 刚才的模型请求失败了：${detail}。` +
    `这不是任务本身的结论。请解释发生了什么，然后决定是重试还是换一种方式继续。`
}

export function apply(ctx) {
  /** @type {Map<string, { turn: number | undefined, consecutive: number, pending: boolean, text: string, timer: ReturnType<typeof setTimeout> | undefined }>} */
  const states = new Map()

  ctx.logger.info('dsh-nudge: plugin active, listeners armed (request-error + status)')

  /**
   * Deliver one pending poke now: follow up on the agent and clear pending.
   * The caller guarantees the driver is idle (status listener) or the
   * fallback timer has fired.
   */
  function poke(agent, state) {
    if (!state.pending) return
    state.pending = false
    if (state.timer !== undefined) {
      clearTimeout(state.timer)
      state.timer = undefined
    }
    ctx.logger.info(`dsh-nudge: poking agent "${agent.id}"`)
    agent.followup(nudgeMessage(state.text))
  }

  /** Arm a pending poke with the idle listener and a fallback timer. */
  function armPoke(agent, state) {
    state.pending = true
    state.timer = setTimeout(() => {
      // Fallback: if the idle transition was not observed in time, poke anyway.
      poke(agent, state)
    }, POKE_FALLBACK_MS)
    // Do not call poke() here: the driver is still converging; the
    // agent/status listener fires on the idle transition and pokes then.
  }

  const dispose = ctx.on('agent/request-error', async (payload, next) => {
    const { agent, turn, failure, signal } = payload
    // Downstream listeners (retry policy, compaction repair) own recovery first.
    const action = await next()
    if (action !== undefined && action.kind === 'retry') return action

    // Nobody retried: the turn is terminal. Decide whether to nudge — the
    // poke itself is deferred until the driver converges to idle.
    const state = states.get(agent.id) ?? { turn: undefined, consecutive: 0, pending: false, text: '', timer: undefined }
    if (state.turn === turn) return undefined
    if (state.consecutive >= MAX_CONSECUTIVE_NUDGES) {
      ctx.logger.warn(
        `dsh-nudge: agent "${agent.id}" hit ${state.consecutive} consecutive terminal failures; stopping nudges`,
      )
      return undefined
    }

    const interrupted = signal?.aborted === true
    if (interrupted && !shouldNudgeForCancel(signal.reason)) {
      // Deliberate user stop or disposal: respect it, no nudge.
      return undefined
    }

    state.turn = turn
    state.consecutive += 1
    state.text = renderPrompt(failure, interrupted, signal?.reason)
    ctx.logger.info(
      `dsh-nudge: queued poke for agent "${agent.id}" after ${interrupted ? 'interruption' : 'request failure'} (turn ${turn})`,
    )
    states.set(agent.id, state)
    armPoke(agent, state)

    return undefined
  })

  // The status listener is the primary delivery mechanism: when the agent
  // flips to idle and a poke is pending, send the follow-up now.
  const disposeStatus = ctx.on('agent/status', (payload) => {
    const { agent, status } = payload
    if (status !== 'idle') return
    const state = states.get(agent.id)
    if (state === undefined || !state.pending) return
    poke(agent, state)
  })

  ctx.effect(() => async () => {
    dispose()
    disposeStatus()
    for (const state of states.values()) {
      if (state.timer !== undefined) clearTimeout(state.timer)
    }
    states.clear()
  }, 'dsh-nudge: remove listeners and clear nudge state')
}
