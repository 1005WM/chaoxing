// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure FOSS QR decoder built on ZXing (`com.google.zxing:core`).
 */
object QrCodeDecoder {
    private const val MAX_DECODE_DIMENSION = 2048

    private val qrDecodeHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    fun decodeFromBytes(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()

        var decodedBitmap: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return emptyList()
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = AndroidImageObfuscator.calculateInSampleSize(
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    maxDimension = MAX_DECODE_DIMENSION,
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decodedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return emptyList()

            listOfNotNull(decodeBitmap(decodedBitmap))
        } catch (_: Exception) {
            emptyList()
        } catch (_: OutOfMemoryError) {
            emptyList()
        } finally {
            if (decodedBitmap != null && !decodedBitmap.isRecycled) {
                decodedBitmap.recycle()
            }
        }
    }

    fun decodeBitmap(bitmap: Bitmap): String? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }

        var workingBitmap = bitmap
        var softwareCopy: Bitmap? = null
        var scaledBitmap: Bitmap? = null

        return try {
            if (workingBitmap.config == Bitmap.Config.HARDWARE) {
                softwareCopy = workingBitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                workingBitmap = softwareCopy
            }
            val maxDim = maxOf(workingBitmap.width, workingBitmap.height)
            if (maxDim > MAX_DECODE_DIMENSION) {
                val scale = MAX_DECODE_DIMENSION.toFloat() / maxDim.toFloat()
                val targetW = maxOf(1, (workingBitmap.width * scale).toInt())
                val targetH = maxOf(1, (workingBitmap.height * scale).toInt())
                scaledBitmap = Bitmap.createScaledBitmap(workingBitmap, targetW, targetH, true)
                workingBitmap = scaledBitmap
            }

            val width = workingBitmap.width
            val height = workingBitmap.height
            val pixels = IntArray(width * height)
            workingBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            decodeLuminanceSource(RGBLuminanceSource(width, height, pixels))
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            if (scaledBitmap != null && scaledBitmap !== softwareCopy && scaledBitmap !== bitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
            if (softwareCopy != null && softwareCopy !== bitmap && !softwareCopy.isRecycled) {
                softwareCopy.recycle()
            }
        }
    }

    internal fun decodeLuminanceSource(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply {
            setHints(qrDecodeHints)
        }
        val invertedSource = source.invert()
        val candidates = arrayOf(
            BinaryBitmap(HybridBinarizer(source)),
            BinaryBitmap(GlobalHistogramBinarizer(source)),
            BinaryBitmap(HybridBinarizer(invertedSource)),
            BinaryBitmap(GlobalHistogramBinarizer(invertedSource)),
        )
        for (binaryBitmap in candidates) {
            try {
                val result = reader.decodeWithState(binaryBitmap)
                val text = result?.text?.takeIf { it.isNotBlank() }
                if (text != null) {
                    return text
                }
            } catch (_: ReaderException) {
                // Continue across binarizers and inverted polarity.
            } finally {
                reader.reset()
            }
        }
        return null
    }

    internal fun extractLuminanceBytes(
        planeBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray? {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null
        val totalOutputBytesLong = width.toLong() * height.toLong()
        if (totalOutputBytesLong > Int.MAX_VALUE) return null

        val readBuf = planeBuffer.asReadOnlyBuffer().apply { rewind() }
        val limit = readBuf.limit()
        val lastRequiredIndex =
            (height - 1).toLong() * rowStride.toLong() + (width - 1).toLong() * pixelStride.toLong()
        if (lastRequiredIndex >= limit.toLong()) return null

        val yBytes = ByteArray(totalOutputBytesLong.toInt())
        if (pixelStride == 1 && rowStride == width) {
            readBuf.get(yBytes, 0, yBytes.size)
            return yBytes
        }

        if (pixelStride == 1) {
            for (row in 0 until height) {
                readBuf.position(row * rowStride)
                readBuf.get(yBytes, row * width, width)
            }
            return yBytes
        }

        for (row in 0 until height) {
            val rowStart = row * rowStride
            val dstRowStart = row * width
            for (col in 0 until width) {
                yBytes[dstRowStart + col] = readBuf.get(rowStart + col * pixelStride)
            }
        }
        return yBytes
    }
}

/**
 * Throttled CameraX [ImageAnalysis.Analyzer] decoding `YUV_420_888` luminance frames into QR codes.
 */
class QrCodeAnalyzer(
    private val onQrDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val lastAnalyzedAtMs = AtomicLong(0L)

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val nowMs = SystemClock.elapsedRealtime()
            val prevMs = lastAnalyzedAtMs.get()
            if (prevMs != 0L && nowMs - prevMs < MIN_ANALYSIS_INTERVAL_MS) {
                return
            }
            if (imageProxy.format != ImageFormat.YUV_420_888 || imageProxy.planes.isEmpty()) {
                return
            }
            val width = imageProxy.width
            val height = imageProxy.height
            if (width <= 0 || height <= 0) {
                return
            }
            lastAnalyzedAtMs.set(nowMs)

            val yPlane = imageProxy.planes[0]
            val yBytes = QrCodeDecoder.extractLuminanceBytes(
                planeBuffer = yPlane.buffer,
                width = width,
                height = height,
                rowStride = yPlane.rowStride,
                pixelStride = yPlane.pixelStride,
            ) ?: return

            val luminanceSource = PlanarYUVLuminanceSource(
                yBytes,
                width,
                height,
                0,
                0,
                width,
                height,
                false,
            )
            val decoded = QrCodeDecoder.decodeLuminanceSource(luminanceSource) ?: return
            onQrDetected(decoded)
        } catch (_: Throwable) {
            // Ignore per-frame decode anomalies; valid detections are forwarded via callback.
        } finally {
            imageProxy.close()
        }
    }

    companion object {
        const val MIN_ANALYSIS_INTERVAL_MS: Long = 250L
    }
}
