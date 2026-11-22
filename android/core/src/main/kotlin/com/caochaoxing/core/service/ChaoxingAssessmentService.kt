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
import java.net.URI

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
    ): ChaoxingAssessmentSummary =
        fetchSummary(ChaoxingEndpoints.PRODUCTION, client, course, nowMs)

    internal suspend fun fetchSummary(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
        nowMs: Long = System.currentTimeMillis(),
    ): ChaoxingAssessmentSummary = coroutineScope {
        val workDeferred = async { attempt { fetchWorkList(endpoints, client, course) } }
        val examDeferred = async { attempt { fetchExamList(endpoints, client, course) } }
        val work = workDeferred.await()
        val exam = examDeferred.await()

        if (work.isFailure && exam.isFailure) {
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
    ): List<ChaoxingAssessmentItem> =
        fetchKind(endpoints, client, course, ChaoxingAssessmentKind.WORK)

    internal suspend fun fetchExamList(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
    ): List<ChaoxingAssessmentItem> =
        fetchKind(endpoints, client, course, ChaoxingAssessmentKind.EXAM)

    private suspend fun fetchKind(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
        kind: ChaoxingAssessmentKind,
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

            if (isLoginPage(response)) throw ChaoxingError.SessionExpired()
            if (!response.isSuccessful) {
                lastError = ChaoxingError.NetworkError(
                    statusCode = response.statusCode,
                    message = "获取${kind.displayName}列表失败",
                )
                continue
            }

            val html = response.bodyAsString()
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

    /** 解析作业列表 `workList` 页面。 */
    fun parseWorkListHtml(
        html: String,
        pageUrl: String,
        course: ChaoxingCourse,
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
            val action = cleanText(innerTag(inner, "b"))
            ChaoxingAssessmentItem(
                kind = ChaoxingAssessmentKind.WORK,
                title = title,
                statusText = action,
                detail = date,
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

            ChaoxingAssessmentItem(
                kind = ChaoxingAssessmentKind.EXAM,
                title = title,
                statusText = cells.getOrNull(2).orEmpty(),
                detail = cells.getOrNull(1).orEmpty(),
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

    private val EMPTY_PAGE_MARKERS = listOf(
        "暂无作业", "暂无考试", "没有作业", "没有考试", "暂无数据", "暂时没有", "无数据",
    )
}
