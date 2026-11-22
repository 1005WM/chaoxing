// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ChaoxingEasemobIMConfig(
    val accessToken: String,
    val uuid: String,
    val username: String,
)

@Serializable
data class ChaoxingIMGroup(
    val id: String,
    val name: String,
    val imageURL: String? = null,
)

@Serializable
data class ChaoxingGroupSignActivity(
    val activeId: Long,
    val title: String,
    val classId: Int,
    val courseId: Long,
    val courseName: String,
    val startTimeTitle: String,
    val activeTypeName: String,
    val isUnsupportedLegacySign: Boolean,
    val messageTimestampMs: Long,
) {
    val id: Long
        get() = activeId

    val signType: ChaoxingSignType
        get() = ChaoxingSignType.fromAtypeName(activeTypeName)
}
