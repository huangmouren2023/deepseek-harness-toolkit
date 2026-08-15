# DeepSeek Harness Web 关停脚本

作者：阿图

这是一个适用于 Windows 的纯关停脚本。它只结束占用 `127.0.0.1:3080` 的 DeepSeek Harness 进程树，释放端口后退出，不会重新启动服务。

## 用法

双击 `stop-deepseek-harness-web.bat`，或在 PowerShell / CMD 中运行：

```bat
tools\stop-web\stop-deepseek-harness-web.bat
```

## 安全行为

- 没有 3080 监听时，提示服务已经停止；
- 通过父进程链确认监听者属于 DeepSeek Harness；
- 无法确认身份时拒绝结束，避免误杀其他服务；
- 结束完整进程树后等待端口释放；
- 只停止服务，不执行 `pnpm.cmd dsh web`。
