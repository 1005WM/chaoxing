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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssessmentOverviewControllerTest {
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
