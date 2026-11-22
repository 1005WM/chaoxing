// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.crypto

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Hand-written Protobuf wire decoder for Easemob roaming message payloads without runtime protobuf dependencies.
 */
object EasemobProtobufDecoder {
    data class DecodedMessage(
        val timestampMs: Long,
        val attachmentJSON: String?,
    )

    /**
     * Decodes an Easemob `Meta` protobuf payload, returning `null` if malformed or missing a message body.
     */
    fun decodeMetaMessage(bytes: ByteArray): DecodedMessage? = try {
        val reader = WireReader(bytes)
        var timestampMs = 0L
        var bodyData: ByteArray? = null
        while (true) {
            val tag = reader.readTag() ?: break
            val fieldNumber = tag.first
            val wireType = tag.second
            when {
                fieldNumber == 4 && wireType == WIRE_VARINT -> {
                    timestampMs = reader.readVarint()
                }
                fieldNumber == 6 && wireType == WIRE_LENGTH_DELIMITED -> {
                    bodyData = reader.readLengthDelimited()
                }
                else -> reader.skipField(wireType)
            }
        }
        val body = bodyData ?: return null
        DecodedMessage(
            timestampMs = timestampMs,
            attachmentJSON = decodeMessageBodyAttachment(body),
        )
    } catch (_: MalformedWireException) {
        null
    }

    private fun decodeMessageBodyAttachment(bytes: ByteArray): String? {
        val reader = WireReader(bytes)
        var attachment: String? = null
        while (true) {
            val tag = reader.readTag() ?: break
            val fieldNumber = tag.first
            val wireType = tag.second
            if (fieldNumber == 5 && wireType == WIRE_LENGTH_DELIMITED) {
                val (key, value) = decodeKeyValue(reader.readLengthDelimited())
                if (key == "attachment") {
                    attachment = value
                }
            } else {
                reader.skipField(wireType)
            }
        }
        return attachment
    }

    private fun decodeKeyValue(bytes: ByteArray): Pair<String?, String?> {
        val reader = WireReader(bytes)
        var key: String? = null
        var stringValue: String? = null
        while (true) {
            val tag = reader.readTag() ?: break
            val fieldNumber = tag.first
            val wireType = tag.second
            when {
                fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED -> {
                    key = decodeStrictUtf8OrNull(reader.readLengthDelimited())
                }
                fieldNumber == 6 && wireType == WIRE_LENGTH_DELIMITED -> {
                    stringValue = decodeStrictUtf8OrNull(reader.readLengthDelimited())
                }
                else -> reader.skipField(wireType)
            }
        }
        return key to stringValue
    }

    private fun decodeStrictUtf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private class MalformedWireException : Exception()

    private class WireReader(private val bytes: ByteArray) {
        private var offset: Int = 0

        fun readTag(): Pair<Int, Int>? {
            if (offset >= bytes.size) return null
            val raw = readVarint()
            val field = raw ushr 3
            val wire = (raw and 0x07L).toInt()
            if (field <= 0L || field > MAX_FIELD_NUMBER || !isValidWireType(wire)) {
                throw MalformedWireException()
            }
            return field.toInt() to wire
        }

        fun readVarint(): Long {
            var result = 0L
            for (index in 0 until 10) {
                if (offset >= bytes.size) throw MalformedWireException()
                val byte = bytes[offset].toInt() and 0xFF
                offset += 1
                // An unsigned 64-bit varint has at most 1 payload bit in its 10th byte.
                if (index == 9 && byte > 1) throw MalformedWireException()
                result = result or ((byte and 0x7F).toLong() shl (index * 7))
                if ((byte and 0x80) == 0) return result
            }
            throw MalformedWireException()
        }

        fun readLengthDelimited(): ByteArray {
            val length = readVarint()
            val remaining = (bytes.size - offset).toLong()
            // Reject negative bit-63 lengths (> Long.MAX_VALUE unsigned) before casting to Int.
            if (length < 0L || length > remaining) throw MalformedWireException()
            val size = length.toInt()
            val end = offset + size
            val slice = bytes.copyOfRange(offset, end)
            offset = end
            return slice
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_LENGTH_DELIMITED -> readLengthDelimited()
                WIRE_FIXED64, WIRE_FIXED32 -> {
                    val count = if (wireType == WIRE_FIXED64) 8 else 4
                    if (count > bytes.size - offset) throw MalformedWireException()
                    offset += count
                }
                else -> throw MalformedWireException()
            }
        }

        private fun isValidWireType(wire: Int): Boolean =
            wire == WIRE_VARINT || wire == WIRE_FIXED64 || wire == WIRE_LENGTH_DELIMITED || wire == WIRE_FIXED32
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5
    private const val MAX_FIELD_NUMBER = 536_870_911L
}
