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
├─ dsh-nudge/
│  ├─ lib/index.js
│  ├─ package.json
│  ├─ cordis.patch.yml
│  └─ README.md
├─ dsh-liang-watch/
│  ├─ lib/index.js
│  ├─ lib/client.js
│  ├─ package.json
│  ├─ cordis.patch.yml
│  └─ README.md
├─ dsh-deepseek-favicon/
│  ├─ lib/index.js
│  ├─ lib/client.js
│  ├─ package.json
│  ├─ cordis.patch.yml
│  └─ README.md
├─ router-progressive/
│  ├─ lib/index.js
│  ├─ src/index.ts
│  ├─ package.json
│  ├─ preset-row.yml
│  ├─ install.ps1
│  └─ README.md
└─ dsh-android-shell/
   ├─ app/
   ├─ gradle/
   ├─ docs/screenshots/
   ├─ gradlew.bat
   └─ README.md
```

## 首个工具：Web 重启启动器

`tools/restart-web/start-deepseek-harness-web.bat` 适用于 Windows。它会：

1. 定位并检查 DeepSeek Harness 工作区；
2. 查找占用 `127.0.0.1:3080` 的旧 Harness 实例；
3. 只在确认进程链属于 Harness 时结束旧进程树；
4. 等待端口释放后，直接执行 DSH 的 Node 源码入口，避免启动时触发 pnpm 依赖裁剪。

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

## 第四个工具：dsh-liang-watch 插件

`tools/dsh-liang-watch/` 是「梁强度雷达」插件：把滑动变祖器（Lichtspektrum/liang-intensity-calibrator）的社区投票/每日时间线接进 DSH。模型端有 `liang_score` / `liang_timeline` / `liang_vote` 三个工具，Web 端侧边栏底部有「👑 梁强度」面板（实时评分 + 快捷投票 + 7 天快照）。host 侧代理转发上游 API 并解决 CORS 与本机直连超时（自动走系统代理）。

已在本机验证：dump-config 组合树正常、代理端点 200、真实投票被上游接受、headless Chrome 实测按钮与面板渲染无报错。安装方式见 `tools/dsh-liang-watch/README.md`。

## 第五个工具：dsh-deepseek-favicon 插件

`tools/dsh-deepseek-favicon/` 把 DSH 的标签页图标换成 DeepSeek 官方鲸鱼 logo。纯 client 端：logo 以 base64 data URI 内嵌在 client.js 里，替换 `link[rel="icon"]`，零 host 依赖、不碰源码。默认的鲸鱼娘头像在深色标签页下黑乎乎一片，官方 logo 清晰醒目。

已在本机验证：headless Chrome 实测 link href 变更为 data URI，16×16 标签页图标清晰可辨。安装方式见 `tools/dsh-deepseek-favicon/README.md`。

## 第六个工具：DSH Android Shell

`tools/dsh-android-shell/` 是 DeepSeek Harness 的 Android 外壳，把 DSH 的 bootstrap、runtime、核心依赖和 arm64 原生库打进 APK，在手机 App 私有目录中启动本地 DSH 服务，再通过 WebView 使用完整界面。

它重点解决了手机端的几个实际问题：

- 首次启动解包到独立的 `dsh-runtime/`，升级只替换 runtime；
- 用户工程、配置、会话、日志和第三方插件放在独立的 `dsh-user/`，避免升级误伤用户内容；
- 手机侧边栏采用覆盖式手势交互，避免窄屏被侧栏挤压；
- 输入区的回车用于换行，不把普通回车误当成发送；
- 针对 Android 私有目录不支持 POSIX hard link 的情况，为 DSH 会话持久化增加同目录原子重命名兜底。

完整工程、三张手机截图和构建说明见 [`tools/dsh-android-shell/README.md`](tools/dsh-android-shell/README.md)。

## 第六个工具：router-progressive 渐进式工具路由

`tools/router-progressive/` 是基于原作者 dsh-routing-suite 原型继续改造的 DSH agent-preset 插件：按首条直接用户请求选择模型可见工具，并在执行阶段再次拦截未选中的受管工具。它不是全局 host 插件，安装与致谢见 `tools/router-progressive/README.md`。

## 我们为什么这样改：机制观察与工程假设

这一节记录的是我们根据 DSH 源码、公开资料和实际 session 观察整理出的工程解释。它有助于理解工具设计方向，但不是 DeepSeek 官方结论，也不是已经完成实验验证的论文。文中的“机制”应理解为可被工程验证的行为假设。

### 当前最重要的判断

我们目前最认可的统一解释是：V4 Agent 的后续表现高度依赖早期形成的 trajectory（任务轨迹）。Harness 的职责不只是把更多能力塞给模型，而是尽量让模型尽早进入正确轨迹，减少无关决策，并在需要时只暴露真实可用的能力。

因此目标不是“工具越少越好”，而是：

> 强模型 + 薄身份 + 低歧义工具面 + 早期正确锚定 + 渐进暴露能力 + 因果连续的历史。

### 几条可操作的假设

1. **多轮质量依赖因果轨迹。** 后续任务依赖的不是逐字保留全部 reasoning token，而是保留可继续推理的因果状态：已经做过的判断、依据、工具结果、已验证事实、失败路径和待办决策。压缩如果只剩事实快照，模型可能会重复探索已经排除的路径。
2. **工具面会增加策略歧义。** 工具数量、描述长度、相似工具之间的选择，以及“注册但不可用”的工具，都会占用模型解释环境和比较策略的预算。真正要降低的不是工具数量本身，而是无关选择和接口歧义。
3. **死工具是假 affordance。** prompt、tool schema、provider 状态和执行 guard 如果互相矛盾，模型会被邀请调用一个实际上不存在或未就绪的能力；失败后还可能继续排查环境，放大 Pro 模型常见的过度反思。四者必须来自同一套事实，并保持一致。
4. **渐进暴露首先是轨迹控制。** 首轮使用较小、熟悉的核心工具面，任务明确命中网页、后台任务、skill 或子代理等能力后再扩展，不是把模型能力简单调成“弱/强”，而是减少早期错误锚定和无关工具竞争。
5. **身份、环境、能力应分层。** 稳定的 persona/行为约束尽量少变；工作目录、当前环境等事实按需注入；工具能力随任务变化。把三者揉成一份长期膨胀的 prompt，容易造成身份漂移和 catalog 与实际工具不一致。
6. **“吸引子”和“雷霆大思考”是行为描述。** 我们观察到不同 Harness 组合可能把同一模型带入相对稳定的行为风格，也观察到高 token、低进展的反思故障；这些词是工程隐喻，不代表我们已经观测到模型内部路由或训练机制。

### 对工程实现的直接要求

- 路由依据应尽量确定性，避免为“选择工具”再消耗一次模型请求。
- model-visible prompt、tool schema、provider availability 和执行 guard 必须同步收敛。
- 不可用 backend 对应的工具不进入 schema；即使运行时仍注册，也要在执行阶段拒绝，并清理 prompt 中的死工具指导。
- session、claimed turn、resume/fork/replay 要保留能解释当前决策的因果状态；不要把“压缩”误解成简单删短文本。
- 工具过滤是 model-visible surface 的控制，不替代 sandbox、approval 等官方安全边界。

### 证据边界与后续验证

我们把材料区分为源码事实、session 实测、高可信工程推断和待证假设。一次成功或一次失败都不能证明机制成立；后续测试应在相同模型、provider、任务集合和环境下比较 baseline 与候选 preset，并同时记录任务质量、工具误调用、轮次、token、耗时、失败恢复和路由误报/漏报。

`router-progressive` 当前是验证这些工程假设的实验性工具，不宣称已经证明模型内部机制。详细的实现边界、回滚方式和测试方法见 [`tools/router-progressive/README.md`](tools/router-progressive/README.md)。
