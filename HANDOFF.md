# 交接说明

## 应用更新和发布

- 更新源为 `1005WM/chaoxing` 的 GitHub Releases。Android 的 `UpdateSource` 和 Swift 的 `AppUpdateSource` 可配置镜像 `latest.json`。
- Android 镜像元数据超时为 2 秒，GitHub API 失败后还会尝试 Release 的 `latest.json`。检查结果缓存 5 分钟。
- Android 下载进度条只放在更新弹窗中，位于更新说明上方。显示百分比和已下载大小；总大小未知时显示不定进度。
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
- 电脑版按最内层题目容器分题，支持 `mark_letter` 和 `answerBg` 选项。不能把作业标题和题号导航当成题目。
- 题目标题可补充定位特殊容器。首题外层包含后续题目时，提取首题要排除已识别的子题，避免漏首题或合并多题。
- 跨域题目 iframe 由 `AiQuestionPageReader` 用当前账号 Cookie 和原页面 UA 单独读取。只允许 HTTPS 学习通地址，单次最多 8 个页面，总预算 90 秒。每页图文处理预算 30 秒，图像编码总量限制为 24 MB。
- 题目未读全时最多补扫 8 次，间隔 400 毫秒。补扫会滚动题目容器以触发加载，结束后恢复滚动位置。保留读到题目最多的一次结果。
- 题量不一致只提醒，不阻止生成。识别到几道就生成几道；一道也没读到时仍阻止请求模型。模拟页覆盖跨域框架、嵌套首题、特殊容器、滚动加载和部分题目生成。学校真实页面和真实模型仍需实测。
- `AiQuestionDomMedia` 按 DOM 顺序保留文字与题图位置，优先读取 LaTeX、MathML 和 MathJax 公式结构。没有公式原文的 SVG 转成 PNG，题干图与选项图分别标记。可见图形的 `aria-hidden` 不代表不显示，不能据此丢掉公式。
- 1 MB 内且长边不超过 2048 像素的 PNG 保留原字节，避免公式被 JPEG 压糊。截图前会等待正式懒加载题图和页面字体。
- 图片不能直接读取、题目使用 CSS 图片或加密字体时，回退为完整单题截图。截图分片按顺序发送，最多 6 张。无法截全时设置 `readError`，只阻止该题请求模型，其他题继续处理。
- 硬件加速的 WebView 不能依赖 `draw(Canvas)` 截图，可能得到白图。`AiQuestionScreenshot` 用 PixelCopy 读取窗口像素；后台章节页面仅在需要截图时创建私有显示窗口，结束后释放。不会新增录屏权限或常驻截图服务。
- 截图只包含题目，遮蔽答案解析、输入框、其他题和题外浮层。结束或取消时恢复样式和滚动位置。补扫计数阶段不重复编码图片。
- 「查看识别内容」可预览提取的文字和实际题图。模拟器测试核对 SVG 像素、题干与选项标签、模型请求中的图片消息、屏外选项、题外内容隔离和后台窗口释放。实际作业仍需用其 DOM 和真实模型核对。
- 调研参考：[OCS 结构化图文提取](https://github.com/ocsjs/ocsjs/blob/09628b7c5df3e91317cb758e7f38b8bcf49e2ff6/packages/scripts/src/utils/work.ts#L369)与[学习通 AI 助手的公式和媒体处理](https://github.com/zhu-hailin/chaoxing-ai-assistant/blob/64d50ffb04610edc4592480df0e754a2817ac2fd/sources/question-parser.js#L62)。

## Android 作业总览

- 首页逾期数只统计作业。服务端标为过期的作业，以及仍未完成但已过截止时间的作业，都计入逾期。

## Android 作业网页

- 链接转换和 WebView 导航是两个入口。改电脑版时，要同时检查初始链接、页面重定向和弹窗转发。
- 桌面 User-Agent 不会把手机专用路径变成电脑版。排查时看 WebView 最终加载的路径，再看页面布局。
- `/mooc-ans/mooc2/work/list` 仅带课程、班级和 CPI 时曾在真机返回白页。回退时打开电脑版课程入口，通过页面的「作业」标签取到当前会话的列表。
- `AssessmentWebUrlTest` 用合成链接验证转换和识别。实际页面仍需用有权限的账号验证。
- `AssessmentWebNavigationTest` 用本地网页复现白页和 CSS 禁止滚动，再用模拟器手势验证列表能滑动。传入 `capture=true` 可把截图写到应用缓存。
