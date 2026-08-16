# DSH Android Shell

当前发布版本：`1.0.2`（`versionCode 3`）。版本变更见 [CHANGELOG.md](CHANGELOG.md)。

## 本次能力配置与渐进式路由

- 启动器的“运行时管理”下新增“能力配置”，默认关闭 DSH 权限提示、DSH 沙箱、Bash 沙箱和 Root Shell；开启任一项前会显示风险提醒。
- 能力配置保存在 `files/dsh-user/config/android-capabilities.properties`，与 APK runtime、用户工程、日志和第三方插件分离；运行时升级不会覆盖它们。
- Root Shell 只让 Bash 工具尝试调用设备上的 `su -c`，不会把 DSH 主进程重启为 Root；没有可用 `su` 时不能开启。
- 发布运行时新增“标准模式 + 渐进式路由”预设，作为与 `standard`、`code`、`minimal`、`cordis` 并列且互斥选择的第五种 Agent 工作模式。它保留标准模式的核心工具，并按当前用户任务收窄模型可见的网页、后台任务、计划、子代理等能力；这属于模型侧路由，不改变宿主审批和沙箱边界。

把 `dsh web` 打包成自包含 Android APK。APK 自带 bootstrap、Node.js arm64 runtime、DSH 核心依赖和 Web 前端；首次启动解压到 App 私有目录后自动启动并进入 WebView。

## 功能

- 启动器先探测 TCP 和 HTTP，确认端口上的确是 DSH 后再进入 WebView。
- 端口空闲时自动准备内置 runtime 并启动 DSH。
- 默认端口为 `3080`，可在启动器中修改并持久化。
- WebView 外壳提供安全区、加载进度、错误重试和双击返回退出；设置等模态界面可用醒目的“关闭”按钮退出，Android 系统返回键也会优先关闭模态界面。
- 不再放置容易误触的悬浮刷新按钮；在对话已经到达底部后继续上拉，会触发一次自动重新加载。
- DSH Web 移动端使用抽屉式侧栏：默认收起，通过左上角菜单按钮打开、遮罩关闭。页面不再全局拦截横向滑动，避免与 WebView 和设置导航的手势冲突。
- “Agent 预设”设置保持紧凑单行，只在加载或保存失败时显示额外错误说明。
- 第五预设的实现包和组合配置均随 APK 内置在只读 runtime 中，不依赖 `dsh-user` 内曾经手工安装的路由包；普通外部插件仍可从当前 DSH profile 加载。
- 输入框中普通回车只换行，`Shift+Enter` 也只换行；桌面端和移动端都使用 `Ctrl+Enter` 或 `Command+Enter` 发送。命令菜单打开时，回车仍用于选择命令。

## 运行时与数据隔离

目录边界固定如下：

```text
App 私有 files/
├─ dsh-runtime/              # APK 自带或升级后的 bootstrap + runtime + DSH 核心依赖
└─ dsh-user/
   ├─ config/                # 用户配置、认证和会话设置
   │  └─ profiles/           # 第三方插件 profile
   ├─ projects/              # 用户工程
   └─ logs/                  # 用户日志
```

首次启动只解压 `dsh-runtime.zip` 到 `dsh-runtime`。后续运行时升级先解压到临时目录、校验入口文件和 SHA-256，再以备份目录完成替换；提交失败会回滚旧 runtime。升级和修复都不会删除或覆盖 `dsh-user`，因此更新 DSH 不会掀掉用户工程、配置、日志和第三方插件。

曾安装开发版 `router-progressive` 的设备可能在 `dsh-user/config` 留有同名用户预设和包。正式版不会把这些历史文件当作内置实现；从开发版切换时建议清除一次应用数据后重新启动。全新安装不需要手工安装或重新构建路由插件。

启动器的“运行时管理”可以填写 JSON 清单 URL，格式如下：

```json
{
  "version": "0.1.0-rc.6",
  "url": "https://example.invalid/dsh-runtime.zip",
  "sha256": "可选的 zip 文件 SHA-256"
}
```

清单版本与已安装版本相同时不会重复下载；没有清单服务时也可以直接选择“修复内置运行时”。运行时进程运行期间不会覆盖正在使用的目录。

## 构建

Windows 环境使用项目自带 Wrapper，并共享用户级 Gradle 缓存：

```powershell
$env:GRADLE_USER_HOME = 'C:\GradleAscii'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat clean assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

`app/src/main/assets/dsh-runtime.zip` 是构建前生成的交付资产，不从用户手机网络下载。它包含 Android arm64 Node.js、DSH 生产依赖和已构建的 Web 前端。

重新升级 DSH 时，先在 PC 端构建 DSH 源码，再生成 runtime zip，最后执行上面的 Gradle 命令。APK 包名保持 `com.dsh.shell`，默认端口保持 `3080`。

## 签名

仓库不包含正式签名密钥。未提供签名参数时，release 变体使用 debug keystore，便于本地构建；正式发布可通过 Gradle properties 或环境变量提供：

- `dsh.release.keystore` / `DSH_RELEASE_KEYSTORE`
- `dsh.release.storePassword` / `DSH_RELEASE_STORE_PASSWORD`
- `dsh.release.keyAlias` / `DSH_RELEASE_KEY_ALIAS`
- `dsh.release.keyPassword` / `DSH_RELEASE_KEY_PASSWORD`
