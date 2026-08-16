# 变更日志

## 1.0.3 - 2026-08-17

### Fix

- The progressive router now removes the deployment-level Harness opener so its complete system prompt starts with `You are a helpful software engineer assistant.`.
- The `{{model}}` template remains dynamically rendered and the router remains mutually exclusive with the four official Agent modes.

## 1.0.2 - 2026-08-16

### 修复

- 将“标准模式 + 渐进式路由”完整打入 APK runtime，作为与官方四种模式并列且互斥选择的第五种 Agent 预设；全新安装不再依赖用户目录中的手工安装包或改造后的 TS 构建产物。
- Android preset 在没有 Loader 内部模块映射时，优先从内置 runtime 的真实目录解析随 APK 发布的包，再从当前 profile 解析普通外部扩展。该顺序绕过 Android/SELinux 下“符号链接存在但 Node realpath 失败”的情况，并避免用户目录中的旧同名包覆盖内置实现。
- 新增第五预设挂载测试及混合来源解析测试；发布验收会清除开发版数据，以首次安装状态逐项验证五种互斥预设。

## 1.0.1 - 2026-08-16

### 修复

- 修复 Android 嵌入式 Loader 缺少内部模块映射时，Agent preset 无法从当前 DSH profile 加载第三方裸包名的问题；新建会话不再因默认 preset 挂载失败而中断。
- 设置面板在手机上显示明确的“关闭”按钮，Android 系统返回键会优先关闭打开的模态界面。
- “Agent 预设”设置收敛为单行标题和选择器；正常说明隐藏，只在失败时显示错误。
- 移除页面根节点的全局左右滑手势与 `touchmove.preventDefault()`，侧栏改为菜单按钮打开、遮罩关闭，避免 WebView 内滚动和横向设置导航卡死。

### 验证

- preset、布局和 Agent 设置相关的 101 条 Vitest 回归测试通过。
- Web 前端和相关客户端包完成生产构建；Android release APK 使用项目 release keystore 签名。
