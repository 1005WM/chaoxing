// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.model

import java.util.Locale

sealed class ChaoxingError(override val message: String) : Exception(message) {
    data class InvalidInput(override val message: String) : ChaoxingError(message)

    class NetworkError(
        val statusCode: Int?,
        message: String,
    ) : ChaoxingError(formatNetworkMessage(statusCode, message)) {
        val detail: String = message

        constructor(message: String) : this(statusCode = null, message = message)

        operator fun component1(): Int? = statusCode

        operator fun component2(): String = detail

        fun copy(
            statusCode: Int? = this.statusCode,
            message: String = this.detail,
        ): NetworkError = NetworkError(statusCode = statusCode, message = message)

        override fun equals(other: Any?): Boolean =
            this === other || (other is NetworkError && statusCode == other.statusCode && detail == other.detail)

        override fun hashCode(): Int = 31 * (statusCode ?: 0) + detail.hashCode()

        override fun toString(): String = "NetworkError(statusCode=$statusCode, detail=$detail)"
    }

    data class LoginFailed(override val message: String) : ChaoxingError(message)

    data class SessionExpired(
        override val message: String = "登录会话已过期，请重新登录",
    ) : ChaoxingError(message)

    data class ParseFailed(override val message: String) : ChaoxingError(message)

    data class UnsupportedSignType(
        override val message: String = "暂不支持此签到类型",
    ) : ChaoxingError(message)

    data class NoPermission(
        override val message: String = "此用户不在班级或无权限访问该活动",
    ) : ChaoxingError(message)

    data class SignExpired(
        override val message: String = "签到已截止",
    ) : ChaoxingError(message)

    data class SignAlreadyEnded(
        override val message: String = "迟到或签到已结束",
    ) : ChaoxingError(message)

    data class AlreadySigned(
        override val message: String = "已经签到过了",
    ) : ChaoxingError(message)

    data class DeviceDuplicated(
        override val message: String = "同一设备不允许重复签到",
    ) : ChaoxingError(message)

    data class WrongPosition(
        val distanceMeters: Double? = null,
    ) : ChaoxingError(formatWrongPositionMessage(distanceMeters))

    data class QrCodeExpired(
        override val message: String = "二维码已过期，请重新获取",
    ) : ChaoxingError(message)

    data class QrCodeParseFailed(
        override val message: String = "二维码解析失败",
    ) : ChaoxingError(message)

    data class FaceError(override val message: String) : ChaoxingError(message)

    data class CaptchaRequired(
        val enc2: String? = null,
    ) : ChaoxingError("需要完成滑块验证码")

    data class CaptchaFailed(
        override val message: String = "验证码校验失败，请重试",
    ) : ChaoxingError(message)

    data class ServerMessage(override val message: String) : ChaoxingError(message)
}

private fun formatNetworkMessage(statusCode: Int?, message: String): String =
    if (statusCode != null) {
        "网络错误 ($statusCode): $message"
    } else {
        "网络错误: $message"
    }

private fun formatWrongPositionMessage(distanceMeters: Double?): String =
    if (distanceMeters != null) {
        String.format(Locale.US, "位置不在设置范围内，距离签到点约 %.1f 米", distanceMeters)
    } else {
        "位置不在设置范围内"
    }
