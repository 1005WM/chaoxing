// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.crypto

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure JVM representation of a decoded ARGB pixel buffer (`0xAARRGGBB` per pixel).
 *
 * Enforces structural buffer invariants while leaving solver dimension/budget limits to
 * [ChaoxingCaptchaSolver.estimateXPosition] so oversized challenges degrade cleanly to `null`.
 */
data class PixelImage(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) {
            "Image dimensions must be positive, got ${width}x$height"
        }
        require(argbPixels.size.toLong() == width.toLong() * height.toLong()) {
            "argbPixels.size (${argbPixels.size}) must equal width * height (${width.toLong() * height.toLong()})"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PixelImage) return false
        return width == other.width &&
            height == other.height &&
            argbPixels.contentEquals(other.argbPixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + argbPixels.contentHashCode()
        return result
    }

    companion object {
        const val MAX_DIMENSION: Int = 1_024
        const val MAX_PIXELS: Int = 524_288
    }
}

/**
 * Pure on-device slider captcha solver using cutout alpha boundary correlation against
 * background grayscale gradients (`|gx| + |gy|`), aligned with the Swift `ChaoxingCaptchaSolver`.
 */
object ChaoxingCaptchaSolver {
    private const val MIN_CUTOUT_DIMENSION = 8
    private const val MIN_SCAN_X = 20
    private const val ALPHA_THRESHOLD = 128
    private const val MAX_EDGE_SAMPLES = 2_048
    private const val MAX_SEARCH_BUDGET = 12_000_000L
    private const val PEAK_UNIQUENESS_MARGIN = 1.01f
    private const val PEAK_EXCLUSION_RADIUS = 2

    /**
     * Estimates the horizontal `xPosition` of [cutout] within [shade], or returns `null`
     * when the inputs are malformed, exceed the search budget, or lack a distinct peak.
     */
    fun estimateXPosition(shade: PixelImage, cutout: PixelImage): Int? {
        val bgW = shade.width
        val bgH = shade.height
        val cutW = cutout.width
        val cutH = cutout.height

        if (bgW <= cutW ||
            bgH < cutH ||
            cutW <= MIN_CUTOUT_DIMENSION ||
            cutH <= MIN_CUTOUT_DIMENSION ||
            bgW > PixelImage.MAX_DIMENSION ||
            bgH > PixelImage.MAX_DIMENSION ||
            bgW > PixelImage.MAX_PIXELS / bgH ||
            cutW > PixelImage.MAX_DIMENSION ||
            cutH > PixelImage.MAX_DIMENSION ||
            cutW > PixelImage.MAX_PIXELS / cutH
        ) {
            return null
        }

        // 1. Compute background grayscale luminance and gradient magnitude (|gx| + |gy|).
        val bgPixels = shade.argbPixels
        val bgGray = FloatArray(bgW * bgH) { i ->
            val px = bgPixels[i]
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }

        val bgGrad = FloatArray(bgW * bgH)
        for (y in 1 until (bgH - 1)) {
            val rowOffset = y * bgW
            for (x in 1 until (bgW - 1)) {
                val idx = rowOffset + x
                val gx = abs(bgGray[idx + 1] - bgGray[idx - 1])
                val gy = abs(bgGray[idx + bgW] - bgGray[idx - bgW])
                bgGrad[idx] = gx + gy
            }
        }

        // 2. Extract interior cutout alpha boundary pixels (alpha >= 128 adjacent to < 128).
        val cutPixels = cutout.argbPixels
        val sampleDx = IntArray(MAX_EDGE_SAMPLES)
        val sampleDy = IntArray(MAX_EDGE_SAMPLES)
        var sampleCount = 0

        for (y in 1 until (cutH - 1)) {
            val rowOffset = y * cutW
            for (x in 1 until (cutW - 1)) {
                val idx = rowOffset + x
                val alpha = (cutPixels[idx] ushr 24) and 0xFF
                if (alpha < ALPHA_THRESHOLD) continue

                val isBoundary = ((cutPixels[idx - 1] ushr 24) and 0xFF) < ALPHA_THRESHOLD ||
                    ((cutPixels[idx + 1] ushr 24) and 0xFF) < ALPHA_THRESHOLD ||
                    ((cutPixels[idx - cutW] ushr 24) and 0xFF) < ALPHA_THRESHOLD ||
                    ((cutPixels[idx + cutW] ushr 24) and 0xFF) < ALPHA_THRESHOLD

                if (isBoundary) {
                    if (sampleCount >= MAX_EDGE_SAMPLES) {
                        return null
                    }
                    sampleDx[sampleCount] = x
                    sampleDy[sampleCount] = y
                    sampleCount++
                }
            }
        }

        if (sampleCount == 0) {
            return null
        }

        // 3. Slide window across candidate positions and enforce bounded search budget.
        val minX = min(MIN_SCAN_X, max(0, bgW - cutW - 1))
        val maxX = bgW - cutW
        val maxY = bgH - cutH

        val candidates = (maxX - minX + 1L) * (maxY + 1L)
        if (candidates > MAX_SEARCH_BUDGET / sampleCount) {
            return null
        }

        val bestScoreByX = FloatArray(maxX + 1)
        var bestScore = -1f
        var bestX = minX

        for (y0 in 0..maxY) {
            if (Thread.currentThread().isInterrupted) {
                return null
            }
            val yBaseOffset = y0 * bgW
            for (x0 in minX..maxX) {
                var score = 0f
                for (i in 0 until sampleCount) {
                    val bgIdx = yBaseOffset + sampleDy[i] * bgW + x0 + sampleDx[i]
                    score += bgGrad[bgIdx]
                }
                if (score > bestScoreByX[x0]) {
                    bestScoreByX[x0] = score
                }
                if (score > bestScore) {
                    bestScore = score
                    bestX = x0
                }
            }
        }

        if (!(bestScore > 0f)) {
            return null
        }

        var alternative = 0f
        for (x in minX..maxX) {
            if (abs(x - bestX) > PEAK_EXCLUSION_RADIUS) {
                val candidate = bestScoreByX[x]
                if (candidate > alternative) {
                    alternative = candidate
                }
            }
        }

        if (!(bestScore > alternative * PEAK_UNIQUENESS_MARGIN)) {
            return null
        }
        return bestX
    }
}
