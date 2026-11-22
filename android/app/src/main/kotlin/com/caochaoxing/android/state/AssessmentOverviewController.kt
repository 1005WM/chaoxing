// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.state

import com.caochaoxing.core.model.ChaoxingAssessmentItem
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong

/**
 * 课程列表页用的作业 / 考试总览：按课程缓存汇总结果，供「我的课程」显示未完成数量。
 */
data class AssessmentOverviewState(
    val summaries: Map<String, ChaoxingAssessmentSummary> = emptyMap(),
    val loadingCourseKeys: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val updatedAtMs: Long = 0L,
) {
    /** 课程列表中某门课的未完成作业数。 */
    fun pendingWorks(courseKey: String): Int =
        summaries[courseKey]?.works?.count { it.status.isPending } ?: 0

    /** 课程列表中某门课的未完成考试数。 */
    fun pendingExams(courseKey: String): Int =
        summaries[courseKey]?.exams?.count { it.status.isPending } ?: 0

    /** 全部课程里还没写完的作业（含考试），按截止时间排序，未提供截止的排后面。 */
    val pendingItems: List<ChaoxingAssessmentItem>
        get() = summaries.values
            .flatMap { it.works + it.exams }
            .filter { it.status.isPending }
            .sortedWith(
                compareBy<ChaoxingAssessmentItem> { it.deadlineAtMs ?: Long.MAX_VALUE }
                    .thenBy { it.courseName },
            )

    val pendingWorkCount: Int
        get() = summaries.values.sumOf { summary -> summary.works.count { it.status.isPending } }

    val pendingExamCount: Int
        get() = summaries.values.sumOf { summary -> summary.exams.count { it.status.isPending } }

    val totalPending: Int
        get() = pendingWorkCount + pendingExamCount

    /** 有未完成项的课程数。 */
    val pendingCourseCount: Int
        get() = summaries.values.count { summary ->
            summary.items.any { it.status.isPending }
        }

    val hasLoadedAny: Boolean
        get() = summaries.isNotEmpty()
}

/**
 * 打开「我的课程」时，用有限并发把每门课的作业 / 考试读一遍，结果按课程缓存。
 *
 * 与 [AssessmentController] 一样按账号隔离；浏览某个课程的详情页时仍由详情页自己的控制器
 * 读取，这里只负责列表总览，避免两处状态互相覆盖。
 */
class AssessmentOverviewController(
    private val clientProvider: () -> ChaoxingHttpClient?,
    private val scope: CoroutineScope,
    private val fetchSummary: suspend (ChaoxingHttpClient, ChaoxingCourse) -> ChaoxingAssessmentSummary =
        { client, course -> ChaoxingAssessmentService.fetchSummary(client, course) },
    private val maxConcurrency: Int = 3,
) {
    constructor(appState: AndroidAppState, scope: CoroutineScope) : this(
        clientProvider = { appState.activeClient },
        scope = scope,
    )

    private val _uiState = MutableStateFlow(AssessmentOverviewState())
    val uiState: StateFlow<AssessmentOverviewState> = _uiState.asStateFlow()

    private val generation = AtomicLong(0L)
    private val activeJobs = mutableListOf<Job>()
    private var accountPhone: String? = null

    fun onAccountChanged(phoneNumber: String?) {
        if (accountPhone == phoneNumber) return
        accountPhone = phoneNumber
        reset()
    }

    /** 清空缓存并取消在途请求（切换账号 / 退出登录）。 */
    fun reset() {
        generation.incrementAndGet()
        activeJobs.forEach { it.cancel() }
        activeJobs.clear()
        _uiState.value = AssessmentOverviewState()
    }

    /**
     * 为尚未缓存（或 [force] 指定的）课程加载作业 / 考试汇总。
     * 已有缓存的课程不会重复请求，除非显式刷新。
     */
    fun load(courses: List<ChaoxingCourse>, force: Boolean = false) {
        val client = clientProvider() ?: return
        val snapshot = _uiState.value
        val targets = courses.filter { course ->
            course.id !in snapshot.loadingCourseKeys && (force || course.id !in snapshot.summaries)
        }
        if (targets.isEmpty()) return

        val gen = generation.get()
        val semaphore = Semaphore(maxConcurrency)
        targets.forEach { course ->
            _uiState.update { it.copy(loadingCourseKeys = it.loadingCourseKeys + course.id) }
            val job = scope.launch {
                try {
                    semaphore.withPermit {
                        if (gen != generation.get()) return@withPermit
                        val result = try {
                            Result.success(fetchSummary(client, course))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                        if (gen != generation.get()) return@withPermit
                        _uiState.update { state ->
                            result.fold(
                                onSuccess = { summary ->
                                    state.copy(
                                        summaries = state.summaries + (course.id to summary),
                                        errors = state.errors - course.id,
                                        updatedAtMs = System.currentTimeMillis(),
                                    )
                                },
                                onFailure = { error ->
                                    state.copy(
                                        errors = state.errors +
                                            (course.id to (error.message ?: "加载失败")),
                                    )
                                },
                            )
                        }
                    }
                } finally {
                    _uiState.update { it.copy(loadingCourseKeys = it.loadingCourseKeys - course.id) }
                }
            }
            activeJobs += job
        }
        activeJobs.removeAll { it.isCompleted }
    }

    /** 单门课程失败后重试。 */
    fun retry(course: ChaoxingCourse) {
        _uiState.update { it.copy(errors = it.errors - course.id) }
        load(listOf(course), force = true)
    }
}
