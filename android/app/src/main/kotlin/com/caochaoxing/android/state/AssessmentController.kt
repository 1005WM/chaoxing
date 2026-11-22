// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.state

import com.caochaoxing.core.model.ChaoxingAssessmentSummary
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.service.ChaoxingAssessmentService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 当前打开课程的作业 / 考试状态。只服务一个课程详情页，切换课程时整体替换。
 */
data class CourseAssessmentUiState(
    val courseKey: String? = null,
    val isLoading: Boolean = false,
    val summary: ChaoxingAssessmentSummary? = null,
    val errorMessage: String? = null,
) {
    val isStale: Boolean
        get() = summary != null && errorMessage != null
}

/**
 * 读取并缓存课程作业 / 考试完成情况。
 *
 * 与 [CourseStudyController] 一样按当前账号隔离：切换账号时取消在途请求并清空状态，
 * 避免把上一个账号的数据显示给下一个账号。
 */
class AssessmentController(
    private val clientProvider: () -> ChaoxingHttpClient?,
    private val scope: CoroutineScope,
    private val fetchSummary: suspend (ChaoxingHttpClient, ChaoxingCourse) -> ChaoxingAssessmentSummary =
        { client, course -> ChaoxingAssessmentService.fetchSummary(client, course) },
) {
    constructor(appState: AndroidAppState, scope: CoroutineScope) : this(
        clientProvider = { appState.activeClient },
        scope = scope,
    )

    private val _uiState = MutableStateFlow(CourseAssessmentUiState())
    val uiState: StateFlow<CourseAssessmentUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var accountPhone: String? = null

    /** 账号切换（含退出）时清空所有缓存并取消在途请求。 */
    fun onAccountChanged(phoneNumber: String?) {
        if (accountPhone == phoneNumber) return
        accountPhone = phoneNumber
        job?.cancel()
        job = null
        _uiState.value = CourseAssessmentUiState()
    }

    fun load(course: ChaoxingCourse, forceRefresh: Boolean = false) {
        val key = course.id
        val current = _uiState.value
        if (!forceRefresh) {
            if (current.courseKey == key && current.isLoading) return
            if (current.courseKey == key && current.summary != null && current.errorMessage == null) return
        }

        val client = clientProvider()
        if (client == null) {
            _uiState.value = CourseAssessmentUiState(
                courseKey = key,
                errorMessage = "登录会话已失效，请重新登录",
            )
            return
        }

        job?.cancel()
        val previous = if (current.courseKey == key) current.summary else null
        _uiState.value = CourseAssessmentUiState(
            courseKey = key,
            isLoading = true,
            summary = previous,
            errorMessage = null,
        )

        job = scope.launch {
            try {
                val summary = fetchSummary(client, course)
                _uiState.value = CourseAssessmentUiState(courseKey = key, summary = summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.value = CourseAssessmentUiState(
                    courseKey = key,
                    summary = previous,
                    errorMessage = e.message ?: "加载作业与考试失败",
                )
            }
        }
    }

    /** 退出登录 / 会话失效时显式清空。 */
    fun clear() {
        job?.cancel()
        job = null
        _uiState.value = CourseAssessmentUiState()
    }
}
