package com.caochaoxing.core.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AppUpdateServiceTest {
    @Test fun comparesSemanticVersions() {
        assertEquals(AppVersion.parse("v1.2.0"), AppVersion.parse("1.2.0+build.2"))
        assertTrue(AppVersion.parse("1.10.0") > AppVersion.parse("1.9.9"))
        assertTrue(AppVersion.parse("2.0.0") > AppVersion.parse("2.0.0-rc.10"))
        assertTrue(AppVersion.parse("2.0.0-rc.10") > AppVersion.parse("2.0.0-rc.2"))
        assertTrue(AppVersion.parse("2.0.0-beta") > AppVersion.parse("2.0.0-1"))
        assertThrows(IllegalArgumentException::class.java) { AppVersion.parse("1.2.0-01") }
        assertThrows(IllegalStateException::class.java) { AppVersion.parse("latest") }
    }

    @Test fun choosesExactArchitectureThenUniversalAndRejectsOtherPlatforms() {
        val service = AppUpdateService()
        val universal = UpdateAsset("android", "universal", "https://example.com/all.apk")
        val arm = UpdateAsset("android", "arm64-v8a", "https://example.com/arm.apk")
        val release = UpdateRelease("1.6.0", assets = listOf(UpdateAsset("ios", "arm64", "https://example.com/app.ipa"), universal, arm))
        assertEquals(arm, service.androidAsset(release, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(universal, service.androidAsset(release, listOf("x86_64")))
        assertThrows(IllegalStateException::class.java) { service.androidAsset(release.copy(assets = listOf(arm)), listOf("x86_64")) }
    }
}
