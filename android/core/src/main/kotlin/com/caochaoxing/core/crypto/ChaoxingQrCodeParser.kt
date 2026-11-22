// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.crypto

import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingQrCodeParseResult
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Parses `SIGNIN:...` payloads and HTTP(S) check-in QR URLs into [ChaoxingQrCodeParseResult].
 */
object ChaoxingQrCodeParser {
    private const val SIGNIN_PREFIX = "SIGNIN:"
    private val ALLOWED_SCHEMES = setOf("http", "https")
    private val TARGET_ID_KEYS = setOf("id", "aid", "activePrimaryId")
    private val CODE_KEYS = setOf("c", "code")

    /**
     * Parses [rawValue] into a [ChaoxingQrCodeParseResult], throwing [ChaoxingError.QrCodeParseFailed] on invalid or conflicting inputs.
     */
    fun parse(rawValue: String): ChaoxingQrCodeParseResult {
        val trimmed = rawValue.trim { it.isWhitespace() }
        if (trimmed.isEmpty()) {
            throw ChaoxingError.QrCodeParseFailed("二维码内容为空")
        }

        if (trimmed.startsWith(SIGNIN_PREFIX)) {
            val content = trimmed.removePrefix(SIGNIN_PREFIX).substringBefore("-")
            val uri = parseUriOrNull("https://qr.invalid/?$content")
                ?: throw ChaoxingError.QrCodeParseFailed("无效的 SIGNIN 二维码格式")
            val queryItems = parseQueryItems(uri.rawQuery)
                ?: throw ChaoxingError.QrCodeParseFailed("无效的 SIGNIN 二维码格式")
            return buildResultFromQueryItems(queryItems)
        }

        val uri = parseUriOrNull(trimmed)
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        if (uri == null || scheme !in ALLOWED_SCHEMES || uri.host.isNullOrEmpty()) {
            throw ChaoxingError.QrCodeParseFailed("无法解析二维码链接")
        }
        val queryItems = parseQueryItems(uri.rawQuery)
            ?: throw ChaoxingError.QrCodeParseFailed("无法解析二维码链接")
        return buildResultFromQueryItems(queryItems)
    }

    private fun buildResultFromQueryItems(items: List<Pair<String, String?>>): ChaoxingQrCodeParseResult {
        val encValues = items.filter { it.first == "enc" }.mapNotNull { it.second }
        val enc = encValues.firstOrNull()
        if (enc == null || enc.trim { it.isWhitespace() }.isEmpty() || encValues.toSet().size != 1) {
            throw ChaoxingError.QrCodeParseFailed("二维码缺少或包含冲突的 enc 参数")
        }

        val targets = items.filter { it.first in TARGET_ID_KEYS }
        val ids = targets.mapNotNull { it.second?.toLongOrNull() }.filter { it > 0L }
        if ((targets.isNotEmpty() && ids.isEmpty()) || ids.toSet().size > 1) {
            throw ChaoxingError.QrCodeParseFailed("二维码活动 ID 无效或相互冲突")
        }

        val rawCode = items.firstOrNull { it.first.lowercase(Locale.ROOT) in CODE_KEYS }?.second
        val code = rawCode?.takeIf { it.isNotEmpty() }
        return ChaoxingQrCodeParseResult(
            aid = ids.firstOrNull(),
            code = code,
            enc = enc,
        )
    }

    private fun parseUriOrNull(value: String): URI? = try {
        URI(value)
    } catch (_: URISyntaxException) {
        null
    }

    private fun parseQueryItems(rawQuery: String?): List<Pair<String, String?>>? {
        if (rawQuery.isNullOrEmpty()) return emptyList()
        val pairs = mutableListOf<Pair<String, String?>>()
        for (segment in rawQuery.split('&')) {
            val eqIndex = segment.indexOf('=')
            if (eqIndex < 0) {
                val name = percentDecodeUtf8OrNull(segment) ?: return null
                pairs.add(name to null)
            } else {
                val name = percentDecodeUtf8OrNull(segment.substring(0, eqIndex)) ?: return null
                val value = percentDecodeUtf8OrNull(segment.substring(eqIndex + 1)) ?: return null
                pairs.add(name to value)
            }
        }
        return pairs
    }

    /**
     * Percent-decodes an RFC 3986 query component as strict UTF-8 without rewriting `+` into spaces.
     */
    private fun percentDecodeUtf8OrNull(input: String): String? {
        if ('%' !in input) return input
        val out = StringBuilder(input.length)
        val byteBuffer = ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '%') {
                if (i + 2 >= input.length) return null
                val hi = hexValue(input[i + 1])
                val lo = hexValue(input[i + 2])
                if (hi < 0 || lo < 0) return null
                byteBuffer.write((hi shl 4) or lo)
                i += 3
            } else {
                if (byteBuffer.size() > 0) {
                    val decoded = decodeStrictUtf8OrNull(byteBuffer.toByteArray()) ?: return null
                    out.append(decoded)
                    byteBuffer.reset()
                }
                out.append(ch)
                i += 1
            }
        }
        if (byteBuffer.size() > 0) {
            val decoded = decodeStrictUtf8OrNull(byteBuffer.toByteArray()) ?: return null
            out.append(decoded)
        }
        return out.toString()
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

    private fun hexValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }
}
