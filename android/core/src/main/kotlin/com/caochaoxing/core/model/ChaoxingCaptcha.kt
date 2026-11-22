// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ChaoxingCaptchaData(
    val captchaId: String,
    val type: String,
    val version: String,
    val token: String,
    val captchaKey: String,
    val iv: String,
    val shadeImageURL: String,
    val cutoutImageURL: String,
)

@Serializable
data class ChaoxingQRCodeParseResult(
    val aid: Long? = null,
    val code: String? = null,
    val enc: String,
)

/** CamelCase alias matching downstream Kotlin naming (`ChaoxingQrCodeParser`). */
typealias ChaoxingQrCodeParseResult = ChaoxingQRCodeParseResult
