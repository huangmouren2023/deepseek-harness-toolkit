# DSH Router Progressive

这是工具集里的 DSH 渐进式工具路由插件。它根据会话第一条直接用户消息，用本地确定性关键词选择较小的模型可见工具集，并在执行阶段再次阻止未选中的受管工具。

它保留常驻编码工具，只有命中网页、后台任务、skill、计划、子代理、工作流、目标、交互或 todo 等能力时，才加入对应工具。`dev_router_status` 和 `dev_router_mode` 用于查看或覆盖当前会话路由。

## 来源与改造边界

本工具基于 DeepSeek Harness 原生 `router-progressive` 实现整理而来；原生实现又是在原作者 `yjh051108/dsh-routing-suite` 的 Router Progressive 原型基础上继续改造的。

- 原作者项目：[yjh051108/dsh-routing-suite](https://github.com/yjh051108/dsh-routing-suite)
- 原作者归属、项目链接和 MIT 许可声明保留在 [ATTRIBUTION.md](ATTRIBUTION.md)。
- 本工具中的 TypeScript 实现、执行守卫、真实 DSH 时序兼容、中文说明和工具集封装属于本地改造内容。
- 路由会把最终实际组合出来的工具面与提示词指导再次对齐；当 `web_fetch` provider 不可用时，不再暴露对应 schema，也不会在提示词里继续诱导调用死工具。
- 本目录不是原作者官方发布，也不代表原作者为本改造背书。

## 安装

在本工具目录执行：

```powershell
.\install.ps1
```

脚本只把包加入指定 profile 的依赖，不修改现有 agent preset。然后把 [preset-row.yml](preset-row.yml) 中的行加入目标 preset，例如：

```yaml
- id: router-progressive
  name: '@dsh-external/dsh-router-progressive'
```

保存 preset 后，从外部重启 DSH，再在新会话中选择该 preset。不要把它直接塞进 host 层；它是 agent-preset 级别的工具面过滤器。

## 验证

原生集成测试位于 [tests/router-progressive.spec.ts](tests/router-progressive.spec.ts)，在 DSH monorepo 中运行：

```powershell
pnpm vitest run packages/preset/router-progressive/tests/router-progressive.spec.ts
```

构建入口已经随本目录保留在 [lib/index.js](lib/index.js)，源码在 [src/index.ts](src/index.ts)。当前本机 DSH 已验证：首条 inbox 消息可被识别，路由原因和能力包可见，工具面在普通文件任务与网页任务之间按预期收敛。
