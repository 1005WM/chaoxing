// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.service

import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingAttachmentKind
import com.caochaoxing.core.model.ChaoxingChapterAttachment
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingLiveTask
import com.caochaoxing.core.model.ChaoxingVideoDragEvent
import com.caochaoxing.core.model.ChaoxingVideoTask
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CourseStudyServiceTest {
    @Test
    fun buildEncSignatureMatchesSyntheticReferenceVectors() {
        val enc13 = ChaoxingCourseStudyService.buildEncSignature(
            clazzId = 200001,
            userId = "1000001",
            jobId = "job-demo-001",
            objectId = "0123456789abcdef0123456789abcdef",
            playingTimeMs = 13_000,
            durationMs = 571_000,
            clipTime = "0_571",
        )
        assertEquals("32eda22ad175618495f92e4b35cc6320", enc13)

        val enc0 = ChaoxingCourseStudyService.buildEncSignature(
            clazzId = 200001,
            userId = "1000001",
            jobId = "job-demo-001",
            objectId = "0123456789abcdef0123456789abcdef",
            playingTimeMs = 0,
            durationMs = 571_000,
            clipTime = "0_571",
        )
        assertEquals("e62813166084e621739d268180008003", enc0)
    }

    @Test
    fun parseThreeLevelHtmlAndReviewStatus() {
        val html = """
            <h3 id="10">第1章 微积分</h3>
            <h4 id="cur20">
                <span class="roundpointStudent noJob"></span>
                <a href="javascript:getTeacherAjax('1','2','20');">1.1 极限</a>
            </h4>
            <h5 id="cur30">
                <input type="hidden" value="1" class="jobUnfinishCount"/>
                <span class="roundpoint orange01 jobCount">1</span>
                <a href="javascript:getTeacherAjax('1','2','30');">1.1.1 客观题</a>
            </h5>
            <h5 id="cur31">
                <span class="roundpoint blue"></span>
                <a href="javascript:getTeacherAjax('1','2','31');">1.1.2 概念</a>
            </h5>
            <h4 id="cur40">
                <span class="roundpointStudent review"></span>
                <a href="javascript:getTeacherAjax('1','2','40');">1.2 复习讲座</a>
            </h4>
        """.trimIndent()

        val result = ChaoxingCourseStudyService.parseCourseChaptersHtml(
            html = html,
            courseId = 1L,
            classId = 2,
            hasExplicitJobCounts = true,
        )
        assertEquals(4, result.chapters.size)
        assertEquals(0, result.chapters[0].pendingJobCount)
        assertEquals("30", result.chapters[1].id)
        assertEquals("第1章 微积分 / 1.1 极限", result.chapters[1].sectionTitle)
        assertEquals(1, result.chapters[1].pendingJobCount)
        assertEquals(0, result.chapters[2].pendingJobCount)
        assertNull(result.chapters[3].pendingJobCount)
    }

    @Test
    fun parseMooc2ChapterItems() {
        val html = """
            <div class="chapter_item" id="cur500001" onclick="toOld('300002', '500001', '200002',0)" title="第一课示例">
                <div class="catalog_title"><span class="catalog_sbar">1.1</span> 第一课示例</div>
                <div class="catalog_task"><div class="catalog_state icon_yiwanc"></div></div>
            </div>
            <div class="chapter_item" id="cur500002" onclick="toOld('300002', '500002', '200002',0)" title="第二课示例">
                <div class="catalog_title"><span class="catalog_sbar">1.2</span> 第二课示例</div>
                <div class="catalog_task"><span class="catalog_points_yi">2</span></div>
            </div>
        """.trimIndent()

        val result = ChaoxingCourseStudyService.parseCourseChaptersHtml(html, courseId = 300002L, classId = 200002)
        assertEquals(2, result.chapters.size)
        assertEquals("1.1 第一课示例", result.chapters[0].title)
        assertEquals(0, result.chapters[0].pendingJobCount)
        assertEquals(2, result.chapters[1].pendingJobCount)
    }

    @Test
    fun parseGasClazzThreeLevelTreeFiltersIntermediateParents() {
        val json = """
            {"data":[{"id":2,"cpi":99,"course":{"data":[{"id":1,"knowledge":{"data":[
                {"id":10,"name":"Ch1","label":"1","indexOrder":1,"parentnodeid":0,"layer":1},
                {"id":20,"name":"Sec1.1","label":"1.1","indexOrder":1,"parentnodeid":10,"layer":2},
                {"id":31,"name":"LeafB","label":"1.1.2","indexOrder":2,"parentnodeid":20,"layer":3},
                {"id":30,"name":"LeafA","label":"1.1.1","indexOrder":1,"parentnodeid":20,"layer":3}
            ]}}]}}]}
        """.trimIndent().toByteArray()

        val res = ChaoxingCourseStudyService.parseGasClazzChaptersJson(json, courseId = 1L, classId = 2)
        assertEquals(99L, res.cpi)
        assertEquals(listOf("30", "31"), res.chapters.map { it.id })
        assertEquals("1 Ch1 / 1.1 Sec1.1", res.chapters[0].sectionTitle)
    }

    @Test
    fun parseChapterCardsHandlesVideosAndCompletedDocuments() {
        val html = """
            <script>
            mArg = {"defaults":{"userid":"42","cpi":999,"schooldoublespeed":0,"chapterCapture":1,"reportTimeInterval":60,"reportUrl":"//mooc1-2.chaoxing.com/mooc-ans/multimedia/log/a/999"},"attachments":[
                {"job":true,"jobid":"j1","type":"video","isPassed":false,"begins":20,"ends":120,"attDuration":200,"otherInfo":"nodeId_1&courseId=1","property":{"name":"trimmed.mp4","doublespeed":1,"objectid":"obj1"}},
                {"job":true,"jobid":"doc1","type":"document","jtoken":"token123","isPassed":false,"property":{"module":"insertdoc","name":"实验指导书.pdf","objectid":"objDoc1"}},
                {"jobid":"docDone","type":"document","jtoken":"token456","property":{"module":"insertdoc","name":"已完成文档.pdf","objectid":"objDoc2"}},
                {"job":true,"jobid":"work-1","type":"workid","property":{"module":"work","title":"章节测验"}}
            ]};
            </script>
        """.trimIndent()

        val content = ChaoxingCourseStudyService.parseChapterCardsContent(html, courseId = 1L, classId = 2, chapterId = "10")
        assertEquals(1, content.videos.size)
        val v = content.videos.first()
        assertEquals(0, v.doublespeed)
        assertTrue(v.requiresFaceCapture)
        assertEquals("20_120", v.effectiveClipTime)
        assertEquals(90, v.requiredWatchSeconds)
        assertEquals("https://mooc1-2.chaoxing.com/mooc-ans/multimedia/log/a/999", v.reportUrl)

        assertEquals(3, content.otherAttachments.size)
        assertEquals(ChaoxingAttachmentKind.DOCUMENT, content.otherAttachments[0].kind)
        assertTrue(content.otherAttachments[0].canCompleteAsDocument)
        assertTrue(content.otherAttachments[1].isPassed)
        assertFalse(content.otherAttachments[1].canCompleteAsDocument)
        assertEquals(ChaoxingAttachmentKind.QUIZ, content.otherAttachments[2].kind)
    }

    @Test
    fun sendVideoLogAndCompleteDocumentTaskWorkOverMockWebServer() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val endpoints = ChaoxingEndpoints.forTesting(baseUrl)
            val client = ChaoxingHttpClient.createForLoopbackTesting(
                session = ChaoxingAccountSession(
                    phoneNumber = "13800000001",
                    encryptedPassword = "enc",
                    name = "测试用户",
                    uid = 1000001,
                    puid = 1000001,
                    deviceCode = "demo-device",
                ),
            )

            // 1. Video log success
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"isPassed":true}"""))
            val task = ChaoxingVideoTask(
                courseId = 300001L,
                classId = 200001,
                chapterId = "800101",
                cpi = 900001L,
                userid = "1000001",
                jobid = "job-demo-001",
                objectId = "0123456789abcdef0123456789abcdef",
                name = "demo.mp4",
                otherInfo = "nodeId_800101",
                dtoken = "dtoken-demo",
                duration = 571,
                attDuration = 571,
                attDurationEnc = "attenc",
                reportUrl = "$baseUrl/mooc-ans/multimedia/log/a/900001",
            )
            val logRes = ChaoxingCourseStudyService.sendVideoLog(
                endpoints = endpoints,
                client = client,
                task = task,
                playingTimeSec = 13,
                event = ChaoxingVideoDragEvent.PLAYING,
                nowMs = 1_700_000_000_000L,
            )
            assertTrue(logRes.isPassed)
            val logReq = server.takeRequest()
            assertTrue(logReq.path!!.contains("enc=32eda22ad175618495f92e4b35cc6320"))
            assertEquals(ChaoxingCourseStudyService.PC_WEB_USER_AGENT, logReq.getHeader("User-Agent"))

            // 2. 403 restTime error parsing
            server.enqueue(MockResponse().setResponseCode(403).setBody("blocked [restTime:180]"))
            val err = assertThrows(ChaoxingError.NetworkError::class.java) {
                kotlinx.coroutines.runBlocking {
                    ChaoxingCourseStudyService.sendVideoLog(
                        endpoints = endpoints,
                        client = client,
                        task = task,
                        playingTimeSec = 60,
                        event = ChaoxingVideoDragEvent.PLAYING,
                    )
                }
            }
            assertEquals(403, err.statusCode)
            assertTrue(err.message.contains("[restTime:180]"))
            server.takeRequest()

            // 3. Document task completion uses official `/mooc-ans/job/document`
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":true,"msg":"ok"}"""))
            val doc = ChaoxingChapterAttachment(
                kind = ChaoxingAttachmentKind.DOCUMENT,
                courseId = 300001L,
                classId = 200001,
                chapterId = "800101",
                jobid = "doc-job-1",
                jtoken = "jtoken-demo",
                name = "实验指导.pdf",
            )
            val docOk = ChaoxingCourseStudyService.completeDocumentTask(endpoints, client, doc, nowMs = 1_700_000_000_000L)
            assertTrue(docOk)
            val docReq = server.takeRequest()
            assertTrue(docReq.path!!.startsWith("/mooc-ans/job/document?"))
            assertTrue(docReq.path!!.contains("checkMicroTopic=true"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun liveReplayParsingAndHeartbeatProtocolWorkOverMockWebServer() = runTest {
        val cardsHtml = """
            <script>
            mArg = {"defaults":{"userid":"1000001","cpi":900001,"reportTimeInterval":60},"attachments":[
                {
                    "job":true,"jobid":"live-5000123456789001","aid":88001,"type":"live",
                    "liveSetEnc":"setenc1","authEnc":"authenc1","liveDragEnc":"dragenc1","liveSwDsEnc":"swdsenc1",
                    "property":{"module":"insertlive","title":"示范回放-第一场","liveId":5000123456789001,"streamName":"STREAM_DEMO_1","vdoid":"vdoid_demo_1","doublespeed":1,"fastforward":"false","rt":0.9}
                },
                {
                    "jobid":"live-5000123456789002","aid":88002,"type":"live",
                    "property":{"module":"insertlive","title":"示范回放-已完成","liveId":5000123456789002,"streamName":"STREAM_DEMO_2","vdoid":"vdoid_demo_2"}
                },
                {
                    "type":"live",
                    "property":{"module":"insertlive","title":"示范回放-非任务点","liveId":5000123456789003,"streamName":"STREAM_DEMO_3","vdoid":"vdoid_demo_3"}
                },
                {
                    "job":true,"jobid":"audio-1","type":"audio","otherInfo":"nodeId_8001","attDuration":180,"attDurationEnc":"audenc",
                    "property":{"module":"insertaudio","name":"示范音频.mp3","objectid":"audioobj1234567890"}
                }
            ]};
            </script>
        """.trimIndent()

        val parsed = ChaoxingCourseStudyService.parseChapterCardsContent(cardsHtml, 300001L, 200001, "8001")
        assertEquals(1, parsed.videos.size)
        assertEquals("Audio", parsed.videos[0].dtype)
        assertEquals(3, parsed.liveTasks.size)
        assertTrue(parsed.liveTasks[0].isReportableTask)
        assertFalse(parsed.liveTasks[0].isPassed)
        assertTrue(parsed.liveTasks[1].isPassed)
        assertFalse(parsed.liveTasks[2].isJob)

        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val endpoints = ChaoxingEndpoints.forTesting(baseUrl)
            val client = ChaoxingHttpClient.createForLoopbackTesting(
                session = ChaoxingAccountSession(
                    phoneNumber = "13800000001",
                    encryptedPassword = "enc",
                    name = "测试用户",
                    uid = 1000001,
                    puid = 1000001,
                    deviceCode = "demo-device",
                ),
            )

            // 1. fetchLiveInfo (including fallback MP4 URL)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"status":true,"courseEndState":0,"temp":{"data":{"liveId":"5000123456789001","title":"示范回放-第一场","liveStatus":4,"ifReview":0,"duration":3000,"playbackTimelong":60000,"percentValue":2.0,"streamName":"STREAM_DEMO_1","vdoid":"vdoid_demo_1","m3u8Url":"http://example.invalid/demo.m3u8","mp4Url":"http://example.invalid/demo.mp4"}}}""",
                ),
            )
            val liveTask = parsed.liveTasks[0]
            val info = ChaoxingCourseStudyService.fetchLiveInfo(endpoints, client, liveTask)
            assertEquals(3000, info.duration)
            assertEquals(60000, info.playbackTimelongMs)
            assertEquals(2.0, info.percentValue, 0.001)
            assertEquals("https://example.invalid/demo.m3u8", info.preferredPlayUrl)
            assertEquals("https://example.invalid/demo.mp4", info.fallbackPlayUrl)
            assertEquals(
                listOf("https://example.invalid/demo.m3u8", "https://example.invalid/demo.mp4"),
                info.allPlayUrls,
            )
            val infoReq = server.takeRequest()
            assertTrue(infoReq.path!!.startsWith("/ananas/live/liveinfo?"))

            // 2. prepareLiveReplaySession (relation + zhibo page with Thymeleaf template literal + real /iframeLoginSSO + savecourselive)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":true}"""))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """<iframe style="display:none" th:if="${'$'}{userId!=null}" src="/iframeLoginSSO?u='+${'$'}{uInfo}"></iframe>""" +
                        """<script>var liveId = 99001001; var uInfo = 'demo_u'; var liveduration=3000; var fid = '12345';</script>""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""<script>parent.postMessage({cmd:"loginSSO"},"*")</script>"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":true}"""))
            val ctx = ChaoxingCourseStudyService.prepareLiveReplaySession(endpoints, client, liveTask)
            assertEquals("99001001", ctx.shortLiveId)
            assertEquals("demo_u", ctx.uInfo)
            assertEquals(3000, ctx.duration)
            assertEquals("12345", ctx.fid)
            assertTrue(server.takeRequest().path!!.startsWith("/mooc-ans/live/relation?"))
            assertTrue(server.takeRequest().path!!.startsWith("/5000123456789001?"))
            val ssoReq = server.takeRequest()
            assertEquals("/iframeLoginSSO?u=demo_u", ssoReq.path)
            assertTrue(server.takeRequest().path!!.startsWith("/live/savecourselive?"))

            // 3. sendLiveHeartbeat (isStart=false -> /saveTimePc?isStart=1 + /apis/live/put/watchMoment)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"result":1,"msg":"","legacy":"@success","data":{"accepted":true,"live":{"duration":3340,"livestatus":4}}}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"result":1,"msg":"请求成功"}"""))
            val hbRes = ChaoxingCourseStudyService.sendLiveHeartbeat(
                endpoints = endpoints,
                client = client,
                task = liveTask,
                session = ctx,
                isStart = false,
                watchMomentSec = 90,
                nowMs = 1_700_000_000_000L,
            )
            assertEquals(1, hbRes.result)
            assertTrue(hbRes.accepted)
            assertEquals(3340, hbRes.duration)
            val saveTimeReq = server.takeRequest()
            assertTrue(saveTimeReq.path!!.startsWith("/saveTimePc?"))
            assertTrue(saveTimeReq.path!!.contains("isStart=1"))
            val wmReq = server.takeRequest()
            assertTrue(wmReq.path!!.startsWith("/apis/live/put/watchMoment?"))
            assertTrue(wmReq.path!!.contains("watchMoment=90"))

            // 4. sendLiveHeartbeat rejects `accepted=false` with ServerMessage instead of silently succeeding
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"result":1,"msg":"心跳未接受","data":{"accepted":false}}""",
                ),
            )
            var rejectedErr: Throwable? = null
            try {
                ChaoxingCourseStudyService.sendLiveHeartbeat(
                    endpoints = endpoints,
                    client = client,
                    task = liveTask,
                    session = ctx,
                    isStart = false,
                    watchMomentSec = 120,
                    nowMs = 1_700_000_030_000L,
                )
            } catch (e: Throwable) {
                rejectedErr = e
            }
            assertTrue(rejectedErr is com.caochaoxing.core.model.ChaoxingError.ServerMessage)
            assertEquals("心跳未接受", rejectedErr?.message)
        } finally {
            server.shutdown()
        }
    }
}
