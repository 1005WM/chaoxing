# iOS 修复与验证记录

更新：2026-11-17。当前为本地验证版本，尚未执行首次 commit 或 push。完整模拟器构建、启动和授权账号只读验证已通过；不代表所有签到类型或真机功能完成验收。

Android 当前验证单独记录在 [ANDROID_VALIDATION.md](ANDROID_VALIDATION.md)，下文构建与真实账号结果均指 iOS。

## Swift 工程范围

- 保留 iOS / iPadOS App、`ChaoxingCore` 与测试；不包含 macOS App 或打包脚本。
- iOS 部署下限 17.0，Swift 6。Core 保留 macOS 14+，供本机离线测试。
- `IOSAppStateSupport` 将实际 `IOSAppState.swift` 编译到测试中，不提供桌面 App。Xcode App 仍直接编译该文件，并依赖本地 `ChaoxingCore`。
- Team 为空，不包含个人签名身份；未更改系统 `xcode-select`，通过单个命令的 `DEVELOPER_DIR` 选择 Xcode。

## 2026-11-17 登录、课程与签到链路复核

- 修复课程刷新在 HTTP 认证失败后、重试解析再次失败时触发第二次重新登录的问题；同次刷新最多重新认证一次。
- 修复同一主账号再次登录未更新会话代次的问题；替换前取消旧任务和验证码等待，并清空旧课程/群聊等状态，拒绝旧响应回写。
- 批量签到入口拒绝非正活动 ID，验证不会进入准备或提交步骤。
- 新增 4 项回归（前三项先复现失败）；附加验证已签到/已过期活动不会调用提交闭包。签到测试使用依赖替身或 URLProtocol 拦截，不向真实服务签到。
- 最新 `swift test -j 2`：**84 项通过**。最新 Simulator Debug `xcodebuild`：**BUILD SUCCEEDED**（均 exit 0）。
- 将本次新构建覆盖安装到独立 iPhone 17 Pro / iOS 26.3.1 模拟器并启动：已保存登录状态恢复，11 门课程加载成功，抽查课程活动列表完成加载并显示空状态。仅检查文件权限元数据，确认运行时状态文件为 0600；未读取凭据文件内容。
- 本轮实测的是登录状态恢复，未重新输入密码登录；首次真实登录证据见下方较早记录。坐标点击工具出现 noWindowsAvailable，元素点击仍可完成课程导航；未据此判断 App 本身故障。
- 未执行任何真实签到或签到准备调用，未 commit 或 push。真实签到结果、完整真机权限与所有异常路径仍未完成验收。

## 2026-11-17 追加安全与可靠性修复

- 切换学校 FID 时清空云盘令牌缓存，避免复用旧配置令牌。
- 本机复现状态文件及恢复副本为 0644；改为写入前创建 0600 临时文件、原子替换，恢复副本也限制为所有者访问。iOS 新文件使用首次解锁后的数据保护。既存文件在后续保存/恢复时应用新规则；这不是应用层加密，完整导出仍含敏感凭据。
- 公共上传接口拒绝含控制字符、引号或反斜杠的 multipart 文件名，避免请求头结构注入；请求前拒绝，未进行真实上传。
- 新增 3 项回归均先失败后通过；最终 **80 项测试通过**，完整 Simulator Debug 构建 **BUILD SUCCEEDED**。
- 本次修改后的版本未重复真实账号实测；以下先前结果按时间保留，不扩大其验证范围。

## 2026-11-17 模拟器与真实账号验证

- Xcode 26.3，iOS 26.3.1 Simulator（23D8133）；新建独立 iPhone 17 Pro 模拟器。
- `swift test -j 2`：exit 0，77 项全部通过。
- `DEVELOPER_DIR=/Applications/Xcode-26.3.app/Contents/Developer xcodebuild -project Caochaoxing.xcodeproj -scheme Caochaoxing -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/CaochaoxingDerivedData build`：完整构建 exit 0，包括 arm64/x86_64 编译、链接及资源打包。实际验证使用仓库外独立 DerivedData；此处使用可替换的通用路径。
- `simctl install`、`simctl launch` 成功；登录页面浅色和深色显示、客户端选项展开/关闭、空输入禁用及填写后启用按钮正常。
- 经账号持有者明确授权，在独立模拟器登录并保存凭据；真实登录、课程列表、群聊列表读取成功。抽查一个课程活动列表显示空状态，设置及地点管理页面正常打开。
- 终止 App 后重新启动，可恢复账号并重新显示课程列表。
- 未执行任何实际签到、签退或资料上传；未保存包含账号资料的截图或凭据到仓库、验证报告。
- 未覆盖：活动非空详情、全部签到类型、相机/定位真机权限、小屏幕/横屏/最大辅助字号、完整 VoiceOver 验收，以及备份往返等清单项目。
- 唯一构建 warning 为未依赖 AppIntents.framework 而跳过元数据提取；没有编译或链接错误。
- 本次保留新加入的 Android 文件和相关说明；Android 不属于本次 iOS 构建及验收范围。

## 2026-11-14 离线验证（历史记录）

以下检查针对同一份源文件快照。检查前后所有 Swift 源码及 Package 的 SHA-256 一致。

| 检查 | 结果 | 边界 |
| --- | --- | --- |
| `swift test --enable-code-coverage` | exit 0，77 项通过 | 合成数据、离线请求拦截与受控异步响应 |
| `swift build` | exit 0 | Package 构建，不是完整 iOS App |
| Core 的 iOS Simulator SDK 模块生成 | exit 0 | 包括共享 SwiftUI 组件 |
| 全部 iOS Swift 文件 SDK 类型检查 | exit 0 | 不代表 App 链接、资源打包或启动 |
| 工程与 Info.plist 校验 | exit 0 | 语法及静态配置 |
| `swift package dump-package` | exit 0 | 本地依赖与测试支持模块自洽 |
| `xcrun simctl list runtimes` | 无可用 Runtime | 平台组件仍在下载 |
| `xcodebuild` 通用 Simulator 构建 | exit 70 | 目的设备解析被 “iOS 26.2 is not installed” 阻止 |

本轮工具链为 Swift 6.1.2 与 Xcode 26.3（17C529）；SDK 列表包含 iOS 26.2。存在 SDK 不等于 Xcode 已完成平台/模拟器组件安装。

行覆盖率：ChaoxingCore **2090 / 3262，64.1%**；实际 iOS 状态控制器 **706 / 1069，66.0%**。前者包括共享界面组件，后者只包含状态控制器而非全部 iOS Views。网络客户端被插桩行覆盖约 98.1%。覆盖率不是线上正确性或安全认证。

## 已修复的主要问题

- 会话快照无锁读取导致的内存异常；Cookie 域/路径/有效期/重定向隔离与重复名称崩溃；过期响应覆盖新会话。
- 并发重新登录冲突、取消无法及时传播、旧客户端在注销后重新认证、学校 fid 恢复不一致。
- 验证码重复结算；空账号选择仍提交；注销或切账号后旧操作继续写状态；课程和二维码解析乱序。页面退出或明确停止会取消后续批次，已取得的部分结果仍会保留。
- 二维码与活动 ID 错配；多账号头像错误复用；预签到重登录与详情并发冲突；过期 IM Token 无法恢复。
- null 活动扩展字段、Protobuf 长度和验证码时间戳导致的崩溃；错误响应被误判为成功、空列表或过期。
- 备份恢复丢字段、保存失败后内存部分更新、损坏存储被默认为空并覆盖。恢复操作先保全原件，失败时不会静默覆盖。
- EXIF 方向丢失、滑块识别在主线程长时间运行、无置信度结果、群列表只取第一页与查询参数加号编码。
- 加密帮助函数自定义 key 长度未检查；正常协议输出已用独立 OpenSSL 合成向量校验。

网络/认证/存储新增 27 项回归，协议/图像新增 15 项，实际 App 状态新增 17 项，加密向量新增 3 项，界面对比度新增 3 项；与原有 12 项合计 77 项。原有颜色测试中的“必须暖色、不能蓝色”断言因用户选择原生蓝色风格而移除，其可读性检查仍保留并扩展。

原并发崩溃探针在修复后连续三次正常退出。跨模块复核独立复现并验证了唯一认证等待者取消、IM Token 刷新与预签到重登录请求顺序。

## UI 与交互

- 原生系统字体和动态字号，中性背景、蓝色强调色，移除全局固定衬线导航字体、装饰性英文标签与二维码凭据预览。
- 共享主/次按钮及账号选择控件设置至少 44 pt 的触控高度；账号选择补充 VoiceOver 的选择状态。
- 主按钮文字的浅色/深色对比度约 6.55:1 / 8.19:1，正文、输入区域与彩色标签的测试组合均达到普通文字 4.5:1 基线。
- 相机采用串行会话配置/启停，提供权限失败和重新尝试入口；相机的真实行为仍需设备验证。
- 位置附件页和设置中可直接管理地点；服务端要求位置时不会因详情晚到而遗漏默认状态。
- 备份导出在用户触发时生成并反馈错误；退出全部账号、清空历史和损坏数据重置需要明确的界面操作。

2026-11-14 的上述 UI 检查仅包含源码、类型检查和颜色计算；**没有模拟器截图、大字号布局或 VoiceOver 实机验收**。未把单元测试通过描述为整套 UI 已符合 WCAG。对比度基线参考 [W3C](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)。

## 可复现命令

在仓库根目录运行：

```sh
swift test --enable-code-coverage
swift build
plutil -lint Caochaoxing.xcodeproj/project.pbxproj Sources/CaochaoxingIOS/Resources/Info.plist
swift package dump-package

export DEVELOPER_DIR=/Applications/Xcode-26.3.app/Contents/Developer
xcrun simctl list runtimes
xcodebuild -project Caochaoxing.xcodeproj \
  -scheme Caochaoxing -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath DerivedData build
```

本轮实际使用仓库外的 `--scratch-path` 与 DerivedData 路径隔离构建产物。辅助 SDK 检查先用 `xcrun swiftc -parse-as-library -emit-module -module-name ChaoxingCore` 生成 Core 模块，再用 `-typecheck -I <模块目录>` 检查全部 iOS 源文件；两步均指定 Simulator SDK、`-target arm64-apple-ios17.0-simulator -swift-version 6`。

## 仍待验证与已知限制

完整 iOS App 链接、资源打包、安装和模拟器启动已通过，详见上方 2026-11-17 记录。仍需按 [手动验收清单](MANUAL_TESTS.md) 完成剩余真机与异常路径检查。

已完成授权账号登录、会话恢复和课程/群聊只读实测；未执行真实签到，设备相机/照片/权限及完整后台切换仍未验收，也没有线上协议成功率保证。预签到页面识别、群分页和图像匹配仍需要合法测试场景中的真实样本验证。

账号会话仍保存在沙盒 JSON 中，未迁移至 Keychain，协议密码加密不能当作本地密码保险箱。损坏状态恢复副本也可能包含凭据。完整说明见 README；不要公开状态、备份或真实二维码。

当前图标仍为有可编辑源文件的候选图标，最终选择尚未确认。历史材料、审计探针和构建日志保留在仓库外，未纳入公开候选。
