# dsh-liang-watch

[![dsh-plugin](https://img.shields.io/badge/dsh--plugin-%E2%9C%93-5B4CF0?style=flat-square)](https://github.com/topics/dsh-plugin)

> 梁强度雷达：把 [滑动变祖器（Lichtspektrum/liang-intensity-calibrator）](https://github.com/Lichtspektrum/liang-intensity-calibrator) 的社区投票与每日时间线接进 DeepSeek Harness。侧边栏一键看「梁子 -1.18 / 888 人投」，模型也能直接查分、投票、看趋势。

作者：小墨

## 这是什么

滑动变祖器是一个社区"梁强度"投票站——每天 16:05 定时结算当日评分，按分数分阶：小难梁 / 牢梁 / 梁子 / 梁圣 / 梁神 / 梁祖。它本身是个网页（Vite + Cloudflare Worker + D1），跟 DSH 没有任何关系。

dsh-liang-watch 是一个**集成插件**，做两件事：

1. **Host 侧代理**：注册 `/_dsh/liang/*` 路由，转发到上游 Worker API，并带上上游白名单要求的 `Origin` 头——这样 Web 端和模型端都绕开 CORS/直连问题（Windows 本机直连 worker.dev 还会超时，代理走系统 Clash 代理 `127.0.0.1:7890` 解决）。
2. **模型工具**：`liang_score`（查当前评分/阶段/票数）、`liang_timeline`（查每日快照）、`liang_vote`（投 -15..15 分）。小墨（或任何 agent）可以直接在对话里报"今天的梁强度"。
3. **侧边栏面板**：Web 端侧边栏底部多一个「👑 梁强度」按钮，点开是实时卡片——当前阶段、评分、今日票数、正/负/中立分布、快捷投票按钮（+15/0/-15）、最近 7 天快照。

## 验证状态

✅ **本机验证完毕**：

- `dsh web --dump-config` 组合树含 `liang-watch`；
- 临时实例上 `/score`、`/timeline`、`/vote` 代理全部 200，真实投票已被上游接受（票数 +1）；
- headless Chrome 实测：侧边栏「👑 梁强度」按钮正常渲染，点开面板拉到实时数据（888 人投票 · 今日 567 · 正376/负489/中立23），无 JS 报错。

## 工作机制

- **代理**：`webServer.register` 注册 exact/prefix 路由；`resolveProxy()` 按 `HTTPS_PROXY`/`HTTP_PROXY` 环境变量 → `~/.dsh/settings.yaml` 的 `proxy` → 默认 `127.0.0.1:7890` 依次找代理，用 undici `EnvHttpProxyAgent` 建立 dispatcher；请求带上 `Origin: https://lichtspektrum.github.io` 通过上游 CORS 白名单。
- **投票指纹**：`voterFingerprint()` 按 `DSH_LIANG_FINGERPRINT` 环境变量 → 本机机器指纹（用户名+主机名 hash）生成 8-128 字符指纹，满足上游校验；投票有 3 小时冷却和 IP 限流，上游说了算。
- **面板**：纯 DOM + fetch，无框架依赖；样式走 DSW 设计令牌（`var(--dsw-alias-*)`），明暗主题自适应。

## 安装

放在 `~/.dsh/dsh-external/dsh-liang-watch/`，然后在 profile 里 link：

```jsonc
// ~/.dsh/profiles/web/package.json
{
  "dependencies": {
    "@dsh-external/dsh-liang-watch": "link:C://Users//<你>//.dsh//dsh-external//dsh-liang-watch"
  },
  "dsh": { "profile": { "bundles": [ "...", "@dsh-external/dsh-liang-watch" ] } }
}
```

然后 `pnpm install`（或手动建 junction 到 `profiles/web/node_modules/@dsh-external/`），重启 DSH。

可选环境变量：

| 变量 | 作用 |
| --- | --- |
| `DSH_LIANG_FINGERPRINT` | 固定投票指纹（默认自动生成机器指纹） |
| `HTTPS_PROXY` / `HTTP_PROXY` | 代理优先级最高，覆盖默认 7890 |

## 上游致谢与区分

- 上游数据来自 [Lichtspektrum/liang-intensity-calibrator](https://github.com/Lichtspektrum/liang-intensity-calibrator)（Vite 前端 + Cloudflare Worker + D1 + 每日 cron）。本插件只是**读取/投票的客户端集成**，不含上游代码。
- 社区里已有其他 remix：`Liang-Saint-Slider`（模型+effort 选择器皮肤）、`dsh-liang-skin`（皮肤主题）。dsh-liang-watch 是**数据接入**方向——把社区投票/时间线变成 DSH 的工具和侧边栏面板，不是皮肤。

## License

MIT
