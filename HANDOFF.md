# 交接说明

## 应用更新和发布

- 更新源为 `1005WM/chaoxing` 的 GitHub Releases。Android 的 `UpdateSource` 和 Swift 的 `AppUpdateSource` 可配置镜像 `latest.json`。
- Android 镜像元数据超时为 2 秒，GitHub API 失败后还会尝试 Release 的 `latest.json`。检查结果缓存 5 分钟。
- APK 下载在应用私有缓存的 `updates` 目录，FileProvider 仅暴露此目录。安装前检查包名、签名、版本号和构建号，有 SHA256 时校验摘要。
- 发布包沿用本机 Debug 签名，应用标识带 `.debug`。后续覆盖安装须保留同一签名并递增构建号。
- iOS 需要配置 `iosStoreURL` 为真实 TestFlight 或 App Store 链接。macOS 通过发布资产的下载链接更新。Apple 构建依赖 macOS 和 Xcode。
- 作业和考试并发读取时，重登会使另一请求的旧会话失效。两边均失败时优先显示实际的登录失败原因。

## Android AI 作答

- `AiAnswerService` 使用兼容 Chat Completions 的 HTTPS 接口。设置页填 URL 和 Key 后自动请求 `/models`，也支持手动填写模型。
- `AiStudyController` 串行生成草稿。Key 用 Android Keystore 加密保存在独立设置中。草稿在当前进程内复用，切换账号时清空。
- 作业网页通过渲染后的题干和选项提取题目。章节测验从任务链接读取。视频完成后会重试此前未读到题目的测验。
- 开启随看课生成答案后，完整题型、题干、说明、选项和题图会发送到配置的模型服务。答案可编辑和复制。
- 图片优先从已渲染网页读取。跨域题图通过独立客户端下载，按目标域名附带学习通 Cookie，再转为 Base64 图片消息。模型 Key 仅发给模型接口。
- 单题最多 6 张图，单图输入上限 5 MB，图片最长边缩放到 2048 像素。图片读取失败会提示重试。草稿保留原题图来源，生成请求时按题转换，避免长期缓存整批 Base64 图片。
- 判断题保留完整陈述和选项顺序。模型返回「正确／错误」时按实际选项顺序映射字母。
- 当前页面解析覆盖常见题目容器和同源 iframe。跨域 iframe、学校定制页面和真实服务商仍需实测。

## Android 作业总览

- 首页逾期数只统计作业。服务端标为过期的作业，以及仍未完成但已过截止时间的作业，都计入逾期。

## Android 作业网页

- 链接转换和 WebView 导航是两个入口。改电脑版时，要同时检查初始链接、页面重定向和弹窗转发。
- 桌面 User-Agent 不会把手机专用路径变成电脑版。排查时看 WebView 最终加载的路径，再看页面布局。
- `/mooc-ans/mooc2/work/list` 仅带课程、班级和 CPI 时曾在真机返回白页。回退时打开电脑版课程入口，通过页面的「作业」标签取到当前会话的列表。
- `AssessmentWebUrlTest` 用合成链接验证转换和识别。实际页面仍需用有权限的账号验证。
- `AssessmentWebNavigationTest` 用本地网页复现白页和 CSS 禁止滚动，再用模拟器手势验证列表能滑动。传入 `capture=true` 可把截图写到应用缓存。
