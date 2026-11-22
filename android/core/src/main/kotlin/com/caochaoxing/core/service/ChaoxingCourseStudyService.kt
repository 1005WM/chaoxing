// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.service

import com.caochaoxing.core.crypto.ChaoxingCrypto
import com.caochaoxing.core.model.ChaoxingAttachmentKind
import com.caochaoxing.core.model.ChaoxingChapterAttachment
import com.caochaoxing.core.model.ChaoxingChapterContent
import com.caochaoxing.core.model.ChaoxingChapterNode
import com.caochaoxing.core.model.ChaoxingCourseChaptersResult
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingLiveHeartbeatResponse
import com.caochaoxing.core.model.ChaoxingLiveInfo
import com.caochaoxing.core.model.ChaoxingLiveSessionContext
import com.caochaoxing.core.model.ChaoxingLiveTask
import com.caochaoxing.core.model.ChaoxingVideoAssetStatus
import com.caochaoxing.core.model.ChaoxingVideoDragEvent
import com.caochaoxing.core.model.ChaoxingVideoLogResponse
import com.caochaoxing.core.model.ChaoxingVideoTask
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.network.HttpResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Fetches Chaoxing course chapter trees, multi-tab video/document task points,
 * reports signed video watch logs, and completes document task points.
 */
object ChaoxingCourseStudyService {
    private const val ENC_SALT = "d_yHJ!\$pdA~5"
    private const val VIDEO_REFERER = "https://mooc1-2.chaoxing.com/ananas/modules/video/index.html"
    private const val AUDIO_REFERER = "https://mooc1-2.chaoxing.com/ananas/modules/audio/index.html"
    private const val LIVE_REFERER = "https://mooc1-2.chaoxing.com/ananas/modules/live/index.html"
    private const val DOCUMENT_REFERER = "https://mooc1-2.chaoxing.com/ananas/modules/pdf/index.html"

    /**
     * Desktop Chrome User-Agent used on PC web study endpoints (`mooc1-2`, `mooc2-ans`, `view=pc`).
     * Mobile API endpoints like `gas/clazz` intentionally retain the client's App UA.
     */
    const val PC_WEB_USER_AGENT: String =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val PC_WEB_HEADERS: Map<String, String> = mapOf("User-Agent" to PC_WEB_USER_AGENT)

    private val LOGIN_PAGE_REGEX = Regex(
        pattern = """<title[^>]*>[^<]*登录|<input[^>]+type\s*=\s*["']password["']""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val STUDENT_COURSE_LINK_REGEX = Regex(
        pattern = """<a\b([^>]*studentstudy\?chapterId=(\d+)[^>]*)>([\s\S]*?)</a>""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    private val HEADING_REGEX = Regex(
        pattern = """<(h3|h4|h5)\b([^>]*)>([\s\S]*?)</\1>""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    private val MOOC2_ITEM_REGEX = Regex(
        pattern = """<div\b([^>]*\bchapter_item\b[^>]*)>([\s\S]*?)</div>\s*</div>""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    private val GET_TEACHER_AJAX_REGEX = Regex(
        """getTeacherAjax\s*\(\s*['"][^'"]*['"]\s*,\s*['"][^'"]*['"]\s*,\s*['"](\d+)['"]""",
    )
    private val CUR_ID_REGEX = Regex("""id\s*=\s*['"]cur(\d+)['"]""")
    private val TO_OLD_REGEX = Regex("""toOld\s*\(\s*['"][^'"]*['"]\s*,\s*['"](\d+)['"]""")
    private val ARIA_LABEL_REGEX = Regex("""aria-label\s*=\s*["']([^"']+)["']""")
    private val TITLE_ATTR_REGEX = Regex("""title\s*=\s*["']([^"']+)["']""")
    private val CATALOG_SBAR_REGEX = Regex("""class\s*=\s*["'][^"']*catalog_sbar[^"']*["'][^>]*>([^<]+)<""")
    private val CATALOG_POINTS_YI_REGEX = Regex("""class\s*=\s*["'][^"']*catalog_points_yi[^"']*["'][^>]*>(\d+)<""")
    private val HIDDEN_UNFINISH_1 = Regex(
        """<input[^>]*value\s*=\s*["'](\d+)["'][^>]*class\s*=\s*["'][^"']*jobUnfinishCount[^"']*["']""",
    )
    private val HIDDEN_UNFINISH_2 = Regex(
        """<input[^>]*class\s*=\s*["'][^"']*jobUnfinishCount[^"']*["'][^>]*value\s*=\s*["'](\d+)["']""",
    )
    private val ORANGE_JOB_COUNT_REGEX = Regex(
        """<span[^>]*class\s*=\s*["'][^"']*(?:orange01|jobCount)[^"']*["'][^>]*>\s*(\d+)\s*<""",
    )
    private val ROUNDPOINT_REVIEW_LOCK_REGEX = Regex("""class\s*=\s*["'][^"']*roundpoint[^"']*\b(review|lock)\b""")
    private val ROUNDPOINT_DONE_REGEX = Regex("""class\s*=\s*["'][^"']*roundpoint[^"']*\b(blue|noJob)\b""")
    private val CARD_COUNT_1 = Regex("""id\s*=\s*["']cardcount["'][^>]*value\s*=\s*["'](\d+)["']""")
    private val CARD_COUNT_2 = Regex("""value\s*=\s*["'](\d+)["'][^>]*id\s*=\s*["']cardcount["']""")
    private val FID_REGEX = Regex(""""fid"\s*:\s*['"]?(\d+)['"]?""")
    private val MARG_ANCHOR_REGEX = Regex("""mArg\s*=\s*\{""")
    private val CPI_REGEX = Regex("""cpi\s*[:=]\s*['"]?(\d{5,})""")
    private val REST_TIME_REGEX = Regex("""\[restTime:(\d+)]""")
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val SHORT_LIVE_ID_REGEX = Regex("""var\s+liveId\s*=\s*['"]?(\d+)['"]?""")
    private val UINFO_REGEX = Regex("""var\s+uInfo\s*=\s*['"]([^'"]+)['"]""")
    private val LIVE_DURATION_REGEX = Regex("""var\s+liveduration\s*=\s*(\d+)""")
    private val STREAM_NAME_REGEX = Regex("""var\s+streamName\s*=\s*['"]([^'"]+)['"]""")
    private val VDOID_REGEX = Regex("""var\s+vdoid\s*=\s*['"]([^'"]+)['"]""")
    private val LIVE_FID_REGEX = Regex("""var\s+fid\s*=\s*['"]?(\d+)['"]?""")
    private val LIVE_M3U8_URLS_REGEX = Regex("""urls\s*=\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
    private val LIVE_ORIGIN_M3U8_REGEX = Regex("""'originM3u8Record'\s*:\s*'(https?://[^']+)'""")
    private val LIVE_SSO_IFRAME_REGEX = Regex("""src\s*=\s*["'](/iframeLoginSSO\?u=[A-Za-z0-9_-]+)["']""")

    // MARK: - Chapters

    suspend fun fetchCourseChapters(
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        knownCpi: Long = 0L,
    ): ChaoxingCourseChaptersResult =
        fetchCourseChapters(ChaoxingEndpoints.PRODUCTION, client, courseId, classId, knownCpi)

    internal suspend fun fetchCourseChapters(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        knownCpi: Long = 0L,
    ): ChaoxingCourseChaptersResult {
        // 1. Fastest & richest path when `cpi` is known: `studentstudycourselist` (h3/h4/h5 + exact job counts).
        if (knownCpi > 0L) {
            val listChapters = runCatchingNonCancellation {
                fetchCourseListEndpoint(endpoints, client, courseId, classId, knownCpi)
            }.orEmpty()
            if (listChapters.isNotEmpty()) {
                return ChaoxingCourseChaptersResult(cpi = knownCpi, chapters = listChapters)
            }
        }

        // 2. HTML redirect path: `visit/stucoursemiddle`
        var discoveredCpi = knownCpi
        var confirmedValidEmptyPage = false
        val middleUrl = "${endpoints.studyBase}/visit/stucoursemiddle?courseid=$courseId&clazzid=$classId&vc=1&cpi=${max(0L, knownCpi)}&ismooc2=0"
        runCatchingNonCancellation {
            getHtmlWithReauth(endpoints, client, middleUrl, PC_WEB_HEADERS, "获取课程章节失败")
        }?.let { html ->
            val parsed = parseCourseChaptersHtml(html, courseId, classId)
            if (parsed.cpi > 0L) discoveredCpi = parsed.cpi
            if (discoveredCpi > 0L) {
                val enriched = runCatchingNonCancellation {
                    fetchCourseListEndpoint(endpoints, client, courseId, classId, discoveredCpi)
                }.orEmpty()
                if (enriched.isNotEmpty()) {
                    return ChaoxingCourseChaptersResult(cpi = discoveredCpi, chapters = enriched)
                }
            }
            if (parsed.chapters.isNotEmpty()) {
                return ChaoxingCourseChaptersResult(cpi = discoveredCpi, chapters = parsed.chapters)
            }
            if (html.contains("暂无章节") || html.contains("studentcourse")) {
                confirmedValidEmptyPage = true
            }
        }

        // 3. `mooc2-ans/mycourse/studentcourse` fallback
        val mooc2Url = "${endpoints.mooc2Base}/mooc2-ans/mycourse/studentcourse?courseid=$courseId&clazzid=$classId&cpi=${max(0L, discoveredCpi)}&ut=s"
        runCatchingNonCancellation {
            getHtmlWithReauth(endpoints, client, mooc2Url, PC_WEB_HEADERS, "获取新版课程章节失败")
        }?.let { html ->
            val parsed = parseCourseChaptersHtml(html, courseId, classId)
            if (parsed.cpi > 0L) discoveredCpi = parsed.cpi
            if (parsed.chapters.isNotEmpty()) {
                return ChaoxingCourseChaptersResult(cpi = discoveredCpi, chapters = parsed.chapters)
            }
            if (html.contains("暂无章节内容") || html.contains("已完成任务点")) {
                confirmedValidEmptyPage = true
            }
        }

        // 4. Fallback: `mooc1-api.chaoxing.com/gas/clazz` JSON knowledge tree (uses default Chaoxing App UA).
        val gasUrl = "${endpoints.gasClazzTreeBase}?id=$classId&fields=id,cpi,name,course.fields(id,name,knowledge.fields(id,name,indexOrder,parentnodeid,status,layer,label))&view=json"
        val response = getWithReauth(endpoints, client, gasUrl, reauthOnForbidden = true)
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "获取课程章节失败 (${response.statusCode})")
        }
        val (gasCpi, gasChapters, isValidCourse) = parseGasClazzChaptersJsonDetailed(response.body, courseId, classId)
        val finalCpi = if (gasCpi > 0L) gasCpi else discoveredCpi
        if (finalCpi > 0L) {
            val enriched = runCatchingNonCancellation {
                fetchCourseListEndpoint(endpoints, client, courseId, classId, finalCpi)
            }.orEmpty()
            if (enriched.isNotEmpty()) {
                return ChaoxingCourseChaptersResult(cpi = finalCpi, chapters = enriched)
            }
        }
        if (gasChapters.isNotEmpty()) {
            return ChaoxingCourseChaptersResult(cpi = finalCpi, chapters = gasChapters)
        }
        if (isValidCourse || confirmedValidEmptyPage) {
            return ChaoxingCourseChaptersResult(cpi = finalCpi, chapters = emptyList())
        }
        throw ChaoxingError.ParseFailed("未解析到课程章节，可能需要重新登录")
    }

    private suspend fun fetchCourseListEndpoint(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        cpi: Long,
    ): List<ChaoxingChapterNode> {
        val url = "${endpoints.studyBase}/mooc-ans/mycourse/studentstudycourselist?courseId=$courseId&chapterId=0&clazzid=$classId&cpi=$cpi"
        val headers = PC_WEB_HEADERS + ("X-Requested-With" to "XMLHttpRequest")
        val html = getHtmlWithReauth(endpoints, client, url, headers, "获取章节列表失败")
        return parseCourseChaptersHtml(html, courseId, classId, hasExplicitJobCounts = true).chapters
    }

    fun parseGasClazzChaptersJson(
        data: ByteArray,
        courseId: Long,
        classId: Int,
    ): ChaoxingCourseChaptersResult {
        val (cpi, chapters, _) = parseGasClazzChaptersJsonDetailed(data, courseId, classId)
        return ChaoxingCourseChaptersResult(cpi = cpi, chapters = chapters)
    }

    private fun parseGasClazzChaptersJsonDetailed(
        data: ByteArray,
        courseId: Long,
        classId: Int,
    ): Triple<Long, List<ChaoxingChapterNode>, Boolean> {
        val root = parseJsonObjectOrNull(data) ?: return Triple(0L, emptyList(), false)
        val clazz = root.arrayOrNull("data")?.firstOrNull() as? JsonObject
            ?: return Triple(0L, emptyList(), false)
        val cpi = clazz.longOrStringLongOrNull("cpi") ?: 0L
        val courseObj = clazz.objectOrNull("course")?.arrayOrNull("data")?.firstOrNull() as? JsonObject
            ?: return Triple(cpi, emptyList(), true)
        val rawNodes = courseObj.objectOrNull("knowledge")?.arrayOrNull("data")
            ?: return Triple(cpi, emptyList(), true)

        data class RawNode(
            val id: Long,
            val name: String,
            val label: String,
            val indexOrder: Int,
            val parentId: Long,
            val layer: Int,
        )

        val nodes = rawNodes.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val id = obj.longOrStringLongOrNull("id") ?: return@mapNotNull null
            RawNode(
                id = id,
                name = obj.stringOnlyOrNull("name").orEmpty(),
                label = obj.stringOnlyOrNull("label").orEmpty(),
                indexOrder = obj.intOrStringIntOrNull("indexOrder") ?: 0,
                parentId = obj.longOrStringLongOrNull("parentnodeid") ?: 0L,
                layer = obj.intOrStringIntOrNull("layer") ?: 1,
            )
        }

        val byId = nodes.associateBy { it.id }
        val parentIds = nodes.mapNotNull { it.parentId.takeIf { pid -> pid > 0L } }.toSet()

        fun orderPath(node: RawNode): List<Int> {
            val path = mutableListOf(node.indexOrder)
            var current = node
            var depth = 0
            while (current.parentId > 0L && depth < 8) {
                val parent = byId[current.parentId] ?: break
                path.add(parent.indexOrder)
                current = parent
                depth++
            }
            return path.asReversed()
        }

        val leafNodes = nodes.filter { it.id !in parentIds }.sortedWith { lhs, rhs ->
            val lp = orderPath(lhs)
            val rp = orderPath(rhs)
            val minLen = min(lp.size, rp.size)
            for (i in 0 until minLen) {
                if (lp[i] != rp[i]) return@sortedWith lp[i].compareTo(rp[i])
            }
            if (lp.size != rp.size) return@sortedWith lp.size.compareTo(rp.size)
            lhs.id.compareTo(rhs.id)
        }

        val chapters = leafNodes.map { node ->
            val ancestors = mutableListOf<String>()
            var curParentId = node.parentId
            var depth = 0
            while (curParentId > 0L && depth < 6) {
                val p = byId[curParentId] ?: break
                val text = if (p.label.isEmpty()) p.name else "${p.label} ${p.name}"
                if (text.isNotEmpty()) ancestors.add(text)
                curParentId = p.parentId
                depth++
            }
            val sectionTitle = ancestors.asReversed().joinToString(" / ").trim()
            val title = (if (node.label.isEmpty()) node.name else "${node.label} ${node.name}").trim()
            ChaoxingChapterNode(
                id = node.id.toString(),
                title = title,
                sectionTitle = sectionTitle,
                courseId = courseId,
                classId = classId,
                pendingJobCount = null,
            )
        }
        return Triple(cpi, chapters, true)
    }

    fun parseCourseChaptersHtml(
        html: String,
        courseId: Long,
        classId: Int,
        hasExplicitJobCounts: Boolean = false,
    ): ChaoxingCourseChaptersResult {
        val cpi = extractCpi(html) ?: 0L
        val chapters = mutableListOf<ChaoxingChapterNode>()
        val seenIds = HashSet<String>()

        // Format A: Classic `studentcourse` page (`<a href="...studentstudy?chapterId=...">`)
        if (!hasExplicitJobCounts) {
            for (match in STUDENT_COURSE_LINK_REGEX.findAll(html)) {
                val attrs = match.groupValues[1]
                val chapterId = match.groupValues[2]
                val inner = match.groupValues[3]
                if (chapterId.isEmpty() || !seenIds.add(chapterId)) continue
                val aria = ARIA_LABEL_REGEX.find(attrs)?.groupValues?.getOrNull(1)
                val title = (aria?.let { stripHtml(it) } ?: stripHtml(inner)).ifEmpty { "章节 $chapterId" }
                chapters.add(
                    ChaoxingChapterNode(
                        id = chapterId,
                        title = title,
                        sectionTitle = "",
                        courseId = courseId,
                        classId = classId,
                        pendingJobCount = null,
                    ),
                )
            }
            if (chapters.isNotEmpty()) {
                return ChaoxingCourseChaptersResult(cpi = cpi, chapters = chapters)
            }
        }

        // Format B: `studentstudycourselist` / `studentstudy` (`<h3>` + `<h4 id="cur...">` + `<h5 id="cur...">`)
        var currentH3 = ""
        var currentH4 = ""
        for (match in HEADING_REGEX.findAll(html)) {
            val tag = match.groupValues[1].lowercase(Locale.ROOT)
            val attrs = match.groupValues[2]
            val inner = match.groupValues[3]

            if (tag == "h3") {
                val text = stripHtml(inner)
                if (text.isNotEmpty() && text != "提示") {
                    currentH3 = text
                    currentH4 = ""
                }
                continue
            }

            val chapterId = GET_TEACHER_AJAX_REGEX.find(inner)?.groupValues?.getOrNull(1)
                ?: CUR_ID_REGEX.find(attrs)?.groupValues?.getOrNull(1)
            if (chapterId.isNullOrEmpty() || !seenIds.add(chapterId)) continue

            val rawText = stripHtml(inner)
            val (badgeCount, cleanTitle) = splitLeadingPendingBadge(rawText)
            val finalTitle = cleanTitle.ifEmpty { "章节 $chapterId" }
            val sectionTitle = if (tag == "h4") {
                currentH4 = finalTitle
                currentH3
            } else {
                when {
                    currentH3.isNotEmpty() && currentH4.isNotEmpty() -> "$currentH3 / $currentH4"
                    currentH4.isNotEmpty() -> currentH4
                    else -> currentH3
                }
            }

            val pendingCount = extractPendingJobCount(inner, badgeCount, hasExplicitJobCounts)
            chapters.add(
                ChaoxingChapterNode(
                    id = chapterId,
                    title = finalTitle,
                    sectionTitle = sectionTitle,
                    courseId = courseId,
                    classId = classId,
                    pendingJobCount = pendingCount,
                ),
            )
        }
        if (chapters.isNotEmpty()) {
            return ChaoxingCourseChaptersResult(cpi = cpi, chapters = chapters)
        }

        // Format C: `mooc2-ans/mycourse/studentcourse` (`chapter_item` + `toOld(...)`)
        for (match in MOOC2_ITEM_REGEX.findAll(html)) {
            val attrs = match.groupValues[1]
            val inner = match.groupValues[2]
            val chapterId = TO_OLD_REGEX.find(attrs)?.groupValues?.getOrNull(1)
                ?: CUR_ID_REGEX.find(attrs)?.groupValues?.getOrNull(1)
            if (chapterId.isNullOrEmpty() || !seenIds.add(chapterId)) continue

            val sbar = CATALOG_SBAR_REGEX.find(inner)?.groupValues?.getOrNull(1)?.let { stripHtml(it) }.orEmpty()
            val titleAttr = TITLE_ATTR_REGEX.find(attrs)?.groupValues?.getOrNull(1)?.let { stripHtml(it) }.orEmpty()
            val combinedTitle = when {
                sbar.isNotEmpty() && titleAttr.isNotEmpty() && !titleAttr.startsWith(sbar) -> "$sbar $titleAttr"
                titleAttr.isNotEmpty() -> titleAttr
                else -> stripHtml(inner)
            }.ifEmpty { "章节 $chapterId" }

            val pendingCount = CATALOG_POINTS_YI_REGEX.find(inner)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: if (inner.contains("icon_yiwanc")) 0 else null

            chapters.add(
                ChaoxingChapterNode(
                    id = chapterId,
                    title = combinedTitle,
                    sectionTitle = "",
                    courseId = courseId,
                    classId = classId,
                    pendingJobCount = pendingCount,
                ),
            )
        }

        return ChaoxingCourseChaptersResult(cpi = cpi, chapters = chapters)
    }

    private fun extractPendingJobCount(
        inner: String,
        leadingBadgeCount: Int?,
        hasExplicitJobCounts: Boolean,
    ): Int? {
        val hiddenVal = (HIDDEN_UNFINISH_1.find(inner) ?: HIDDEN_UNFINISH_2.find(inner))
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (hiddenVal != null) return hiddenVal

        val orangeVal = ORANGE_JOB_COUNT_REGEX.find(inner)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (orangeVal != null) return orangeVal

        if (leadingBadgeCount != null) return leadingBadgeCount

        if (ROUNDPOINT_REVIEW_LOCK_REGEX.containsMatchIn(inner)) return null
        if (ROUNDPOINT_DONE_REGEX.containsMatchIn(inner)) return 0

        return if (hasExplicitJobCounts) 0 else null
    }

    // MARK: - Chapter Content & Tasks

    suspend fun fetchChapterCardCount(
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        chapterId: String,
        cpi: Long,
    ): Int = fetchChapterCardCount(ChaoxingEndpoints.PRODUCTION, client, courseId, classId, chapterId, cpi)

    internal suspend fun fetchChapterCardCount(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        chapterId: String,
        cpi: Long,
    ): Int {
        val url = "${endpoints.studyBase}/mooc-ans/mycourse/studentstudyAjax?courseId=$courseId&clazzid=$classId&chapterId=$chapterId&cpi=$cpi&verificationcode="
        val headers = PC_WEB_HEADERS + ("X-Requested-With" to "XMLHttpRequest")
        val html = runCatchingNonCancellation {
            getHtmlWithReauth(endpoints, client, url, headers, "获取章节标签数失败")
        } ?: return 1
        return parseCardCount(html)
    }

    fun parseCardCount(html: String): Int {
        val count = (CARD_COUNT_1.find(html) ?: CARD_COUNT_2.find(html))
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (count != null && count >= 1) min(count, 12) else 1
    }

    suspend fun fetchChapterVideos(
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        chapterId: String,
        cpi: Long,
    ): List<ChaoxingVideoTask> =
        fetchChapterContent(ChaoxingEndpoints.PRODUCTION, client, courseId, classId, chapterId, cpi).videos

    suspend fun fetchChapterContent(
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        chapterId: String,
        cpi: Long,
    ): ChaoxingChapterContent =
        fetchChapterContent(ChaoxingEndpoints.PRODUCTION, client, courseId, classId, chapterId, cpi)

    internal suspend fun fetchChapterContent(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        chapterId: String,
        cpi: Long,
    ): ChaoxingChapterContent = coroutineScope {
        val resolvedCpi = if (cpi > 0L) {
            cpi
        } else {
            fetchCourseChapters(endpoints, client, courseId, classId).cpi
        }

        val cardCount = fetchChapterCardCount(endpoints, client, courseId, classId, chapterId, resolvedCpi)

        data class CardTabResult(
            val tabIndex: Int,
            val fid: String,
            val content: ChaoxingChapterContent,
            val error: Throwable?,
        )

        val tabs = (0 until cardCount).map { num ->
            async {
                val url = "${endpoints.studyBase}/mooc-ans/knowledge/cards?clazzid=$classId&courseid=$courseId&knowledgeid=$chapterId&num=$num&ut=s&cpi=$resolvedCpi&mooc2=0&isMicroCourse=false&editorPreview=0"
                try {
                    val html = getHtmlWithReauth(endpoints, client, url, PC_WEB_HEADERS, "获取章节内容失败")
                    val fid = FID_REGEX.find(html)?.groupValues?.getOrNull(1) ?: "0"
                    val parsed = parseChapterCardsContent(html, courseId, classId, chapterId, resolvedCpi, endpoints.studyBase)
                    CardTabResult(num, fid, parsed, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    CardTabResult(num, "0", ChaoxingChapterContent(emptyList()), e)
                }
            }
        }.awaitAll().sortedBy { it.tabIndex }

        if (tabs.isNotEmpty() && tabs.all { it.error != null }) {
            throw tabs.first().error!!
        }

        val uniqueVideos = mutableListOf<Pair<ChaoxingVideoTask, String>>()
        val seenVideoIds = HashSet<String>()
        val uniqueLives = mutableListOf<ChaoxingLiveTask>()
        val seenLiveIds = HashSet<String>()
        val uniqueOthers = mutableListOf<ChaoxingChapterAttachment>()
        val seenOtherIds = HashSet<String>()

        for (tab in tabs) {
            for (v in tab.content.videos) {
                if (seenVideoIds.add(v.id)) uniqueVideos.add(v to tab.fid)
            }
            for (live in tab.content.liveTasks) {
                if (seenLiveIds.add(live.id)) uniqueLives.add(live)
            }
            for (o in tab.content.otherAttachments) {
                if (seenOtherIds.add(o.id)) uniqueOthers.add(o)
            }
        }

        val enrichedVideosDeferred = async {
            uniqueVideos.mapIndexed { idx, (task, fid) ->
                async {
                    val status = runCatchingNonCancellation {
                        fetchVideoAssetStatus(endpoints, client, task.objectId, fid)
                    }
                    val updated = if (status != null) {
                        val updatedDuration = if (status.duration > 0) status.duration else task.duration
                        task.copy(
                            dtoken = status.dtoken.ifEmpty { task.dtoken },
                            duration = updatedDuration,
                            attDuration = if (task.attDuration > 0) task.attDuration else updatedDuration,
                            videoPlayUrl = status.videoPlayUrl ?: task.videoPlayUrl,
                            screenshotUrl = status.screenshotUrl ?: task.screenshotUrl,
                        )
                    } else {
                        task
                    }
                    idx to updated
                }
            }.awaitAll().sortedBy { it.first }.map { it.second }
        }

        val enrichedLivesDeferred = async {
            uniqueLives.mapIndexed { idx, live ->
                async {
                    val info = runCatchingNonCancellation {
                        fetchLiveInfo(endpoints, client, live)
                    }
                    val updated = if (info != null) {
                        val baseUpdated = live.copy(
                            liveId = info.resolvedLiveId.ifEmpty { live.liveId },
                            title = info.title.ifEmpty { live.title },
                            liveStatus = info.liveStatus,
                            ifReview = info.ifReview,
                            duration = if (info.duration > 0) info.duration else live.duration,
                            playbackTimelongMs = info.playbackTimelongMs,
                            percentValue = info.percentValue,
                            streamName = info.streamName.ifEmpty { live.streamName },
                            vdoid = info.vdoid.ifEmpty { live.vdoid },
                            playUrl = info.preferredPlayUrl ?: live.playUrl,
                            fallbackPlayUrl = info.fallbackPlayUrl ?: live.fallbackPlayUrl,
                        )
                        baseUpdated.copy(
                            webPageUrl = buildLiveWebUrl(baseUpdated, endpoints.zhiboBase, info.courseEndState),
                        )
                    } else {
                        live
                    }
                    idx to updated
                }
            }.awaitAll().sortedBy { it.first }.map { it.second }
        }

        ChaoxingChapterContent(
            videos = enrichedVideosDeferred.await(),
            liveTasks = enrichedLivesDeferred.await(),
            otherAttachments = uniqueOthers,
        )
    }

    fun parseChapterCardsHtml(
        html: String,
        courseId: Long,
        classId: Int,
        chapterId: String,
        fallbackCpi: Long = 0L,
    ): List<ChaoxingVideoTask> =
        parseChapterCardsContent(html, courseId, classId, chapterId, fallbackCpi).videos

    fun parseChapterCardsContent(
        html: String,
        courseId: Long,
        classId: Int,
        chapterId: String,
        fallbackCpi: Long = 0L,
        defaultStudyBase: String = ChaoxingEndpoints.PRODUCTION.studyBase,
    ): ChaoxingChapterContent {
        val jsonString = extractMArgJsonObject(html) ?: return ChaoxingChapterContent(emptyList())
        val root = parseJsonObjectOrNull(jsonString) ?: return ChaoxingChapterContent(emptyList())

        val defaults = root.objectOrNull("defaults")
        val cpi = defaults?.longOrStringLongOrNull("cpi") ?: fallbackCpi
        val userid = defaults?.stringOrNumberString("userid").orEmpty()
        val reportTimeInterval = defaults?.intOrStringIntOrNull("reportTimeInterval") ?: 60
        val schoolDoubleSpeed = defaults?.intOrStringIntOrNull("schooldoublespeed") ?: 1
        val chapterCapture = defaults?.intOrStringIntOrNull("chapterCapture") ?: 0
        val rawReportUrl = defaults?.stringOnlyOrNull("reportUrl")
            ?: "$defaultStudyBase/mooc-ans/multimedia/log/a/$cpi"
        val normalizedReportUrl = if (rawReportUrl.startsWith("//")) "https:$rawReportUrl" else rawReportUrl
        val reportUrl = normalizedReportUrl.replace("http://", "https://")

        val attachments = root.arrayOrNull("attachments") ?: return ChaoxingChapterContent(emptyList())
        val videos = mutableListOf<ChaoxingVideoTask>()
        val liveTasks = mutableListOf<ChaoxingLiveTask>()
        val others = mutableListOf<ChaoxingChapterAttachment>()

        for (elem in attachments) {
            val item = elem as? JsonObject ?: continue
            val typeStr = item.stringOnlyOrNull("type")?.lowercase(Locale.ROOT).orEmpty()
            val property = item.objectOrNull("property")
            val moduleStr = property?.stringOnlyOrNull("module")?.lowercase(Locale.ROOT).orEmpty()

            val jobid = item.stringOrNumberString("jobid")
                ?: property?.stringOrNumberString("jobid")
                ?: property?.stringOrNumberString("_jobid")
                ?: ""
            val rawJobFlag = item.flexibleBooleanOrNull("job")
            val isJob = rawJobFlag ?: jobid.isNotEmpty()
            val rawPassedFlag = item.flexibleBooleanOrNull("isPassed")
            val isPassed = rawPassedFlag ?: false
            val objectId = property?.stringOnlyOrNull("objectid")
                ?: item.stringOnlyOrNull("objectid")
                ?: ""

            val isAudioAsset = (typeStr == "audio" || moduleStr == "insertaudio") && objectId.isNotEmpty()
            if (typeStr == "video" || isAudioAsset) {
                if (objectId.isEmpty()) continue
                val name = property?.stringOnlyOrNull("name") ?: if (isAudioAsset) "课程音频" else "课程视频"
                val rawOtherInfo = item.stringOnlyOrNull("otherInfo").orEmpty()
                val cleanOtherInfo = rawOtherInfo.substringBefore("&")
                val headOffsetMs = item.intOrStringIntOrNull("headOffset") ?: 0
                val playTimeMs = item.intOrStringIntOrNull("playTime") ?: 0
                val attDuration = item.intOrStringIntOrNull("attDuration") ?: 0
                val attDurationEnc = item.stringOnlyOrNull("attDurationEnc").orEmpty()
                val videoFaceCaptureEnc = item.stringOnlyOrNull("videoFaceCaptureEnc").orEmpty()
                val begins = item.intOrStringIntOrNull("begins") ?: 0
                val ends = item.intOrStringIntOrNull("ends") ?: 0
                val rt = item.doubleOrStringDoubleOrNull("rt")
                    ?: property?.doubleOrStringDoubleOrNull("rt")
                    ?: 0.9

                val propDS = property?.intOrStringIntOrNull("doublespeed")
                val effectiveDoubleSpeed = when {
                    isAudioAsset -> 0
                    propDS == null -> schoolDoubleSpeed
                    propDS == 0 -> 0
                    propDS == 2 -> schoolDoubleSpeed
                    else -> if (schoolDoubleSpeed == 0) 0 else propDS
                }

                videos.add(
                    ChaoxingVideoTask(
                        courseId = courseId,
                        classId = classId,
                        chapterId = chapterId,
                        cpi = cpi,
                        userid = userid,
                        jobid = jobid,
                        objectId = objectId,
                        name = name,
                        otherInfo = cleanOtherInfo,
                        dtoken = "",
                        duration = attDuration,
                        attDuration = attDuration,
                        attDurationEnc = attDurationEnc,
                        videoFaceCaptureEnc = videoFaceCaptureEnc,
                        rt = rt,
                        doublespeed = effectiveDoubleSpeed,
                        reportTimeInterval = reportTimeInterval,
                        headOffsetMs = headOffsetMs,
                        playTimeMs = playTimeMs,
                        isPassed = isPassed,
                        isJob = isJob,
                        begins = begins,
                        ends = ends,
                        requiresFaceCapture = !isAudioAsset && chapterCapture == 1,
                        reportUrl = reportUrl,
                        dtype = if (isAudioAsset) "Audio" else "Video",
                    ),
                )
                continue
            }

            // Parse `live` / `insertlive` (external `zhibo.chaoxing.com` live replays) into dedicated `ChaoxingLiveTask`.
            if (typeStr == "live" || moduleStr == "insertlive") {
                val rawJobId = item.stringOrNumberString("jobid")
                    ?: property?.stringOrNumberString("jobid")
                    ?: ""
                val rawUnderscoreJobId = property?.stringOrNumberString("_jobid").orEmpty()
                var liveId = property?.stringOrNumberString("liveId")
                    ?: item.stringOrNumberString("liveId")
                    ?: ""
                if (liveId.isEmpty() && rawUnderscoreJobId.contains("live-")) {
                    liveId = rawUnderscoreJobId.substringAfter("live-")
                }
                if (liveId.isNotEmpty()) {
                    val streamName = property?.stringOnlyOrNull("streamName").orEmpty()
                    val vdoid = property?.stringOnlyOrNull("vdoid").orEmpty()
                    val aid = item.stringOrNumberString("aid").orEmpty()
                    val title = (property?.stringOnlyOrNull("title") ?: property?.stringOnlyOrNull("name")).orEmpty().trim()
                    val rt = item.doubleOrStringDoubleOrNull("rt")
                        ?: property?.doubleOrStringDoubleOrNull("rt")
                        ?: 0.9
                    val ds = property?.intOrStringIntOrNull("doublespeed") ?: 1
                    val fastforwardRaw = property?.stringOrNumberString("fastforward")?.lowercase(Locale.ROOT)
                    val isNotDrag = if (fastforwardRaw == null || fastforwardRaw == "true") 1 else 0
                    val switchWindow = if (property?.stringOrNumberString("switchwindow")?.lowercase(Locale.ROOT) == "true") 1 else 0
                    val liveSetEnc = item.stringOnlyOrNull("liveSetEnc").orEmpty()
                    val authEnc = item.stringOnlyOrNull("authEnc").orEmpty()
                    val liveDragEnc = item.stringOnlyOrNull("liveDragEnc").orEmpty()
                    val liveSwDsEnc = item.stringOnlyOrNull("liveSwDsEnc").orEmpty()
                    val hasLiveJobId = rawJobId.isNotEmpty()
                    val liveIsPassed = when {
                        rawPassedFlag != null -> rawPassedFlag
                        hasLiveJobId -> !(rawJobFlag ?: false)
                        else -> false
                    }
                    val baseTask = ChaoxingLiveTask(
                        courseId = courseId,
                        classId = classId,
                        chapterId = chapterId,
                        cpi = cpi,
                        userid = userid,
                        jobid = rawJobId,
                        aid = aid,
                        liveId = liveId,
                        streamName = streamName,
                        vdoid = vdoid,
                        title = title.ifEmpty { "直播回放" },
                        rt = rt,
                        doublespeed = ds,
                        isNotDrag = isNotDrag,
                        switchWindow = switchWindow,
                        liveSetEnc = liveSetEnc,
                        authEnc = authEnc,
                        liveDragEnc = liveDragEnc,
                        liveSwDsEnc = liveSwDsEnc,
                        isJob = hasLiveJobId,
                        isPassed = liveIsPassed,
                    )
                    liveTasks.add(
                        baseTask.copy(
                            webPageUrl = buildLiveWebUrl(baseTask, ChaoxingEndpoints.PRODUCTION.zhiboBase),
                        ),
                    )
                    continue
                }
            }

            val kind = when {
                typeStr == "document" || moduleStr == "insertdoc" -> ChaoxingAttachmentKind.DOCUMENT
                typeStr == "audio" || moduleStr == "insertaudio" -> ChaoxingAttachmentKind.AUDIO
                typeStr == "live" || moduleStr == "insertlive" -> ChaoxingAttachmentKind.LIVE
                typeStr == "workid" || moduleStr == "work" -> ChaoxingAttachmentKind.QUIZ
                else -> ChaoxingAttachmentKind.OTHER
            }
            val rawName = (property?.stringOnlyOrNull("name") ?: property?.stringOnlyOrNull("title")).orEmpty().trim()
            val fallbackName = when (kind) {
                ChaoxingAttachmentKind.DOCUMENT -> "文档任务点"
                ChaoxingAttachmentKind.AUDIO -> "音频任务点"
                ChaoxingAttachmentKind.LIVE -> "直播任务点"
                ChaoxingAttachmentKind.QUIZ -> "章节测验"
                ChaoxingAttachmentKind.VIDEO, ChaoxingAttachmentKind.OTHER -> "学习任务点"
            }
            val jtoken = item.stringOnlyOrNull("jtoken").orEmpty()
            val externalUrl = property?.stringOnlyOrNull("url")
                ?: property?.stringOnlyOrNull("href")
                ?: property?.stringOnlyOrNull("link")
            // Per Chaoxing `documentJob.js` (`checkJobFinish`): non-empty `jobid` with `job` absent/false means already finished.
            val effectiveOtherPassed = when {
                rawPassedFlag != null -> rawPassedFlag
                jobid.isNotEmpty() && (rawJobFlag == null || rawJobFlag == false) -> true
                else -> false
            }

            others.add(
                ChaoxingChapterAttachment(
                    kind = kind,
                    courseId = courseId,
                    classId = classId,
                    chapterId = chapterId,
                    jobid = jobid,
                    objectId = objectId,
                    jtoken = jtoken,
                    name = rawName.ifEmpty { fallbackName },
                    isJob = isJob,
                    isPassed = effectiveOtherPassed,
                    externalUrl = externalUrl,
                ),
            )
        }

        return ChaoxingChapterContent(videos = videos, liveTasks = liveTasks, otherAttachments = others)
    }

    /**
     * Completes a PDF / DOC / PPT document task point using Chaoxing's official `/mooc-ans/job/document` endpoint.
     */
    suspend fun completeDocumentTask(
        client: ChaoxingHttpClient,
        task: ChaoxingChapterAttachment,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = completeDocumentTask(ChaoxingEndpoints.PRODUCTION, client, task, nowMs)

    internal suspend fun completeDocumentTask(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        task: ChaoxingChapterAttachment,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (task.kind != ChaoxingAttachmentKind.DOCUMENT || task.jobid.isEmpty() || task.jtoken.isEmpty()) {
            throw ChaoxingError.InvalidInput("该文档任务点缺少 jobid 或 jtoken")
        }
        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            "${endpoints.studyBase}/mooc-ans/job/document",
            listOf(
                "jobid" to task.jobid,
                "knowledgeid" to task.chapterId,
                "courseid" to task.courseId.toString(),
                "clazzid" to task.classId.toString(),
                "jtoken" to task.jtoken,
                "checkMicroTopic" to "true",
                "microTopicId" to "",
                "courseEngineInfo" to "false",
                "_dc" to nowMs.toString(),
            ),
        )
        val headers = PC_WEB_HEADERS + mapOf(
            "Referer" to DOCUMENT_REFERER,
            "X-Requested-With" to "XMLHttpRequest",
        )
        val response = getWithReauth(endpoints, client, url, headers)
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "文档任务点上报失败 (${response.statusCode})")
        }
        val json = parseJsonObjectOrNull(response.body)
        if (json != null) {
            json.flexibleBooleanOrNull("status")?.let { return it }
            val msg = json.stringOnlyOrNull("msg").orEmpty()
            if (msg.contains("ok", ignoreCase = true) || msg.contains("成功") || msg.contains("已完成")) {
                return true
            }
        }
        val body = response.bodyAsString()
        return body.contains("\"status\":true") || body.contains("ok")
    }

    // MARK: - External Live Replay (`zhibo.chaoxing.com`)

    fun buildLiveWebUrl(
        task: ChaoxingLiveTask,
        baseUrl: String = ChaoxingEndpoints.PRODUCTION.zhiboBase,
        courseEndState: Int = 0,
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val isJobFlag = task.isJob && !task.isPassed
        val effectiveNotDrag = if (isJobFlag) task.isNotDrag else 0
        val effectiveSw = if (isJobFlag) task.switchWindow else 0
        val effectiveDs = if (isJobFlag) task.doublespeed else 1
        val params = mutableListOf<Pair<String, String>>()
        if (courseEndState != 1) {
            params.add("courseId" to task.courseId.toString())
        }
        params.addAll(
            listOf(
                "classId" to task.classId.toString(),
                "knowledgeId" to task.chapterId,
                "jobId" to task.jobid,
                "userId" to task.userid,
                "rt" to formatRt(task.rt),
                "livesetenc" to task.liveSetEnc,
                "isjob" to if (isJobFlag) "true" else "false",
                "watchingInCourse" to "1",
                "customPara1" to "${task.classId}_${task.courseId}",
                "customPara2" to task.authEnc,
                "jobfs" to if (isJobFlag) "0" else "1",
                "isNotDrag" to effectiveNotDrag.toString(),
                "livedragenc" to task.liveDragEnc,
                "sw" to effectiveSw.toString(),
                "ds" to effectiveDs.toString(),
                "liveswdsenc" to task.liveSwDsEnc,
            ),
        )
        return ChaoxingHttpClient.encodeChaoxingUrl("$trimmedBase/${task.liveId}", params)
    }

    fun parseLiveInfoJson(rawBytes: ByteArray, fallbackLiveId: String): ChaoxingLiveInfo {
        val root = parseJsonObjectOrNull(rawBytes)
            ?: throw ChaoxingError.ParseFailed("直播详情 JSON 解析失败")
        val temp = root.objectOrNull("temp")
            ?: throw ChaoxingError.ParseFailed("直播详情缺少 temp 字段")
        val detail = temp.objectOrNull("data")
            ?: throw ChaoxingError.ParseFailed("直播详情缺少 data 字段")
        val resolvedId = detail.stringOrNumberString("liveId").orEmpty().ifEmpty { fallbackLiveId }
        return ChaoxingLiveInfo(
            resolvedLiveId = resolvedId,
            title = detail.stringOnlyOrNull("title").orEmpty().trim(),
            liveStatus = detail.intOrStringIntOrNull("liveStatus") ?: 4,
            ifReview = detail.intOrStringIntOrNull("ifReview") ?: 0,
            duration = detail.intOrStringIntOrNull("duration") ?: 0,
            playbackTimelongMs = detail.intOrStringIntOrNull("playbackTimelong")
                ?: detail.intOrStringIntOrNull("timeLong")
                ?: 0,
            percentValue = detail.doubleOrStringDoubleOrNull("percentValue") ?: 0.0,
            streamName = detail.stringOnlyOrNull("streamName").orEmpty(),
            vdoid = detail.stringOnlyOrNull("vdoid").orEmpty(),
            m3u8Url = normalizeHttpsUrl(detail.stringOnlyOrNull("m3u8Url")),
            mp4Url = normalizeHttpsUrl(detail.stringOnlyOrNull("mp4Url")),
            viewUrl = normalizeHttpsUrl(detail.stringOnlyOrNull("viewUrl")),
            courseEndState = root.intOrStringIntOrNull("courseEndState") ?: 0,
        )
    }

    fun parseLiveSessionHtml(
        html: String,
        pageUrl: String,
        fallbackTask: ChaoxingLiveTask,
    ): ChaoxingLiveSessionContext {
        val shortLiveId = SHORT_LIVE_ID_REGEX.find(html)?.groupValues?.getOrNull(1) ?: fallbackTask.shortLiveId
        val uInfo = UINFO_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()
        val parsedDuration = LIVE_DURATION_REGEX.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val streamName = STREAM_NAME_REGEX.find(html)?.groupValues?.getOrNull(1)?.ifEmpty { null }
            ?: fallbackTask.streamName
        val vdoid = VDOID_REGEX.find(html)?.groupValues?.getOrNull(1)?.ifEmpty { null }
            ?: fallbackTask.vdoid
        val fid = LIVE_FID_REGEX.find(html)?.groupValues?.getOrNull(1)
        val playUrl = LIVE_M3U8_URLS_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: LIVE_ORIGIN_M3U8_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: fallbackTask.playUrl
        return ChaoxingLiveSessionContext(
            pageUrl = pageUrl,
            shortLiveId = shortLiveId,
            uInfo = uInfo,
            streamName = streamName,
            vdoid = vdoid,
            duration = if (parsedDuration > 0) parsedDuration else fallbackTask.duration,
            fid = fid,
            playUrl = normalizeHttpsUrl(playUrl),
        )
    }

    suspend fun fetchLiveInfo(
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
    ): ChaoxingLiveInfo = fetchLiveInfo(ChaoxingEndpoints.PRODUCTION, client, task)

    internal suspend fun fetchLiveInfo(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
    ): ChaoxingLiveInfo {
        var resolvedLiveId = task.liveId
        val liveHeaders = PC_WEB_HEADERS + mapOf(
            "Referer" to LIVE_REFERER,
            "X-Requested-With" to "XMLHttpRequest",
        )
        if (resolvedLiveId.isNotEmpty() && resolvedLiveId.length < 16) {
            val convUrl = "${endpoints.studyBase}/ananas/live/getnewliveid?oliveid=$resolvedLiveId"
            val convResp = runCatchingNonCancellation {
                getWithReauth(endpoints, client, convUrl, liveHeaders)
            }
            if (convResp != null && convResp.isSuccessful) {
                val convJson = parseJsonObjectOrNull(convResp.body)
                val newId = convJson?.stringOrNumberString("newLiveId").orEmpty()
                if (newId.isNotEmpty()) resolvedLiveId = newId
            }
        }

        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            "${endpoints.studyBase}/ananas/live/liveinfo",
            listOf(
                "liveid" to resolvedLiveId,
                "userid" to task.userid,
                "clazzid" to task.classId.toString(),
                "knowledgeid" to task.chapterId,
                "courseid" to task.courseId.toString(),
                "jobid" to task.jobid,
                "ut" to "s",
            ),
        )
        val response = getWithReauth(endpoints, client, url, liveHeaders)
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "获取直播详情失败 (${response.statusCode})")
        }
        return parseLiveInfoJson(response.body, resolvedLiveId)
    }

    suspend fun prepareLiveReplaySession(
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
    ): ChaoxingLiveSessionContext = prepareLiveReplaySession(ChaoxingEndpoints.PRODUCTION, client, task)

    internal suspend fun prepareLiveReplaySession(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
    ): ChaoxingLiveSessionContext {
        if (task.jobid.isNotEmpty() && task.chapterId.isNotEmpty()) {
            val relUrl = ChaoxingHttpClient.encodeChaoxingUrl(
                "${endpoints.studyBase}/mooc-ans/live/relation",
                listOf(
                    "courseid" to task.courseId.toString(),
                    "knowledgeid" to task.chapterId,
                    "ut" to "s",
                    "jobid" to task.jobid,
                    "aid" to task.aid,
                ),
            )
            val relHeaders = PC_WEB_HEADERS + mapOf(
                "Referer" to LIVE_REFERER,
                "X-Requested-With" to "XMLHttpRequest",
            )
            runCatchingNonCancellation { getWithReauth(endpoints, client, relUrl, relHeaders) }
        }

        val trimmedZhiboBase = endpoints.zhiboBase.trimEnd('/')
        val existingWebUrl = task.webPageUrl?.takeIf { it.startsWith("$trimmedZhiboBase/") }
        val pageUrl = existingWebUrl ?: buildLiveWebUrl(task, trimmedZhiboBase)
        val pageHeaders = PC_WEB_HEADERS + ("Referer" to "${endpoints.studyBase}/")
        val pageResp = getWithReauth(endpoints, client, pageUrl, pageHeaders)
        if (!pageResp.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = pageResp.statusCode, message = "打开直播回放页面失败 (${pageResp.statusCode})")
        }
        val html = pageResp.bodyAsString()
        val ctx = parseLiveSessionHtml(html, pageUrl, task)

        // Warm `zhibo.chaoxing.com` SSO iframe using the rendered `uInfo` token
        // (avoids matching unrendered Thymeleaf `th:if` literals in HTML).
        if (ctx.uInfo.isNotEmpty()) {
            val ssoUrl = ChaoxingHttpClient.encodeChaoxingUrl(
                "$trimmedZhiboBase/iframeLoginSSO",
                listOf("u" to ctx.uInfo),
            )
            runCatchingNonCancellation {
                client.get(ssoUrl, PC_WEB_HEADERS + ("Referer" to pageUrl))
            }
        } else {
            val ssoPath = LIVE_SSO_IFRAME_REGEX.find(html)?.groupValues?.getOrNull(1)
            if (!ssoPath.isNullOrEmpty()) {
                val ssoUrl = "$trimmedZhiboBase$ssoPath"
                runCatchingNonCancellation {
                    client.get(ssoUrl, PC_WEB_HEADERS + ("Referer" to pageUrl))
                }
            }
        }

        if (!ctx.fid.isNullOrEmpty() && ctx.streamName.isNotEmpty() && ctx.vdoid.isNotEmpty()) {
            val saveUrl = ChaoxingHttpClient.encodeChaoxingUrl(
                "$trimmedZhiboBase/live/savecourselive",
                listOf(
                    "streamName" to ctx.streamName,
                    "vdoid" to ctx.vdoid,
                    "courseId" to task.courseId.toString(),
                    "fid" to ctx.fid,
                ),
            )
            runCatchingNonCancellation {
                client.get(
                    saveUrl,
                    PC_WEB_HEADERS + mapOf("Referer" to pageUrl, "X-Requested-With" to "XMLHttpRequest"),
                )
            }
        }
        return ctx
    }

    fun parseLiveHeartbeatJson(rawBytes: ByteArray): ChaoxingLiveHeartbeatResponse {
        val root = parseJsonObjectOrNull(rawBytes)
            ?: throw ChaoxingError.ParseFailed(
                "直播心跳响应解析失败: ${String(rawBytes, Charsets.UTF_8).take(120)}",
            )
        val result = root.intOrStringIntOrNull("result")
            ?: if (root.flexibleBooleanOrNull("status") == true) 1 else 0
        val msg = root.stringOnlyOrNull("msg").orEmpty().trim()
        val payload = root.objectOrNull("data")
        val accepted = payload?.flexibleBooleanOrNull("accepted") ?: (result == 1)
        val liveObj = payload?.objectOrNull("live")
        val duration = liveObj?.intOrStringIntOrNull("duration") ?: 0
        val liveStatus = liveObj?.intOrStringIntOrNull("livestatus")
            ?: liveObj?.intOrStringIntOrNull("liveStatus")
            ?: 4
        return ChaoxingLiveHeartbeatResponse(
            result = result,
            accepted = accepted,
            message = msg,
            duration = duration,
            liveStatus = liveStatus,
        )
    }

    suspend fun sendLiveHeartbeat(
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
        session: ChaoxingLiveSessionContext,
        isStart: Boolean,
        watchMomentSec: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): ChaoxingLiveHeartbeatResponse =
        sendLiveHeartbeat(ChaoxingEndpoints.PRODUCTION, client, task, session, isStart, watchMomentSec, nowMs)

    internal suspend fun sendLiveHeartbeat(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
        session: ChaoxingLiveSessionContext,
        isStart: Boolean,
        watchMomentSec: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): ChaoxingLiveHeartbeatResponse {
        if (session.streamName.isEmpty() || session.vdoid.isEmpty()) {
            throw ChaoxingError.InvalidInput("缺少直播 streamName 或 vdoid")
        }
        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            "${endpoints.zhiboBase.trimEnd('/')}/saveTimePc",
            listOf(
                "streamName" to session.streamName,
                "vdoid" to session.vdoid,
                "userId" to task.userid,
                "isStart" to if (isStart) "0" else "1",
                "t" to nowMs.toString(),
                "courseId" to task.courseId.toString(),
            ),
        )
        val headers = PC_WEB_HEADERS + mapOf(
            "Referer" to session.pageUrl,
            "X-Requested-With" to "XMLHttpRequest",
        )
        var response = getWithReauth(endpoints, client, url, headers)
        if (response.statusCode == 403) {
            throw ChaoxingError.NetworkError(statusCode = 403, message = "直播回放心跳被限流 (403)")
        }
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "直播回放心跳上报失败 (${response.statusCode})")
        }

        var parsed = parseLiveHeartbeatJson(response.body)
        if (parsed.result != 1 || !parsed.accepted) {
            val lowerMsg = parsed.message.lowercase(Locale.ROOT)
            if (parsed.message.contains("登录") || lowerMsg.contains("login") || lowerMsg.contains("auth") || lowerMsg.contains("session")) {
                ChaoxingAuthService.reLogin(endpoints, client)
                response = client.get(url, headers)
                if (response.statusCode == 403) {
                    throw ChaoxingError.NetworkError(statusCode = 403, message = "直播回放心跳被限流 (403)")
                }
                if (!response.isSuccessful) {
                    throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "直播回放心跳上报失败 (${response.statusCode})")
                }
                parsed = parseLiveHeartbeatJson(response.body)
            }
        }
        if (parsed.result != 1 || !parsed.accepted) {
            val reason = parsed.message.ifEmpty {
                "服务端未接收直播心跳 (result=${parsed.result}, accepted=${parsed.accepted})"
            }
            throw ChaoxingError.ServerMessage(reason)
        }

        if (!isStart && session.shortLiveId.isNotEmpty() && session.uInfo.isNotEmpty()) {
            val wmUrl = ChaoxingHttpClient.encodeChaoxingUrl(
                "${endpoints.zhiboBase.trimEnd('/')}/apis/live/put/watchMoment",
                listOf(
                    "liveId" to session.shortLiveId,
                    "streamName" to session.streamName,
                    "vdoid" to session.vdoid,
                    "watchMoment" to max(0, watchMomentSec).toString(),
                    "t" to nowMs.toString(),
                    "u" to session.uInfo,
                ),
            )
            runCatchingNonCancellation { client.get(wmUrl, headers) }
        }
        return parsed
    }

    private fun normalizeHttpsUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> trimmed.replace("http://", "https://")
        }
    }

    internal suspend fun fetchVideoAssetStatus(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        objectId: String,
        fid: String,
    ): ChaoxingVideoAssetStatus {
        val nowMs = System.currentTimeMillis()
        val url = "${endpoints.studyBase}/ananas/status/$objectId?k=$fid&flag=normal&ro=0&_dc=$nowMs"
        val headers = PC_WEB_HEADERS + mapOf(
            "Referer" to VIDEO_REFERER,
            "X-Requested-With" to "XMLHttpRequest",
        )
        val response = getWithReauth(endpoints, client, url, headers)
        val json = if (response.isSuccessful) parseJsonObjectOrNull(response.body) else null
        if (json == null) {
            throw ChaoxingError.ParseFailed("视频元数据解析失败")
        }
        val dtoken = json.stringOnlyOrNull("dtoken").orEmpty()
        val duration = json.intOrStringIntOrNull("duration") ?: 0
        val rawPlayUrl = json.stringOnlyOrNull("http")
            ?: json.stringOnlyOrNull("https")
            ?: json.stringOnlyOrNull("httphd")
            ?: json.stringOnlyOrNull("httpmd")
            ?: json.stringOnlyOrNull("hls")
        val playUrl = rawPlayUrl?.replace("http://", "https://")
        val shotUrl = json.stringOnlyOrNull("screenshot")?.replace("http://", "https://")
        return ChaoxingVideoAssetStatus(dtoken = dtoken, duration = duration, videoPlayUrl = playUrl, screenshotUrl = shotUrl)
    }

    // MARK: - Video Log Signing & Reporting

    /**
     * Computes Chaoxing's MD5 log signature:
     * `md5("[{clazzId}][{userId}][{jobId}][{objectId}][{playingTimeMs}][d_yHJ!$pdA~5][{durationMs}][{clipTime}]")`.
     */
    fun buildEncSignature(
        clazzId: Int,
        userId: String,
        jobId: String,
        objectId: String,
        playingTimeMs: Int,
        durationMs: Int,
        clipTime: String,
    ): String {
        val raw = "[$clazzId][$userId][$jobId][$objectId][$playingTimeMs][$ENC_SALT][$durationMs][$clipTime]"
        return ChaoxingCrypto.md5Hex(raw)
    }

    suspend fun sendVideoLog(
        client: ChaoxingHttpClient,
        task: ChaoxingVideoTask,
        playingTimeSec: Int,
        event: ChaoxingVideoDragEvent,
        nowMs: Long = System.currentTimeMillis(),
    ): ChaoxingVideoLogResponse =
        sendVideoLog(ChaoxingEndpoints.PRODUCTION, client, task, playingTimeSec, event, nowMs)

    internal suspend fun sendVideoLog(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        task: ChaoxingVideoTask,
        playingTimeSec: Int,
        event: ChaoxingVideoDragEvent,
        nowMs: Long = System.currentTimeMillis(),
    ): ChaoxingVideoLogResponse {
        if (task.dtoken.isEmpty()) {
            throw ChaoxingError.InvalidInput("缺少视频 dtoken")
        }
        val duration = task.totalDuration
        val clipStart = task.effectiveClipStart
        val clipEnd = max(clipStart, task.effectiveClipEnd)
        val clampedSec = max(clipStart, if (clipEnd > 0) min(playingTimeSec, clipEnd) else playingTimeSec)
        val clipTime = task.effectiveClipTime

        val enc = buildEncSignature(
            clazzId = task.classId,
            userId = task.userid,
            jobId = task.jobid,
            objectId = task.objectId,
            playingTimeMs = clampedSec * 1000,
            durationMs = duration * 1000,
            clipTime = clipTime,
        )

        val baseLogUrl = "${task.reportUrl.trimEnd('/')}/${task.dtoken}"
        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            baseLogUrl,
            listOf(
                "clazzId" to task.classId.toString(),
                "playingTime" to clampedSec.toString(),
                "duration" to duration.toString(),
                "clipTime" to clipTime,
                "objectId" to task.objectId,
                "otherInfo" to task.otherInfo,
                "courseId" to task.courseId.toString(),
                "jobid" to task.jobid,
                "userid" to task.userid,
                "isdrag" to event.code.toString(),
                "view" to "pc",
                "enc" to enc,
                "rt" to formatRt(task.rt),
                "videoFaceCaptureEnc" to task.videoFaceCaptureEnc,
                "dtype" to task.dtype,
                "_t" to nowMs.toString(),
                "attDuration" to (if (task.attDuration > 0) task.attDuration else duration).toString(),
                "attDurationEnc" to task.attDurationEnc,
                "courseEngineInfo" to "false",
            ),
        )

        val headers = PC_WEB_HEADERS + mapOf(
            "Referer" to if (task.dtype == "Audio") AUDIO_REFERER else VIDEO_REFERER,
            "Content-Type" to "application/json",
        )

        var response = client.get(url, headers)
        var bodyText = response.bodyAsString()

        if (response.statusCode == 401 || response.statusCode == 302 || response.statusCode == 202 ||
            isLoginPage(response) ||
            (response.isSuccessful && !bodyText.contains("isPassed"))
        ) {
            ChaoxingAuthService.reLogin(endpoints, client)
            response = client.get(url, headers)
            bodyText = response.bodyAsString()
        }

        if (response.statusCode == 403) {
            val restSec = REST_TIME_REGEX.find(bodyText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (restSec != null) {
                val minutes = max(1, ceil(restSec / 60.0).toInt())
                throw ChaoxingError.NetworkError(
                    statusCode = 403,
                    message = "本次学习时长已达上限，请休息 $minutes 分钟后继续 [restTime:$restSec]",
                )
            }
            throw ChaoxingError.NetworkError(statusCode = 403, message = "视频日志被拒绝 (403): $bodyText")
        }
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(
                statusCode = response.statusCode,
                message = "视频进度上报失败 (${response.statusCode})",
            )
        }

        val json = parseJsonObjectOrNull(response.body)
            ?: throw ChaoxingError.ParseFailed("视频日志响应解析失败: ${bodyText.take(120)}")
        return ChaoxingVideoLogResponse(
            isPassed = json.flexibleBooleanOrNull("isPassed") ?: false,
            videoTimeLimit = json.flexibleBooleanOrNull("videoTimeLimit") ?: false,
            hasJobLimit = json.flexibleBooleanOrNull("hasJobLimit") ?: false,
        )
    }

    // MARK: - Helpers

    private fun isLoginPage(response: HttpResult): Boolean {
        val host = response.finalUrl.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT).orEmpty()
        if (host == "passport2.chaoxing.com" || host == "passport.chaoxing.com") return true
        if (response.body.size >= 80_000) return false
        return LOGIN_PAGE_REGEX.containsMatchIn(response.bodyAsString())
    }

    private suspend fun getWithReauth(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap(),
        reauthOnForbidden: Boolean = false,
    ): HttpResult {
        var response = client.get(url, headers)
        val shouldReauth = response.statusCode == 401 ||
            response.statusCode == 302 ||
            (reauthOnForbidden && response.statusCode == 403) ||
            isLoginPage(response)
        if (shouldReauth) {
            ChaoxingAuthService.reLogin(endpoints, client)
            response = client.get(url, headers)
        }
        return response
    }

    private suspend fun getHtmlWithReauth(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap(),
        failureMessage: String,
    ): String {
        val response = getWithReauth(endpoints, client, url, headers)
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = failureMessage)
        }
        return response.bodyAsString()
    }

    internal fun extractMArgJsonObject(html: String): String? {
        val anchor = MARG_ANCHOR_REGEX.find(html) ?: return null
        val braceStart = anchor.range.last
        var depth = 0
        var inString = false
        var escaped = false

        for (i in braceStart until html.length) {
            val ch = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return html.substring(braceStart, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun extractCpi(html: String): Long? =
        CPI_REGEX.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun stripHtml(value: String): String =
        value.replace(HTML_TAG_REGEX, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    private fun splitLeadingPendingBadge(text: String): Pair<Int?, String> {
        val idx = text.indexOf(' ')
        if (idx > 0) {
            val head = text.substring(0, idx)
            val tail = text.substring(idx + 1).trimStart()
            if (head.all { it.isDigit() } && tail.isNotEmpty()) {
                head.toIntOrNull()?.let { return it to tail }
            }
        }
        return null to text
    }

    private fun formatRt(value: Double): String {
        if (abs(value - 0.9) < 0.0001) return "0.9"
        return String.format(Locale.US, "%.2g", value)
    }

    private fun JsonObject.flexibleBooleanOrNull(key: String): Boolean? {
        val prim = this[key] as? JsonPrimitive ?: return null
        val text = prim.content.trim().lowercase(Locale.ROOT)
        return when (text) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    private inline fun <T> runCatchingNonCancellation(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
}
