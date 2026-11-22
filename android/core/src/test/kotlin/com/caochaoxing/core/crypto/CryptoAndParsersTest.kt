// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.crypto

import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingQrCodeParseResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class CryptoAndParsersTest {
    @Test
    fun `aes login encryption matches standard PKCS5 CBC vector`() {
        val cipher = ChaoxingCrypto.encryptLoginField("13800138000")
        assertTrue(cipher.isNotEmpty())
        // AES block size 16, Base64 length is multiple of 4
        assertEquals(0, cipher.length % 4)

        assertEquals(
            "l4yAQPAzbLzfFxr9mqXAJ/2nEzt/bhpxJkywgDme93Y=",
            ChaoxingCrypto.encryptLoginField("test-account-001"),
        )
        assertEquals(
            "GaDwrMeYCYfXqmR728+wOWUmjioVVFmq+BzxT7Px6R0=",
            ChaoxingCrypto.encryptLoginField("学习用-password"),
        )
    }

    @Test
    fun `invalid aes key length throws InvalidInput instead of truncating`() {
        for (badKey in listOf("", "x", "abcdefghijklmnopq", "中文密钥")) {
            assertThrows(ChaoxingError.InvalidInput::class.java) {
                ChaoxingCrypto.encryptLoginField("synthetic", key = badKey)
            }
        }
    }

    @Test
    fun `im password decrypts independent DES vector and preserves invalid or plain input`() {
        val ciphertext = "f3174b9efad13cd1f6d76ffc946082f35290812e720226f5"
        assertEquals("Synthetic-IM-password", ChaoxingCrypto.decryptIMPasswordDES(ciphertext))
        assertEquals("plain-synthetic-password", ChaoxingCrypto.decryptIMPasswordDES("plain-synthetic-password"))
        assertEquals(ciphertext, ChaoxingCrypto.decryptIMPasswordDES(ciphertext, key = "x"))
        assertEquals("not-hex-01234567", ChaoxingCrypto.decryptIMPasswordDES("not-hex-01234567"))
        assertEquals("00112233445566", ChaoxingCrypto.decryptIMPasswordDES("00112233445566"))
    }

    @Test
    fun `md5Hex and urlFormEncodeBase64 match protocol expectations`() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", ChaoxingCrypto.md5Hex("hello"))
        assertEquals("a%2Bb%2Fc%3D%2Bd", ChaoxingCrypto.urlFormEncodeBase64("a+b/c= d"))
    }

    @Test
    fun `randomized device code decodes to 64 repeated sha256 bytes`() {
        val code = ChaoxingCrypto.randomizedDeviceCode()
        val raw = Base64.getDecoder().decode(code)
        assertEquals(64, raw.size)
        assertTrue(raw.copyOfRange(0, 32).contentEquals(raw.copyOfRange(32, 64)))
    }

    @Test
    fun `qr parser handles SIGNIN and HTTPS formats`() {
        val r1: ChaoxingQrCodeParseResult = ChaoxingQrCodeParser.parse("SIGNIN:aid=1001&enc=abc123&c=999-extra")
        assertEquals(1001L, r1.aid)
        assertEquals("abc123", r1.enc)
        assertEquals("999", r1.code)

        val r2 = ChaoxingQrCodeParser.parse("https://mobilelearn.chaoxing.com/widget/sign/e?id=2002&enc=def456")
        assertEquals(2002L, r2.aid)
        assertEquals("def456", r2.enc)
        assertNull(r2.code)

        val r3 = ChaoxingQrCodeParser.parse("SIGNIN:aid=123456&source=15&Code=abc999&enc=deadbeef1234-extra")
        assertEquals(123456L, r3.aid)
        assertEquals("abc999", r3.code)
        assertEquals("deadbeef1234", r3.enc)
    }

    @Test
    fun `qr parser rejects ambiguous targets and malformed inputs`() {
        val fallback = ChaoxingQrCodeParser.parse("https://example.invalid/?id=oops&aid=999&enc=a+b")
        assertEquals(999L, fallback.aid)
        assertEquals("a+b", fallback.enc)

        val missingAid = ChaoxingQrCodeParser.parse("https://example.invalid/?activePrimaryId=42&enc=cafe")
        assertEquals(42L, missingAid.aid)

        val noAid = ChaoxingQrCodeParser.parse("https://example.invalid/?enc=cafe")
        assertNull(noAid.aid)

        val duplicateSameAid = ChaoxingQrCodeParser.parse("https://example.invalid/?id=101&aid=101&enc=abc")
        assertEquals(101L, duplicateSameAid.aid)

        val invalidInputs = listOf(
            "",
            "   ",
            "ftp://example.invalid/?id=101&enc=abc",
            "https:///nohost?id=101&enc=abc",
            "https://example.invalid/?id=101&aid=999&enc=abc",
            "https://example.invalid/?id=-1&enc=abc",
            "https://example.invalid/?id=101&enc=%20",
            "https://example.invalid/?id=101&enc=a&enc=b",
            "SIGNIN:aid=101&enc=%ZZ",
        )
        for (bad in invalidInputs) {
            assertThrows(ChaoxingError.QrCodeParseFailed::class.java) {
                ChaoxingQrCodeParser.parse(bad)
            }
        }
    }

    @Test
    fun `easemob protobuf decoder extracts timestamp and attachment json`() {
        val keyBytes = "attachment".toByteArray(Charsets.UTF_8)
        val valBytes = """{"aid":1}""".toByteArray(Charsets.UTF_8)

        val kv = ByteArrayOutputStream().apply {
            write(0x0A) // field 1, length-delimited
            write(keyBytes.size)
            write(keyBytes)
            write(0x32) // field 6, length-delimited
            write(valBytes.size)
            write(valBytes)
        }.toByteArray()

        val msgBody = ByteArrayOutputStream().apply {
            // Skip unknown fixed32 field (field 2, wire 5) before field 5
            write(0x15)
            write(byteArrayOf(1, 2, 3, 4))
            write(0x2A) // field 5, length-delimited
            write(kv.size)
            write(kv)
        }.toByteArray()

        val meta = ByteArrayOutputStream().apply {
            write(0x20) // field 4, varint = 1000
            write(0xE8)
            write(0x07)
            write(0x32) // field 6, length-delimited
            write(msgBody.size)
            write(msgBody)
        }.toByteArray()

        val decoded = EasemobProtobufDecoder.decodeMetaMessage(meta)
        assertNotNull(decoded)
        assertEquals(1000L, decoded!!.timestampMs)
        assertEquals("""{"aid":1}""", decoded.attachmentJSON)
    }

    @Test
    fun `easemob protobuf decoder enforces varint and length bounds`() {
        // Field 6 length UInt64.max (10 bytes, last byte 0x01) exceeds remaining bytes
        val overflowLength = byteArrayOf(0x32) + ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x01)
        assertNull(EasemobProtobufDecoder.decodeMetaMessage(overflowLength))

        // Field 6 empty body followed by truncated varint, zero tag, or overflowing 10th varint byte
        val badTails = listOf(
            byteArrayOf(0x20, 0x80.toByte()),
            byteArrayOf(0x00),
            byteArrayOf(0x20) + ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x02),
        )
        for (tail in badTails) {
            assertNull(EasemobProtobufDecoder.decodeMetaMessage(byteArrayOf(0x32, 0x00) + tail))
        }

        val emptyBody = EasemobProtobufDecoder.decodeMetaMessage(byteArrayOf(0x32, 0x00))
        assertNotNull(emptyBody)
        assertEquals(0L, emptyBody!!.timestampMs)
        assertNull(emptyBody.attachmentJSON)

        // Missing body field 6
        assertNull(EasemobProtobufDecoder.decodeMetaMessage(byteArrayOf(0x20, 0x01)))
    }
}
