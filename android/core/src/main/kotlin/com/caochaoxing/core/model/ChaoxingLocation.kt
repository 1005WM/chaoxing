// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
enum class CoordinateSystem(val displayName: String) {
    @SerialName("BD09")
    BD09("百度坐标 (BD-09)"),

    @SerialName("GCJ02")
    GCJ02("火星坐标 / 高德腾讯 (GCJ-02)"),

    @SerialName("WGS84")
    WGS84("地球坐标 / GPS / OSM (WGS-84)"),
}

@Serializable
data class ChaoxingLocationTarget(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val rangeMeters: Int? = null,
)

@Serializable
data class ChaoxingLocationSignInfo(
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val enableRandomOffset: Boolean = true,
    val randomizedLatitude: Double = if (enableRandomOffset) {
        latitude + Random.nextDouble(-MAX_RANDOM_OFFSET_DEGREES, MAX_RANDOM_OFFSET_DEGREES)
    } else {
        latitude
    },
    val randomizedLongitude: Double = if (enableRandomOffset) {
        longitude + Random.nextDouble(-MAX_RANDOM_OFFSET_DEGREES, MAX_RANDOM_OFFSET_DEGREES)
    } else {
        longitude
    },
) {
    fun disableRandomOffset(): ChaoxingLocationSignInfo = copy(
        enableRandomOffset = false,
        randomizedLatitude = latitude,
        randomizedLongitude = longitude,
    )

    companion object {
        const val MAX_RANDOM_OFFSET_DEGREES: Double = 0.000045
    }
}

@Serializable
data class ChaoxingLocationPreset(
    val id: String = ChaoxingModelDefaults.newUuidString(),
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
) {
    fun toSignInfo(enableRandomOffset: Boolean = true): ChaoxingLocationSignInfo =
        ChaoxingLocationSignInfo(
            latitude = latitude,
            longitude = longitude,
            address = address,
            enableRandomOffset = enableRandomOffset,
        )
}
