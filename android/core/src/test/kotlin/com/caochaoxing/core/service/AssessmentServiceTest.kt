// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.service

import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingAssessmentKind
import com.caochaoxing.core.model.ChaoxingAssessmentStatus
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssessmentServiceTest {
    private val course = ChaoxingCourse(
        courseName = "高等数学",
        teacherName = "张老师",
        courseId = 300001L,
        classId = 200001,
        imageURL = "",
    )

    private fun syntheticClient(): ChaoxingHttpClient =
        ChaoxingHttpClient.createForLoopbackTesting(
            session = ChaoxingAccountSession(
                phoneNumber = "13800000001",
                encryptedPassword = "enc",
                name = "测试用户",
                uid = 1000001,
                puid = 1000001,
                deviceCode = "demo-device",
            ),
        )

    private suspend fun expectChaoxingError(block: suspend () -> Unit): ChaoxingError {
        try {
            block()
        } catch (e: ChaoxingError) {
            return e
        }
        throw AssertionError("预期抛出 ChaoxingError，但正常返回")
    }

    private val workHtml = """
        <html><head><title>作业</title></head><body>
        <dl class="work-list">
          <dt data="work/phone/task-work?courseId=300001&classId=200001&workId=9001" status="1">
            <h3>第一章 极限（客观题）</h3>
            <p>2026-11-20 发布</p>
            <b>做作业</b>
          </dt>
          <dt data="/work/phone/task-work?workId=9002" status="2">
            <h3>第二章 导数</h3>
            <p>2026-11-18 发布</p>
            <b>待批阅</b>
          </dt>
          <dt data="work/phone/task-work?workId=9003" status="3">
            <h3>课堂练习</h3>
            <p>2026-11-10 发布</p>
            <b>已过期</b>
          </dt>
          <dt status="9"><h3>缺少 data 的装饰行</h3><b>略</b></dt>
        </dl>
        </body></html>
    """.trimIndent()

    private val examHtml = """
        <html><head><title>考试</title></head><body>
        <table>
          <tr><th>考试</th><th>分数</th><th>状态</th></tr>
          <tr data="/exam/phone/task-exam?examId=8001" title="期中考试" status="2">
            <td><span>期中考试</span></td><td>86</td><td>已批阅</td>
          </tr>
          <tr data="/exam/phone/task-exam?examId=8002" title="单元测验" status="0">
            <td>单元测验</td><td>--</td><td>未交</td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    @Test
    fun parseWorkListExtractsTitleStatusDateAndAbsoluteTaskUrl() {
        val items = ChaoxingAssessmentService.parseWorkListHtml(
            workHtml,
            "https://mooc1-api.chaoxing.com/phone/moocAnalysis/workList?courseId=300001",
            course,
        )

        assertEquals(3, items.size)
        val first = items[0]
        assertEquals(ChaoxingAssessmentKind.WORK, first.kind)
        assertEquals("第一章 极限（客观题）", first.title)
        assertEquals("做作业", first.statusText)
        assertEquals("2026-11-20 发布", first.detail)
        assertEquals(ChaoxingAssessmentStatus.NOT_STARTED, first.status)
        assertEquals(
            "https://mooc1-api.chaoxing.com/work/phone/task-work?courseId=300001&classId=200001&workId=9001",
            first.taskUrl,
        )
        assertEquals(300001L, first.courseId)
        assertEquals("高等数学", first.courseName)

        assertEquals(ChaoxingAssessmentStatus.PENDING_REVIEW, items[1].status)
        assertTrue(items[1].isCompleted)
        assertEquals(ChaoxingAssessmentStatus.EXPIRED, items[2].status)
        assertFalse(items[2].isCompleted)
        assertTrue(items[2].status.isExpired)
    }

    @Test
    fun parseExamListReadsScoreAndStatusCells() {
        val items = ChaoxingAssessmentService.parseExamListHtml(
            examHtml,
            "https://mooc1-api.chaoxing.com/phone/moocAnalysis/examList?courseId=300001",
            course,
        )

        assertEquals(2, items.size)
        assertEquals(ChaoxingAssessmentStatus.REVIEWED, items[0].status)
        assertEquals("86", items[0].detail)
        assertEquals("已批阅", items[0].statusText)
        assertEquals("单元测验", items[1].title)
        assertEquals(ChaoxingAssessmentStatus.NOT_STARTED, items[1].status)
        assertFalse(items[1].isCompleted)
    }

    @Test
    fun statusClassifierHandlesServerWording() {
        val cases = mapOf(
            "做作业" to ChaoxingAssessmentStatus.NOT_STARTED,
            "未交" to ChaoxingAssessmentStatus.NOT_STARTED,
            "未提交" to ChaoxingAssessmentStatus.NOT_STARTED,
            "开始考试" to ChaoxingAssessmentStatus.NOT_STARTED,
            "进行中" to ChaoxingAssessmentStatus.IN_PROGRESS,
            "已交" to ChaoxingAssessmentStatus.SUBMITTED,
            "已交卷" to ChaoxingAssessmentStatus.SUBMITTED,
            "待批阅" to ChaoxingAssessmentStatus.PENDING_REVIEW,
            "已批阅" to ChaoxingAssessmentStatus.REVIEWED,
            "已过期" to ChaoxingAssessmentStatus.EXPIRED,
            "缺考" to ChaoxingAssessmentStatus.EXPIRED,
            "查看" to ChaoxingAssessmentStatus.UNKNOWN,
            "" to ChaoxingAssessmentStatus.UNKNOWN,
        )

        cases.forEach { (text, expected) ->
            assertEquals(expected, ChaoxingAssessmentStatus.fromStatusText(text), "status text: $text")
        }
    }

    @Test
    fun summaryCountsPendingAndCompletedAcrossBothKinds() {
        val works = ChaoxingAssessmentService.parseWorkListHtml(
            workHtml,
            "https://mooc1-api.chaoxing.com/phone/moocAnalysis/workList",
            course,
        )
        val exams = ChaoxingAssessmentService.parseExamListHtml(
            examHtml,
            "https://mooc1-api.chaoxing.com/phone/moocAnalysis/examList",
            course,
        )
        val summary = com.caochaoxing.core.model.ChaoxingAssessmentSummary(
            works = works,
            exams = exams,
            fetchedAtMs = 1L,
        )

        assertEquals(3, summary.works.size)
        assertEquals(2, summary.exams.size)
        assertEquals(2, summary.pendingCount) // 做作业 + 未交
        assertEquals(2, summary.completedCount) // 待批阅 + 已批阅
        assertEquals(1, summary.expiredCount)
        assertEquals(5, summary.items.size)
        assertEquals("作业 1 项未完成 · 考试 1 项未完成", summary.headline())
        assertTrue(summary.warnings.isEmpty())
    }

    @Test
    fun fetchSummaryHitsBothEndpointsAndMergesResults() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            server.dispatcher = pathDispatcher(work = workHtml, exam = examHtml)

            val summary = ChaoxingAssessmentService.fetchSummary(
                endpoints = ChaoxingEndpoints.forTesting(base),
                client = syntheticClient(),
                course = course,
            )

            assertEquals(3, summary.works.size)
            assertEquals(2, summary.exams.size)
            assertEquals(2, summary.pendingCount)
            assertEquals(2, summary.completedCount)

            val paths = List(2) { server.takeRequest().path.orEmpty() }
            assertTrue(paths.any { it.startsWith("/phone/moocAnalysis/workList?courseId=300001&classId=200001") })
            assertTrue(paths.any { it.startsWith("/phone/moocAnalysis/examList?courseId=300001&classId=200001") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun oneFailingPageKeepsTheOtherAndReportsWarning() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path.orEmpty().contains("workList")) {
                        MockResponse().setResponseCode(500).setBody("boom")
                    } else {
                        MockResponse().setResponseCode(200).setBody(examHtml)
                    }
            }

            val summary = ChaoxingAssessmentService.fetchSummary(
                endpoints = ChaoxingEndpoints.forTesting(base),
                client = syntheticClient(),
                course = course,
            )

            assertTrue(summary.works.isEmpty())
            assertEquals(2, summary.exams.size)
            assertEquals(1, summary.warnings.size)
            assertTrue(summary.warnings.single().startsWith("作业："))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bothPagesFailingSurfacesAnError() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(500).setBody("boom")
            }

            val error = expectChaoxingError {
                ChaoxingAssessmentService.fetchSummary(
                    endpoints = ChaoxingEndpoints.forTesting(base),
                    client = syntheticClient(),
                    course = course,
                )
            }
            assertTrue(error is ChaoxingError.NetworkError)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun loginPageTriggersReloginAndSurfacesLoginFailure() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val loginHtml = """
                <html><head><title>用户登录</title></head>
                <body><input type="password" name="pwd"/>
                <a href="https://passport2.chaoxing.com/login">登录</a></body></html>
            """.trimIndent()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return if (path.startsWith("/fanyalogin")) {
                        MockResponse().setResponseCode(200)
                            .setBody("""{"status":false,"msg2":"用户名或密码错误"}""")
                    } else {
                        MockResponse().setResponseCode(200).setBody(loginHtml)
                    }
                }
            }

            val error = expectChaoxingError {
                ChaoxingAssessmentService.fetchSummary(
                    endpoints = ChaoxingEndpoints.forTesting(base),
                    client = syntheticClient(),
                    course = course,
                )
            }
            assertTrue(error is ChaoxingError.LoginFailed)
            assertTrue(error.message.orEmpty().contains("用户名或密码错误"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fallbackHostIsUsedWhenFirstAssessmentBaseFails() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val endpoints = ChaoxingEndpoints(
                assessmentBase = "$base/primary/phone/moocAnalysis",
                assessmentFallbackBases = listOf("$base/fallback/phone/moocAnalysis"),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/primary/") -> MockResponse().setResponseCode(500)
                        path.contains("workList") -> MockResponse().setResponseCode(200).setBody(workHtml)
                        else -> MockResponse().setResponseCode(200).setBody(examHtml)
                    }
                }
            }

            val summary = ChaoxingAssessmentService.fetchSummary(
                endpoints = endpoints,
                client = syntheticClient(),
                course = course,
            )

            assertEquals(3, summary.works.size)
            assertEquals(2, summary.exams.size)
            val paths = List(4) { server.takeRequest().path.orEmpty() }
            assertTrue(paths.count { it.startsWith("/fallback/") } == 2)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun emptyListPageIsNotTreatedAsParseFailure() {
        assertTrue(ChaoxingAssessmentService.looksLikeEmptyListPage("<div>暂无考试</div>"))
        assertFalse(ChaoxingAssessmentService.looksLikeEmptyListPage("<div>访问被拒绝</div>"))
    }

    @Test
    fun resolveUrlHandlesRelativeAndAbsolutePaths() {
        assertEquals(
            "https://mooc1-api.chaoxing.com/work/phone/task-work?a=1",
            ChaoxingAssessmentService.resolveUrl("https://mooc1-api.chaoxing.com", "work/phone/task-work?a=1"),
        )
        assertEquals(
            "https://mooc1-api.chaoxing.com/x/y",
            ChaoxingAssessmentService.resolveUrl("https://mooc1-api.chaoxing.com", "/x/y"),
        )
        assertEquals(
            "https://other.example.com/z",
            ChaoxingAssessmentService.resolveUrl("https://mooc1-api.chaoxing.com", "https://other.example.com/z"),
        )
        assertNull(ChaoxingAssessmentService.resolveUrl("https://mooc1-api.chaoxing.com", "  "))
        assertNotNull(ChaoxingAssessmentService.resolveUrl("https://mooc1-api.chaoxing.com", "a"))
    }

    @Test
    fun htmlEntitiesAreDecodedInTitles() {
        val html = """<dt data="work/phone/task-work?workId=1"><h3>A &amp; B&nbsp;测试</h3><b>做作业</b></dt>"""
        val items = ChaoxingAssessmentService.parseWorkListHtml(
            html,
            "https://mooc1-api.chaoxing.com/phone/moocAnalysis/workList",
            course,
        )
        assertEquals("A & B 测试", items.single().title)
    }

    private fun pathDispatcher(work: String, exam: String) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path.orEmpty().contains("workList") ->
                MockResponse().setResponseCode(200).setBody(work)
            request.path.orEmpty().contains("examList") ->
                MockResponse().setResponseCode(200).setBody(exam)
            else -> MockResponse().setResponseCode(404)
        }
    }
}
