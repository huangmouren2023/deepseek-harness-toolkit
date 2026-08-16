import { Context } from '@deepseek-ai/cordis'
import type { Agent } from '@deepseek-ai/dsh-agent'
import { createUserMessage } from '@deepseek-ai/dsh-llm'
import { Session, SessionId } from '@deepseek-ai/dsh-session'
import { createScope } from '@deepseek-ai/dsh-scope'
import SystemPrompt from '@deepseek-ai/dsh-system-prompt'
import ToolRuntime from '@deepseek-ai/dsh-tools'
import { describe, expect, it } from 'vitest'
import Router, { firstUserText, routeFor } from '@deepseek-ai/dsh-router-progressive'

function fakeAgent(text: string): Agent {
  const session = Session.create(SessionId(`router-${Math.random().toString(36).slice(2)}`), [{
    seq: 0,
    time: Date.now(),
    type: 'user/message',
    data: createUserMessage({
      content: [{ type: 'text', text }],
      source: { kind: 'user' },
    }),
    surfaceOp: 'append',
  }])
  return { id: session.id, options: { model: 'deepseek-v4-flash' }, session } as Agent
}

async function mount(agent: Agent): Promise<Context> {
  const ctx = new Context()
  await ctx.plugin(SystemPrompt, { persona: 'base persona' })
  await ctx.plugin(ToolRuntime)
  const scope = createScope(ctx, agent)
  await scope.ctx.plugin(Router)
  return ctx
}

function tool(name: string) {
  return {
    name,
    description: name,
    parameters: { type: 'object', properties: {} },
    output: { schema: { type: 'string' as const }, render: (_args: unknown, value: unknown) => [{ type: 'text' as const, text: String(value) }] },
    execute: async () => 'ok',
  }
}

describe('dsh-router-progressive', () => {
  it('selects web capability without a second classifier', () => {
    const route = routeFor('请搜索今天 DeepSeek Harness 的最新网页资料')
    expect(route.capabilities).toEqual(['web'])
    expect(route.tools).toEqual(['web_search', 'web_fetch'])
  })

  it('ignores injected plugin context when finding the first human request', () => {
    const agent = fakeAgent('请修复这个报错并运行测试')
    expect(firstUserText(agent.session)).toContain('修复')
  })

  it('finds a claimed first request before the loop appends user/message', () => {
    const session = Session.create(SessionId(`inbox-${Math.random().toString(36).slice(2)}`))
    session.append('agent/inbox/spliced', {
      target: 'next-turn',
      inserted: [createUserMessage({
        content: [{ type: 'text', text: '请搜索最新网页资料' }],
        source: { kind: 'user' },
      })],
    })
    expect(firstUserText(session)).toContain('搜索')
    expect(routeFor(firstUserText(session)).capabilities).toEqual(['web'])
  })

  it('filters prompt schemas and guidance to the selected capability package', async () => {
    const agent = fakeAgent('请搜索最新资料')
    const ctx = await mount(agent)
    for (const name of ['read', 'edit', 'write', 'glob', 'grep', 'pwsh', 'web_search', 'web_fetch', 'job_output']) {
      ctx.tools.register(tool(name))
    }
    ctx.systemPrompt.section({ name: 'tool:web_search', order: 10, text: 'web guidance' })
    ctx.systemPrompt.section({ name: 'tool:job_output', order: 11, text: 'job guidance' })
    const assembly = await ctx.systemPrompt.assemble({ scope: agent, agent })
    expect(assembly.tools.map(tool => tool.name).sort()).toEqual([
      'dev_router_mode', 'dev_router_status', 'edit', 'glob', 'grep', 'pwsh', 'read', 'web_fetch', 'web_search', 'write',
    ])
    expect(assembly.sections.map(section => section.name)).not.toContain('tool:job_output')
    expect(assembly.sections.map(section => section.name)).toContain('tool:web_search')
    expect(assembly.sections.find(section => section.name === 'router:route')?.text).toContain('capabilities=web')
  })

  it('replaces the Standard persona with the current-model identity anchor', async () => {
    const agent = fakeAgent('请修复这个报错')
    const ctx = await mount(agent)
    ctx.tools.register(tool('read'))

    const assembly = await ctx.systemPrompt.assemble({ scope: agent, agent })
    const persona = assembly.sections.find(section => section.name === 'deployment:persona')?.text ?? ''
    expect(persona).toContain('You are a helpful software engineer assistant.')
    expect(persona).toContain('You are currently the {{model}} model.')
    expect(persona).toContain('You operate within DeepSeek Harness.')
    expect(persona).not.toContain('You are a coding agent powered by')
  })

  it('removes unavailable fetch guidance when the final schema does not contain fetch', async () => {
    const agent = fakeAgent('请搜索最新资料')
    const ctx = await mount(agent)
    for (const name of ['read', 'edit', 'write', 'glob', 'grep', 'pwsh', 'web_search']) {
      ctx.tools.register(tool(name))
    }
    ctx.systemPrompt.section({
      name: 'tool:web_search',
      order: 10,
      text: 'Follow up with web_fetch when you need the full content of a specific result, and cite the relevant URLs as markdown links.',
    })

    const assembly = await ctx.systemPrompt.assemble({ scope: agent, agent })
    expect(assembly.tools.map(tool => tool.name)).not.toContain('web_fetch')
    const guidance = assembly.sections.find(section => section.name === 'tool:web_search')?.text ?? ''
    expect(guidance).toContain('returned source snippets')
    expect(guidance).not.toContain('web_fetch')
  })
})
