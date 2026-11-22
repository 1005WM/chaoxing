// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.caochaoxing.core.util.ImageObfuscationHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Bitmap-level JPEG anti-duplication obfuscator:
 * normalizes EXIF orientation, downsamples to bounded dimensions (`<= 4096`), applies random
 * `0..2px` edge cropping, `±2px` output dimension resize, sparse `±2` global + `±1` per-sample
 * RGB jitter (preserving alpha), and re-encodes an EXIF-free JPEG at quality `82..90`.
 */
object AndroidImageObfuscator {
    internal const val MAX_DIMENSION = 4096
    private const val MIN_VALID_DIMENSION = 16
    private const val MIN_OUTPUT_DIMENSION = 8
    private const val TARGET_PIXEL_SAMPLES = 4096L
    private const val MIN_JPEG_QUALITY = 82
    private const val MAX_JPEG_QUALITY_EXCLUSIVE = 91

    fun obfuscateJpeg(rawBytes: ByteArray): ByteArray {
        if (rawBytes.isEmpty()) {
            return ImageObfuscationHelpers.appendRandomJpegComment(rawBytes)
        }

        val orientation = readExifOrientation(rawBytes)
        var decodedBitmap: Bitmap? = null
        var orientedBitmap: Bitmap? = null
        var outputBitmap: Bitmap? = null

        try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)
            val rawWidth = boundsOptions.outWidth
            val rawHeight = boundsOptions.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) {
                return ImageObfuscationHelpers.appendRandomJpegComment(rawBytes)
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(rawWidth, rawHeight, MAX_DIMENSION)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            }
            decodedBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                ?: return ImageObfuscationHelpers.appendRandomJpegComment(rawBytes)

            val transformMatrix = matrixForExifOrientation(orientation)
            orientedBitmap = if (!transformMatrix.isIdentity) {
                Bitmap.createBitmap(
                    decodedBitmap,
                    0,
                    0,
                    decodedBitmap.width,
                    decodedBitmap.height,
                    transformMatrix,
                    true,
                )
            } else {
                decodedBitmap
            }

            if (orientedBitmap.width <= MIN_VALID_DIMENSION || orientedBitmap.height <= MIN_VALID_DIMENSION) {
                return ImageObfuscationHelpers.appendRandomJpegComment(rawBytes)
            }

            val random = Random.Default
            val cropLeft = random.nextInt(0, 3)
            val cropTop = random.nextInt(0, 3)
            val cropRight = random.nextInt(0, 3)
            val cropBottom = random.nextInt(0, 3)

            val srcWidth = (orientedBitmap.width - cropLeft - cropRight)
                .coerceIn(MIN_OUTPUT_DIMENSION, orientedBitmap.width - cropLeft)
            val srcHeight = (orientedBitmap.height - cropTop - cropBottom)
                .coerceIn(MIN_OUTPUT_DIMENSION, orientedBitmap.height - cropTop)

            var outWidth = maxOf(MIN_OUTPUT_DIMENSION, srcWidth + random.nextInt(-2, 3))
            var outHeight = maxOf(MIN_OUTPUT_DIMENSION, srcHeight + random.nextInt(-2, 3))
            val outMax = maxOf(outWidth, outHeight)
            if (outMax > MAX_DIMENSION) {
                val scale = MAX_DIMENSION.toDouble() / outMax.toDouble()
                outWidth = maxOf(MIN_OUTPUT_DIMENSION, (outWidth * scale).toInt())
                outHeight = maxOf(MIN_OUTPUT_DIMENSION, (outHeight * scale).toInt())
            }

            outputBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outputBitmap)
            val srcRect = Rect(cropLeft, cropTop, cropLeft + srcWidth, cropTop + srcHeight)
            val dstRect = Rect(0, 0, outWidth, outHeight)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            canvas.drawBitmap(orientedBitmap, srcRect, dstRect, paint)

            applySparsePixelPerturbation(outputBitmap, random)

            val quality = random.nextInt(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY_EXCLUSIVE)
            val outStream = ByteArrayOutputStream(rawBytes.size.coerceAtLeast(4096))
            val compressed = outputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outStream)
            val result = outStream.toByteArray()
            if (!compressed || result.isEmpty()) {
                return ImageObfuscationHelpers.appendRandomJpegComment(rawBytes)
            }
            return result
        } catch (_: Exception) {
            return ImageObfuscationHelpers.appendRandomJpegComment(rawBytes)
        } catch (_: OutOfMemoryError) {
            return ImageObfuscationHelpers.appendRandomJpegComment(rawBytes)
        } finally {
            if (orientedBitmap != null && orientedBitmap !== decodedBitmap && !orientedBitmap.isRecycled) {
                orientedBitmap.recycle()
            }
            if (decodedBitmap != null && !decodedBitmap.isRecycled) {
                decodedBitmap.recycle()
            }
            if (outputBitmap != null && !outputBitmap.isRecycled) {
                outputBitmap.recycle()
            }
        }
    }

    internal fun calculateInSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int = MAX_DIMENSION,
    ): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var inSampleSize = 1
        val largest = maxOf(width, height)
        while (largest / inSampleSize > maxDimension) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    internal fun perturbArgbPixel(
        argb: Int,
        rBias: Int,
        gBias: Int,
        bBias: Int,
        rJitter: Int,
        gJitter: Int,
        bJitter: Int,
    ): Int {
        val alpha = (argb ushr 24) and 0xFF
        val red = (((argb ushr 16) and 0xFF) + rBias + rJitter).coerceIn(0, 255)
        val green = (((argb ushr 8) and 0xFF) + gBias + gJitter).coerceIn(0, 255)
        val blue = ((argb and 0xFF) + bBias + bJitter).coerceIn(0, 255)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun applySparsePixelPerturbation(bitmap: Bitmap, random: Random) {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width.toLong() * height.toLong()
        if (totalPixels <= 0L) return

        val rBias = random.nextInt(-2, 3)
        val gBias = random.nextInt(-2, 3)
        val bBias = random.nextInt(-2, 3)
        val stride = maxOf(1L, totalPixels / TARGET_PIXEL_SAMPLES)

        var pixelIndex = 0L
        while (pixelIndex < totalPixels) {
            val x = (pixelIndex % width).toInt()
            val y = (pixelIndex / width).toInt()
            val argb = bitmap.getPixel(x, y)
            val perturbed = perturbArgbPixel(
                argb = argb,
                rBias = rBias,
                gBias = gBias,
                bBias = bBias,
                rJitter = random.nextInt(-1, 2),
                gJitter = random.nextInt(-1, 2),
                bJitter = random.nextInt(-1, 2),
            )
            bitmap.setPixel(x, y, perturbed)
            pixelIndex += stride
        }
    }

    private fun readExifOrientation(rawBytes: ByteArray): Int = try {
        ByteArrayInputStream(rawBytes).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun matrixForExifOrientation(orientation: Int): Matrix {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> Unit
        }
        return matrix
    }
}
