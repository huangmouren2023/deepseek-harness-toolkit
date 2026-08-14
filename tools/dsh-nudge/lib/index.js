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
 * Anti-loop guards: one nudge per turn, a consecutive-nudge cap per agent,
 * and no nudge at all for user-initiated cancellation or disposal.
 */

import { randomUUID } from 'node:crypto'

export const name = '@dsh-external/dsh-nudge'

/** Maximum consecutive terminal failures we keep nudging about. */
const MAX_CONSECUTIVE_NUDGES = 3

/**
 * Per-agent nudge state. Keyed by agent id for the lifetime of the plugin.
 * @typedef {{ turn: number | undefined, consecutive: number }} NudgeState
 */

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
  /** @type {Map<string, import('./index.js').NudgeState>} */
  const states = new Map()

  const dispose = ctx.on('agent/request-error', async (payload, next) => {
    const { agent, turn, failure, signal } = payload
    // Downstream listeners (retry policy, compaction repair) own recovery first.
    const action = await next()
    if (action !== undefined && action.kind === 'retry') return action

    // Nobody retried: the turn is terminal. Nudge — unless we already did for
    // this turn or the agent has been failing too many times in a row.
    const state = states.get(agent.id) ?? { turn: undefined, consecutive: 0 }
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

    const text = renderPrompt(failure, interrupted, signal?.reason)
    ctx.logger.info(
      `dsh-nudge: poking agent "${agent.id}" after ${interrupted ? 'interruption' : 'request failure'} (turn ${turn})`,
    )
    agent.followup(nudgeMessage(text))

    state.turn = turn
    state.consecutive += 1
    states.set(agent.id, state)
    return undefined
  })

  ctx.effect(() => async () => {
    dispose()
    states.clear()
  }, 'dsh-nudge: remove listener and clear nudge state')
}
