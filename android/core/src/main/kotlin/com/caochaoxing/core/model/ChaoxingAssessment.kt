// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 课程维度的非签到任务：作业（work）与考试（exam）。
 *
 * 这些条目来自课程页 `作业 / 考试` 的 H5 列表（`/phone/moocAnalysis/workList`
 * 与 `/phone/moocAnalysis/examList`），不是签到活动列表 `activelist`。
 */
@Serializable
enum class ChaoxingAssessmentKind(val displayName: String) {
    @SerialName("work")
    WORK("作业"),

    @SerialName("exam")
    EXAM("考试"),
}

/**
 * 作业 / 考试的完成情况。
 *
 * 服务端在不同页面用不同文案表示同一状态（例如「做作业」「未交」「待批阅」），
 * 因此统一由 [ChaoxingAssessmentStatus.fromStatusText] 按文案归类；归类不了时
 * 保留 [UNKNOWN]，由界面直接展示服务端原文。
 */
@Serializable
enum class ChaoxingAssessmentStatus(val displayName: String) {
    @SerialName("notStarted")
    NOT_STARTED("未完成"),

    @SerialName("inProgress")
    IN_PROGRESS("进行中"),

    @SerialName("submitted")
    SUBMITTED("已提交"),

    @SerialName("pendingReview")
    PENDING_REVIEW("待批阅"),

    @SerialName("reviewed")
    REVIEWED("已批阅"),

    @SerialName("expired")
    EXPIRED("已过期"),

    @SerialName("unknown")
    UNKNOWN("状态未知");

    /** 学生已经完成（含已交、待批阅、已批阅）。 */
    val isCompleted: Boolean
        get() = this == SUBMITTED || this == PENDING_REVIEW || this == REVIEWED

    /** 还需要学生去做，或正在做。 */
    val isPending: Boolean
        get() = this == NOT_STARTED || this == IN_PROGRESS

    val isExpired: Boolean
        get() = this == EXPIRED

    companion object {
        private val REVIEWED_KEYWORDS = listOf("已批阅", "已评阅", "已评分", "已出分", "已批改", "批阅完成")
        private val PENDING_REVIEW_KEYWORDS = listOf("待批阅", "待评阅", "待评分", "待批改", "批阅中", "评阅中")
        private val EXPIRED_KEYWORDS = listOf("已过期", "过期", "已结束", "已截止", "缺考", "未参加")
        /** 带否定前缀的「还没做」，必须先于「完成」判断，否则「未完成」会命中「完成」。 */
        private val NEGATED_PENDING_KEYWORDS = listOf("未完成", "未交", "未提交", "未开始", "未做", "未作答", "未考")
        // 「查看」是学习通作业/考试已提交后的入口文案（未提交时是「做作业」）。
        private val SUBMITTED_KEYWORDS = listOf("已交卷", "已提交", "已交", "已作答", "已完成", "已通过", "完成", "查看")
        private val IN_PROGRESS_KEYWORDS = listOf("进行中", "考试中", "作答中", "正在")
        private val NOT_STARTED_KEYWORDS = listOf(
            "去完成", "去做", "做作业", "开始考试", "参加考试", "待完成", "待考试", "待作答", "立即",
        )

        /**
         * 把服务端文案归类为统一状态。
         *
         * 顺序很关键：「待批阅」先于「已交」，「未参加」先于「未完成」，
         * 而「未完成」这类否定词必须先于「完成」。
         */
        fun fromStatusText(raw: String): ChaoxingAssessmentStatus {
            val text = raw.trim()
            if (text.isEmpty()) return UNKNOWN
            return when {
                REVIEWED_KEYWORDS.any(text::contains) -> REVIEWED
                PENDING_REVIEW_KEYWORDS.any(text::contains) -> PENDING_REVIEW
                EXPIRED_KEYWORDS.any(text::contains) -> EXPIRED
                NEGATED_PENDING_KEYWORDS.any(text::contains) -> NOT_STARTED
                SUBMITTED_KEYWORDS.any(text::contains) -> SUBMITTED
                IN_PROGRESS_KEYWORDS.any(text::contains) -> IN_PROGRESS
                NOT_STARTED_KEYWORDS.any(text::contains) -> NOT_STARTED
                else -> UNKNOWN
            }
        }
    }
}

/**
 * 一条作业或考试。
 *
 * [statusText] 是页面原文（例如「做作业」「已批阅」），用于在界面忠实展示；
 * [status] 是归一化后的结果，用于统计「还有几项没写」。
 */
@Serializable
data class ChaoxingAssessmentItem(
    val kind: ChaoxingAssessmentKind,
    val title: String,
    val statusText: String = "",
    /** 次要信息：作业为发布日期，考试为分数。 */
    val detail: String = "",
    /** 截止时间原文（可能只有日期，也可能精确到时分）。 */
    val deadlineText: String? = null,
    /** 截止时间（毫秒）。页面只给日期、没给时分时为 null，不臆造时间。 */
    val deadlineAtMs: Long? = null,
    /** 页面 `status` 属性原值，两套页面编码不同，仅作保留。 */
    val rawStatus: Int = 0,
    val courseId: Long = 0L,
    val classId: Int = 0,
    val courseName: String = "",
    /** 做题页地址（`/work/phone/task-work` 或 `/exam/phone/task-exam`）。 */
    val taskUrl: String? = null,
) {
    val id: String
        get() = "${kind.name.lowercase()}:$courseId:$classId:$title"

    val status: ChaoxingAssessmentStatus
        get() = ChaoxingAssessmentStatus.fromStatusText(statusText)

    val isCompleted: Boolean
        get() = status.isCompleted

    val isPending: Boolean
        get() = status.isPending

    /** 界面上优先显示服务端原文，没有原文时退回归一化文案。 */
    val displayStatus: String
        get() = statusText.trim().ifEmpty { status.displayName }

    /** 截止时间是否已知（精确到时分）。 */
    val hasExactDeadline: Boolean
        get() = deadlineAtMs != null

    /** 未完成但已过截止时间。 */
    fun isOverdueAt(nowMs: Long): Boolean =
        deadlineAtMs?.let { nowMs > it } == true && !status.isCompleted
}

/**
 * 一门课程的作业 + 考试汇总。任一列表加载失败时保留另一份结果，并在 [warnings] 中说明。
 */
@Serializable
data class ChaoxingAssessmentSummary(
    val works: List<ChaoxingAssessmentItem> = emptyList(),
    val exams: List<ChaoxingAssessmentItem> = emptyList(),
    val warnings: List<String> = emptyList(),
    val fetchedAtMs: Long = 0L,
) {
    val items: List<ChaoxingAssessmentItem>
        get() = works + exams

    val pendingCount: Int
        get() = items.count { it.status.isPending }

    val completedCount: Int
        get() = items.count { it.status.isCompleted }

    val expiredCount: Int
        get() = items.count { it.status.isExpired }

    val unknownCount: Int
        get() = items.count { it.status == ChaoxingAssessmentStatus.UNKNOWN }

    val isEmpty: Boolean
        get() = items.isEmpty()

    /** 例如「作业 2 项未完成 · 考试 1 项已批阅」。 */
    fun headline(): String {
        if (isEmpty) return "暂无作业和考试"
        val parts = mutableListOf<String>()
        val workPending = works.count { it.status.isPending }
        val examPending = exams.count { it.status.isPending }
        if (workPending > 0) parts += "作业 $workPending 项未完成"
        if (examPending > 0) parts += "考试 $examPending 项未完成"
        if (parts.isEmpty()) {
            parts += if (completedCount == items.size) "全部已完成" else "$completedCount 项已完成"
        }
        return parts.joinToString(" · ")
    }
}
