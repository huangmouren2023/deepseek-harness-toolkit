# DeepSeek Harness 小工具集

作者：阿图

这是一个面向 Windows 的 DeepSeek Harness 杂项工具箱，收录启动器、诊断脚本、插件和其他临时救火包。工具按目录独立放置，后续可以继续增加互不干扰的小工具。

## 工具目录

```text
tools/
├─ restart-web/
│  ├─ start-deepseek-harness-web.bat
│  └─ README.md
├─ stop-web/
│  ├─ stop-deepseek-harness-web.bat
│  └─ README.md
└─ dsh-nudge/
   ├─ lib/index.js
   ├─ package.json
   ├─ cordis.patch.yml
   └─ README.md
```

## 首个工具：Web 重启启动器

`tools/restart-web/start-deepseek-harness-web.bat` 适用于 Windows。它会：

1. 定位并检查 DeepSeek Harness 工作区；
2. 查找占用 `127.0.0.1:3080` 的旧 Harness 实例；
3. 只在确认进程链属于 Harness 时结束旧进程树；
4. 等待端口释放后，执行 `pnpm.cmd dsh web` 正常启动。

使用示例：

```bat
tools\restart-web\start-deepseek-harness-web.bat "C:\path\to\deepseek-harness"
```

也可以先设置环境变量：

```bat
set DEEPSEEK_HARNESS_ROOT=C:\path\to\deepseek-harness
tools\restart-web\start-deepseek-harness-web.bat
```

脚本默认使用当前目录作为 Harness 根目录，因此也可以把它复制到 Harness 工作区后直接运行。

前置条件：Windows、Node.js、pnpm，以及已经构建好的 `apps\web\dist\index.html`。

## 第二个工具：Web 关停脚本

`tools/stop-web/stop-deepseek-harness-web.bat` 只结束占用 `127.0.0.1:3080` 的 DSH 实例进程树，释放端口后退出，不会重新启动服务。没有 3080 监听时提示已停止；无法确认监听者属于 Harness 时拒绝结束，避免误杀。

```bat
tools\stop-web\stop-deepseek-harness-web.bat
```

## 第三个工具：dsh-nudge 插件

`tools/dsh-nudge/` 是一个 DSH 插件：任务报错或中断时，强制戳 LLM 一下，让模型解释报错、从中断处继续，而不是装死躺平。针对 agent 稳定性的基础设施。

它监听 `agent/request-error` waterfall：先放行给下游重试策略，只有终态失败（重试耗尽、无人接管）才接管，用 `agent.followup()` 唤醒模型。用户主动取消和 agent 销毁不戳；同 turn 只戳一次，连续失败上限 3 次。

安装方式见 `tools/dsh-nudge/README.md`（放到 `~/.dsh/dsh-external/` 并在 profile 里 link，或手动建 junction）。
