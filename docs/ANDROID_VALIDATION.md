# Android 验证记录

更新：2026-11-17。后续 UI 与真实账号登录/会话恢复结果见 [ANDROID_UI_VALIDATION.md](ANDROID_UI_VALIDATION.md)；下文保留初次工具链验证边界。本轮仅修改本地工作区，未执行 commit、push、真实签到、签退或上传。iOS 的历史结果见 [VALIDATION.md](VALIDATION.md)，不能当作 Android 线上验证结果。

## 环境

- JDK 17.0.21（Zulu），Gradle 8.10.2，AGP 8.7.3，Kotlin 2.0.21。
- Android SDK Platform 35、Build Tools 35.0.0、Platform Tools、Emulator 37.2.13。
- Pixel 7 配置的独立 ARM64 Google APIs / Android 15（API 35）模拟器。
- JDK 与 Gradle 归档已核对供应商 SHA-256。Gradle Wrapper 由 Gradle 8.10.2 生成，配置包含分发包 SHA-256。
- SDK 与系统镜像许可经用户确认后接受。未修改全局 Java、Xcode 或签名配置。

## 已修复的问题

1. `ChaoxingCookiePolicy` 在内联 lambda 中使用 `continue`，导致当前 Kotlin 配置编译失败；改为普通条件分支，保留过期 Cookie 的剔除行为。
2. `CxFlowRow` 对外暴露实验性的 `FlowRowScope`，导致多处 UI 调用无法编译；外部接收者改为稳定的 `RowScope`，实验 API 仅在包装器内部使用。
3. 课程刷新收到认证错误后，重试解析失败会再次登录；现在单次刷新最多重新认证一次。MockWebServer 回归先复现了两次登录，再验证修复后一次登录、两次课程请求。
4. 切换学校 FID 未清空云盘令牌缓存；现在同时失效旧令牌，新增回归先失败后通过。
5. 应用层测试辅助类无法访问外层样例方法，导致测试不能编译；改为内部类。损坏文件保护测试以正常恢复后的保存次数为基准，验证进入阻断状态后不再写入。
6. 补齐 Gradle Wrapper，忽略 Kotlin 本地构建缓存。补充 Android 云备份与设备迁移规则，排除 `files/caochaoxing/` 内的状态和恢复副本；保留 `allowBackup=false`。配置依据 [Android Auto Backup 文档](https://developer.android.com/identity/data/autobackup)，未实际执行云备份或设备迁移验收。

## 已执行验证

本轮使用独立安装的 Gradle 8.10.2，在 `android/` 目录执行：

```sh
gradle --no-daemon --console=plain --continue \
  :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:lintDebug :app:connectedDebugAndroidTest
```

另执行 `gradle --no-daemon --console=plain :app:assembleRelease`，结果通过。

执行时通过命令级 `JAVA_HOME` 和 `ANDROID_HOME` 指向上述工具链；仪器测试指定专用模拟器。仓库可复现入口为 `./android/gradlew -p android` 加上相同任务参数。

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| `:core:test` | 59 项通过，0 失败 | 网络/Cookie、认证、课程、群聊、活动与签到协议、解析、加密及持久化；使用合成账号和 MockWebServer |
| `:app:testDebugUnitTest` | 15 项通过，0 失败 | 应用状态、会话切换/旧响应、验证码队列、存储保护与工具函数；无真实服务写入 |
| `:app:assembleDebug` | 通过，exit 0 | 编译、DEX、资源与开发签名 APK 打包 |
| `:app:lintDebug` | 0 error，54 warning | 42 条依赖/插件版本提示，另有参数顺序、WebView、资源配置和 Locale 等提示；不是零警告 |
| `:app:assembleRelease` | 通过，exit 0 | R8 压缩与资源裁剪完成；未签名，未安装运行 |
| `:app:connectedDebugAndroidTest` | 3 项通过，0 失败、0 跳过 | 在 API 35 ARM 模拟器实际执行，见下文 |

仪器测试通过真实 `AndroidKeyStore` 和 Android 文件系统验证：

- 加密后重新创建存储实例可读回；重复保存使用不同 IV；篡改密文后读取和覆盖均失败，原始坏文件保持不变，显式恢复保留原始字节副本。
- 旧明文状态可迁移为加密文件并删除旧文件，重新读取一致。
- 未登录状态接收合成活动深链接、Activity 重建后仍无账号与签到记录，Activity 回到 RESUMED。此测试不提交签到，也不代表已登录详情页完整验收。

仪器测试仅在独立临时目录写入合成数据，不读写真实账号。已有账号的设备会跳过未登录生命周期测试，因此建议使用专用测试模拟器。

## 实际启动与产物

Debug APK 已使用 `adb install -r` 安装并启动。取得了模拟器真实登录页截图，账号/密码输入框、空输入时禁用的登录按钮、客户端设置及备份入口正常绘制；启动日志未见 `AndroidRuntime` 崩溃。多次 `am start -W` 冷启动计时约 2.3～26 秒，最后一次约 16.3 秒；模拟器存在图形上下文告警，尚不能将这些数据归因为应用性能问题，也不能据此宣称启动性能已达标。仪器测试结束会清理安装，之后已重新安装 Debug 包用于后续测试。

- Debug：`android/app/build/outputs/apk/debug/app-debug.apk`，应用 ID `com.caochaoxing.android.debug`。
- Release：`android/app/build/outputs/apk/release/app-release-unsigned.apk`，仅验证构建，不能直接作为已签名发布包安装。
- 本节功能验证时的 Debug APK SHA-256：`4284d8cf62989ecb65300138ea45d1961558d751792554e23f56b270e5d40b1d`。
- 日志、测试报告副本、源码哈希清单、截图和独立 APK 副本保留在仓库外，不纳入 Git。
- REA MCP 进程未找到新安装的 adb；通过已安装 REA CLI、仅为该命令指定 `REA_ADB_PATH` 成功获取一帧截图。没有修改 MCP 配置。桌面 UI 控制工具尚不能识别 Android 模拟器窗口。

## 验收边界

- **未完成 Android 真实账号登录、课程/群聊线上读取与完整页面交互验收**。上述协议测试为受控测试，不能证明当前线上接口全部可用。
- 按用户要求，没有执行真实签到、签退、照片上传或验证码验证。已签到/过期/权限拒绝等服务响应仅用合成样本测试。
- 尚未覆盖 Android 真机、API 26 最低版本、相机/定位权限、真实地图网络、横屏/大字号/无障碍全量验收、厂商备份迁移差异。
- GitHub Actions 仅有配置，没有远程 CI 运行证据。当前 APK 为开发测试包，不能作为已发布稳定版本。

## 后续图标资源更新

本文前述记录对应当时测试的构建。当前的黑白漫画头像及生成方法见[启动图标说明](artwork/ANDROID_ICON.md)，最新一次验证结果见 [README](../README.md#验证状态)。
