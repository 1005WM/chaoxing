# Android 作业 / 考试读取（本 fork 新增）

更新：2026-11-23。仅 Android 端实现；iOS / macOS 尚未接入。

## 功能

课程详情页在「签到」「看课」之外新增第三个分栏 **作业/考试**，只读展示：

- 该课程下全部作业与考试的名称、服务端原始状态文案、发布日期（作业）或分数（考试）；
- 顶部汇总：未完成 / 已完成 / 已过期 / 状态未知的项数，以及「更新于」时间；
- 作业与考试分节展示，各自统计「N 项 · M 项未完成」；
- 下拉刷新与顶栏刷新按钮；加载失败时保留上次结果并提示，重试不会清空已有数据。

**只读**：本功能不打开做题页、不提交、不开始任何作业或考试，也不会修改服务端状态。

## 数据源

官方 App / 移动网页的 H5 列表页，同源 Cookie 鉴权：

```text
GET https://mooc1-api.chaoxing.com/phone/moocAnalysis/workList?courseId={courseId}&classId={classId}
GET https://mooc1-api.chaoxing.com/phone/moocAnalysis/examList?courseId={courseId}&classId={classId}
```

请求失败或返回无法识别的页面时，依次回退到 `mooc1.chaoxing.com` 与 `mooc1-2.chaoxing.com` 的同路径。
`mooc1-api` 是当前客户端常用网关；三套主机共用同一套 Cookie 会话。

### 页面结构

作业（`workList`）：

```html
<dt data="work/phone/task-work?courseId=…&classId=…&workId=…" status="1">
  <h3>第一章 极限（客观题）</h3>
  <p>2026-11-20 发布</p>
  <b>做作业</b>
</dt>
```

- `data`：做题页相对路径，本功能只保存为 `taskUrl`，不自动打开；
- `<h3>`：标题；`<p>`：发布日期；`<b>`：动作/状态文案。

考试（`examList`）：

```html
<tr data="/exam/phone/task-exam?examId=…" title="期中考试" status="2">
  <td>期中考试</td><td>86</td><td>已批阅</td>
</tr>
```

- `title` 属性或第一个 `<td>`：标题；第二个 `<td>`：分数；第三个 `<td>`：状态。

## 状态归一化

服务端在不同页面用不同文案表达同一状态，统一归为：

| 统一状态 | 识别文案（包含即命中） | 计入 |
| --- | --- | --- |
| 已批阅 | 已批阅、已评阅、已评分、已出分、已批改、批阅完成 | 已完成 |
| 待批阅 | 待批阅、待评阅、待评分、待批改、批阅中、评阅中 | 已完成 |
| 已过期 | 已过期、过期、已结束、已截止、缺考、未参加 | 已过期 |
| 未完成 | 未完成、未交、未提交、未开始、未做、未作答、未考、做作业、开始考试… | 未完成 |
| 进行中 | 进行中、考试中、作答中、正在 | 未完成 |
| 已提交 | 已交卷、已提交、已交、已作答、已完成、已通过、完成 | 已完成 |
| 状态未知 | 其余文案（如「查看」） | 不计入未完成 |

判定顺序是固定的：先判「已批阅 / 待批阅 / 已过期」，再判带否定前缀的「未完成」类，
然后才是「已交 / 完成」，避免「未完成」被「完成」误命中。界面始终优先显示服务端原文，
所以即使归类为「状态未知」，「是否写完」仍可从原文看出。

## 代码位置

| 层 | 文件 |
| --- | --- |
| 模型与状态归类 | `android/core/src/main/kotlin/com/caochaoxing/core/model/ChaoxingAssessment.kt` |
| 读取与 HTML 解析 | `android/core/src/main/kotlin/com/caochaoxing/core/service/ChaoxingAssessmentService.kt` |
| 端点与回退主机 | `android/core/src/main/kotlin/com/caochaoxing/core/service/ChaoxingAuthService.kt`（`assessmentBase` / `assessmentFallbackBases`） |
| 页面状态控制器 | `android/app/src/main/kotlin/com/caochaoxing/android/state/AssessmentController.kt` |
| 分栏 UI | `android/app/src/main/kotlin/com/caochaoxing/android/ui/screens/CourseAssessmentPanel.kt` |
| 入口 | `android/app/src/main/kotlin/com/caochaoxing/android/ui/screens/CourseDetailScreen.kt`（第三个分栏） |

控制器按账号隔离：`CaochaoxingApp` 订阅 `AndroidAppState` 的当前账号，切换或退出时取消在途请求并清空缓存。

## 验证范围

- `:core:test`：78 项通过，其中新增 12 项覆盖作业/考试解析、状态归类、汇总统计、
  两个端点并发合并、单边失败保留另一边、两边失败报错、登录页触发重登、回退主机、空列表页与 HTML 实体。
- `:app:testDebugUnitTest`：新增控制器用例覆盖缓存、强制刷新、失败保留旧数据、账号切换清空、无会话提示。
- 测试使用 `MockWebServer` 与合成的 HTML，**不访问真实服务端**。

### 尚未验证

- 真实账号下 `mooc1-api` / `mooc1` / `mooc1-2` 三套主机的实际返回结构，以及是否需要额外的
  `cpi`、`ut`、`Referer` 参数；
- 列表分页（若服务端对超过一页的作业/考试分页返回）；
- 与学校/教师后台的「已交 / 已批阅」结果是否一一对应。

如果真实页面结构与上文不同，解析会明确报「无法识别作业列表页面」而不是静默返回空列表；
把脱敏后的页面片段（去掉姓名、学号、Cookie）贴到 Issue 即可对照修正正则。
