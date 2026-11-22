// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.location

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.caochaoxing.core.crypto.CoordinateConverter
import com.caochaoxing.core.model.CoordinateSystem
import java.net.URLEncoder
import java.util.Locale

/**
 * Launches external map apps (Baidu Map, Amap, Tencent Map) or generic `geo:` handlers via
 * implicit `ACTION_VIEW` intents without bundling any proprietary closed-source map SDKs.
 */
object ExternalMapLauncher {
    private const val APP_NAME = "Caochaoxing"
    private const val BAIDU_SRC = "andr.caochaoxing.android"
    private const val DEFAULT_MARKER_LABEL = "目标位置"

    fun openBaiduMap(
        context: Context,
        lat: Double?,
        lon: Double?,
        label: String?,
    ): Boolean = openBaiduMap(context, lat, lon, label, CoordinateSystem.BD09)

    fun openBaiduMap(
        context: Context,
        lat: Double?,
        lon: Double?,
        label: String?,
        sourceSystem: CoordinateSystem,
    ): Boolean = launchUri(context, buildBaiduMapUri(lat, lon, label, sourceSystem))

    fun openAmap(
        context: Context,
        lat: Double?,
        lon: Double?,
        label: String?,
    ): Boolean = openAmap(context, lat, lon, label, CoordinateSystem.BD09)

    fun openAmap(
        context: Context,
        lat: Double?,
        lon: Double?,
        label: String?,
        sourceSystem: CoordinateSystem,
    ): Boolean = launchUri(context, buildAmapUri(lat, lon, label, sourceSystem))

    fun openTencentMap(
        context: Context,
        lat: Double?,
        lon: Double?,
        label: String?,
    ): Boolean = openTencentMap(context, lat, lon, label, CoordinateSystem.BD09)

    fun openTencentMap(
        context: Context,
        lat: Double?,
        lon: Double?,
        label: String?,
        sourceSystem: CoordinateSystem,
    ): Boolean = launchUri(context, buildTencentMapUri(lat, lon, label, sourceSystem))

    fun openGeoFallback(
        context: Context,
        lat: Double?,
        lon: Double?,
        label: String?,
        sourceSystem: CoordinateSystem = CoordinateSystem.BD09,
    ): Boolean = launchUri(context, buildGeoFallbackUri(lat, lon, label, sourceSystem))

    internal fun buildBaiduMapUri(
        lat: Double?,
        lon: Double?,
        label: String?,
        sourceSystem: CoordinateSystem = CoordinateSystem.BD09,
    ): String {
        val validCoords = validateCoordinates(lat, lon)
        val cleanLabel = sanitizeLabel(label)
        if (validCoords == null) {
            return if (cleanLabel != null) {
                "baidumap://map/place/search?query=${encodeQueryValue(cleanLabel)}&src=$BAIDU_SRC"
            } else {
                "baidumap://map/show?src=$BAIDU_SRC"
            }
        }
        val bd = CoordinateConverter.toBd09(validCoords.first, validCoords.second, sourceSystem)
        val latStr = formatCoordinate(bd.latitude)
        val lonStr = formatCoordinate(bd.longitude)
        val encodedLabel = encodeQueryValue(cleanLabel ?: DEFAULT_MARKER_LABEL)
        return "baidumap://map/marker?location=$latStr,$lonStr" +
            "&title=$encodedLabel&content=$encodedLabel&coord_type=bd09ll&src=$BAIDU_SRC"
    }

    internal fun buildAmapUri(
        lat: Double?,
        lon: Double?,
        label: String?,
        sourceSystem: CoordinateSystem = CoordinateSystem.BD09,
    ): String {
        val validCoords = validateCoordinates(lat, lon)
        val cleanLabel = sanitizeLabel(label)
        if (validCoords == null) {
            return if (cleanLabel != null) {
                "amapuri://poi?sourceApplication=$APP_NAME&keywords=${encodeQueryValue(cleanLabel)}&dev=0"
            } else {
                "amapuri://viewMap?sourceApplication=$APP_NAME&dev=0"
            }
        }
        val gcj = CoordinateConverter.convert(
            lat = validCoords.first,
            lon = validCoords.second,
            from = sourceSystem,
            to = CoordinateSystem.GCJ02,
        )
        val latStr = formatCoordinate(gcj.latitude)
        val lonStr = formatCoordinate(gcj.longitude)
        val encodedLabel = encodeQueryValue(cleanLabel ?: DEFAULT_MARKER_LABEL)
        return "amapuri://viewMap?sourceApplication=$APP_NAME" +
            "&poiname=$encodedLabel&lat=$latStr&lon=$lonStr&dev=0"
    }

    internal fun buildTencentMapUri(
        lat: Double?,
        lon: Double?,
        label: String?,
        sourceSystem: CoordinateSystem = CoordinateSystem.BD09,
    ): String {
        val validCoords = validateCoordinates(lat, lon)
        val cleanLabel = sanitizeLabel(label)
        if (validCoords == null) {
            return if (cleanLabel != null) {
                "qqmap://map/search?keyword=${encodeQueryValue(cleanLabel)}&referer=$APP_NAME"
            } else {
                "qqmap://map/search?referer=$APP_NAME"
            }
        }
        val gcj = CoordinateConverter.convert(
            lat = validCoords.first,
            lon = validCoords.second,
            from = sourceSystem,
            to = CoordinateSystem.GCJ02,
        )
        val latStr = formatCoordinate(gcj.latitude)
        val lonStr = formatCoordinate(gcj.longitude)
        val encodedLabel = encodeQueryValue(cleanLabel ?: DEFAULT_MARKER_LABEL)
        return "qqmap://map/marker?marker=coord:$latStr,$lonStr;title:$encodedLabel&referer=$APP_NAME"
    }

    internal fun buildGeoFallbackUri(
        lat: Double?,
        lon: Double?,
        label: String?,
        sourceSystem: CoordinateSystem = CoordinateSystem.BD09,
    ): String {
        val validCoords = validateCoordinates(lat, lon)
        val cleanLabel = sanitizeLabel(label)
        if (validCoords == null) {
            return if (cleanLabel != null) {
                "geo:0,0?q=${encodeQueryValue(cleanLabel)}"
            } else {
                "geo:0,0"
            }
        }
        val wgs = CoordinateConverter.toWgs84(validCoords.first, validCoords.second, sourceSystem)
        val latStr = formatCoordinate(wgs.latitude)
        val lonStr = formatCoordinate(wgs.longitude)
        return if (cleanLabel != null) {
            "geo:$latStr,$lonStr?q=$latStr,$lonStr(${encodeQueryValue(cleanLabel)})"
        } else {
            "geo:$latStr,$lonStr?q=$latStr,$lonStr"
        }
    }

    private fun launchUri(context: Context, uriString: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun validateCoordinates(lat: Double?, lon: Double?): Pair<Double, Double>? {
        if (lat == null || lon == null) return null
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private fun sanitizeLabel(label: String?): String? =
        label?.trim { it.isWhitespace() }?.takeIf { it.isNotEmpty() }

    private fun formatCoordinate(value: Double): String =
        String.format(Locale.US, "%.6f", value)

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
