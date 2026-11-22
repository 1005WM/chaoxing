// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ChaoxingSignType(
    val rawValue: String,
    val displayName: String,
) {
    @SerialName("0")
    PHOTO_OR_CLICK("0", "拍照 / 点击签到"),

    @SerialName("2")
    QR_CODE("2", "二维码签到"),

    @SerialName("3")
    GESTURE("3", "手势签到"),

    @SerialName("4")
    LOCATION("4", "位置签到"),

    @SerialName("5")
    PASSWORD("5", "签到码签到"),

    @SerialName("-1")
    UNKNOWN("-1", "其他签到");

    companion object {
        fun fromOtherId(otherId: String?): ChaoxingSignType =
            entries.firstOrNull { it.rawValue == otherId } ?: UNKNOWN

        fun fromAtypeName(atypeName: String): ChaoxingSignType = when (atypeName) {
            "拍照签到", "普通签到", "签到" -> PHOTO_OR_CLICK
            "二维码签到" -> QR_CODE
            "手势签到" -> GESTURE
            "位置签到" -> LOCATION
            "密码签到", "签到码签到" -> PASSWORD
            else -> UNKNOWN
        }
    }
}

@Serializable
enum class ChaoxingSignActivityStatus {
    @SerialName("readyToSign")
    READY_TO_SIGN,

    @SerialName("alreadySigned")
    ALREADY_SIGNED,

    @SerialName("expired")
    EXPIRED,
}

@Serializable
data class ChaoxingCourse(
    val courseName: String,
    val teacherName: String,
    val courseId: Long,
    val classId: Int,
    val imageURL: String,
    val schools: String? = null,
    val cpi: Long? = null,
) {
    val id: String
        get() = "${courseId}_$classId"
}

@Serializable
data class ChaoxingSignActivity(
    val id: Long,
    val startTimeMs: Long? = null,
    val endTimeMs: Long? = null,
    val userStatus: Int,
    val otherId: String,
    val isLook: Boolean,
    val type: Int,
    val activeType: Int,
    val nameOne: String,
    val status: Int,
    val nameFour: String,
    val courseId: Long,
    val classId: Int,
    val courseName: String,
    val extContent: String,
) {
    val signType: ChaoxingSignType
        get() = ChaoxingSignType.fromOtherId(otherId)

    val isAvailable: Boolean
        get() = status == 1

    val isAlreadySignedByStatus: Boolean
        get() = userStatus in SIGNED_STATUSES

    val isLate: Boolean
        get() = isLateAt(System.currentTimeMillis())

    fun isLateAt(nowMs: Long): Boolean {
        val endMs = endTimeMs ?: return false
        return endMs > 0 && nowMs > endMs
    }

    companion object {
        private val SIGNED_STATUSES = setOf(1, 2, 3, 9)
    }
}

@Serializable
data class ChaoxingSignOutInfo(
    val signInId: Long? = null,
    val signOutId: Long? = null,
    val signOutPublishTimeMs: Long? = null,
    val classId: Int,
    val courseId: Long,
)

@Serializable
data class ChaoxingSignDetailMetadata(
    val activeId: Long,
    val signType: ChaoxingSignType,
    val title: String,
    val isCaptchaRequired: Boolean,
    val isFaceRequired: Boolean,
    val isPositionRequired: Boolean,
    val isPhotoRequired: Boolean,
    val isRefreshQRCode: Boolean,
    val numberCount: Int? = null,
    val targetLocation: ChaoxingLocationTarget? = null,
    val signOutInfo: ChaoxingSignOutInfo,
)
