# dsh-nudge

[![dsh-plugin](https://img.shields.io/badge/dsh--plugin-%E2%9C%93-5B4CF0?style=flat-square)](https://github.com/topics/dsh-plugin)

> 任务报错、中断时，强制戳 LLM 一下——让模型解释报错、从中断处继续，而不是装死躺平。针对 agent 稳定性问题的"基础设施级"插件。

作者：小墨

## 为什么

所有 agent 框架都有同一个病：模型请求失败或回合被中断后，循环静默收场，任务死在半路没人吭声。dsh-nudge 在 **terminal failure**（重试耗尽、无人接管的失败）那一刻接管，向 agent 排队一条 follow-up 消息，模型必须回应：

- **报错** → 模型解释发生了什么，决定重试还是换路子
- **中断** → 模型确认进度，从中断处继续，或说明卡点

## 验证状态

✅ **已修复并验证**：集成测试用 DSH 官方 MockAdapter + harness 模拟"模型请求失败"和"用户取消"两个场景，2/2 通过——失败后模型会收到 `[系统通知]` 消息，用户主动取消则不打扰。本机 DSH 已加载，重启后即可在真实失败场景生效。

## 工作机制

监听 `agent/request-error` waterfall：

1. **先放行**：`next()` 让下游（提供方重试策略、压缩修复等）先尝试恢复；只有它们全部放弃（返回 `undefined`）才轮到我们——不抢重试的活。
2. **延迟戳**：不直接在 waterfall 里 `followup()`——DSH 驱动器处理终端错误时会停摆，此刻 followup 的消息会躺在队列里没人处理。插件改为标记 pending，等 `agent/status` 确认驱动器变 idle 后再 followup（带 1.5s 兜底定时器），保证消息被新轮次消费。
3. **防死循环**：
   - 同一 turn 只戳一次；
   - 每个 agent 连续戳上限 3 次，之后只记日志不再戳（模型/API 真挂了就别刷屏）；
   - **用户主动取消（`{kind:'user'}`）和 agent 销毁（`disposed`）不戳**——尊重用户，也避免戳空气；
   - 父代理中断（`parent`）和钩子取消（`hook`）会戳——这是"意外"，模型该有反应。

## 安装

放在 `~/.dsh/dsh-external/dsh-nudge/`，然后在 profile 里 link：

```jsonc
// ~/.dsh/profiles/web/package.json
{
  "dependencies": {
    "@dsh-external/dsh-nudge": "link:C://Users//<你>//.dsh//dsh-external//dsh-nudge"
  },
  "dsh": { "profile": { "bundles": [ "...", "@dsh-external/dsh-nudge" ] } }
}
```

或手动建 junction（本机当前安装方式）：

```powershell
New-Item -ItemType Junction -Path "$env:USERPROFILE\.dsh\profiles\web\node_modules\@dsh-external\dsh-nudge" -Target "$env:USERPROFILE\.dsh\dsh-external\dsh-nudge"
```

## 行为边界

- 只对**模型请求**的 terminal failure 生效（网络错误、API 拒绝、超时、中断）。工具执行失败本身不会触发——工具错误已作为工具结果回到模型面前，模型自己能看到。
- 戳是**追加轮次**，不打断正在进行的对话；模型回复会出现在普通对话流里。
- 插件卸载（dispose）会清掉所有 nudge 状态。

## License

MIT
