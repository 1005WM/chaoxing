// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import com.caochaoxing.android.state.CourseStudyController
import com.caochaoxing.android.state.CourseStudyDependencies
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingAttachmentKind
import com.caochaoxing.core.model.ChaoxingChapterAttachment
import com.caochaoxing.core.model.ChaoxingChapterContent
import com.caochaoxing.core.model.ChaoxingChapterNode
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingCourseChaptersResult
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingLiveHeartbeatResponse
import com.caochaoxing.core.model.ChaoxingLiveInfo
import com.caochaoxing.core.model.ChaoxingLiveSessionContext
import com.caochaoxing.core.model.ChaoxingLiveTask
import com.caochaoxing.core.model.ChaoxingVideoDragEvent
import com.caochaoxing.core.model.ChaoxingVideoLogResponse
import com.caochaoxing.core.model.ChaoxingVideoTask
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class CourseStudyControllerTest {

    private fun demoClient(phone: String = "13800000001"): ChaoxingHttpClient =
        ChaoxingHttpClient(
            session = ChaoxingAccountSession(
                phoneNumber = phone,
                encryptedPassword = "enc",
                name = "测试用户",
                uid = 1000001,
                puid = 1000001,
                deviceCode = "demo-device",
            ),
        )

    private fun demoCourse(courseId: Long, classId: Int, name: String): ChaoxingCourse =
        ChaoxingCourse(
            courseId = courseId,
            classId = classId,
            courseName = name,
            teacherName = "教师",
            imageURL = "",
            cpi = 900001L,
        )

    private fun demoVideo(
        courseId: Long,
        classId: Int,
        chapterId: String,
        jobid: String,
        duration: Int = 20,
        doublespeed: Int = 1,
    ): ChaoxingVideoTask = ChaoxingVideoTask(
        courseId = courseId,
        classId = classId,
        chapterId = chapterId,
        cpi = 900001L,
        userid = "1000001",
        jobid = jobid,
        objectId = "0123456789abcdef0123456789abcdef",
        name = "视频-$jobid.mp4",
        otherInfo = "nodeId_$chapterId",
        dtoken = "dtoken-$jobid",
        duration = duration,
        attDuration = duration,
        attDurationEnc = "attenc",
        doublespeed = doublespeed,
        reportTimeInterval = 15,
        reportUrl = "https://mooc1-2.chaoxing.com/mooc-ans/multimedia/log/a/900001",
    )

    private fun demoDoc(
        courseId: Long,
        classId: Int,
        chapterId: String,
        jobid: String,
    ): ChaoxingChapterAttachment = ChaoxingChapterAttachment(
        kind = ChaoxingAttachmentKind.DOCUMENT,
        courseId = courseId,
        classId = classId,
        chapterId = chapterId,
        jobid = jobid,
        objectId = "docobj-$jobid",
        jtoken = "jtoken-$jobid",
        name = "课件-$jobid.pdf",
        isJob = true,
        isPassed = false,
    )

    private class FakeStudyDependencies(
        private val chaptersByCourseId: Map<Long, List<ChaoxingChapterNode>>,
        private val contentByChapterId: Map<String, ChaoxingChapterContent>,
        private val onSendVideoLog: (ChaoxingVideoTask, Int, ChaoxingVideoDragEvent) -> ChaoxingVideoLogResponse =
            { task, pos, event ->
                ChaoxingVideoLogResponse(isPassed = event == ChaoxingVideoDragEvent.ENDED || pos >= task.effectiveClipEnd)
            },
        private val onCompleteDoc: (ChaoxingChapterAttachment) -> Boolean = { true },
    ) : CourseStudyDependencies {
        val fetchedCourseIds = CopyOnWriteArrayList<Long>()
        val completedDocIds = CopyOnWriteArrayList<String>()
        val logCalls = CopyOnWriteArrayList<Triple<String, Int, ChaoxingVideoDragEvent>>()
        val virtualClockMs = AtomicLong(1_700_000_000_000L)

        override suspend fun fetchCourseChapters(
            client: ChaoxingHttpClient,
            courseId: Long,
            classId: Int,
            knownCpi: Long,
        ): ChaoxingCourseChaptersResult {
            fetchedCourseIds.add(courseId)
            return ChaoxingCourseChaptersResult(
                cpi = knownCpi.takeIf { it > 0L } ?: 900001L,
                chapters = chaptersByCourseId[courseId].orEmpty(),
            )
        }

        override suspend fun fetchChapterContent(
            client: ChaoxingHttpClient,
            courseId: Long,
            classId: Int,
            chapterId: String,
            cpi: Long,
        ): ChaoxingChapterContent = contentByChapterId[chapterId] ?: ChaoxingChapterContent(emptyList())

        override suspend fun sendVideoLog(
            client: ChaoxingHttpClient,
            task: ChaoxingVideoTask,
            playingTimeSec: Int,
            event: ChaoxingVideoDragEvent,
        ): ChaoxingVideoLogResponse {
            logCalls.add(Triple(task.jobid, playingTimeSec, event))
            return onSendVideoLog(task, playingTimeSec, event)
        }

        override suspend fun completeDocumentTask(
            client: ChaoxingHttpClient,
            attachment: ChaoxingChapterAttachment,
        ): Boolean {
            completedDocIds.add(attachment.jobid)
            return onCompleteDoc(attachment)
        }

        override suspend fun sleep(delayMs: Long) {
            virtualClockMs.addAndGet(delayMs)
        }

        override fun nowMs(): Long = virtualClockMs.get()
    }

    @Test
    fun startWatchDefaultsToOnlySelectedCourseWhenAutoMatchIsOff() = runTest {
        val courseA = demoCourse(300001L, 200001, "主选课程")
        val courseB = demoCourse(300002L, 200002, "其他课程")
        val chA = ChaoxingChapterNode("8001", "第一章", "", 300001L, 200001, pendingJobCount = 2)
        val chB = ChaoxingChapterNode("9001", "其他章", "", 300002L, 200002, pendingJobCount = 1)
        val deps = FakeStudyDependencies(
            chaptersByCourseId = mapOf(
                300001L to listOf(chA),
                300002L to listOf(chB),
            ),
            contentByChapterId = mapOf(
                "8001" to ChaoxingChapterContent(
                    videos = listOf(demoVideo(300001L, 200001, "8001", "job-a1", duration = 20)),
                    otherAttachments = listOf(demoDoc(300001L, 200001, "8001", "doc-a1")),
                ),
                "9001" to ChaoxingChapterContent(
                    videos = listOf(demoVideo(300002L, 200002, "9001", "job-b1", duration = 20)),
                ),
            ),
        )

        val client = demoClient()
        val controller = CourseStudyController(
            clientProvider = { client },
            scope = this,
            dependencies = deps,
        )

        assertFalse(controller.uiState.value.watchAllCoursesAutomatically)
        controller.startWatch(courseA, listOf(courseA, courseB))
        advanceUntilIdle()

        val state = controller.uiState.value
        assertFalse(state.isWatching)
        assertEquals(1, state.completedVideosCount)
        assertEquals(1, state.completedDocsCount)
        assertTrue(300001L in deps.fetchedCourseIds)
        assertFalse(300002L in deps.fetchedCourseIds)
        assertEquals(listOf("doc-a1"), deps.completedDocIds)
        assertTrue(deps.logCalls.any { it.first == "job-a1" && it.third == ChaoxingVideoDragEvent.PLAY })
        assertTrue(deps.logCalls.any { it.first == "job-a1" && it.third == ChaoxingVideoDragEvent.ENDED })
    }

    @Test
    fun startWatchCoversAllCoursesWhenAutoMatchToggleIsEnabled() = runTest {
        val courseA = demoCourse(300001L, 200001, "主选课程")
        val courseB = demoCourse(300002L, 200002, "其他课程")
        val chA = ChaoxingChapterNode("8001", "第一章", "", 300001L, 200001, pendingJobCount = 1)
        val chB = ChaoxingChapterNode("9001", "其他章", "", 300002L, 200002, pendingJobCount = 1)
        val deps = FakeStudyDependencies(
            chaptersByCourseId = mapOf(
                300001L to listOf(chA),
                300002L to listOf(chB),
            ),
            contentByChapterId = mapOf(
                "8001" to ChaoxingChapterContent(
                    videos = listOf(demoVideo(300001L, 200001, "8001", "job-a1", duration = 16)),
                ),
                "9001" to ChaoxingChapterContent(
                    videos = listOf(demoVideo(300002L, 200002, "9001", "job-b1", duration = 16)),
                ),
            ),
        )

        val client = demoClient()
        val controller = CourseStudyController(
            clientProvider = { client },
            scope = this,
            dependencies = deps,
        )
        controller.setWatchAllCoursesAutomatically(true)
        controller.startWatch(courseA, listOf(courseA, courseB))
        advanceUntilIdle()

        val state = controller.uiState.value
        assertEquals(2, state.completedVideosCount)
        assertTrue(300001L in deps.fetchedCourseIds)
        assertTrue(300002L in deps.fetchedCourseIds)
    }

    @Test
    fun rateLimit403WithRestTimeTriggersCooldownAndCompletesOnRetry() = runTest {
        val course = demoCourse(300001L, 200001, "测试课")
        val ch = ChaoxingChapterNode("8001", "第一章", "", 300001L, 200001, pendingJobCount = 1)
        val attempts = AtomicInteger(0)
        val deps = FakeStudyDependencies(
            chaptersByCourseId = mapOf(300001L to listOf(ch)),
            contentByChapterId = mapOf(
                "8001" to ChaoxingChapterContent(
                    videos = listOf(demoVideo(300001L, 200001, "8001", "job-rl", duration = 12)),
                ),
            ),
            onSendVideoLog = { task, pos, event ->
                if (event == ChaoxingVideoDragEvent.ENDED && attempts.incrementAndGet() == 1) {
                    throw ChaoxingError.NetworkError(
                        statusCode = 403,
                        message = "本次学习时长已达上限 [restTime:15]",
                    )
                }
                ChaoxingVideoLogResponse(isPassed = event == ChaoxingVideoDragEvent.ENDED || pos >= task.effectiveClipEnd)
            },
        )

        val client = demoClient()
        val controller = CourseStudyController(
            clientProvider = { client },
            scope = this,
            dependencies = deps,
        )
        controller.setMaxConcurrentWatches(3)
        controller.startWatch(course, listOf(course))
        advanceUntilIdle()

        val state = controller.uiState.value
        assertEquals(1, state.completedVideosCount)
        assertEquals(0, state.skippedCount)
        assertEquals(0, state.cooldownRemainingSec)
        assertTrue(state.logs.any { it.message.contains("限流 (403)") && it.message.contains("并发降至 2 路") })
    }

    @Test
    fun accountSwitchClearsCachedChaptersAndPreservesUserPreferences() = runTest {
        val course = demoCourse(300001L, 200001, "账号A课程")
        val ch = ChaoxingChapterNode("8001", "第一章", "", 300001L, 200001, pendingJobCount = 1)
        val doc = demoDoc(300001L, 200001, "8001", "doc-1")
        val deps = FakeStudyDependencies(
            chaptersByCourseId = mapOf(300001L to listOf(ch)),
            contentByChapterId = mapOf(
                "8001" to ChaoxingChapterContent(
                    videos = emptyList(),
                    otherAttachments = listOf(doc),
                ),
            ),
        )
        val client = demoClient("13800000001")
        val controller = CourseStudyController(
            clientProvider = { client },
            scope = this,
            dependencies = deps,
        )

        controller.onAccountChanged("13800000001")
        controller.setMaxConcurrentWatches(5)
        controller.setWatchAllCoursesAutomatically(true)
        controller.loadChapters(course)
        controller.loadChapterContent(course, ch)
        advanceUntilIdle()

        assertEquals(1, controller.uiState.value.chaptersByCourse.size)
        controller.completeDocument(course.id, ch, doc)
        advanceUntilIdle()

        val chKey = CourseStudyController.chapterKey(ch)
        assertTrue(controller.uiState.value.contentByChapterKey[chKey]!!.otherAttachments.first().isPassed)
        assertEquals(0, controller.uiState.value.chaptersByCourse[course.id]!!.chapters.first().pendingJobCount)

        // Switch account: caches must clear completely while user toggles survive.
        controller.onAccountChanged("13800000002")
        val afterSwitch = controller.uiState.value
        assertEquals("13800000002", afterSwitch.activeAccountPhone)
        assertTrue(afterSwitch.chaptersByCourse.isEmpty())
        assertTrue(afterSwitch.contentByChapterKey.isEmpty())
        assertEquals(5, afterSwitch.maxConcurrentWatches)
        assertTrue(afterSwitch.watchAllCoursesAutomatically)
    }

    @Test
    fun startWatchRunsExternalZhiboLiveReplayHeartbeatLoopAndMarksPassed() = runTest {
        val course = demoCourse(300001L, 200001, "国家安全示例")
        val ch = ChaoxingChapterNode("8001", "第一讲直播回放", "", 300001L, 200001, pendingJobCount = 1)
        val live = ChaoxingLiveTask(
            courseId = 300001L,
            classId = 200001,
            chapterId = "8001",
            cpi = 900001L,
            userid = "1000001",
            jobid = "live-5000123456789001",
            aid = "88001",
            liveId = "5000123456789001",
            streamName = "STREAM_DEMO_1",
            vdoid = "vdoid_demo_1",
            title = "示例直播回放",
            rt = 0.9,
            isJob = true,
            isPassed = false,
            liveStatus = 4,
            ifReview = 0,
            duration = 60,
            playbackTimelongMs = 0,
            percentValue = 0.0,
            webPageUrl = "https://zhibo.chaoxing.com/5000123456789001?watchingInCourse=1",
        )
        val heartbeatFlags = CopyOnWriteArrayList<Pair<Boolean, Int>>()
        val watchedMs = AtomicInteger(0)
        val cardPassed = AtomicInteger(0)

        val deps = object : CourseStudyDependencies {
            val clock = AtomicLong(1_700_000_000_000L)
            override suspend fun fetchCourseChapters(
                client: ChaoxingHttpClient,
                courseId: Long,
                classId: Int,
                knownCpi: Long,
            ) = ChaoxingCourseChaptersResult(
                cpi = 900001L,
                chapters = listOf(ch.copy(pendingJobCount = if (cardPassed.get() > 0) 0 else 1)),
            )

            override suspend fun fetchChapterContent(
                client: ChaoxingHttpClient,
                courseId: Long,
                classId: Int,
                chapterId: String,
                cpi: Long,
            ) = ChaoxingChapterContent(
                videos = emptyList(),
                liveTasks = listOf(live.copy(isPassed = cardPassed.get() > 0)),
            )

            override suspend fun sendVideoLog(
                client: ChaoxingHttpClient,
                task: ChaoxingVideoTask,
                playingTimeSec: Int,
                event: ChaoxingVideoDragEvent,
            ) = ChaoxingVideoLogResponse(isPassed = true)

            override suspend fun completeDocumentTask(
                client: ChaoxingHttpClient,
                attachment: ChaoxingChapterAttachment,
            ) = true

            override suspend fun fetchLiveInfo(
                client: ChaoxingHttpClient,
                task: ChaoxingLiveTask,
            ): ChaoxingLiveInfo {
                val played = watchedMs.get()
                val pct = (played / 1000.0 / 60.0) * 100.0
                if (pct >= 90.0) cardPassed.set(1)
                return ChaoxingLiveInfo(
                    resolvedLiveId = task.liveId,
                    title = task.title,
                    liveStatus = 4,
                    ifReview = 0,
                    duration = 60,
                    playbackTimelongMs = played,
                    percentValue = pct,
                    streamName = task.streamName,
                    vdoid = task.vdoid,
                )
            }

            override suspend fun prepareLiveReplaySession(
                client: ChaoxingHttpClient,
                task: ChaoxingLiveTask,
            ) = ChaoxingLiveSessionContext(
                pageUrl = task.webPageUrl.orEmpty(),
                shortLiveId = "99001001",
                uInfo = "demo_u",
                streamName = task.streamName,
                vdoid = task.vdoid,
                duration = 60,
            )

            override suspend fun sendLiveHeartbeat(
                client: ChaoxingHttpClient,
                task: ChaoxingLiveTask,
                session: ChaoxingLiveSessionContext,
                isStart: Boolean,
                watchMomentSec: Int,
            ): ChaoxingLiveHeartbeatResponse {
                heartbeatFlags.add(isStart to watchMomentSec)
                if (!isStart) {
                    watchedMs.addAndGet(30_000)
                }
                return ChaoxingLiveHeartbeatResponse(result = 1, accepted = true, duration = 60, liveStatus = 4)
            }

            override suspend fun sleep(delayMs: Long) {
                clock.addAndGet(delayMs)
            }

            override fun nowMs(): Long = clock.get()
        }

        val client = demoClient()
        val controller = CourseStudyController(
            clientProvider = { client },
            scope = this,
            dependencies = deps,
        )
        controller.startWatch(course, listOf(course))
        advanceUntilIdle()

        val state = controller.uiState.value
        assertFalse(state.isWatching)
        assertEquals(1, state.completedVideosCount)
        assertEquals(0, state.skippedCount)
        assertTrue(heartbeatFlags.first().first) // initial isStart=0
        assertTrue(heartbeatFlags.count { !it.first } >= 2) // at least two ticks for 60s replay
        val chKey = CourseStudyController.chapterKey(ch)
        assertTrue(state.contentByChapterKey[chKey]!!.liveTasks.first().isPassed)
        assertEquals(0, state.chaptersByCourse[course.id]!!.chapters.first().pendingJobCount)
    }

    @Test
    fun parallelLiveReplaysCompleteConcurrentlyWithoutBackwardClamping() = runTest {
        val course = ChaoxingCourse(
            courseId = 300002L,
            classId = 200002,
            courseName = "并行直播测试课",
            teacherName = "教师",
            imageURL = "",
            cpi = 900002L,
        )
        val chapters = (1..3).map { idx ->
            ChaoxingChapterNode(
                id = "800$idx",
                title = "${idx}.1 专题讲座直播",
                courseId = course.courseId,
                classId = course.classId,
                pendingJobCount = 1,
            )
        }
        val livesByChapter = chapters.associate { ch ->
            ch.id to ChaoxingLiveTask(
                courseId = course.courseId,
                classId = course.classId,
                chapterId = ch.id,
                cpi = 900002L,
                userid = "1000001",
                jobid = "live-500012345678900${ch.id}",
                liveId = "500012345678900${ch.id}",
                streamName = "STREAM_DEMO_${ch.id}",
                vdoid = "vdoid_demo_${ch.id}",
                title = "示范直播-${ch.id}",
                duration = 120,
                rt = 0.9,
                isJob = true,
                isPassed = false,
            )
        }
        val ticksByLive = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val passedByLive = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val momentsByLive = java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<Int>>()

        val deps = object : CourseStudyDependencies {
            val clock = AtomicLong(1_700_000_000_000L)
            override suspend fun fetchCourseChapters(
                client: ChaoxingHttpClient,
                courseId: Long,
                classId: Int,
                knownCpi: Long,
            ) = ChaoxingCourseChaptersResult(
                cpi = 900002L,
                chapters = chapters.map { ch ->
                    val live = livesByChapter.getValue(ch.id)
                    ch.copy(pendingJobCount = if (passedByLive[live.id] == true) 0 else 1)
                },
            )

            override suspend fun fetchChapterContent(
                client: ChaoxingHttpClient,
                courseId: Long,
                classId: Int,
                chapterId: String,
                cpi: Long,
            ): ChaoxingChapterContent {
                val live = livesByChapter.getValue(chapterId)
                return ChaoxingChapterContent(
                    videos = emptyList(),
                    liveTasks = listOf(live.copy(isPassed = passedByLive[live.id] == true)),
                )
            }

            override suspend fun sendVideoLog(
                client: ChaoxingHttpClient,
                task: ChaoxingVideoTask,
                playingTimeSec: Int,
                event: ChaoxingVideoDragEvent,
            ) = ChaoxingVideoLogResponse(isPassed = true)

            override suspend fun completeDocumentTask(
                client: ChaoxingHttpClient,
                attachment: ChaoxingChapterAttachment,
            ) = true

            override suspend fun fetchLiveInfo(
                client: ChaoxingHttpClient,
                task: ChaoxingLiveTask,
            ): ChaoxingLiveInfo {
                // Simulate Chaoxing's real 60s flush delay: lags behind by 2 ticks (60s) before flushing!
                val ticks = ticksByLive[task.id]?.get() ?: 0
                val flushedTicks = (ticks - 1).coerceAtLeast(0)
                val playedMs = (flushedTicks * 30_000).coerceAtMost(120_000)
                val pct = (playedMs / 1000.0 / 120.0) * 100.0
                if (pct >= 90.0) passedByLive[task.id] = true
                return ChaoxingLiveInfo(
                    resolvedLiveId = task.liveId,
                    title = task.title,
                    liveStatus = 4,
                    ifReview = 0,
                    duration = 120,
                    playbackTimelongMs = playedMs,
                    percentValue = pct,
                    streamName = task.streamName,
                    vdoid = task.vdoid,
                )
            }

            override suspend fun prepareLiveReplaySession(
                client: ChaoxingHttpClient,
                task: ChaoxingLiveTask,
            ) = ChaoxingLiveSessionContext(
                pageUrl = "https://zhibo.chaoxing.com/${task.liveId}",
                shortLiveId = "99001001",
                uInfo = "demo_u",
                streamName = task.streamName,
                vdoid = task.vdoid,
                duration = 120,
            )

            override suspend fun sendLiveHeartbeat(
                client: ChaoxingHttpClient,
                task: ChaoxingLiveTask,
                session: ChaoxingLiveSessionContext,
                isStart: Boolean,
                watchMomentSec: Int,
            ): ChaoxingLiveHeartbeatResponse {
                if (!isStart) {
                    ticksByLive.computeIfAbsent(task.id) { AtomicInteger(0) }.incrementAndGet()
                    momentsByLive.computeIfAbsent(task.id) { CopyOnWriteArrayList() }.add(watchMomentSec)
                }
                return ChaoxingLiveHeartbeatResponse(result = 1, accepted = true, duration = 120, liveStatus = 4)
            }

            override suspend fun sleep(delayMs: Long) {
                clock.addAndGet(delayMs)
            }

            override fun nowMs(): Long = clock.get()
        }

        val client = demoClient()
        val controller = CourseStudyController(
            clientProvider = { client },
            scope = this,
            dependencies = deps,
        )
        controller.setMaxConcurrentWatches(3)
        controller.startWatch(course, listOf(course))
        advanceUntilIdle()

        val state = controller.uiState.value
        assertFalse(state.isWatching)
        assertEquals(3, state.completedVideosCount)
        assertEquals(0, state.skippedCount)
        // Verify watchMomentSec never regressed backwards on ANY of the 3 parallel streams during 60s flush lag!
        for (live in livesByChapter.values) {
            val moments = momentsByLive[live.id].orEmpty()
            assertTrue("Expected >= 4 ticks for ${live.title}", moments.size >= 4)
            for (i in 1 until moments.size) {
                assertTrue(
                    "watchMoment regressed on ${live.title}: ${moments[i - 1]} -> ${moments[i]}",
                    moments[i] >= moments[i - 1],
                )
            }
        }
    }
}
