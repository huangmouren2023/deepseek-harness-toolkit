# DeepSeek Harness 小工具集

作者：阿图

这是一个面向 Windows 的 DeepSeek Harness 杂项工具箱，收录启动器、诊断脚本和其他临时救火包。工具按目录独立放置，后续可以继续增加互不干扰的小工具。

## 工具目录

```text
tools/
└─ restart-web/
   ├─ start-deepseek-harness-web.bat
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
