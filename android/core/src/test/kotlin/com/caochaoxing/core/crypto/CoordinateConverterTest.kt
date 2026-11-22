// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.crypto

import com.caochaoxing.core.model.CoordinateSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CoordinateConverterTest {
    @Test
    fun `wgs84 to bd09 and back roundtrips within 2 meters`() {
        val points = listOf(
            39.9042 to 116.4074, // Beijing
            31.2304 to 121.4737, // Shanghai
            34.3416 to 108.9398, // Xi'an
            43.8256 to 87.6168,  // Urumqi
        )
        for ((srcLat, srcLon) in points) {
            val bd = CoordinateConverter.toBd09(srcLat, srcLon, CoordinateSystem.WGS84)
            val back = CoordinateConverter.toWgs84(bd.latitude, bd.longitude, CoordinateSystem.BD09)
            assertTrue(abs(back.latitude - srcLat) < 0.00003)
            assertTrue(abs(back.longitude - srcLon) < 0.00003)
        }
    }

    @Test
    fun `convert supports all system pairs and preserves outside china coordinates`() {
        val srcLat = 39.9042
        val srcLon = 116.4074
        val gcj = CoordinateConverter.convert(srcLat, srcLon, CoordinateSystem.WGS84, CoordinateSystem.GCJ02)
        assertEquals(CoordinateConverter.wgs84ToGcj02(srcLat, srcLon), gcj)
        val backFromGcj = CoordinateConverter.gcj02ToWgs84(gcj.latitude, gcj.longitude)
        assertTrue(abs(backFromGcj.latitude - srcLat) < 1e-6)
        assertTrue(abs(backFromGcj.longitude - srcLon) < 1e-6)

        val bd = CoordinateConverter.convert(gcj.latitude, gcj.longitude, CoordinateSystem.GCJ02, CoordinateSystem.BD09)
        val gcjFromBd = CoordinateConverter.convert(bd.latitude, bd.longitude, CoordinateSystem.BD09, CoordinateSystem.GCJ02)
        assertTrue(abs(gcjFromBd.latitude - gcj.latitude) < 1e-5)
        assertTrue(abs(gcjFromBd.longitude - gcj.longitude) < 1e-5)
        assertEquals(LatLon(srcLat, srcLon), CoordinateConverter.convert(srcLat, srcLon, CoordinateSystem.WGS84, CoordinateSystem.WGS84))

        assertFalse(CoordinateConverter.outOfChina(srcLat, srcLon))

        val overseas = listOf(
            35.6895 to 139.6917,  // Tokyo
            51.5074 to -0.1278,   // London
            40.7128 to -74.0060,  // New York
        )
        for ((lat, lon) in overseas) {
            assertTrue(CoordinateConverter.outOfChina(lat, lon))
            val expected = LatLon(lat, lon)
            assertEquals(expected, CoordinateConverter.wgs84ToGcj02(lat, lon))
            assertEquals(expected, CoordinateConverter.gcj02ToWgs84(lat, lon))
            assertEquals(expected, CoordinateConverter.gcj02ToBd09(lat, lon))
            assertEquals(expected, CoordinateConverter.bd09ToGcj02(lat, lon))
            assertEquals(expected, CoordinateConverter.toBd09(lat, lon, CoordinateSystem.WGS84))
            assertEquals(expected, CoordinateConverter.toWgs84(lat, lon, CoordinateSystem.BD09))
        }
    }

    @Test
    fun `external parser distinguishes lon-lat and lat-lon inside china`() {
        val p1 = ExternalCoordinateParser.parse("高德定位：116.397428,39.909230", CoordinateSystem.GCJ02)
        assertNotNull(p1)
        assertEquals(39.909230, p1!!.latitude, 1e-6)
        assertEquals(116.397428, p1.longitude, 1e-6)
        assertEquals(CoordinateSystem.GCJ02, p1.sourceSystem)

        val p2 = ExternalCoordinateParser.parse("39.909230, 116.397428", CoordinateSystem.WGS84)
        assertNotNull(p2)
        assertEquals(39.909230, p2!!.latitude, 1e-6)
        assertEquals(116.397428, p2.longitude, 1e-6)
        assertEquals(CoordinateSystem.WGS84, p2.sourceSystem)
        assertNull(p2.label)

        // Western China lon < 90 still resolves lon,lat unambiguously against China bounds
        val xinjiang = ExternalCoordinateParser.parse("喀什 75.9897, 39.4704", CoordinateSystem.GCJ02)
        assertNotNull(xinjiang)
        assertEquals(39.4704, xinjiang!!.latitude, 1e-6)
        assertEquals(75.9897, xinjiang.longitude, 1e-6)
    }

    @Test
    fun `external parser supports map urls, share copy, labeled axes, and rejects garbage`() {
        val amap = ExternalCoordinateParser.parse(
            "分享位置 https://uri.amap.com/marker?position=116.397428,39.909230&name=%E5%A4%A9%E5%AE%89%E9%97%A8",
            CoordinateSystem.WGS84,
        )
        assertNotNull(amap)
        assertEquals(39.909230, amap!!.latitude, 1e-6)
        assertEquals(116.397428, amap.longitude, 1e-6)
        assertEquals(CoordinateSystem.GCJ02, amap.sourceSystem)
        assertEquals("天安门", amap.label)

        val baidu = ExternalCoordinateParser.parse(
            "https://api.map.baidu.com/marker?location=39.915,116.404&title=%E5%8C%97%E4%BA%AC&output=html",
            CoordinateSystem.WGS84,
        )
        assertNotNull(baidu)
        assertEquals(39.915, baidu!!.latitude, 1e-6)
        assertEquals(116.404, baidu.longitude, 1e-6)
        assertEquals(CoordinateSystem.BD09, baidu.sourceSystem)
        assertEquals("北京", baidu.label)

        val tencent = ExternalCoordinateParser.parse(
            "https://apis.map.qq.com/uri/v1/marker?marker=coord:39.892326,116.342763;title:超星",
            CoordinateSystem.WGS84,
        )
        assertNotNull(tencent)
        assertEquals(39.892326, tencent!!.latitude, 1e-6)
        assertEquals(116.342763, tencent.longitude, 1e-6)
        assertEquals(CoordinateSystem.GCJ02, tencent.sourceSystem)
        assertEquals("超星", tencent.label)

        val osm = ExternalCoordinateParser.parse(
            "https://www.openstreetmap.org/?mlat=39.909230&mlon=116.397428#map=17/39.909230/116.397428",
            CoordinateSystem.BD09,
        )
        assertNotNull(osm)
        assertEquals(39.909230, osm!!.latitude, 1e-6)
        assertEquals(116.397428, osm.longitude, 1e-6)
        assertEquals(CoordinateSystem.WGS84, osm.sourceSystem)

        val geo = ExternalCoordinateParser.parse(
            "geo:39.909230,116.397428?q=39.909230,116.397428(主楼)",
            CoordinateSystem.GCJ02,
        )
        assertNotNull(geo)
        assertEquals(39.909230, geo!!.latitude, 1e-6)
        assertEquals(116.397428, geo.longitude, 1e-6)
        assertEquals(CoordinateSystem.WGS84, geo.sourceSystem)
        assertEquals("主楼", geo.label)

        val geoPlaceholder = ExternalCoordinateParser.parse(
            "geo:0,0?q=39.909230,116.397428(天安门)",
            CoordinateSystem.GCJ02,
        )
        assertNotNull(geoPlaceholder)
        assertEquals(39.909230, geoPlaceholder!!.latitude, 1e-6)
        assertEquals(116.397428, geoPlaceholder.longitude, 1e-6)
        assertEquals(CoordinateSystem.WGS84, geoPlaceholder.sourceSystem)
        assertEquals("天安门", geoPlaceholder.label)

        val geoZeroFallback = ExternalCoordinateParser.parse(
            "geo:0,0?q=天安门",
            CoordinateSystem.WGS84,
        )
        assertNotNull(geoZeroFallback)
        assertEquals(0.0, geoZeroFallback!!.latitude, 1e-6)
        assertEquals(0.0, geoZeroFallback.longitude, 1e-6)

        val prose = ExternalCoordinateParser.parse(
            "版本1.2 一教18号楼附近，坐标：116.397428, 39.909230",
            CoordinateSystem.GCJ02,
        )
        assertNotNull(prose)
        assertEquals(39.909230, prose!!.latitude, 1e-6)
        assertEquals(116.397428, prose.longitude, 1e-6)

        assertNull(
            ExternalCoordinateParser.parse("WGS 39.909230, View 116.397428", CoordinateSystem.WGS84),
        )
        assertNull(
            ExternalCoordinateParser.parse("GPS 39.909230, New 116.397428", CoordinateSystem.WGS84),
        )

        val gpsValidPair = ExternalCoordinateParser.parse(
            "GPS 39.909230, 116.397428",
            CoordinateSystem.WGS84,
        )
        assertNotNull(gpsValidPair)
        assertEquals(39.909230, gpsValidPair!!.latitude, 1e-6)
        assertEquals(116.397428, gpsValidPair.longitude, 1e-6)

        val noFalseFlatSalon = ExternalCoordinateParser.parse(
            "Flat 12, Salon 116: 39.909230, 116.397428",
            CoordinateSystem.WGS84,
        )
        assertNotNull(noFalseFlatSalon)
        assertEquals(39.909230, noFalseFlatSalon!!.latitude, 1e-6)
        assertEquals(116.397428, noFalseFlatSalon.longitude, 1e-6)

        val chinesePrefixCardinal = ExternalCoordinateParser.parse(
            "坐标N39.909230, 坐标E116.397428",
            CoordinateSystem.WGS84,
        )
        assertNotNull(chinesePrefixCardinal)
        assertEquals(39.909230, chinesePrefixCardinal!!.latitude, 1e-6)
        assertEquals(116.397428, chinesePrefixCardinal.longitude, 1e-6)

        val chineseWordCardinal = ExternalCoordinateParser.parse(
            "北纬39.909230, 东经116.397428",
            CoordinateSystem.WGS84,
        )
        assertNotNull(chineseWordCardinal)
        assertEquals(39.909230, chineseWordCardinal!!.latitude, 1e-6)
        assertEquals(116.397428, chineseWordCardinal.longitude, 1e-6)

        val labeled = ExternalCoordinateParser.parse("纬度: 39.909230, 经度: 116.397428", CoordinateSystem.WGS84)
        assertNotNull(labeled)
        assertEquals(39.909230, labeled!!.latitude, 1e-6)
        assertEquals(116.397428, labeled.longitude, 1e-6)

        val cardinal = ExternalCoordinateParser.parse("33.8688° S, 151.2093° E", CoordinateSystem.WGS84)
        assertNotNull(cardinal)
        assertEquals(-33.8688, cardinal!!.latitude, 1e-6)
        assertEquals(151.2093, cardinal.longitude, 1e-6)

        assertNull(ExternalCoordinateParser.parse("", CoordinateSystem.WGS84))
        assertNull(ExternalCoordinateParser.parse("无坐标文本", CoordinateSystem.WGS84))
        assertNull(ExternalCoordinateParser.parse("200.0, 100.0", CoordinateSystem.WGS84))
    }
}
