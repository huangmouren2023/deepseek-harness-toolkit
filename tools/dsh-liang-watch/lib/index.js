/**
 * dsh-liang-watch — host side.
 *
 * 梁强度雷达：把「滑动变祖器」的社区投票/时间线接入 DSH。
 *
 * 1. Proxy routes on the DSH webServer:
 *      GET  /_dsh/liang/score    → upstream /api/score
 *      GET  /_dsh/liang/timeline → upstream /api/timeline
 *      POST /_dsh/liang/vote     → upstream /api/vote
 *    The upstream worker enforces a strict Origin allowlist; the proxy sends
 *    the whitelisted GitHub Pages origin so DSH's own origin (127.0.0.1:3080)
 *    works without being on that list. Server-to-server has no CORS.
 *
 * 2. Three model-facing tools:
 *      liang_score     — community strength score + stage + vote counts
 *      liang_timeline  — daily score snapshots
 *      liang_vote      — cast one vote (cooldown 3h per fingerprint)
 */

import { defineTool } from '@deepseek-ai/dsh-tools'
import { execSync } from 'node:child_process'
import { EnvHttpProxyAgent, fetch as proxiedFetch } from 'undici'

export const name = '@dsh-external/dsh-liang-watch'

export const inject = ['tools']

/** Upstream worker API base. */
const UPSTREAM = 'https://liang-intensity-api.sebastiiiiiiii.workers.dev'
/** Origin the upstream allowlist accepts (GitHub Pages site). */
const ALLOWED_ORIGIN = 'https://lichtspektrum.github.io'

const PROXY_PREFIX = '/_dsh/liang'

const renderJson = (_args, value) => [{
  type: 'text',
  text: JSON.stringify(value, null, 2),
}]

/**
 * Fetch through the system HTTP(S) proxy when one is configured. This worker
 * domain is only reachable via the local proxy (Clash etc.); a bare undici
 * fetch connects directly and times out. EnvHttpProxyAgent honors
 * HTTPS_PROXY/HTTP_PROXY; resolveProxy() seeds them first.
 */
function proxyDispatcher() {
  return new EnvHttpProxyAgent()
}

/**
 * Resolve the outbound proxy. Priority:
 *   1. explicit HTTPS_PROXY/HTTP_PROXY already in the environment;
 *   2. the Windows registry proxy (best-effort);
 *   3. the conventional local Clash port (127.0.0.1:7890) as a last resort,
 *      since this upstream worker is only reachable through the local proxy.
 */
function resolveProxy() {
  if (process.env.HTTPS_PROXY || process.env.HTTP_PROXY) return
  const registered = windowsSystemProxy()
  const proxy = registered ?? 'http://127.0.0.1:7890'
  process.env.HTTPS_PROXY = proxy
  process.env.HTTP_PROXY = proxy
}

/** Read the Windows Internet Settings proxy (registry), if enabled. */
function windowsSystemProxy() {
  try {
    const out = execSync(
      'reg query "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings" /v ProxyEnable /v ProxyServer',
      { encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] },
    )
    const enabled = /ProxyEnable\s+REG_DWORD\s+0x1/i.test(out)
    const match = /ProxyServer\s+REG_SZ\s+(\S+)/i.exec(out)
    if (enabled && match !== null) {
      const server = match[1]
      return server.startsWith('http') ? server : `http://${server}`
    }
  } catch {
    // Registry read failure: fall through to the default local proxy.
  }
  return null
}

/** Forward a request to the upstream worker with the whitelisted origin. */
async function upstream(path, options = {}) {
  resolveProxy()
  const headers = new Headers(options.headers ?? {})
  headers.set('Origin', ALLOWED_ORIGIN)
  headers.set('User-Agent', 'dsh-liang-watch/0.1')
  const response = await proxiedFetch(`${UPSTREAM}${path}`, { ...options, headers, dispatcher: proxyDispatcher() })
  const text = await response.text()
  let body
  try {
    body = JSON.parse(text)
  } catch {
    body = { error: `upstream non-JSON (${response.status})` }
  }
  return { status: response.status, body }
}

/** Anonymous voter fingerprint: stable per DSH installation. */
function voterFingerprint() {
  // Reuse the harness anonymous identity if present, else a stable hash.
  const id = process.env.DSH_ANON_ID ?? process.env.USERPROFILE ?? 'dsh-liang-watcher'
  return `dsh:${Buffer.from(id).toString('base64').slice(0, 32)}`
}

async function proxyHandler(req, res) {
  const respond = (value, status = 200) => {
    res.writeHead(status, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(value))
  }
  try {
    const url = new URL(req.url ?? '/', 'http://localhost')
    const path = url.pathname.slice(PROXY_PREFIX.length)

    if (path === '/score' && req.method === 'GET') {
      const { status, body } = await upstream('/api/score')
      respond(body, status)
      return
    }
    if (path === '/timeline' && req.method === 'GET') {
      const from = url.searchParams.get('from')
      const qs = from === null ? '' : `?from=${encodeURIComponent(from)}`
      const { status, body } = await upstream(`/api/timeline${qs}`)
      respond(body, status)
      return
    }
    if (path === '/vote' && req.method === 'POST') {
      let payload
      try {
        payload = JSON.parse(await readBody(req))
      } catch {
        respond({ ok: false, error: 'invalid-json' }, 400)
        return
      }
      const position = Number(payload?.position)
      const fingerprint = typeof payload?.fingerprint === 'string' ? payload.fingerprint : voterFingerprint()
      const { status, body } = await upstream('/api/vote', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ position, fingerprint }),
      })
      respond(body, status)
      return
    }
    respond({ ok: false, error: 'not-found' }, 404)
  } catch (error) {
    respond({ ok: false, error: error instanceof Error ? error.message : String(error) }, 500)
  }
}

async function readBody(req) {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  return Buffer.concat(chunks).toString('utf8')
}

/** Build the three model-facing tools. */
function createTools() {
  return [
    defineTool({
      name: 'liang_score',
      description: '查询「滑动变祖器」社区对梁的强度评分：当前分数（-15 到 +15）、阶段（小难梁/牢梁/梁子/梁圣/梁神/梁祖）、投票人数、今日投票、正负中立计数。纯查询，不投票。',
      parameters: {},
      output: {
        schema: {
          type: 'object', additionalProperties: true,
          properties: {
            score: { type: 'number', required: true },
            stage: { type: 'string', required: true },
            voterCount: { type: 'integer', required: true },
          },
        },
        render: renderJson,
      },
      async execute() {
        const { status, body } = await upstream('/api/score')
        if (status !== 200) throw new Error(`liang score upstream ${status}: ${JSON.stringify(body)}`)
        return body
      },
      isConcurrencySafe: () => true,
    }),
    defineTool({
      name: 'liang_timeline',
      description: '查询「滑动变祖器」每日社区评分快照时间线：每天一条（date/score/stage/voterCount）。可选 from 参数（YYYY-MM-DD）只看某天之后。',
      parameters: {
        from: { type: 'string', description: '起始日期 YYYY-MM-DD，可选' },
      },
      output: {
        schema: {
          type: 'array',
          items: {
            type: 'object', additionalProperties: true,
            properties: {
              date: { type: 'string', required: true },
              score: { type: 'number', required: true },
              stage: { type: 'string', required: true },
              voterCount: { type: 'integer', required: true },
            },
          },
        },
        render: renderJson,
      },
      async execute(args) {
        const from = typeof args.from === 'string' && args.from.length > 0 ? args.from : undefined
        const qs = from === undefined ? '' : `?from=${encodeURIComponent(from)}`
        const { status, body } = await upstream(`/api/timeline${qs}`)
        if (status !== 200) throw new Error(`liang timeline upstream ${status}: ${JSON.stringify(body)}`)
        return body
      },
      isConcurrencySafe: () => true,
    }),
    defineTool({
      name: 'liang_vote',
      description: '给「滑动变祖器」投一票：position 为 -15 到 +15 的整数（负数=小难梁方向，正数=梁祖方向，0=中立）。同一身份每 3 小时可投一次。返回投票结果与最新社区数据。',
      parameters: {
        position: { type: 'integer', required: true, description: '强度值 -15 到 +15' },
      },
      output: {
        schema: {
          type: 'object', additionalProperties: true,
          properties: {
            accepted: { type: 'boolean', required: true },
            reason: { type: 'string' },
            score: { type: 'number' },
            stage: { type: 'string' },
          },
        },
        render: renderJson,
      },
      async execute(args) {
        const position = Number(args.position)
        if (!Number.isInteger(position) || position < -15 || position > 15) {
          throw new Error('position must be an integer between -15 and 15')
        }
        const { status, body } = await upstream('/api/vote', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ position, fingerprint: voterFingerprint() }),
        })
        return body
      },
      isConcurrencySafe: () => false,
    }),
  ]
}

export async function apply(ctx) {
  // Model-facing tools.
  const disposers = createTools().map(tool => ctx.tools.register(tool))

  // Proxy routes.
  let disposeRoute = () => {}
  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(() => {
      const dispose = webCtx.webServer.register({
        kind: 'prefix',
        path: PROXY_PREFIX,
        handler: proxyHandler,
      })
      return dispose
    }, 'dsh-liang-watch: proxy routes')
  })

  ctx.effect(() => async () => {
    for (const dispose of disposers) dispose()
    disposeRoute()
  }, 'dsh-liang-watch: remove tools and routes')
}
