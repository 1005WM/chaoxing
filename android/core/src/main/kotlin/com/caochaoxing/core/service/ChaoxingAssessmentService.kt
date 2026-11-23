// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.service

import com.caochaoxing.core.model.ChaoxingAssessmentItem
import com.caochaoxing.core.model.ChaoxingAssessmentKind
import com.caochaoxing.core.model.ChaoxingAssessmentSummary
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.network.HttpResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 读取一门课程的作业（work）与考试（exam）列表，并给出完成情况。
 *
 * 数据源是官方 App / 移动网页使用的 H5 列表：
 * `GET {assessmentBase}/workList?courseId=&classId=` 与 `/examList?...`。
 * 两页都是同源 Cookie 鉴权的 HTML，字段分别是：
 * - 作业：`<dt data="<做题页路径>" status="n"><h3>标题</h3><p>日期</p>…<b>动作</b></dt>`
 * - 考试：`<tr data="…" title="标题" status="n"><td>标题</td><td>分数</td><td>状态</td></tr>`
 *
 * 只读：本服务不会提交、开始或修改任何作业 / 考试。
 * HTML 结构属于未公开接口，服务端改版时解析会失败并明确报错，而不是返回空列表。
 */
object ChaoxingAssessmentService {
    private val workListPath = "/workList"
    private val examListPath = "/examList"

    fun workListUrl(base: String, course: ChaoxingCourse): String =
        "$base$workListPath?courseId=${course.courseId}&classId=${course.classId}"

    fun examListUrl(base: String, course: ChaoxingCourse): String =
        "$base$examListPath?courseId=${course.courseId}&classId=${course.classId}"

    /**
     * 并发读取作业与考试。单边失败不会拖垮另一边：结果里保留成功的一边，
     * 并在 [ChaoxingAssessmentSummary.warnings] 标注哪一边失败；两边都失败才抛异常。
     */
    suspend fun fetchSummary(
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
        nowMs: Long = System.currentTimeMillis(),
        onSample: ((ChaoxingAssessmentKind, String) -> Unit)? = null,
    ): ChaoxingAssessmentSummary =
        fetchSummary(ChaoxingEndpoints.PRODUCTION, client, course, nowMs, onSample)

    internal suspend fun fetchSummary(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
        nowMs: Long = System.currentTimeMillis(),
        onSample: ((ChaoxingAssessmentKind, String) -> Unit)? = null,
    ): ChaoxingAssessmentSummary = coroutineScope {
        val workDeferred = async { attempt { fetchWorkList(endpoints, client, course, onSample) } }
        val examDeferred = async { attempt { fetchExamList(endpoints, client, course, onSample) } }
        val work = workDeferred.await()
        val exam = examDeferred.await()

        if (work.isFailure && exam.isFailure) {
            // A concurrent re-login can invalidate the other list request. Keep the actual login error visible.
            listOfNotNull(work.exceptionOrNull(), exam.exceptionOrNull())
                .firstOrNull { it is ChaoxingError.LoginFailed }?.let { throw it }
            throw work.exceptionOrNull() ?: exam.exceptionOrNull()
                ?: ChaoxingError.ParseFailed("作业与考试列表均加载失败")
        }

        val warnings = buildList {
            work.exceptionOrNull()?.let { add("作业：${it.message ?: "加载失败"}") }
            exam.exceptionOrNull()?.let { add("考试：${it.message ?: "加载失败"}") }
        }
        ChaoxingAssessmentSummary(
            works = work.getOrDefault(emptyList()),
            exams = exam.getOrDefault(emptyList()),
            warnings = warnings,
            fetchedAtMs = nowMs,
        )
    }

    internal suspend fun fetchWorkList(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
        onSample: ((ChaoxingAssessmentKind, String) -> Unit)? = null,
    ): List<ChaoxingAssessmentItem> =
        fetchKind(endpoints, client, course, ChaoxingAssessmentKind.WORK, onSample)

    internal suspend fun fetchExamList(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
        onSample: ((ChaoxingAssessmentKind, String) -> Unit)? = null,
    ): List<ChaoxingAssessmentItem> =
        fetchKind(endpoints, client, course, ChaoxingAssessmentKind.EXAM, onSample)

    private suspend fun fetchKind(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
        kind: ChaoxingAssessmentKind,
        onSample: ((ChaoxingAssessmentKind, String) -> Unit)? = null,
    ): List<ChaoxingAssessmentItem> {
        var lastError: ChaoxingError? = null
        for (base in endpoints.assessmentBases) {
            val url = when (kind) {
                ChaoxingAssessmentKind.WORK -> workListUrl(base, course)
                ChaoxingAssessmentKind.EXAM -> examListUrl(base, course)
            }
            val response: HttpResult = try {
                getWithReauth(endpoints, client, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e as? ChaoxingError
                    ?: ChaoxingError.NetworkError(e.message ?: "网络请求失败")
                continue
            }

            val html = response.bodyAsString()
            onSample?.invoke(
                kind,
                buildString {
                    appendLine("### ${kind.displayName}  HTTP ${response.statusCode}")
                    appendLine(url)
                    appendLine("--- 页面长度 ${html.length} ---")
                    append(html.take(6_000))
                },
            )
            if (isLoginPage(response)) throw ChaoxingError.SessionExpired()
            if (!response.isSuccessful) {
                lastError = ChaoxingError.NetworkError(
                    statusCode = response.statusCode,
                    message = "获取${kind.displayName}列表失败",
                )
                continue
            }
            if (isLoginPageHtml(html)) throw ChaoxingError.SessionExpired()

            val items = when (kind) {
                ChaoxingAssessmentKind.WORK -> parseWorkListHtml(html, url, course)
                ChaoxingAssessmentKind.EXAM -> parseExamListHtml(html, url, course)
            }
            if (items.isNotEmpty() || looksLikeEmptyListPage(html)) return items
            lastError = ChaoxingError.ParseFailed(
                "无法识别${kind.displayName}列表页面，接口可能已变更",
            )
        }
        throw lastError ?: ChaoxingError.ParseFailed("获取${kind.displayName}列表失败")
    }

    private suspend fun getWithReauth(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        url: String,
    ): HttpResult {
        var response = client.get(url)
        if (response.statusCode == 401 || response.statusCode == 302 || isLoginPage(response)) {
            ChaoxingAuthService.reLogin(endpoints, client)
            response = client.get(url)
        }
        return response
    }

    /**
     * 列表页往往只有日期，没有截止时分。对仍未完成、且缺少精确截止时间的作业，
     * 打开它的做题页（只读 GET）再解析一次「截止时间」。
     *
     * 只有同时满足「作业 / 未完成 / 没有精确截止 / 有做题页链接」的条目才会请求，
     * 每门课最多 [maxItems] 个，最多 [concurrency] 路并发；失败或解析不到就保持原样。
     * 只读，不会开始或提交作业。
     */
    suspend fun enrichPendingWorkDeadlines(
        client: ChaoxingHttpClient,
        works: List<ChaoxingAssessmentItem>,
        nowMs: Long = System.currentTimeMillis(),
        maxItems: Int = 8,
        concurrency: Int = 3,
        zone: ZoneId = ZoneId.systemDefault(),
        requireChaoxingHost: Boolean = true,
    ): List<ChaoxingAssessmentItem> {
        val targets = works
            .asSequence()
            .filter {
                it.kind == ChaoxingAssessmentKind.WORK &&
                    it.status.isPending &&
                    it.deadlineAtMs == null &&
                    !it.taskUrl.isNullOrBlank()
            }
            .take(maxItems)
            .toList()
        if (targets.isEmpty()) return works

        val resolved = java.util.concurrent.ConcurrentHashMap<String, ChaoxingAssessmentDeadlineInfo>()
        coroutineScope {
            val semaphore = Semaphore(concurrency)
            targets.forEach { item ->
                launch {
                    semaphore.withPermit {
                        val info = runCatching {
                            fetchDeadlineFromTaskPage(
                                client = client,
                                taskUrl = item.taskUrl.orEmpty(),
                                nowMs = nowMs,
                                zone = zone,
                                requireChaoxingHost = requireChaoxingHost,
                            )
                        }.getOrNull()
                        if (info != null) resolved[item.id] = info
                    }
                }
            }
        }
        if (resolved.isEmpty()) return works
        return works.map { item ->
            resolved[item.id]?.let { info ->
                item.copy(
                    deadlineText = item.deadlineText ?: info.text,
                    deadlineAtMs = item.deadlineAtMs ?: info.atMs,
                )
            } ?: item
        }
    }

    /** 只读打开做题页，要求页面里出现带「截止」等关键词的时间才采信。 */
    private suspend fun fetchDeadlineFromTaskPage(
        client: ChaoxingHttpClient,
        taskUrl: String,
        nowMs: Long,
        zone: ZoneId,
        requireChaoxingHost: Boolean,
    ): ChaoxingAssessmentDeadlineInfo? {
        val uri = runCatching { URI(taskUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (requireChaoxingHost && !host.endsWith(".chaoxing.com") && host != "chaoxing.com") return null
        if (requireChaoxingHost && !uri.scheme.equals("https", ignoreCase = true)) return null

        val (response, html) = try {
            val result = client.get(taskUrl)
            if (!result.isSuccessful) return null
            result to result.bodyAsString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return null
        }
        if (isLoginPageHtml(html)) return null
        return ChaoxingAssessmentDeadline.extract(
            raw = html,
            nowMs = nowMs,
            zone = zone,
            allowSingleUnkeyed = false,
        )
    }

    /** 解析作业列表 `workList` 页面。 */
    fun parseWorkListHtml(
        html: String,
        pageUrl: String,
        course: ChaoxingCourse,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ChaoxingAssessmentItem> {
        val origin = originOf(pageUrl)
        return WORK_ROW_REGEX.findAll(html).mapNotNull { match ->
            val attributes = match.groupValues[1]
            val inner = match.groupValues[2]
            val data = attribute(attributes, "data")
            if (data.isNullOrBlank()) return@mapNotNull null

            val title = cleanText(innerTag(inner, "h3"))
                .ifBlank { cleanText(innerTag(inner, "h2")) }
            if (title.isBlank()) return@mapNotNull null

            val date = cleanText(innerTag(inner, "p"))
            val action = workStatusText(inner)
            val deadline = ChaoxingAssessmentDeadline.extract(
                raw = "$attributes $inner",
                nowMs = nowMs,
                zone = zone,
                allowSingleUnkeyed = false,
            )
            ChaoxingAssessmentItem(
                kind = ChaoxingAssessmentKind.WORK,
                title = title,
                statusText = action,
                detail = date,
                deadlineText = deadline?.text,
                deadlineAtMs = deadline?.atMs,
                rawStatus = attribute(attributes, "status")?.toIntOrNull() ?: 0,
                courseId = course.courseId,
                classId = course.classId,
                courseName = course.courseName,
                taskUrl = resolveUrl(origin, data),
            )
        }.toList()
    }

    /** 解析考试列表 `examList` 页面。 */
    fun parseExamListHtml(
        html: String,
        pageUrl: String,
        course: ChaoxingCourse,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ChaoxingAssessmentItem> {
        val origin = originOf(pageUrl)
        return EXAM_ROW_REGEX.findAll(html).mapNotNull { match ->
            val attributes = match.groupValues[1]
            val inner = match.groupValues[2]
            val data = attribute(attributes, "data")
            if (data.isNullOrBlank()) return@mapNotNull null

            val cells = TD_REGEX.findAll(inner).map { cleanText(it.groupValues[1]) }.toList()
            val title = decodeEntities(attribute(attributes, "title").orEmpty())
                .ifBlank { cells.firstOrNull().orEmpty() }
            if (title.isBlank()) return@mapNotNull null

            val deadline = ChaoxingAssessmentDeadline.extract(
                raw = "$attributes $inner",
                nowMs = nowMs,
                zone = zone,
                allowSingleUnkeyed = true,
            )
            ChaoxingAssessmentItem(
                kind = ChaoxingAssessmentKind.EXAM,
                title = title,
                statusText = cells.getOrNull(2).orEmpty(),
                detail = cells.getOrNull(1).orEmpty(),
                deadlineText = deadline?.text,
                deadlineAtMs = deadline?.atMs,
                rawStatus = attribute(attributes, "status")?.toIntOrNull() ?: 0,
                courseId = course.courseId,
                classId = course.classId,
                courseName = course.courseName,
                taskUrl = resolveUrl(origin, data),
            )
        }.toList()
    }

    /** 页面文案里出现这些词时，视为「确实没有条目」而不是解析失败。 */
    internal fun looksLikeEmptyListPage(html: String): Boolean =
        EMPTY_PAGE_MARKERS.any(html::contains)

    /**
     * 作业行的状态文案不一定在 `<b>` 里：有的版本用 `<i>` / `<em>` / `<span>`，
     * 因此先找带状态关键词的元素，再退回 `<b>`。
     */
    internal fun workStatusText(inner: String): String {
        val candidates = STATUS_TAGS.flatMap { tag ->
            Regex("""<$tag\b[^>]*>(.*?)</$tag>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(inner)
                .map { cleanText(it.groupValues[1]) }
                .filter { it.isNotBlank() }
        }
        return candidates.firstOrNull { text -> STATUS_KEYWORDS.any(text::contains) }
            ?: cleanText(innerTag(inner, "b"))
    }

    internal fun isLoginPageHtml(html: String): Boolean =
        html.length < 80_000 &&
            (LOGIN_TITLE_REGEX.containsMatchIn(html) || PASSWORD_INPUT_REGEX.containsMatchIn(html))

    private fun isLoginPage(response: HttpResult): Boolean {
        val host = runCatching { URI(response.finalUrl).host?.lowercase() }.getOrNull()
        if (host == "passport2.chaoxing.com" || host == "passport.chaoxing.com") return true
        if (response.statusCode != 200) return false
        return isLoginPageHtml(response.bodyAsString())
    }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrElse { "https://mooc1-api.chaoxing.com" }

    internal fun resolveUrl(origin: String, raw: String): String? {
        val value = decodeEntities(raw).trim()
        if (value.isEmpty()) return null
        return when {
            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("/") -> origin + value
            else -> "$origin/$value"
        }
    }

    internal fun attribute(attributes: String, name: String): String? =
        Regex("""(?:^|\s)${Regex.escape(name)}\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(attributes)?.groupValues?.get(1)
            ?: Regex("""(?:^|\s)${Regex.escape(name)}\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE)
                .find(attributes)?.groupValues?.get(1)

    internal fun innerTag(html: String, tag: String): String =
        Regex("""<$tag\b[^>]*>(.*?)</$tag>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1).orEmpty()

    internal fun cleanText(html: String): String =
        decodeEntities(html.replace(TAG_REGEX, " "))
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    internal fun decodeEntities(raw: String): String {
        if (!raw.contains('&')) return raw
        var text = raw
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
        text = NUMERIC_ENTITY_REGEX.replace(text) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        return text
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private val WORK_ROW_REGEX = Regex("""<dt\b([^>]*)>(.*?)</dt>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val EXAM_ROW_REGEX = Regex("""<tr\b([^>]*)>(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TD_REGEX = Regex("""<td\b[^>]*>(.*?)</td>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAG_REGEX = Regex("""<[^>]+>""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val NUMERIC_ENTITY_REGEX = Regex("""&#(\d+);""")
    private val LOGIN_TITLE_REGEX = Regex("""(?is)<title[^>]*>[^<]*登录""")
    private val PASSWORD_INPUT_REGEX = Regex("""(?is)<input[^>]+type\s*=\s*["']password["']""")

    private val STATUS_TAGS = listOf("b", "i", "em", "span", "strong")
    private val STATUS_KEYWORDS = listOf(
        "未交", "已交", "提交", "批阅", "完成", "查看", "做作业", "过期", "缺考", "进行中", "开始",
    )
    private val EMPTY_PAGE_MARKERS = listOf(
        "暂无作业", "暂无考试", "没有作业", "没有考试", "暂无数据", "暂时没有", "无数据",
        "暂未收到", "未收到考试", "暂未发布",
    )
}

/** 解析出的截止时间：`text` 用于展示，`atMs` 在只有日期时为 null。 */
internal data class ChaoxingAssessmentDeadlineInfo(
    val text: String,
    val atMs: Long?,
)

/**
 * 从列表行文本里提取截止时间，精确到时分。
 *
 * 页面没有统一的字段名，因此按「日期时间 + 上下文」判断：
 * - 命中「截止 / 结束 / 到期 / 有效期」等关键词的时间优先；
 * - 带「发布 / 创建 / 布置」的时间视为发布时间，排除；
 * - 形如 `2026-11-27 09:00-11:00` 的区间取结束时间；
 * - 没有关键词时，作业要求至少两个时间（发布 + 截止）才采信，考试允许单个。
 *
 * 只解析页面真实提供的时分；只有日期时返回 `atMs = null`，绝不臆造时间。
 */
internal object ChaoxingAssessmentDeadline {
    private const val CONTEXT = 14
    private const val HALF_YEAR_MS = 183L * 24 * 60 * 60 * 1000

    private val FULL_DATE_TIME = Regex(
        """(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?\s*[T\s]*(\d{1,2})\s*[:：]\s*(\d{2})""",
    )
    private val SHORT_DATE_TIME = Regex(
        """(?<!\d)(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?\s*(\d{1,2})\s*[:：]\s*(\d{2})""",
    )
    private val DATE_ONLY = Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?""")
    private val RANGE_TAIL = Regex("""^\s*[-~—–至到]\s*(\d{1,2})\s*[:：]\s*(\d{2})""")
    private val DEADLINE_MARKERS = listOf("截止", "结束", "到期", "有效期", "最后", "endtime", "deadline")
    private val PUBLISH_MARKERS = listOf("发布", "创建", "布置", "starttime")

    private data class Candidate(
        val atMs: Long,
        val text: String,
        val keyworded: Boolean,
        val publishLike: Boolean,
    )

    fun extract(
        raw: String,
        nowMs: Long,
        zone: ZoneId,
        allowSingleUnkeyed: Boolean,
    ): ChaoxingAssessmentDeadlineInfo? {
        val text = raw.replace('\u00A0', ' ')
        if (text.isBlank()) return null

        val candidates = mutableListOf<Candidate>()
        FULL_DATE_TIME.findAll(text).forEach { match ->
            val date = runCatching {
                LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }.getOrNull() ?: return@forEach
            var time = runCatching {
                LocalTime.of(match.groupValues[4].toInt(), match.groupValues[5].toInt())
            }.getOrNull() ?: return@forEach

            var endIndex = match.range.last
            val tail = text.substring(endIndex + 1).take(16)
            RANGE_TAIL.find(tail)?.let { range ->
                val parsed = runCatching {
                    LocalTime.of(range.groupValues[1].toInt(), range.groupValues[2].toInt())
                }.getOrNull()
                if (parsed != null) {
                    time = parsed
                    endIndex += range.range.last + 1
                }
            }

            val window = window(text, match.range.first, endIndex)
            candidates += Candidate(
                atMs = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli(),
                text = "$date $time",
                keyworded = DEADLINE_MARKERS.any(window::contains),
                publishLike = PUBLISH_MARKERS.any(window::contains),
            )
        }

        if (candidates.isEmpty()) {
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            SHORT_DATE_TIME.findAll(text).forEach { match ->
                val month = match.groupValues[1].toInt()
                val day = match.groupValues[2].toInt()
                val time = runCatching {
                    LocalTime.of(match.groupValues[3].toInt(), match.groupValues[4].toInt())
                }.getOrNull() ?: return@forEach
                var year = now.year
                var date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return@forEach
                var epoch = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
                if (epoch < nowMs - HALF_YEAR_MS) {
                    year += 1
                    date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return@forEach
                    epoch = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
                }
                val window = window(text, match.range.first, match.range.last)
                candidates += Candidate(
                    atMs = epoch,
                    text = "$date $time",
                    keyworded = DEADLINE_MARKERS.any(window::contains),
                    publishLike = PUBLISH_MARKERS.any(window::contains),
                )
            }
        }

        if (candidates.isEmpty()) {
            // 只有日期、没有时分：仅在明确是「截止」且有别于发布信息时采用。
            DATE_ONLY.findAll(text).lastOrNull { match ->
                val window = window(text, match.range.first, match.range.last)
                DEADLINE_MARKERS.any(window::contains) && PUBLISH_MARKERS.none(window::contains)
            }?.let { return ChaoxingAssessmentDeadlineInfo(it.value, null) }
            return null
        }

        val usable = candidates.filterNot { it.publishLike }
        val chosen = usable.lastOrNull { it.keyworded }
            ?: usable.lastOrNull()?.takeIf { allowSingleUnkeyed || usable.size >= 2 }
            ?: return null
        return ChaoxingAssessmentDeadlineInfo(chosen.text, chosen.atMs)
    }

    private fun window(text: String, start: Int, end: Int): String {
        val from = (start - CONTEXT).coerceAtLeast(0)
        val to = (end + CONTEXT).coerceAtMost(text.length)
        return text.substring(from, to)
    }
}
