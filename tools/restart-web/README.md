# DeepSeek Harness Web 重启启动器

作者：阿图

这是一个 Windows 批处理启动器，用于一键重启 DeepSeek Harness Web。它解决“旧实例仍在后台占用 3080，重新启动却只看到 `EADDRINUSE`”的问题。

## 用法

从工具箱根目录执行：

```bat
tools\restart-web\start-deepseek-harness-web.bat "C:\path\to\deepseek-harness"
```

也可以使用环境变量：

```bat
set DEEPSEEK_HARNESS_ROOT=C:\path\to\deepseek-harness
tools\restart-web\start-deepseek-harness-web.bat
```

不传参数时，脚本把当前目录当作 Harness 根目录。传入 `--help` 可查看简要帮助。

## 行为

- 检查 Harness 根目录中是否存在 `apps\cli\src\bin.ts`；
- 检查前端产物 `apps\web\dist\index.html`；
- 查找监听 `127.0.0.1:3080` 的进程；
- 通过父进程链确认它属于 Harness；
- 使用 `taskkill /T /F` 结束旧实例的完整进程树；
- 等待 3080 释放后直接执行 `node.exe --import tsx/esm apps/cli/src/bin.ts web`；
- 不在启动阶段调用 pnpm，避免 production 依赖裁剪触发开发工具缺失。

如果 3080 被无法确认身份的程序占用，脚本会拒绝结束它并退出，避免误杀其他服务。
