// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.crypto

import com.caochaoxing.core.util.ImageObfuscationHelpers
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.random.Random

class CaptchaSolverAndImageTest {

    @Test
    fun `estimateXPosition locates synthetic 50x50 box at x=142 y=40`() {
        val bgW = 300
        val bgH = 150
        val targetX = 142
        val targetY = 40
        val cutSize = 50

        val bgPixels = IntArray(bgW * bgH) { 0xFF202020.toInt() }
        // Place a 50x50 high-contrast box at (142, 40) with its interior step at (1..48).
        for (y in (targetY + 1) until (targetY + cutSize - 1)) {
            for (x in (targetX + 1) until (targetX + cutSize - 1)) {
                bgPixels[y * bgW + x] = 0xFFE8E8E8.toInt()
            }
        }

        val cutPixels = IntArray(cutSize * cutSize)
        for (y in 1 until (cutSize - 1)) {
            for (x in 1 until (cutSize - 1)) {
                cutPixels[y * cutSize + x] = 0xFFE8E8E8.toInt()
            }
        }

        val shade = PixelImage(bgW, bgH, bgPixels)
        val cutout = PixelImage(cutSize, cutSize, cutPixels)

        val estimated = ChaoxingCaptchaSolver.estimateXPosition(shade, cutout)
        assertNotNull(estimated)
        assertTrue(estimated!! in 140..144, "Expected x in 140..144, got $estimated")
        assertEquals(targetX, estimated)
    }

    @Test
    fun `estimateXPosition locates jigsaw cutout on noisy textured background`() {
        val bgW = 300
        val bgH = 150
        val targetX = 142
        val targetY = 40
        val cutSize = 60

        val rng = Random(2026)
        val bgPixels = IntArray(bgW * bgH) { i ->
            val x = i % bgW
            val y = i / bgW
            val base = 70 + ((x * 3 + y * 5) % 25) + rng.nextInt(-6, 7)
            val c = base.coerceIn(0, 255)
            (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        }

        val cutPixels = IntArray(cutSize * cutSize)
        for (dy in 8 until 50) {
            for (dx in 6 until 46) {
                cutPixels[dy * cutSize + dx] = 0xFFFFFFFF.toInt()
            }
        }
        // Jigsaw tab on the right side of the piece.
        for (dy in 22 until 36) {
            for (dx in 46 until 56) {
                cutPixels[dy * cutSize + dx] = 0xFFFFFFFF.toInt()
            }
        }

        // Stamp high-contrast jigsaw silhouette at (targetX, targetY) on shade background.
        for (dy in 0 until cutSize) {
            for (dx in 0 until cutSize) {
                if (((cutPixels[dy * cutSize + dx] ushr 24) and 0xFF) >= 128) {
                    bgPixels[(targetY + dy) * bgW + (targetX + dx)] = 0xFFF2F2F2.toInt()
                }
            }
        }

        val estimated = ChaoxingCaptchaSolver.estimateXPosition(
            PixelImage(bgW, bgH, bgPixels),
            PixelImage(cutSize, cutSize, cutPixels),
        )
        assertNotNull(estimated)
        assertTrue(estimated!! in 140..144, "Expected x in 140..144, got $estimated")
    }

    @Test
    fun `estimateXPosition returns null for empty uniform ambiguous and over-budget inputs`() {
        val uniformShade = PixelImage(300, 150, IntArray(300 * 150) { 0xFF444444.toInt() })
        val validCutoutPixels = IntArray(50 * 50).apply {
            for (y in 4 until 46) {
                for (x in 4 until 46) {
                    this[y * 50 + x] = 0xFFFFFFFF.toInt()
                }
            }
        }
        val validCutout = PixelImage(50, 50, validCutoutPixels)

        // 1. Uniform background -> zero gradient everywhere.
        assertNull(ChaoxingCaptchaSolver.estimateXPosition(uniformShade, validCutout))

        // 2. Empty (all transparent) and fully opaque cutouts -> zero boundary samples.
        val emptyCutout = PixelImage(50, 50, IntArray(50 * 50))
        val opaqueCutout = PixelImage(50, 50, IntArray(50 * 50) { 0xFFAAAAAA.toInt() })
        val contrastShadePixels = IntArray(300 * 150) { 0xFF101010.toInt() }.apply {
            for (y in 40 until 90) {
                for (x in 142 until 192) {
                    this[y * 300 + x] = 0xFFEEEEEE.toInt()
                }
            }
        }
        val contrastShade = PixelImage(300, 150, contrastShadePixels)
        assertNull(ChaoxingCaptchaSolver.estimateXPosition(contrastShade, emptyCutout))
        assertNull(ChaoxingCaptchaSolver.estimateXPosition(contrastShade, opaqueCutout))

        // 3. Too-small cutout (<= 8) or background not larger than cutout.
        val tinyCutout = PixelImage(8, 8, IntArray(64) { 0xFFFFFFFF.toInt() })
        assertNull(ChaoxingCaptchaSolver.estimateXPosition(contrastShade, tinyCutout))
        val smallShade = PixelImage(50, 50, IntArray(50 * 50) { 0xFF101010.toInt() })
        assertNull(ChaoxingCaptchaSolver.estimateXPosition(smallShade, validCutout))

        // 4. Ambiguous background with two identical high-contrast boxes (tie fails 1.01 margin).
        val twinShadePixels = IntArray(300 * 150) { 0xFF101010.toInt() }
        for (boxX in listOf(60, 180)) {
            for (y in 40 until 90) {
                for (x in boxX until (boxX + 50)) {
                    twinShadePixels[y * 300 + x] = 0xFFEEEEEE.toInt()
                }
            }
        }
        assertNull(ChaoxingCaptchaSolver.estimateXPosition(PixelImage(300, 150, twinShadePixels), validCutout))

        // 5. Excess boundary samples (> 2048).
        val denseCutPixels = IntArray(128 * 128) { idx ->
            val x = idx % 128
            val y = idx / 128
            if ((x + y) % 2 == 0) 0xFFFFFFFF.toInt() else 0x00000000
        }
        assertNull(ChaoxingCaptchaSolver.estimateXPosition(contrastShade, PixelImage(128, 128, denseCutPixels)))

        // 6. Oversized image dimensions / pixel count (> 1024 or > 524_288) degrade safely to null.
        assertNull(
            ChaoxingCaptchaSolver.estimateXPosition(
                PixelImage(1_025, 100, IntArray(1_025 * 100) { 0xFF222222.toInt() }),
                validCutout,
            ),
        )
        assertNull(
            ChaoxingCaptchaSolver.estimateXPosition(
                PixelImage(100, 1_025, IntArray(100 * 1_025) { 0xFF222222.toInt() }),
                validCutout,
            ),
        )
        assertNull(
            ChaoxingCaptchaSolver.estimateXPosition(
                PixelImage(800, 800, IntArray(800 * 800) { 0xFF222222.toInt() }),
                validCutout,
            ),
        )

        // 7. Search budget exceeded on 1024x512 shade with valid 388-sample 100x100 cutout.
        // Same cutout solves cleanly on a within-budget 220x120 shade (candidates = 2_121 <= 30_927),
        // proving MAX_SEARCH_BUDGET is the sole guard rejecting 1024x512 (candidates = 373_765 > 30_927).
        val largeCutPixels = IntArray(100 * 100) { idx ->
            val x = idx % 100
            val y = idx / 100
            if (x in 1..98 && y in 1..98) 0xFF000000.toInt() else 0x00000000
        }
        val largeCutout = PixelImage(100, 100, largeCutPixels)
        val withinBudgetPixels = IntArray(220 * 120) { 0xFF222222.toInt() }
        for (y in 11 until 109) {
            for (x in 81 until 179) {
                withinBudgetPixels[y * 220 + x] = 0xFFEEEEEE.toInt()
            }
        }
        assertEquals(
            80,
            ChaoxingCaptchaSolver.estimateXPosition(PixelImage(220, 120, withinBudgetPixels), largeCutout),
        )
        val maxShadePixels = IntArray(1_024 * 512) { 0xFF222222.toInt() }
        for (y in 121 until 219) {
            for (x in 301 until 399) {
                maxShadePixels[y * 1_024 + x] = 0xFFEEEEEE.toInt()
            }
        }
        assertNull(ChaoxingCaptchaSolver.estimateXPosition(PixelImage(1_024, 512, maxShadePixels), largeCutout))
    }

    @Test
    fun `PixelImage rejects non-positive or mismatched buffers and checks structural equality`() {
        assertThrows(IllegalArgumentException::class.java) {
            PixelImage(0, 10, IntArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PixelImage(10, 0, IntArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PixelImage(10, 10, IntArray(99))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PixelImage(65_536, 65_536, IntArray(0))
        }

        val a = PixelImage(4, 4, IntArray(16) { it })
        val b = PixelImage(4, 4, IntArray(16) { it })
        val c = PixelImage(4, 4, IntArray(16) { it + 1 })
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }

    @Test
    fun `appendRandomJpegComment preserves SOI EOI inserts valid COM marker and changes SHA256`() {
        val sampleJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), // SOI
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x06, 0x4A, 0x46, 0x49, 0x46, // APP0-like bytes
            0xFF.toByte(), 0xDA.toByte(), 0x11, 0x22, 0x33, // SOS-like bytes
            0xFF.toByte(), 0xD9.toByte(), // EOI
        )

        val out1 = ImageObfuscationHelpers.appendRandomJpegComment(sampleJpeg)
        val out2 = ImageObfuscationHelpers.appendRandomJpegComment(sampleJpeg)

        assertTrue(out1.size > sampleJpeg.size + 4)
        assertEquals(0xFF.toByte(), out1[0])
        assertEquals(0xD8.toByte(), out1[1])
        assertEquals(0xFF.toByte(), out1[out1.size - 2])
        assertEquals(0xD9.toByte(), out1[out1.size - 1])

        // Original prefix before EOI must remain untouched.
        assertArrayEquals(
            sampleJpeg.copyOfRange(0, sampleJpeg.size - 2),
            out1.copyOfRange(0, sampleJpeg.size - 2),
        )

        // COM marker (FF FE) with big-endian length must sit directly before trailing EOI (FF D9).
        val markerPos = sampleJpeg.size - 2
        assertEquals(0xFF.toByte(), out1[markerPos])
        assertEquals(0xFE.toByte(), out1[markerPos + 1])
        val segmentLength = ((out1[markerPos + 2].toInt() and 0xFF) shl 8) or
            (out1[markerPos + 3].toInt() and 0xFF)
        assertEquals(out1.size - 2, markerPos + 2 + segmentLength)

        assertFalse(sha256Hex(sampleJpeg) == sha256Hex(out1))
        assertFalse(sha256Hex(out1) == sha256Hex(out2))
    }

    @Test
    fun `appendRandomJpegComment falls back gracefully on non-EOI and empty inputs`() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val outA = ImageObfuscationHelpers.appendRandomJpegComment(pngHeader)
        val outB = ImageObfuscationHelpers.appendRandomJpegComment(pngHeader)
        assertTrue(outA.size > pngHeader.size)
        assertArrayEquals(pngHeader, outA.copyOfRange(0, pngHeader.size))
        assertFalse(sha256Hex(outA) == sha256Hex(outB))

        val emptyOut1 = ImageObfuscationHelpers.appendRandomJpegComment(ByteArray(0))
        val emptyOut2 = ImageObfuscationHelpers.appendRandomJpegComment(ByteArray(0))
        assertTrue(emptyOut1.isNotEmpty())
        assertFalse(sha256Hex(emptyOut1) == sha256Hex(emptyOut2))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { b -> "%02x".format(b.toInt() and 0xFF) }
}
