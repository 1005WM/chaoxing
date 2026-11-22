# Android UI 与授权只读验证

日期：2026-11-17。仅修改本地工作区；未 commit 或 push。

## UI 修改

- 登录页采用紧凑品牌行、明确标题和原生输入框标签。保留客户端配置，备份恢复降为次级文字按钮。
- 共用按钮最小高度调整为 48 dp，统一圆角和字体层级，减少图标边框。
- 课程页账号入口改为标准图标按钮，避免长姓名挤占标题；课程和群聊列表减少边框噪声。
- 设置页合并账号摘要，手动学校编号默认折叠，保留原有下拉与手动配置功能。开关整行可操作，Switch 与父行共用一次回调。
- 签到页移除重复的信息标题，活动编号保留为次要正文；未修改签到提交逻辑。
- 原有蓝色强调、浅色/深色语义色与最新桌面图标保留。iOS 源码未改动。

## 构建与离线验证

在 `android/` 目录、JDK 17 / Gradle 8.10.2 / SDK 35 环境执行：

```sh
gradle --no-daemon --console=plain \
  :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:lintDebug :app:assembleDebugAndroidTest
```

- 最终构建 exit 0；核心库 59 项、应用层 15 项单元测试通过。
- Lint 无错误，仍有依赖版本、Modifier 参数顺序等警告。
- 第一轮 UI 调整后执行 `:app:connectedDebugAndroidTest`，3 项系统仪器测试通过（Keystore、迁移/损坏恢复、未登录 Activity 生命周期）。后续设置页布局调整使用手动安装与授权只读测试验证，以保留模拟器内已授权的会话。
- 本轮未重新构建 Release。

## 真实账号只读验证

经账号持有者明确授权，在专用 Android 15 ARM 模拟器运行 `AuthorizedReadOnlyTest`。使用应用实际 `AndroidAppState.loginPrimary`、课程/群聊与活动加载方法，随后启动实际 `MainActivity` 查看真实数据。

- 真实登录成功，加载 **11 门课程、12 个群聊**。
- 抽查一门课程与一个群聊的活动列表，均成功返回空列表；不代表非空活动详情、全部签到类型已验收。
- 加密会话文件可重新读回，签到历史未发生变化。
- 最终 UI 更新安装后再次执行只读测试，无临时凭据文件，原会话恢复成功，仍读取到 11 门课程与 12 个群聊；之后普通启动入口返回成功。
- 检查实际登录、课程、群聊、设置页面截图；深色课程截图通过测试中同一组件强制深色主题生成，不代表系统主题切换已完整验收。
- 这是应用控制器与页面集成验证，**不是逐个点击登录表单、筛选、开关或签到按钮的端到端验收**。
- 没有打开真实签到工作台、执行预签到、签到、签退、验证码验证或文件上传。

在线测试默认跳过，仅显式传入 `liveReadOnly=true` 才启用，并限制在模拟器。凭据不在源码/测试参数/报告中；通过应用私有缓存中的临时文件传入，测试在认证前删除临时文件，正常登录后的会话由应用保存到 Keystore 加密存储。截图含私人资料，仅保留在仓库外。

```sh
# 仅在账号持有者授权、已准备独立模拟器时执行；不在 CI 自动运行。
adb -s <测试模拟器> shell am instrument -w \
  -e class com.caochaoxing.android.AuthorizedReadOnlyTest \
  -e liveReadOnly true \
  com.caochaoxing.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

最终 Debug APK SHA-256：`e8bdfb5ba0abed01f66a6f95c85d120de807f192d42e657af767237a04345983`。APK 不含上述测试登录凭据；测试类仅在独立 androidTest APK 中。

仍待验证：真实 Android 手机、最低 API 26、小屏/大字号/横屏与 TalkBack 全流程、相机/定位授权、非空签到详情。真实提交不在本次授权范围。
