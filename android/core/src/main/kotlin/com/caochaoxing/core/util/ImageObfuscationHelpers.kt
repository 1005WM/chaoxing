// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.util

import java.util.UUID

/**
 * Pure JVM byte-level helpers for anti-duplication JPEG obfuscation.
 */
object ImageObfuscationHelpers {
    private const val JPEG_MARKER_PREFIX: Byte = 0xFF.toByte()
    private const val JPEG_SOI: Byte = 0xD8.toByte()
    private const val JPEG_EOI: Byte = 0xD9.toByte()
    private const val JPEG_COM: Byte = 0xFE.toByte()

    /**
     * Inserts a valid JPEG `COM` (`FF FE`) marker carrying a random UUID payload right before
     * the trailing `FF D9` EOI marker so `FF D9` stays at EOF.
     *
     * If [jpegBytes] does not have both a leading `FF D8` SOI and trailing `FF D9` EOI,
     * the random payload bytes are safely appended at the end of the array.
     */
    fun appendRandomJpegComment(jpegBytes: ByteArray): ByteArray {
        val commentBytes = UUID.randomUUID().toString().toByteArray(Charsets.US_ASCII)
        val hasValidJpegBounds = jpegBytes.size >= 4 &&
            jpegBytes[0] == JPEG_MARKER_PREFIX &&
            jpegBytes[1] == JPEG_SOI &&
            jpegBytes[jpegBytes.size - 2] == JPEG_MARKER_PREFIX &&
            jpegBytes[jpegBytes.size - 1] == JPEG_EOI

        if (!hasValidJpegBounds) {
            val fallback = ByteArray(jpegBytes.size + commentBytes.size)
            jpegBytes.copyInto(fallback, destinationOffset = 0)
            commentBytes.copyInto(fallback, destinationOffset = jpegBytes.size)
            return fallback
        }

        // JPEG COM segment length field includes the 2-byte length itself.
        val segmentLength = 2 + commentBytes.size
        val eoiIndex = jpegBytes.size - 2
        val output = ByteArray(jpegBytes.size + 4 + commentBytes.size)

        jpegBytes.copyInto(output, destinationOffset = 0, startIndex = 0, endIndex = eoiIndex)
        output[eoiIndex] = JPEG_MARKER_PREFIX
        output[eoiIndex + 1] = JPEG_COM
        output[eoiIndex + 2] = ((segmentLength ushr 8) and 0xFF).toByte()
        output[eoiIndex + 3] = (segmentLength and 0xFF).toByte()
        commentBytes.copyInto(output, destinationOffset = eoiIndex + 4)
        output[output.size - 2] = JPEG_MARKER_PREFIX
        output[output.size - 1] = JPEG_EOI
        return output
    }
}
