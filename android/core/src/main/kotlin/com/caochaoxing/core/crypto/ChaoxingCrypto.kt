// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.crypto

import com.caochaoxing.core.model.ChaoxingError
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol cryptographic primitives used by Chaoxing login, IM password decoding, and device identity.
 */
object ChaoxingCrypto {
    const val TRANSFER_KEY: String = "u2oh6Vu^HWe4_AES"
    const val IM_DES_KEY: String = "SL2(M/eD"

    private const val AES_KEY_SIZE_BYTES = 16
    private const val DES_KEY_SIZE_BYTES = 8
    private const val DES_BLOCK_SIZE_BYTES = 8
    private const val MIN_HEX_DES_LENGTH = 16

    /**
     * Encrypts a login credential field using AES-128-CBC/PKCS5Padding with [key] used as both key and IV.
     */
    fun encryptLoginField(message: String, key: String = TRANSFER_KEY): String {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        if (keyBytes.size != AES_KEY_SIZE_BYTES) {
            throw ChaoxingError.InvalidInput("AES 协议密钥必须为 16 字节")
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val iv = IvParameterSpec(keyBytes)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv)
            val encrypted = cipher.doFinal(message.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(encrypted)
        } catch (e: GeneralSecurityException) {
            throw ChaoxingError.ParseFailed("AES 加密失败: ${e.message ?: "unknown"}")
        }
    }

    /**
     * Decrypts a hex-encoded DES/ECB/PKCS5Padding IM password, returning [hexOrPlain] unchanged on plain/invalid inputs.
     */
    fun decryptIMPasswordDES(hexOrPlain: String, key: String = IM_DES_KEY): String {
        if (hexOrPlain.length < MIN_HEX_DES_LENGTH) return hexOrPlain
        val cipherBytes = decodeHexOrNull(hexOrPlain) ?: return hexOrPlain
        if (cipherBytes.size % DES_BLOCK_SIZE_BYTES != 0) return hexOrPlain
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        if (keyBytes.size != DES_KEY_SIZE_BYTES) return hexOrPlain

        return try {
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
            val decrypted = cipher.doFinal(cipherBytes)
            decodeStrictUtf8OrNull(decrypted) ?: hexOrPlain
        } catch (_: GeneralSecurityException) {
            hexOrPlain
        }
    }

    /**
     * Computes a 32-character lowercase hexadecimal MD5 digest of the UTF-8 representation of [value].
     */
    fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val unsigned = byte.toInt() and 0xFF
                append(HEX_DIGITS[unsigned ushr 4])
                append(HEX_DIGITS[unsigned and 0x0F])
            }
        }
    }

    /**
     * Generates a synthetic 64-byte device code formed by concatenating two identical SHA-256 digests, Base64-encoded.
     */
    fun randomizedDeviceCode(): String {
        val seed = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val doubled = ByteArray(digest.size * 2)
        digest.copyInto(doubled, destinationOffset = 0)
        digest.copyInto(doubled, destinationOffset = digest.size)
        return Base64.getEncoder().encodeToString(doubled)
    }

    /**
     * Encodes Base64 characters (`+`, `/`, `=`, and spaces) for `application/x-www-form-urlencoded` payloads.
     */
    fun urlFormEncodeBase64(base64: String): String =
        base64
            .replace("+", "%2B")
            .replace("/", "%2F")
            .replace("=", "%3D")
            .replace(" ", "%2B")

    private fun decodeHexOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexNibble(hex[i * 2])
            val lo = hexNibble(hex[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexNibble(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
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

    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    )
}
