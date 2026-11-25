// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import com.caochaoxing.android.state.AssessmentOverviewController
import com.caochaoxing.android.state.AssessmentOverviewState
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingAssessmentItem
import com.caochaoxing.core.model.ChaoxingAssessmentKind
import com.caochaoxing.core.model.ChaoxingAssessmentSummary
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssessmentOverviewControllerTest {
    @Test
    fun manualCompletionUpdatesAllCountsSurvivesRefreshAndCanBeUndone() = runTest {
        val course = course(1, "数学")
        val work = pendingWork(course, "矩估计作业").copy(deadlineAtMs = 99)
        val exam = work.copy(kind = ChaoxingAssessmentKind.EXAM)
        val controller = AssessmentOverviewController(
            clientProvider = { client() }, scope = this,
            fetchSummary = { _, _ -> ChaoxingAssessmentSummary(works = listOf(work), exams = listOf(exam)) },
        )
        controller.onAccountChanged("student")
        controller.load(listOf(course))
        advanceUntilIdle()
        assertTrue(controller.setWorkCompleted(work, true, "student"))
        with(controller.uiState.value) {
            assertEquals(0, pendingWorkCount)
            assertEquals(0, pendingWorks(course.id))
            assertEquals(0, overdueWorkCount(100))
            assertEquals(1, pendingExamCount)
            assertEquals(listOf(exam), pendingItems)
            assertEquals(1, pendingCourseCount)
            assertEquals("未交", summaries[course.id]!!.works.single().statusText)
        }
        assertFalse(controller.setWorkCompleted(exam, true, "student"))
        controller.load(listOf(course), force = true)
        advanceUntilIdle()
        assertEquals(0, controller.uiState.value.pendingWorkCount)
        assertTrue(controller.setWorkCompleted(work, false, "student"))
        assertEquals(1, controller.uiState.value.pendingWorkCount)
        assertEquals(1, controller.uiState.value.overdueWorkCount(100))
    }

    @Test
    fun completionMarksAreRestoredPerAccountAndRejectOldAccountUndo() = runTest {
        val course = course(1, "数学")
        val work = pendingWork(course, "作业")
        val saved = mutableMapOf<String, Set<String>>()
        fun controller() = AssessmentOverviewController(
            clientProvider = { client() }, scope = this,
            fetchSummary = { _, _ -> ChaoxingAssessmentSummary(works = listOf(work)) },
            loadCompletedWorks = { saved[it].orEmpty() },
            saveCompletedWorks = { phone, keys -> saved[phone] = keys },
        )
        val first = controller()
        first.onAccountChanged("A")
        first.load(listOf(course))
        advanceUntilIdle()
        assertTrue(first.setWorkCompleted(work, true, "A"))
        assertEquals(0, first.uiState.value.pendingCourseCount)
        first.onAccountChanged("B")
        first.load(listOf(course))
        advanceUntilIdle()
        assertEquals(1, first.uiState.value.pendingWorkCount)
        assertFalse(first.setWorkCompleted(work, false, "A"))
        val restored = controller()
        restored.onAccountChanged("A")
        restored.load(listOf(course))
        advanceUntilIdle()
        assertEquals(0, restored.uiState.value.totalPending)
        restored.onAccountChanged(null)
        assertTrue(restored.uiState.value.completedWorkKeys.isEmpty())
    }

    @Test
    fun sameTitleAssignmentsUseWorkIdAndIgnoreChangingSessionParameters() = runTest {
        val course = course(1, "数学")
        val first = pendingWork(course, "作业").copy(taskUrl = "https://mooc1.chaoxing.com/work?workId=1&token=old")
        val second = first.copy(taskUrl = "https://mooc1.chaoxing.com/work?workId=2")
        var latestFirst = first
        val controller = AssessmentOverviewController(
            clientProvider = { client() }, scope = this,
            fetchSummary = { _, _ -> ChaoxingAssessmentSummary(works = listOf(latestFirst, second)) },
        )
        controller.onAccountChanged("student")
        controller.load(listOf(course))
        advanceUntilIdle()
        assertTrue(controller.setWorkCompleted(first, true, "student"))
        latestFirst = first.copy(title = "作业（已改名）", taskUrl = "https://mooc1.chaoxing.com/work?workId=1&token=new")
        controller.load(listOf(course), force = true)
        advanceUntilIdle()
        assertEquals(listOf(second), controller.uiState.value.pendingItems)
        assertTrue(controller.uiState.value.completedWorkKeys.none { it.contains("token") })
    }

    private fun course(id: Long, name: String): ChaoxingCourse = ChaoxingCourse(
        courseName = name,
        teacherName = "老师",
        courseId = id,
        classId = 200001,
        imageURL = "",
    )

    private fun client(): ChaoxingHttpClient = ChaoxingHttpClient(
        session = ChaoxingAccountSession(
            phoneNumber = "13800000004",
            encryptedPassword = "enc",
            name = "测试用户",
            uid = 1,
            puid = 2,
            deviceCode = "device",
        ),
    )

    private fun pendingWork(course: ChaoxingCourse, title: String) = ChaoxingAssessmentItem(
        kind = ChaoxingAssessmentKind.WORK,
        title = title,
        statusText = "未交",
        courseId = course.courseId,
        classId = course.classId,
        courseName = course.courseName,
    )

    private fun summaryOf(course: ChaoxingCourse, pending: Int, pendingExams: Int = 0) =
        ChaoxingAssessmentSummary(
            works = (1..pending).map { pendingWork(course, "${course.courseName} 作业 $it") },
            exams = (1..pendingExams).map {
                ChaoxingAssessmentItem(
                    kind = ChaoxingAssessmentKind.EXAM,
                    title = "${course.courseName} 考试 $it",
                    statusText = "未交",
                    courseId = course.courseId,
                    classId = course.classId,
                    courseName = course.courseName,
                )
            },
            fetchedAtMs = 1L,
        )

    @Test
    fun overdueCountIncludesExpiredAndLatePendingHomeworkOnly() {
        val math = course(1L, "高等数学")
        val pending = pendingWork(math, "作业")
        val state = AssessmentOverviewState(
            summaries = mapOf(
                math.id to ChaoxingAssessmentSummary(
                    works = listOf(
                        pending.copy(statusText = "已过期"),
                        pending.copy(deadlineAtMs = 99),
                        pending.copy(deadlineAtMs = 100),
                        pending.copy(deadlineAtMs = 101),
                        pending.copy(statusText = "待批阅", deadlineAtMs = 99),
                        pending.copy(statusText = "", deadlineAtMs = 99),
                        pending,
                    ),
                    exams = listOf(pending.copy(kind = ChaoxingAssessmentKind.EXAM, statusText = "已过期")),
                ),
            ),
        )

        assertEquals(2, state.overdueWorkCount(nowMs = 100))
    }

    @Test
    fun loadCachesPerCourseAndAggregatesPending() = runTest {
        val math = course(1L, "高等数学")
        val physics = course(2L, "大学物理")
        var calls = 0
        val controller = AssessmentOverviewController(
            clientProvider = { client() },
            scope = this,
            fetchSummary = { _, target ->
                calls += 1
                when (target.courseName) {
                    "高等数学" -> summaryOf(target, pending = 2, pendingExams = 1)
                    else -> summaryOf(target, pending = 0)
                }
            },
        )

        controller.load(listOf(math, physics))
        advanceUntilIdle()

        assertEquals(2, calls)
        val state = controller.uiState.value
        assertEquals(2, state.pendingWorks(math.id))
        assertEquals(1, state.pendingExams(math.id))
        assertEquals(0, state.pendingWorks(physics.id))
        assertEquals(3, state.totalPending)
        assertEquals(1, state.pendingCourseCount)
        assertTrue(state.pendingItems.all { it.status.isPending })
        assertEquals("高等数学", state.pendingItems.first().courseName)

        controller.load(listOf(math, physics))
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test
    fun homeCountMatchesTodoCountIncludingExams() = runTest {
        // 回归：主页卡片曾只统计作业（2），待办页统计作业+考试（4），导致“少一半”。
        val course = course(1L, "书法鉴赏")
        val work = pendingWork(course, "作业一")
        val exam = work.copy(kind = ChaoxingAssessmentKind.EXAM, title = "考试一")
        val controller = AssessmentOverviewController(
            clientProvider = { client() }, scope = this,
            fetchSummary = { _, _ -> ChaoxingAssessmentSummary(works = listOf(work), exams = listOf(exam)) },
        )
        controller.onAccountChanged("student")
        controller.load(listOf(course))
        advanceUntilIdle()

        with(controller.uiState.value) {
            assertEquals(1, pendingWorkCount)
            assertEquals(1, pendingExamCount)
            assertEquals(2, totalPending)
            // 主页用的 totalPending 必须与待办页用的 pendingItems.size 一致。
            assertEquals(pendingItems.size, totalPending)
        }

        // 本地标记完成一项后，两处口径仍保持一致。
        assertTrue(controller.setWorkCompleted(work, true, "student"))
        with(controller.uiState.value) {
            assertEquals(pendingItems.size, totalPending)
            assertEquals(1, totalPending)
        }
    }

    @Test
    fun forceReloadRefetchesCachedCourses() = runTest {
        val math = course(1L, "高等数学")
        var calls = 0
        val controller = AssessmentOverviewController(
            clientProvider = { client() },
            scope = this,
            fetchSummary = { _, target ->
                calls += 1
                summaryOf(target, pending = 1)
            },
        )

        controller.load(listOf(math))
        advanceUntilIdle()
        controller.load(listOf(math), force = true)
        advanceUntilIdle()

        assertEquals(2, calls)
    }

    @Test
    fun failedCourseDoesNotBlockOthersAndRecordsError() = runTest {
        val math = course(1L, "高等数学")
        val physics = course(2L, "大学物理")
        val controller = AssessmentOverviewController(
            clientProvider = { client() },
            scope = this,
            fetchSummary = { _, target ->
                if (target.courseName == "高等数学") throw ChaoxingError.NetworkError("读取失败")
                summaryOf(target, pending = 1)
            },
        )

        controller.load(listOf(math, physics))
        advanceUntilIdle()

        val state = controller.uiState.value
        assertEquals(1, state.errors.size)
        assertNull(state.summaries[math.id])
        assertEquals(1, state.pendingWorks(physics.id))
    }

    @Test
    fun accountChangeClearsOverview() = runTest {
        val math = course(1L, "高等数学")
        val controller = AssessmentOverviewController(
            clientProvider = { client() },
            scope = this,
            fetchSummary = { _, target -> summaryOf(target, pending = 1) },
        )

        controller.load(listOf(math))
        advanceUntilIdle()
        assertTrue(controller.uiState.value.hasLoadedAny)

        controller.onAccountChanged("13900000005")
        assertTrue(controller.uiState.value.summaries.isEmpty())
        assertEquals(0, controller.uiState.value.totalPending)
    }
}
