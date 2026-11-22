// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.model

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A chapter or section entry extracted from a Chaoxing course study tree.
 */
@Serializable
data class ChaoxingChapterNode(
    val id: String,
    val title: String,
    val sectionTitle: String = "",
    val courseId: Long,
    val classId: Int,
    /**
     * `null` = unknown/review (must still be inspected), `0` = explicitly completed or no jobs,
     * `> 0` = unfinished task count.
     */
    val pendingJobCount: Int? = null,
)

/**
 * Event codes accepted by Chaoxing's `/mooc-ans/multimedia/log/a/{cpi}/{dtoken}` `isdrag` parameter.
 */
@Serializable
enum class ChaoxingVideoDragEvent(val code: Int, val displayName: String) {
    PLAYING(0, "playing(0)"),
    DRAG(1, "drag(1)"),
    PAUSE(2, "pause(2)"),
    PLAY(3, "play(3)"),
    ENDED(4, "ended(4)"),
}

/**
 * Response returned by `/mooc-ans/multimedia/log/a/{cpi}/{dtoken}`.
 */
@Serializable
data class ChaoxingVideoLogResponse(
    val isPassed: Boolean,
    val videoTimeLimit: Boolean = false,
    val hasJobLimit: Boolean = false,
)

/**
 * A video task point parsed from `knowledge/cards` `mArg` and `/ananas/status/{objectId}`.
 */
@Serializable
data class ChaoxingVideoTask(
    val courseId: Long,
    val classId: Int,
    val chapterId: String,
    val cpi: Long,
    val userid: String,
    val jobid: String,
    val objectId: String,
    val name: String,
    val otherInfo: String,
    val dtoken: String,
    val duration: Int,
    val attDuration: Int,
    val attDurationEnc: String,
    val videoFaceCaptureEnc: String = "",
    val rt: Double = 0.9,
    val doublespeed: Int = 0,
    val reportTimeInterval: Int = 60,
    val headOffsetMs: Int = 0,
    val playTimeMs: Int = 0,
    val isPassed: Boolean = false,
    val isJob: Boolean = true,
    val begins: Int = 0,
    val ends: Int = 0,
    val requiresFaceCapture: Boolean = false,
    val reportUrl: String,
    val videoPlayUrl: String? = null,
    val screenshotUrl: String? = null,
    /** `"Video"` for standard video tasks, `"Audio"` for `insertaudio` tasks. */
    val dtype: String = "Video",
) {
    val id: String
        get() = "${chapterId}_${jobid}_$objectId"

    val totalDuration: Int
        get() = max(duration, attDuration)

    val effectiveClipStart: Int
        get() = if (begins > 0) begins else 0

    val effectiveClipEnd: Int
        get() = if (ends > effectiveClipStart) {
            if (totalDuration > 0) min(ends, totalDuration) else ends
        } else {
            totalDuration
        }

    val effectiveClipTime: String
        get() = "${effectiveClipStart}_$effectiveClipEnd"

    val isReportableTask: Boolean
        get() = isJob && jobid.isNotEmpty() && dtoken.isNotEmpty() && effectiveClipEnd > effectiveClipStart

    /** Maximum server-accepted playback multiplier inferred from effective `doublespeed`. */
    val allowedMaxRate: Double
        get() = if (doublespeed != 0) 2.0 else 1.0

    val requiredWatchSeconds: Int
        get() {
            val span = max(0, effectiveClipEnd - effectiveClipStart)
            if (span <= 0) return 0
            return ceil(span * rt).toInt()
        }

    fun updating(isPassed: Boolean, headOffsetMs: Int? = null): ChaoxingVideoTask =
        copy(
            isPassed = isPassed,
            headOffsetMs = headOffsetMs ?: this.headOffsetMs,
            playTimeMs = headOffsetMs ?: this.playTimeMs,
        )
}

@Serializable
enum class ChaoxingAttachmentKind(val displayName: String) {
    VIDEO("视频"),
    DOCUMENT("文档/PPT"),
    AUDIO("音频"),
    LIVE("直播"),
    QUIZ("测验/作业"),
    OTHER("其他"),
}

/**
 * Non-video attachment / task point parsed from `knowledge/cards` `mArg`.
 */
@Serializable
data class ChaoxingChapterAttachment(
    val kind: ChaoxingAttachmentKind,
    val courseId: Long,
    val classId: Int,
    val chapterId: String,
    val jobid: String,
    val objectId: String = "",
    val jtoken: String = "",
    val name: String,
    val isJob: Boolean = true,
    val isPassed: Boolean = false,
    val externalUrl: String? = null,
) {
    val id: String
        get() = "${chapterId}_${kind.name}_${jobid}_${objectId}_$name"

    val canCompleteAsDocument: Boolean
        get() = kind == ChaoxingAttachmentKind.DOCUMENT &&
            isJob && !isPassed && jobid.isNotEmpty() && jtoken.isNotEmpty()

    fun updating(isPassed: Boolean): ChaoxingChapterAttachment = copy(isPassed = isPassed)
}

/**
 * Live broadcast / live replay task point (`type == "live"`, `module == "insertlive"`)
 * hosted on `zhibo.chaoxing.com` and enriched via `/ananas/live/liveinfo`.
 */
@Serializable
data class ChaoxingLiveTask(
    val courseId: Long,
    val classId: Int,
    val chapterId: String,
    val cpi: Long,
    val userid: String,
    val jobid: String,
    val aid: String = "",
    val liveId: String,
    val shortLiveId: String = "",
    val streamName: String = "",
    val vdoid: String = "",
    val title: String,
    val rt: Double = 0.9,
    val doublespeed: Int = 1,
    val isNotDrag: Int = 0,
    val switchWindow: Int = 0,
    val liveSetEnc: String = "",
    val authEnc: String = "",
    val liveDragEnc: String = "",
    val liveSwDsEnc: String = "",
    val isJob: Boolean = true,
    val isPassed: Boolean = false,
    val liveStatus: Int = 4,
    val ifReview: Int = 0,
    val duration: Int = 0,
    val playbackTimelongMs: Int = 0,
    val percentValue: Double = 0.0,
    val playUrl: String? = null,
    val fallbackPlayUrl: String? = null,
    val webPageUrl: String? = null,
) {
    val id: String
        get() = "live_${chapterId}_${jobid}_$liveId"

    val allPlayUrls: List<String>
        get() = listOfNotNull(playUrl, fallbackPlayUrl)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    val canWatchReplay: Boolean
        get() = (liveStatus == 4 && ifReview == 0) || liveStatus == 1

    val isReportableTask: Boolean
        get() = isJob && jobid.isNotEmpty() && liveId.isNotEmpty() &&
            streamName.isNotEmpty() && vdoid.isNotEmpty() && canWatchReplay

    val requiredWatchSeconds: Int
        get() = if (duration > 0) ceil(duration * rt).toInt() else 0

    val watchedSeconds: Int
        get() = max(0, playbackTimelongMs / 1000)

    val statusDescription: String
        get() = when {
            liveStatus == 0 -> "直播未开始"
            liveStatus == 1 -> "直播进行中"
            liveStatus == 4 && ifReview == 0 -> "直播回放"
            liveStatus == 4 -> "已结束（无回放）"
            else -> "直播回放"
        }
}

/**
 * Live room metadata returned by `/ananas/live/liveinfo`.
 */
data class ChaoxingLiveInfo(
    val resolvedLiveId: String,
    val title: String,
    val liveStatus: Int,
    val ifReview: Int,
    val duration: Int,
    val playbackTimelongMs: Int,
    val percentValue: Double,
    val streamName: String,
    val vdoid: String,
    val m3u8Url: String? = null,
    val mp4Url: String? = null,
    val viewUrl: String? = null,
    val courseEndState: Int = 0,
) {
    val preferredPlayUrl: String?
        get() = m3u8Url ?: mp4Url

    val fallbackPlayUrl: String?
        get() = if (!m3u8Url.isNullOrEmpty()) mp4Url else null

    val allPlayUrls: List<String>
        get() = listOfNotNull(m3u8Url, mp4Url)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

/**
 * Response returned by `zhibo.chaoxing.com/saveTimePc`.
 */
data class ChaoxingLiveHeartbeatResponse(
    val result: Int,
    val accepted: Boolean,
    val message: String = "",
    val duration: Int = 0,
    val liveStatus: Int = 4,
)

/**
 * Active `zhibo.chaoxing.com` course-replay playback session context used for 30s heartbeats.
 */
data class ChaoxingLiveSessionContext(
    val pageUrl: String,
    val shortLiveId: String,
    val uInfo: String,
    val streamName: String,
    val vdoid: String,
    val duration: Int,
    val fid: String? = null,
    val playUrl: String? = null,
)

/**
 * Complete parsed contents of a chapter across all card tabs.
 */
@Serializable
data class ChaoxingChapterContent(
    val videos: List<ChaoxingVideoTask>,
    val liveTasks: List<ChaoxingLiveTask> = emptyList(),
    val otherAttachments: List<ChaoxingChapterAttachment> = emptyList(),
)

/**
 * Result of fetching a course's chapter tree.
 */
data class ChaoxingCourseChaptersResult(
    val cpi: Long,
    val chapters: List<ChaoxingChapterNode>,
)

/**
 * Metadata returned by `/ananas/status/{objectId}`.
 */
data class ChaoxingVideoAssetStatus(
    val dtoken: String,
    val duration: Int,
    val videoPlayUrl: String?,
    val screenshotUrl: String?,
)
