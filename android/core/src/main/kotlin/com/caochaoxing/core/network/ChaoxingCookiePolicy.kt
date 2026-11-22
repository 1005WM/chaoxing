// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.network

import com.caochaoxing.core.model.ChaoxingStoredCookie
import okhttp3.HttpUrl
import java.time.Instant
import java.util.Locale

/**
 * Strict domain, scheme, path, and redirect security policy for Chaoxing session cookies.
 */
object ChaoxingCookiePolicy {
    private const val CHAOXING_DOMAIN = "chaoxing.com"
    private const val FORBIDDEN_COOKIE_NAME_CHARS = "()<>@,;:\\\"/[]?={} \t\r\n"

    fun domain(value: String): String =
        value.lowercase(Locale.ROOT).trimStart('.')

    fun domainMatches(host: String, domain: String): Boolean {
        val normalizedHost = host.lowercase(Locale.ROOT)
        val normalizedDomain = domain(domain)
        if (normalizedHost.isEmpty() || normalizedDomain.isEmpty()) return false
        return normalizedHost == normalizedDomain || normalizedHost.endsWith(".$normalizedDomain")
    }

    fun isTrusted(url: HttpUrl): Boolean =
        isTrusted(url, allowLoopbackForTesting = false)

    internal fun isTrusted(url: HttpUrl, allowLoopbackForTesting: Boolean): Boolean {
        if (url.isHttps && domainMatches(url.host, CHAOXING_DOMAIN)) {
            return true
        }
        return allowLoopbackForTesting && url.scheme == "http" && isLoopbackHost(url.host)
    }

    internal fun isLoopbackHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
        return normalized == "127.0.0.1" ||
            normalized == "localhost" ||
            normalized == "::1" ||
            normalized == "0:0:0:0:0:0:0:1"
    }

    fun merging(
        existing: List<ChaoxingStoredCookie>,
        incoming: List<ChaoxingStoredCookie>,
        now: Instant = Instant.now(),
    ): List<ChaoxingStoredCookie> =
        merging(existing = existing, incoming = incoming, now = now, allowLoopbackForTesting = false)

    internal fun merging(
        existing: List<ChaoxingStoredCookie>,
        incoming: List<ChaoxingStoredCookie>,
        now: Instant = Instant.now(),
        allowLoopbackForTesting: Boolean,
    ): List<ChaoxingStoredCookie> {
        val map = LinkedHashMap<String, ChaoxingStoredCookie>()
        for (rawCookie in existing + incoming) {
            if (!isValidCookie(rawCookie, allowLoopbackForTesting)) continue
            val normalizedCookie = rawCookie.copy(domain = domain(rawCookie.domain))
            val key = cookieKey(normalizedCookie)
            val expiry = normalizedCookie.expiresAtInstant()
            if (normalizedCookie.expiresAt != null && expiry == null) {
                map.remove(key)
                continue
            }
            if (expiry != null && !expiry.isAfter(now)) {
                map.remove(key)
            } else {
                map[key] = normalizedCookie
            }
        }
        return map.values.sortedBy { cookieKey(it) }
    }

    fun buildCookieHeader(
        cookies: List<ChaoxingStoredCookie>,
        url: HttpUrl,
        now: Instant = Instant.now(),
    ): String? =
        buildCookieHeader(
            cookies = cookies,
            url = url,
            now = now,
            allowLoopbackForTesting = false,
        )

    internal fun buildCookieHeader(
        cookies: List<ChaoxingStoredCookie>,
        url: HttpUrl,
        now: Instant = Instant.now(),
        allowLoopbackForTesting: Boolean,
    ): String? {
        if (!isTrusted(url, allowLoopbackForTesting)) return null
        val host = url.host.lowercase(Locale.ROOT)
        val requestPath = url.encodedPath.ifEmpty { "/" }

        val matched = cookies.filter { cookie ->
            if (!isValidCookie(cookie, allowLoopbackForTesting)) return@filter false
            val cookieDomain = domain(cookie.domain)
            val domainMatchesRequest = if (cookie.hostOnly) {
                host == cookieDomain
            } else {
                domainMatches(host, cookieDomain)
            }
            if (!domainMatchesRequest) return@filter false
            if (!pathMatches(requestPath, cookie.path)) return@filter false

            val expiry = if (cookie.expiresAt != null) {
                cookie.expiresAtInstant() ?: return@filter false
            } else {
                null
            }
            if (expiry != null && !expiry.isAfter(now)) return@filter false

            !cookie.isSecure || url.isHttps
        }.sortedWith(
            compareByDescending<ChaoxingStoredCookie> { it.path.length }
                .thenBy { cookieKey(it) },
        )

        if (matched.isEmpty()) return null
        return matched.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun allowsRedirect(from: HttpUrl, to: HttpUrl, method: String): Boolean =
        allowsRedirect(from = from, to = to, method = method, allowLoopbackForTesting = false)

    internal fun allowsRedirect(
        from: HttpUrl,
        to: HttpUrl,
        method: String,
        allowLoopbackForTesting: Boolean,
    ): Boolean {
        val schemeAllowed = to.isHttps || (allowLoopbackForTesting && to.scheme == "http" && isLoopbackHost(to.host))
        if (!schemeAllowed) return false
        if (to.username.isNotEmpty() || to.password.isNotEmpty()) return false
        if (isTrusted(from, allowLoopbackForTesting) != isTrusted(to, allowLoopbackForTesting)) return false

        val sameOrigin = from.scheme == to.scheme &&
            from.host.equals(to.host, ignoreCase = true) &&
            from.port == to.port
        val normalizedMethod = method.uppercase(Locale.ROOT)
        return sameOrigin || normalizedMethod == "GET" || normalizedMethod == "HEAD"
    }

    private fun cookieKey(cookie: ChaoxingStoredCookie): String =
        "${cookie.name}\n${domain(cookie.domain)}\n${cookie.path}"

    private fun isValidCookie(
        cookie: ChaoxingStoredCookie,
        allowLoopbackForTesting: Boolean,
    ): Boolean {
        if (cookie.name.isEmpty()) return false
        if (cookie.name.any { it.code !in 0x21..0x7E || it in FORBIDDEN_COOKIE_NAME_CHARS }) {
            return false
        }
        if (cookie.value.any { it.isISOControl() || it == ';' }) {
            return false
        }
        if (!cookie.path.startsWith("/") || cookie.path.any { it.isISOControl() || it == ';' }) {
            return false
        }
        val normalizedDomain = domain(cookie.domain)
        if (normalizedDomain.isEmpty()) return false
        return domainMatches(normalizedDomain, CHAOXING_DOMAIN) ||
            (allowLoopbackForTesting && isLoopbackHost(normalizedDomain))
    }

    private fun pathMatches(requestPath: String, cookiePath: String): Boolean {
        if (requestPath == cookiePath) return true
        if (!requestPath.startsWith(cookiePath)) return false
        return cookiePath.endsWith("/") || requestPath[cookiePath.length] == '/'
    }
}
