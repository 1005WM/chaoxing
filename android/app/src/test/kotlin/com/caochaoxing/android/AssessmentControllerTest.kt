// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import com.caochaoxing.android.state.AssessmentController
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssessmentControllerTest {
    private val course = ChaoxingCourse(
        courseName = "线性代数",
        teacherName = "李老师",
        courseId = 400001L,
        classId = 210001,
        imageURL = "",
    )

    private fun client(): ChaoxingHttpClient = ChaoxingHttpClient(
        session = ChaoxingAccountSession(
            phoneNumber = "13800000002",
            encryptedPassword = "enc",
            name = "测试用户",
            uid = 1,
            puid = 2,
            deviceCode = "device",
        ),
    )

    private fun summaryOf(pending: Int): ChaoxingAssessmentSummary =
        ChaoxingAssessmentSummary(
            works = (1..pending).map {
                ChaoxingAssessmentItem(
                    kind = ChaoxingAssessmentKind.WORK,
                    title = "作业 $it",
                    statusText = "未交",
                )
            },
            fetchedAtMs = 100L,
        )

    @Test
    fun loadPublishesSummaryAndCachesItUntilForced() = runTest {
        var calls = 0
        val controller = AssessmentController(
            clientProvider = { client() },
            scope = this,
            fetchSummary = { _, _ ->
                calls += 1
                summaryOf(2)
            },
        )

        controller.load(course)
        advanceUntilIdle()
        assertEquals(2, controller.uiState.value.summary?.pendingCount ?: -1)
        assertEquals(false, controller.uiState.value.isLoading)

        controller.load(course)
        advanceUntilIdle()
        assertEquals(1, calls)

        controller.load(course, forceRefresh = true)
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test
    fun failedRefreshKeepsPreviousSummaryAndShowsError() = runTest {
        var fail = false
        val controller = AssessmentController(
            clientProvider = { client() },
            scope = this,
            fetchSummary = { _, _ ->
                if (fail) throw ChaoxingError.NetworkError("网络断了")
                summaryOf(1)
            },
        )

        controller.load(course)
        advanceUntilIdle()
        assertNotNull(controller.uiState.value.summary)

        fail = true
        controller.load(course, forceRefresh = true)
        advanceUntilIdle()

        assertNotNull(controller.uiState.value.summary)
        assertNotNull(controller.uiState.value.errorMessage)
        assertTrue(controller.uiState.value.isStale)
    }

    @Test
    fun accountChangeClearsCachedAssessment() = runTest {
        val controller = AssessmentController(
            clientProvider = { client() },
            scope = this,
            fetchSummary = { _, _ -> summaryOf(3) },
        )

        controller.load(course)
        advanceUntilIdle()
        assertNotNull(controller.uiState.value.summary)

        controller.onAccountChanged("13900000003")
        assertNull(controller.uiState.value.summary)
        assertEquals(null, controller.uiState.value.courseKey)
    }

    @Test
    fun missingSessionSurfacesLoginHint() = runTest {
        val controller = AssessmentController(
            clientProvider = { null },
            scope = this,
            fetchSummary = { _, _ -> summaryOf(1) },
        )

        controller.load(course)
        advanceUntilIdle()

        assertNull(controller.uiState.value.summary)
        assertTrue(controller.uiState.value.errorMessage.orEmpty().contains("重新登录"))
    }
}
