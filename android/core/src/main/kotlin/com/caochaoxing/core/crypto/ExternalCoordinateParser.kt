// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.crypto

import com.caochaoxing.core.model.CoordinateSystem
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.abs

data class ParsedExternalCoordinate(
    val latitude: Double,
    val longitude: Double,
    val sourceSystem: CoordinateSystem,
    val label: String? = null,
)

/**
 * Parses coordinates from raw coordinate pairs (`lat, lon` / `lon, lat`), map share text, and map URLs.
 */
object ExternalCoordinateParser {
    private val URL_REGEX = Regex(
        """(?i)\b(?:https?://|baidumap://|amapuri://|androidamap://|iosamap://|qqmap://|geo:)[^\s<>"]+""",
    )
    private val OSM_FRAGMENT_REGEX = Regex("""map=\d+/([+-]?\d+(?:\.\d+)?)/([+-]?\d+(?:\.\d+)?)""")
    private val PUNCTUATED_DECIMAL_PAIR_REGEX = Regex(
        """(?<![\d.])([+-]?\d+\.\d+)\s*[,，;；|/]\s*([+-]?\d+\.\d+)(?![\d.])""",
    )
    private val WHITESPACE_DECIMAL_PAIR_REGEX = Regex(
        """(?<![\d.])([+-]?\d+\.\d+)\s+([+-]?\d+\.\d+)(?![\d.])""",
    )
    private val STANDALONE_PAIR_REGEX = Regex(
        """^[(\[（【]?\s*([+-]?\d+(?:\.\d+)?)\s*[,，;；|/\s]\s*([+-]?\d+(?:\.\d+)?)\s*[)\]）】]?$""",
    )
    private val LABELED_LAT_REGEX = Regex(
        """(?i)(?<![A-Za-z])(?:latitude|lat|纬度)\s*[:：=\s]\s*([+-]?\d+(?:\.\d+)?)""",
    )
    private val LABELED_LON_REGEX = Regex(
        """(?i)(?<![A-Za-z])(?:longitude|lng|lon|经度)\s*[:：=\s]\s*([+-]?\d+(?:\.\d+)?)""",
    )
    private val CARDINAL_LAT_REGEX = Regex(
        """(?i)(?<![A-Za-z])(?:(北纬|南纬|[NS])\s*([+-]?\d+(?:\.\d+)?)\s*°?|([+-]?\d+(?:\.\d+)?)\s*°?\s*(北纬|南纬|[NS])(?![A-Za-z]))""",
    )
    private val CARDINAL_LON_REGEX = Regex(
        """(?i)(?<![A-Za-z])(?:(东经|西经|[EW])\s*([+-]?\d+(?:\.\d+)?)\s*°?|([+-]?\d+(?:\.\d+)?)\s*°?\s*(东经|西经|[EW])(?![A-Za-z]))""",
    )

    private val LAT_PARAM_KEYS = listOf("lat", "latitude", "mlat", "dlat", "slat")
    private val LON_PARAM_KEYS = listOf("lon", "lng", "longitude", "mlon", "dlon", "slon")
    private val COMBINED_PARAM_KEYS = listOf(
        "position",
        "location",
        "coord",
        "center",
        "ll",
        "latlng",
        "point",
        "q",
        "l",
        "dest",
        "origin",
    )
    private val LON_FIRST_PARAM_KEYS = setOf("position", "center")
    private val LABEL_PARAM_KEYS = listOf("name", "title", "dname", "sname", "poiname", "address", "addr", "content")

    /**
     * Attempts to extract coordinates and infer [CoordinateSystem] from [rawText], falling back to [assumedSystem].
     */
    fun parse(rawText: String, assumedSystem: CoordinateSystem): ParsedExternalCoordinate? {
        val trimmed = rawText.trim { it.isWhitespace() }
        if (trimmed.isEmpty()) return null

        for (urlMatch in URL_REGEX.findAll(trimmed)) {
            parseUrl(urlMatch.value, fullText = trimmed, assumedSystem = assumedSystem)?.let { return it }
        }

        val detectedSystem = detectCoordinateSystemFromText(trimmed, assumedSystem)

        parseExplicitLabeledAxes(trimmed, detectedSystem)?.let { return it }
        parseCardinalCoordinates(trimmed, detectedSystem)?.let { return it }

        for (regex in listOf(PUNCTUATED_DECIMAL_PAIR_REGEX, WHITESPACE_DECIMAL_PAIR_REGEX)) {
            for (match in regex.findAll(trimmed)) {
                val first = match.groupValues[1].toDoubleOrNull() ?: continue
                val second = match.groupValues[2].toDoubleOrNull() ?: continue
                val resolved = resolveLatLon(first, second, preferLonFirst = false) ?: continue
                val label = extractPrefixLabel(trimmed, match.range.first)
                return ParsedExternalCoordinate(
                    latitude = resolved.first,
                    longitude = resolved.second,
                    sourceSystem = detectedSystem,
                    label = label,
                )
            }
        }

        STANDALONE_PAIR_REGEX.matchEntire(trimmed)?.let { match ->
            val first = match.groupValues[1].toDoubleOrNull()
            val second = match.groupValues[2].toDoubleOrNull()
            if (first != null && second != null) {
                resolveLatLon(first, second, preferLonFirst = false)?.let { (lat, lon) ->
                    return ParsedExternalCoordinate(
                        latitude = lat,
                        longitude = lon,
                        sourceSystem = detectedSystem,
                        label = null,
                    )
                }
            }
        }

        return null
    }

    private fun parseUrl(
        rawUrl: String,
        fullText: String,
        assumedSystem: CoordinateSystem,
    ): ParsedExternalCoordinate? {
        val uri = try {
            URI(rawUrl)
        } catch (_: URISyntaxException) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        // Opaque URIs (such as `geo:lat,lon?q=...`) expose query text via `rawSchemeSpecificPart`.
        val effectiveRawQuery = uri.rawQuery
            ?: uri.rawSchemeSpecificPart?.substringAfter('?', "")?.takeIf { it.isNotEmpty() }
        val queryItems = parseUrlQueryItems(effectiveRawQuery)
        val system = detectCoordinateSystemFromUri(scheme, host, queryItems, fullText, assumedSystem)

        if (scheme == "geo") {
            parseGeoUri(uri, rawUrl, fullText, system, queryItems)?.let { return it }
        }

        val explicitLat = LAT_PARAM_KEYS.firstNotNullOfOrNull { key ->
            queryItems.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second?.toDoubleOrNull()
        }
        val explicitLon = LON_PARAM_KEYS.firstNotNullOfOrNull { key ->
            queryItems.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second?.toDoubleOrNull()
        }
        if (explicitLat != null && explicitLon != null &&
            explicitLat.isFinite() && explicitLon.isFinite() &&
            explicitLat in -90.0..90.0 && explicitLon in -180.0..180.0
        ) {
            val label = extractExplicitLabel(queryItems)
                ?: extractPrefixLabel(fullText, fullText.indexOf(rawUrl))
            return ParsedExternalCoordinate(explicitLat, explicitLon, system, label)
        }

        // Handle Tencent `marker=coord:lat,lon;title:...`
        val markerValue = queryItems.firstOrNull { it.first.equals("marker", ignoreCase = true) }?.second
        if (!markerValue.isNullOrBlank()) {
            var markerCoord: Pair<Double, Double>? = null
            var markerTitle: String? = null
            for (part in markerValue.split(';')) {
                val idx = part.indexOf(':')
                if (idx <= 0) continue
                val k = part.substring(0, idx).trim().lowercase(Locale.ROOT)
                val v = part.substring(idx + 1).trim()
                when (k) {
                    "coord" -> markerCoord = extractPairFromString(v, preferLonFirst = false)
                    "title" -> if (v.isNotEmpty()) markerTitle = v
                }
            }
            if (markerCoord != null) {
                val label = markerTitle
                    ?: extractExplicitLabel(queryItems)
                    ?: extractPrefixLabel(fullText, fullText.indexOf(rawUrl))
                return ParsedExternalCoordinate(markerCoord.first, markerCoord.second, system, label)
            }
        }

        val isAmapHost = scheme in setOf("amapuri", "androidamap", "iosamap") || "amap.com" in host
        for (key in COMBINED_PARAM_KEYS) {
            val value = queryItems.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second ?: continue
            val preferLonFirst = isAmapHost && key in LON_FIRST_PARAM_KEYS
            val coords = extractPairFromString(value, preferLonFirst = preferLonFirst) ?: continue
            val label = extractParenthesizedLabel(value)
                ?: extractExplicitLabel(queryItems)
                ?: extractPrefixLabel(fullText, fullText.indexOf(rawUrl))
            return ParsedExternalCoordinate(coords.first, coords.second, system, label)
        }

        uri.rawFragment?.let { fragment ->
            OSM_FRAGMENT_REGEX.find(fragment)?.let { match ->
                val lat = match.groupValues[1].toDoubleOrNull()
                val lon = match.groupValues[2].toDoubleOrNull()
                if (lat != null && lon != null) {
                    resolveLatLon(lat, lon, preferLonFirst = false)?.let { (rLat, rLon) ->
                        val label = extractExplicitLabel(queryItems)
                            ?: extractPrefixLabel(fullText, fullText.indexOf(rawUrl))
                        return ParsedExternalCoordinate(rLat, rLon, system, label)
                    }
                }
            }
        }

        return null
    }

    private fun parseGeoUri(
        uri: URI,
        rawUrl: String,
        fullText: String,
        system: CoordinateSystem,
        queryItems: List<Pair<String, String>>,
    ): ParsedExternalCoordinate? {
        val ssp = uri.rawSchemeSpecificPart.orEmpty()
        val coordPart = ssp.substringBefore('?').substringBefore(';')
        val qParam = queryItems.firstOrNull { it.first.equals("q", ignoreCase = true) }?.second
        val label = extractParenthesizedLabel(qParam)
            ?: extractExplicitLabel(queryItems)
            ?: extractPrefixLabel(fullText, fullText.indexOf(rawUrl))

        val primary = extractPairFromString(coordPart, preferLonFirst = false)
        val isNearZero = primary != null && abs(primary.first) < 1e-6 && abs(primary.second) < 1e-6
        if (primary == null || isNearZero) {
            val qCoords = qParam?.let { extractPairFromString(it, preferLonFirst = false) }
            if (qCoords != null) {
                return ParsedExternalCoordinate(qCoords.first, qCoords.second, system, label)
            }
        }
        return primary?.let { (lat, lon) ->
            ParsedExternalCoordinate(lat, lon, system, label)
        }
    }

    private fun parseExplicitLabeledAxes(
        text: String,
        system: CoordinateSystem,
    ): ParsedExternalCoordinate? {
        val latMatch = LABELED_LAT_REGEX.find(text) ?: return null
        val lonMatch = LABELED_LON_REGEX.find(text) ?: return null
        val lat = latMatch.groupValues[1].toDoubleOrNull() ?: return null
        val lon = lonMatch.groupValues[1].toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return null
        }
        val earliestStart = minOf(latMatch.range.first, lonMatch.range.first)
        return ParsedExternalCoordinate(
            latitude = lat,
            longitude = lon,
            sourceSystem = system,
            label = extractPrefixLabel(text, earliestStart),
        )
    }

    private fun parseCardinalCoordinates(
        text: String,
        system: CoordinateSystem,
    ): ParsedExternalCoordinate? {
        val latMatch = CARDINAL_LAT_REGEX.find(text) ?: return null
        val lonMatch = CARDINAL_LON_REGEX.find(text) ?: return null
        val latHemi = (latMatch.groupValues[1].ifEmpty { latMatch.groupValues[4] }).uppercase(Locale.ROOT)
        val latRaw = (latMatch.groupValues[2].ifEmpty { latMatch.groupValues[3] }).toDoubleOrNull() ?: return null
        val lonHemi = (lonMatch.groupValues[1].ifEmpty { lonMatch.groupValues[4] }).uppercase(Locale.ROOT)
        val lonRaw = (lonMatch.groupValues[2].ifEmpty { lonMatch.groupValues[3] }).toDoubleOrNull() ?: return null

        val lat = if (latHemi == "S" || latHemi == "南纬") -abs(latRaw) else latRaw
        val lon = if (lonHemi == "W" || lonHemi == "西经") -abs(lonRaw) else lonRaw
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return null
        }
        val earliestStart = minOf(latMatch.range.first, lonMatch.range.first)
        return ParsedExternalCoordinate(
            latitude = lat,
            longitude = lon,
            sourceSystem = system,
            label = extractPrefixLabel(text, earliestStart),
        )
    }

    private fun extractPairFromString(text: String, preferLonFirst: Boolean): Pair<Double, Double>? {
        val cleaned = text.substringBefore('(').trim()
        val match = PUNCTUATED_DECIMAL_PAIR_REGEX.find(cleaned)
            ?: WHITESPACE_DECIMAL_PAIR_REGEX.find(cleaned)
            ?: STANDALONE_PAIR_REGEX.matchEntire(cleaned)
            ?: return null
        val first = match.groupValues[1].toDoubleOrNull() ?: return null
        val second = match.groupValues[2].toDoubleOrNull() ?: return null
        return resolveLatLon(first, second, preferLonFirst = preferLonFirst)
    }

    /**
     * Resolves `(lat, lon)` vs `(lon, lat)` using coordinate range constraints and disjoint China lat/lon bands.
     */
    private fun resolveLatLon(
        first: Double,
        second: Double,
        preferLonFirst: Boolean,
    ): Pair<Double, Double>? {
        if (!first.isFinite() || !second.isFinite()) return null
        val firstAsLatValid = first in -90.0..90.0 && second in -180.0..180.0
        val secondAsLatValid = second in -90.0..90.0 && first in -180.0..180.0
        if (!firstAsLatValid && !secondAsLatValid) return null
        if (firstAsLatValid && !secondAsLatValid) return first to second
        if (!firstAsLatValid && secondAsLatValid) return second to first

        val firstInChinaLat = first in 0.8293..55.8271
        val secondInChinaLon = second in 72.004..137.8347
        val secondInChinaLat = second in 0.8293..55.8271
        val firstInChinaLon = first in 72.004..137.8347

        val looksLikeChinaLatLon = firstInChinaLat && secondInChinaLon
        val looksLikeChinaLonLat = secondInChinaLat && firstInChinaLon
        if (looksLikeChinaLatLon && !looksLikeChinaLonLat) return first to second
        if (looksLikeChinaLonLat && !looksLikeChinaLatLon) return second to first

        return if (preferLonFirst) second to first else first to second
    }

    private fun detectCoordinateSystemFromUri(
        scheme: String,
        host: String,
        queryItems: List<Pair<String, String>>,
        fullText: String,
        assumedSystem: CoordinateSystem,
    ): CoordinateSystem {
        val coordType = queryItems.firstOrNull {
            it.first.equals("coord_type", ignoreCase = true) ||
                it.first.equals("coordtype", ignoreCase = true) ||
                it.first.equals("crs", ignoreCase = true)
        }?.second?.lowercase(Locale.ROOT)
        if (coordType != null) {
            when {
                "bd09" in coordType || "bd-09" in coordType -> return CoordinateSystem.BD09
                "gcj02" in coordType || "gcj-02" in coordType || "amap" in coordType -> return CoordinateSystem.GCJ02
                "wgs84" in coordType || "wgs-84" in coordType || "gps" in coordType -> return CoordinateSystem.WGS84
            }
        }

        return when {
            scheme == "baidumap" || "baidu.com" in host -> CoordinateSystem.BD09
            scheme in setOf("amapuri", "androidamap", "iosamap") || "amap.com" in host -> CoordinateSystem.GCJ02
            scheme == "qqmap" || "map.qq.com" in host || "apis.map.qq.com" in host -> CoordinateSystem.GCJ02
            scheme == "geo" || "openstreetmap.org" in host || "osm.org" in host ||
                "google.com" in host || "maps.apple.com" in host -> CoordinateSystem.WGS84
            else -> detectCoordinateSystemFromText(fullText, assumedSystem)
        }
    }

    private fun detectCoordinateSystemFromText(
        text: String,
        assumedSystem: CoordinateSystem,
    ): CoordinateSystem {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            "bd09" in lower || "bd-09" in lower || "百度" in text -> CoordinateSystem.BD09
            "gcj02" in lower || "gcj-02" in lower || "高德" in text || "腾讯" in text || "火星" in text -> CoordinateSystem.GCJ02
            "wgs84" in lower || "wgs-84" in lower || "openstreetmap" in lower || "osm" in lower || "gps" in lower -> CoordinateSystem.WGS84
            else -> assumedSystem
        }
    }

    private fun parseUrlQueryItems(rawQuery: String?): List<Pair<String, String>> {
        if (rawQuery.isNullOrEmpty()) return emptyList()
        val items = mutableListOf<Pair<String, String>>()
        for (segment in rawQuery.split('&')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            val key = if (eq >= 0) decodeUrlComponent(segment.substring(0, eq)) else decodeUrlComponent(segment)
            val value = if (eq >= 0) decodeUrlComponent(segment.substring(eq + 1)) else ""
            if (key.isNotEmpty()) {
                items.add(key to value)
            }
        }
        return items
    }

    private fun decodeUrlComponent(raw: String): String = try {
        URLDecoder.decode(raw, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        raw
    }

    private fun extractExplicitLabel(queryItems: List<Pair<String, String>>): String? =
        LABEL_PARAM_KEYS.firstNotNullOfOrNull { key ->
            queryItems.firstOrNull { it.first.equals(key, ignoreCase = true) }
                ?.second
                ?.trim { it.isWhitespace() }
                ?.takeIf { it.isNotEmpty() }
        }

    private fun extractParenthesizedLabel(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val start = value.indexOf('(')
        val end = value.lastIndexOf(')')
        if (start >= 0 && end > start + 1) {
            return value.substring(start + 1, end).trim { it.isWhitespace() }.takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun extractPrefixLabel(fullText: String, matchStart: Int): String? {
        if (matchStart <= 0) return null
        val rawPrefix = fullText.substring(0, matchStart)
            .trim { it.isWhitespace() || it in LABEL_TRIM_CHARS }
        return rawPrefix.takeIf { it.isNotEmpty() }
    }

    private val LABEL_TRIM_CHARS = charArrayOf(':', '：', ',', '，', ';', '；', '|', '-', '—', '(', '（', '[', '【', ')', '）', ']', '】')
}
