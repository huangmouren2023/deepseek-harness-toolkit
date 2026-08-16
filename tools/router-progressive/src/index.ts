/**
 * Deterministic progressive routing for an agent preset.
 *
 * The first human message selects a small model-facing tool set. The selection
 * is derived from durable session state on every prompt assembly, so a later
 * request does not silently widen the catalog or require a second LLM router.
 *
 * @module @deepseek-ai/dsh-router-progressive
 */

import type { Context } from '@deepseek-ai/cordis'
import type { Agent } from '@deepseek-ai/dsh-agent'
import type { ContentBlock } from '@deepseek-ai/dsh-llm'
import type { Session } from '@deepseek-ai/dsh-session'
import { defineTool } from '@deepseek-ai/dsh-tools'
import type { ToolExecution } from '@deepseek-ai/dsh-tools'

/** Cordis plugin name. */
export const name = 'router-progressive'

/** Services used by the scoped router row. */
export const inject = ['systemPrompt', 'tools']

/** Identity anchor owned by this preset rather than the Standard prompt. */
export const ROUTER_PERSONA = [
  'You are a helpful software engineer assistant.',
  'You are currently the {{model}} model.',
  'You operate within DeepSeek Harness.',
].join('\n')

/** The coarse reasoning mode selected by the deterministic classifier. */
export type RouterMode = number | 'weak'

/** The stable model-facing mode bands. */
export type RouterBand = 'spec' | 'transition' | 'react' | 'weak'

/** A capability package selected from the first human message. */
export interface RouterCapability {
  readonly id: string
  readonly tools: readonly string[]
  readonly label: string
  readonly test: RegExp
}

/** The route decision exposed by `dev_router_status`. */
export interface RouterDecision {
  readonly mode: RouterMode
  readonly capabilities: readonly string[]
  readonly tools: readonly string[]
  readonly reason: string
  readonly confidence: 'heuristic'
}

const REACT_RE = /(开发|创建|写一个|生成|从零|做一个|网站|网页|构建|新项目|实现|做出|上线|落地|脚本|应用|build|create|develop|generate|implement|make a|new project)/i
const SPEC_RE = /(修复|修一个|调试|重构|维护|排查|报错|出错|崩溃|优化|审查|review|fix|debug|refactor|maintain|repair|broken|迁移|升级|兼容)/i

/** Tools present for every routed standard-plus agent. */
export const CORE_TOOLS = [
  'read',
  'edit',
  'write',
  'glob',
  'grep',
  'pwsh',
  'dev_router_status',
  'dev_router_mode',
] as const

const CAPABILITIES: readonly RouterCapability[] = [
  { id: 'web', test: /(今天|最新|价格|网页|网址|搜索|浏览|新闻|http:\/\/|https:\/\/|web|search)/i, tools: ['web_search', 'web_fetch'], label: '命中：网页、搜索或最新信息' },
  { id: 'jobs', test: /(后台运行|长时间任务|持续执行|稍后读取|background|long-running|job|定时)/i, tools: ['job_output', 'job_list', 'job_kill'], label: '命中：后台或长时间任务' },
  { id: 'skills', test: /(技能|能力包|说明|skill)/i, tools: ['skill'], label: '命中：skill 或能力包' },
  { id: 'planning', test: /(计划|规划|方案|先分析再做|设计方案|plan)/i, tools: ['exit_plan_mode'], label: '命中：计划、方案或设计' },
  { id: 'subagent', test: /(并行调查|独立上下文|复杂分工|子任务|子代理|subagent|delegate|delegation)/i, tools: ['subagent', 'subagent_fork', 'subagent_codex', 'subagent_claude_code', 'list_agents', 'send_message', 'interrupt_agent'], label: '命中：并行分工或 subagent' },
  { id: 'workflow', test: /(工作流|多阶段编排|multi-agent orchestration|workflow)/i, tools: ['workflow'], label: '命中：workflow 或多阶段编排' },
  { id: 'goal', test: /(长期目标|持续跟踪|目标管理|goal)/i, tools: ['get_goal', 'create_goal', 'update_goal'], label: '命中：goal 或持续目标' },
  { id: 'interaction', test: /(先问我|需要我选择|确认选项|ask_user)/i, tools: ['ask_user_question'], label: '命中：用户选择或确认' },
  { id: 'todo', test: /(待办|任务清单|todo)/i, tools: ['todo_write'], label: '命中：待办或 todo' },
]

const MANAGED_TOOLS = new Set([
  ...CORE_TOOLS,
  ...CAPABILITIES.flatMap(capability => capability.tools),
])

const WEB_FETCH_FOLLOW_UP_SENTENCE = 'Follow up with web_fetch when you need the full content of a specific result, and cite the relevant URLs as markdown links.'
const WEB_FETCH_TOKEN_RE = /\bweb_fetch\b/g

type WebAvailability = {
  isAvailable: (capability: 'search' | 'fetch') => boolean
}

const overrides = new Map<string, RouterMode>()

/** Clamp a numeric mode to the supported 0..1 range. */
export function clampMode(value: number): number {
  return Math.min(1, Math.max(0, value))
}

/** Convert a mode into the user-facing band used in prompt diagnostics. */
export function bandOf(mode: RouterMode): RouterBand {
  if (mode === 'weak') return 'weak'
  if (mode < 0.2) return 'spec'
  if (mode < 0.5) return 'transition'
  return 'react'
}

/** Classify build/fix intent without making a model request. */
export function classifyTask(text: string): RouterMode {
  const react = REACT_RE.test(text) ? 1 : 0
  const spec = SPEC_RE.test(text) ? 1 : 0
  if (react > spec) return 1
  if (spec > react) return 0
  return 'weak'
}

/** Extract text blocks from a durable user message. */
export function extractText(message: { readonly content: readonly ContentBlock[] } | undefined): string {
  return message?.content
    .map(block => block.type === 'text' ? block.text : '')
    .join(' ') ?? ''
}

/** Return the first direct human message, excluding injected plugin context. */
export function firstUserText(session: Session): string {
  for (const candidate of session.events) {
    if (candidate.type === 'user/message' && candidate.data.source.kind === 'user') {
      return extractText(candidate.data)
    }
    if (candidate.type === 'agent/inbox/spliced' && candidate.data.outcome !== 'canceled') {
      const message = candidate.data.inserted.find(item => item.source.kind === 'user')
      if (message !== undefined) return extractText(message)
    }
  }
  return ''
}

/** Build the deterministic capability decision for one first-turn text. */
export function routeFor(text: string): RouterDecision {
  const capabilities: string[] = []
  const tools: string[] = []
  const reasons: string[] = []
  for (const capability of CAPABILITIES) {
    if (!capability.test.test(text)) continue
    capabilities.push(capability.id)
    tools.push(...capability.tools)
    reasons.push(capability.label)
  }
  return {
    mode: classifyTask(text),
    capabilities,
    tools: [...new Set(tools)],
    reason: reasons.join('；') || '未命中能力关键词，保持核心工具集',
    confidence: 'heuristic',
  }
}

/** Return the progressive model-facing tool set for a route. */
export function progressiveToolsFor(route: RouterDecision): ReadonlySet<string> {
  return new Set([...CORE_TOOLS, ...route.tools])
}

/** Parse a user-facing mode override. */
export function parseMode(value: unknown): RouterMode | 'auto' | null {
  if (value === undefined || value === null) return null
  const token = String(value).trim().toLowerCase()
  if (token === 'auto') return 'auto'
  if (token === 'weak' || token === 'router') return 'weak'
  if (token === 'spec' || token === 'spec-lean') return 0
  if (token === 'balanced' || token === 'mixed') return 0.3
  if (token === 'react' || token === 'react-lean') return 1
  const number = Number(token)
  if (!Number.isFinite(number)) return null
  return clampMode(token.includes('.') ? number : number / 100)
}

function modeFor(agent: Agent): RouterMode {
  return overrides.get(String(agent.session.id)) ?? routeFor(firstUserText(agent.session)).mode
}

function allowedToolsFor(agent: Agent, web: WebAvailability | undefined): ReadonlySet<string> {
  const route = routeFor(firstUserText(agent.session))
  const allowed = progressiveToolsFor(route) as Set<string>
  if (web !== undefined && route.capabilities.includes('web')) {
    if (!web.isAvailable('search')) allowed.delete('web_search')
    if (!web.isAvailable('fetch')) allowed.delete('web_fetch')
  }
  return allowed
}

/** Keep prompt prose consistent with the final model-visible tool set. */
function sanitizeUnavailableToolMentions(text: string, allowed: ReadonlySet<string>): string {
  if (allowed.has('web_fetch')) return text
  return text
    .replace(WEB_FETCH_FOLLOW_UP_SENTENCE, 'Use the returned source snippets when available, and cite the relevant URLs as markdown links.')
    .replace(WEB_FETCH_TOKEN_RE, 'the returned source snippets')
}

function routeNote(route: RouterDecision, mode: RouterMode): string {
  return `路由：mode=${bandOf(mode)}；reason=${route.reason}；capabilities=${route.capabilities.join(',') || 'core'}；confidence=${route.confidence}`
}

function routeGuidance(mode: RouterMode): string {
  if (bandOf(mode) !== 'weak') return ''
  return '路由提示：先判断这是“直接制作”还是“检查修复”；制作类任务直接落地并验证，修复类任务先检查再修改。'
}

function routeToolGuard(execution: Readonly<ToolExecution>, web: WebAvailability | undefined): string | undefined {
  if (execution.agent === undefined || !MANAGED_TOOLS.has(execution.name)) return undefined
  const allowed = allowedToolsFor(execution.agent, web)
  return allowed.has(execution.name)
    ? undefined
    : `progressive router：工具“${execution.name}”未被当前首条用户请求选中`
}

function statusText(agent: Agent, web: WebAvailability | undefined): string {
  const route = routeFor(firstUserText(agent.session))
  const mode = modeFor(agent)
  const selected = [...allowedToolsFor(agent, web)].join(', ')
  return [
    `mode=${mode} (band=${bandOf(mode)})`,
    `reason=${route.reason}`,
    `confidence=${route.confidence}`,
    `capabilities=[${route.capabilities.join(', ')}]`,
    `selected=[${selected}]`,
    `override=${overrides.has(String(agent.session.id)) ? 'yes' : 'no'}`,
  ].join('\n')
}

/** Install the progressive router in an agent-preset scope. */
export function apply(ctx: Context): void {
  const web = ctx.get('web') as WebAvailability | undefined
  ctx.on('system-prompt/assemble', async (_assembly, context, next) => {
    const agent = context.agent
    if (agent === undefined) return next()
    const route = routeFor(firstUserText(agent.session))
    const mode = modeFor(agent)
    const transformed = await next()
    const composedNames = new Set(transformed.tools.map(tool => tool.name))
    const allowed = new Set([...allowedToolsFor(agent, web)].filter(name => composedNames.has(name)))
    const sections = transformed.sections
      // Replace the first/global identity anchor, then remove the later
      // Standard persona so two competing identity blocks cannot remain.
      .map(section => section.name === 'harness:identity'
        ? { ...section, text: ROUTER_PERSONA }
        : section)
      .filter(section => section.name !== 'deployment:persona')
      .filter(section => !section.name.startsWith('tool:') || allowed.has(section.name.slice('tool:'.length)))
      .filter(section => section.name !== 'router:route' && section.name !== 'router:guidance')
      .map(section => ({
        ...section,
        text: sanitizeUnavailableToolMentions(section.text, allowed),
      }))
    sections.push({ name: 'router:route', text: routeNote(route, mode) })
    const guidance = routeGuidance(mode)
    if (guidance !== '') sections.push({ name: 'router:guidance', text: guidance })
    return {
      ...transformed,
      sections,
      tools: transformed.tools.filter(tool => tool.name === 'run_code' || allowed.has(tool.name)),
    }
  })

  ctx.tools.guard(execution => routeToolGuard(execution, web))
  ctx.on('session/disposed', session => { overrides.delete(String(session.id)) })

  ctx.tools.register(defineTool({
    name: 'dev_router_status',
    description: '显示当前会话的确定性路由模式、原因、能力包和选中的工具。',
    parameters: {},
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    execute: async (_args, execution) => execution.agent === undefined ? 'no agent session' : statusText(execution.agent, web),
  }))

  ctx.tools.register(defineTool({
    name: 'dev_router_mode',
    description: '设置本会话下一轮使用的路由模式：auto / weak / spec / balanced / react / 0-100。',
    parameters: {
      mode: { type: 'string', required: true, description: 'auto / weak / spec / balanced / react / 0-100' },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    execute: async (args, execution) => {
      if (execution.agent === undefined) return 'no agent session'
      const parsed = parseMode(args.mode)
      if (parsed === null) return `invalid mode "${args.mode}"`
      const id = String(execution.agent.session.id)
      if (parsed === 'auto') overrides.delete(id)
      else overrides.set(id, parsed)
      const current = modeFor(execution.agent)
      return `mode=${current} (band=${bandOf(current)})；下一轮请求生效`
    },
  }))
}

export default Object.assign(apply, { inject })
