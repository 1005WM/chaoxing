// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.crypto

import com.caochaoxing.core.model.CoordinateSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(
    val latitude: Double,
    val longitude: Double,
)

/**
 * Pure Kotlin coordinate converter across WGS-84, GCJ-02, and BD-09 coordinate reference systems.
 *
 * Coordinates outside mainland/nearby China bounds pass through unchanged.
 */
object CoordinateConverter {
    private const val X_PI: Double = PI * 3000.0 / 180.0
    private const val KRASOVSKY_A: Double = 6378245.0
    private const val KRASOVSKY_EE: Double = 0.00669342162296594323

    private const val CHINA_MIN_LON = 72.004
    private const val CHINA_MAX_LON = 137.8347
    private const val CHINA_MIN_LAT = 0.8293
    private const val CHINA_MAX_LAT = 55.8271

    fun outOfChina(lat: Double, lon: Double): Boolean =
        !lat.isFinite() ||
            !lon.isFinite() ||
            lon < CHINA_MIN_LON ||
            lon > CHINA_MAX_LON ||
            lat < CHINA_MIN_LAT ||
            lat > CHINA_MAX_LAT

    fun convert(lat: Double, lon: Double, from: CoordinateSystem, to: CoordinateSystem): LatLon {
        if (from == to) return LatLon(lat, lon)
        return when (to) {
            CoordinateSystem.BD09 -> toBd09(lat, lon, from)
            CoordinateSystem.WGS84 -> toWgs84(lat, lon, from)
            CoordinateSystem.GCJ02 -> when (from) {
                CoordinateSystem.WGS84 -> wgs84ToGcj02(lat, lon)
                CoordinateSystem.BD09 -> bd09ToGcj02(lat, lon)
                CoordinateSystem.GCJ02 -> LatLon(lat, lon)
            }
        }
    }

    fun toBd09(lat: Double, lon: Double, from: CoordinateSystem): LatLon = when (from) {
        CoordinateSystem.BD09 -> LatLon(lat, lon)
        CoordinateSystem.GCJ02 -> gcj02ToBd09(lat, lon)
        CoordinateSystem.WGS84 -> wgs84ToBd09(lat, lon)
    }

    fun toWgs84(lat: Double, lon: Double, from: CoordinateSystem): LatLon = when (from) {
        CoordinateSystem.WGS84 -> LatLon(lat, lon)
        CoordinateSystem.GCJ02 -> gcj02ToWgs84(lat, lon)
        CoordinateSystem.BD09 -> bd09ToWgs84(lat, lon)
    }

    fun wgs84ToGcj02(lat: Double, lon: Double): LatLon {
        if (outOfChina(lat, lon)) return LatLon(lat, lon)
        val delta = gcjOffset(lat, lon)
        return LatLon(lat + delta.latitude, lon + delta.longitude)
    }

    /**
     * Converts GCJ-02 to WGS-84 using an initial inverse offset followed by 2 fixed-point refinement steps.
     */
    fun gcj02ToWgs84(lat: Double, lon: Double): LatLon {
        if (outOfChina(lat, lon)) return LatLon(lat, lon)
        val delta = gcjOffset(lat, lon)
        var wgsLat = lat - delta.latitude
        var wgsLon = lon - delta.longitude
        repeat(2) {
            val gcj = wgs84ToGcj02(wgsLat, wgsLon)
            wgsLat += lat - gcj.latitude
            wgsLon += lon - gcj.longitude
        }
        return LatLon(wgsLat, wgsLon)
    }

    fun gcj02ToBd09(lat: Double, lon: Double): LatLon {
        if (outOfChina(lat, lon)) return LatLon(lat, lon)
        val z = sqrt(lon * lon + lat * lat) + 0.00002 * sin(lat * X_PI)
        val theta = atan2(lat, lon) + 0.000003 * cos(lon * X_PI)
        val bdLon = z * cos(theta) + 0.0065
        val bdLat = z * sin(theta) + 0.006
        return LatLon(bdLat, bdLon)
    }

    fun bd09ToGcj02(lat: Double, lon: Double): LatLon {
        if (outOfChina(lat, lon)) return LatLon(lat, lon)
        val x = lon - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        val gcjLon = z * cos(theta)
        val gcjLat = z * sin(theta)
        return LatLon(gcjLat, gcjLon)
    }

    fun wgs84ToBd09(lat: Double, lon: Double): LatLon {
        val gcj = wgs84ToGcj02(lat, lon)
        return gcj02ToBd09(gcj.latitude, gcj.longitude)
    }

    fun bd09ToWgs84(lat: Double, lon: Double): LatLon {
        val gcj = bd09ToGcj02(lat, lon)
        return gcj02ToWgs84(gcj.latitude, gcj.longitude)
    }

    private fun gcjOffset(lat: Double, lon: Double): LatLon {
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1.0 - KRASOVSKY_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((KRASOVSKY_A * (1.0 - KRASOVSKY_EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (KRASOVSKY_A / sqrtMagic * cos(radLat) * PI)
        return LatLon(dLat, dLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
