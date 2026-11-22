// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.network

import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingClientPreset
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingModelDefaults
import com.caochaoxing.core.model.ChaoxingStoredCookie
import com.caochaoxing.core.model.ChaoxingUserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Buffered HTTP response returned by [ChaoxingHttpClient] calls after manual redirect evaluation.
 */
data class HttpResult(
    val statusCode: Int,
    val body: ByteArray,
    val headers: Headers,
    val finalUrl: String,
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299

    fun bodyAsString(): String = body.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResult) return false
        return statusCode == other.statusCode &&
            body.contentEquals(other.body) &&
            headers == other.headers &&
            finalUrl == other.finalUrl
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + body.contentHashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + finalUrl.hashCode()
        return result
    }
}

/**
 * Thread-safe HTTP client for Chaoxing sessions with strict cookie scoping, manual safe redirects,
 * generation-based stale response rejection, and single-flight reauthentication coalescing.
 */
class ChaoxingHttpClient private constructor(
    session: ChaoxingAccountSession,
    userAgent: String,
    sharedBaseClient: OkHttpClient,
    private val allowLoopbackHttpForTesting: Boolean,
) {
    constructor(
        session: ChaoxingAccountSession,
        userAgent: String = ChaoxingClientPreset.DEFAULT.userAgent,
        sharedBaseClient: OkHttpClient = defaultOkHttpClient(),
    ) : this(
        session = session,
        userAgent = userAgent,
        sharedBaseClient = sharedBaseClient,
        allowLoopbackHttpForTesting = false,
    )

    internal data class AuthenticationContext(
        val session: ChaoxingAccountSession,
        val generation: Long,
    )

    private class RefreshState(
        val id: Long,
        val job: Job,
        val waiters: MutableMap<Long, CompletableDeferred<ChaoxingAccountSession>> = LinkedHashMap(),
    )

    private class RawResponse(
        val statusCode: Int,
        val body: ByteArray,
        val headers: Headers,
        val url: HttpUrl,
    )

    private val lock = Any()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextWaiterId = AtomicLong(0L)
    private val nextRefreshId = AtomicLong(0L)
    private val activeCalls = HashSet<Call>()

    private val httpClient: OkHttpClient = sharedBaseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    private var _session: ChaoxingAccountSession
    private var _userAgent: String = userAgent
    private var _storageCloudToken: String? = null
    private var cookies: List<ChaoxingStoredCookie>
    private var generation: Long = 0L
    private var isInvalidated: Boolean = false
    private var refreshState: RefreshState? = null

    init {
        val merged = ChaoxingCookiePolicy.merging(
            existing = emptyList(),
            incoming = session.cookies,
            allowLoopbackForTesting = allowLoopbackHttpForTesting,
        )
        cookies = merged
        _session = session.copy(cookies = merged)
    }

    val session: ChaoxingAccountSession
        get() = synchronized(lock) { _session }

    var userAgent: String
        get() = synchronized(lock) { _userAgent }
        set(value) {
            synchronized(lock) { _userAgent = value }
        }

    var storageCloudToken: String?
        get() = synchronized(lock) { _storageCloudToken }
        set(value) {
            synchronized(lock) { _storageCloudToken = value }
        }

    /**
     * Permanently retires a client removed from the account pool, cancelling active transport calls
     * and rejecting all future requests or reauthentication attempts.
     */
    fun invalidate() {
        val cause = CancellationException("ChaoxingHttpClient has been invalidated")
        val (cancelledRefresh, callsToCancel) = synchronized(lock) {
            isInvalidated = true
            generation += 1L
            _storageCloudToken = null
            cookies = emptyList()
            _session = _session.copy(cookies = emptyList())
            val invalidated = refreshState
            refreshState = null
            val calls = activeCalls.toList()
            activeCalls.clear()
            invalidated to calls
        }
        cancelRefreshState(cancelledRefresh, cause)
        for (call in callsToCancel) {
            call.cancel()
        }
        refreshScope.cancel(cause)
    }

    fun updateSession(updated: ChaoxingAccountSession) {
        val cancelled = synchronized(lock) {
            val normalizedCookies = ChaoxingCookiePolicy.merging(
                existing = emptyList(),
                incoming = updated.cookies,
                allowLoopbackForTesting = allowLoopbackHttpForTesting,
            )
            var invalidated: RefreshState? = null
            if (
                _session.phoneNumber != updated.phoneNumber ||
                _session.encryptedPassword != updated.encryptedPassword ||
                _session.deviceCode != updated.deviceCode ||
                cookies != normalizedCookies
            ) {
                generation += 1L
                _storageCloudToken = null
                invalidated = refreshState
                refreshState = null
            }
            cookies = normalizedCookies
            _session = updated.copy(cookies = normalizedCookies)
            invalidated
        }
        cancelRefreshState(cancelled, CancellationException("Account session updated"))
    }

    fun updateTags(tags: List<String>) {
        synchronized(lock) {
            _session = _session.copy(tags = tags.toList())
        }
    }

    fun updateConfiguredFid(fid: Int) {
        val cancelled = synchronized(lock) {
            var invalidated: RefreshState? = null
            if (_session.configuredFid != fid) {
                _storageCloudToken = null
                generation += 1L
                invalidated = refreshState
                refreshState = null
            }
            val updatedCookies = ChaoxingCookiePolicy.merging(
                existing = emptyList(),
                incoming = cookies.filterNot { it.name == "fid" } + ChaoxingStoredCookie(
                    name = "fid",
                    value = fid.toString(),
                    domain = "chaoxing.com",
                    isSecure = true,
                ),
                allowLoopbackForTesting = allowLoopbackHttpForTesting,
            )
            cookies = updatedCookies
            _session = _session.copy(configuredFid = fid, cookies = updatedCookies)
            invalidated
        }
        cancelRefreshState(cancelled, CancellationException("Configured school fid updated"))
    }

    fun exportCookies(): List<ChaoxingStoredCookie> =
        synchronized(lock) {
            ChaoxingCookiePolicy.merging(
                existing = emptyList(),
                incoming = cookies,
                allowLoopbackForTesting = allowLoopbackHttpForTesting,
            )
        }

    fun replaceCookies(newCookies: List<ChaoxingStoredCookie>) {
        val cancelled = synchronized(lock) {
            generation += 1L
            _storageCloudToken = null
            val merged = ChaoxingCookiePolicy.merging(
                existing = emptyList(),
                incoming = newCookies,
                allowLoopbackForTesting = allowLoopbackHttpForTesting,
            )
            cookies = merged
            _session = _session.copy(cookies = merged)
            val invalidated = refreshState
            refreshState = null
            invalidated
        }
        cancelRefreshState(cancelled, CancellationException("Session cookies replaced"))
    }

    fun mergeCookies(incoming: List<ChaoxingStoredCookie>) {
        synchronized(lock) {
            val merged = ChaoxingCookiePolicy.merging(
                existing = cookies,
                incoming = incoming,
                allowLoopbackForTesting = allowLoopbackHttpForTesting,
            )
            cookies = merged
            _session = _session.copy(cookies = merged)
        }
    }

    fun clearCookies() {
        replaceCookies(emptyList())
    }

    internal fun authenticationContext(): AuthenticationContext =
        synchronized(lock) {
            AuthenticationContext(session = _session, generation = generation)
        }

    internal fun activeRefreshWaiterCountForTesting(): Int =
        synchronized(lock) {
            refreshState?.waiters?.size ?: 0
        }

    internal fun applyAuthenticatedProfile(
        profile: ChaoxingUserProfile,
        matching: ChaoxingAccountSession,
        generation: Long? = null,
    ): ChaoxingAccountSession =
        synchronized(lock) {
            val generationMatches = generation == null || generation == this.generation
            if (
                isInvalidated ||
                !generationMatches ||
                _session.phoneNumber != matching.phoneNumber ||
                _session.encryptedPassword != matching.encryptedPassword ||
                _session.deviceCode != matching.deviceCode
            ) {
                throw ChaoxingError.SessionExpired("账号会话已变更，请重试")
            }

            val nextFid = if (profile.schools.any { it.fid == _session.configuredFid }) {
                _session.configuredFid
            } else {
                profile.schools.firstOrNull()?.fid ?: profile.fid
            }

            val updatedCookies = ChaoxingCookiePolicy.merging(
                existing = emptyList(),
                incoming = cookies.filterNot { it.name == "fid" } + ChaoxingStoredCookie(
                    name = "fid",
                    value = nextFid.toString(),
                    domain = "chaoxing.com",
                    isSecure = true,
                ),
                allowLoopbackForTesting = allowLoopbackHttpForTesting,
            )
            cookies = updatedCookies
            _storageCloudToken = null
            _session = _session.copy(
                name = profile.name,
                uid = profile.uid,
                puid = profile.puid,
                configuredFid = nextFid,
                schools = profile.schools,
                avatarURL = profile.avatarURL,
                imEncryptedPassword = profile.imEncryptedPassword,
                clientId = profile.clientId,
                lastLoginAt = ChaoxingModelDefaults.nowIso8601(),
                cookies = updatedCookies,
            )
            _session
        }

    /**
     * Coalesces concurrent reauthentication callers onto a single active refresh job.
     * Cancelling one waiter cancels only that waiter; cancelling all waiters cancels the refresh job.
     */
    suspend fun reauthenticate(operation: suspend () -> ChaoxingAccountSession): ChaoxingAccountSession {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        val callerJob = callerContext[Job]

        val waiterId = nextWaiterId.incrementAndGet()
        val waiterDeferred = CompletableDeferred<ChaoxingAccountSession>()
        var jobToStart: Job? = null

        synchronized(lock) {
            if (isInvalidated || (callerJob != null && !callerJob.isActive)) {
                throw CancellationException("Reauthentication cancelled before start")
            }
            val activeRefresh = refreshState
            if (activeRefresh != null) {
                activeRefresh.waiters[waiterId] = waiterDeferred
            } else {
                generation += 1L
                _storageCloudToken = null
                val refreshId = nextRefreshId.incrementAndGet()
                val job = refreshScope.launch(start = CoroutineStart.LAZY) {
                    val outcome = try {
                        currentCoroutineContext().ensureActive()
                        val refreshed = operation()
                        currentCoroutineContext().ensureActive()
                        Result.success(refreshed)
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                    finishRefresh(refreshId, outcome)
                }
                val state = RefreshState(id = refreshId, job = job)
                state.waiters[waiterId] = waiterDeferred
                refreshState = state
                jobToStart = job
            }
        }

        jobToStart?.start()

        val result = try {
            waiterDeferred.await()
        } catch (cancelled: CancellationException) {
            cancelRefreshWaiter(waiterId)
            throw cancelled
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult =
        execute(
            url = url,
            method = "GET",
            body = null,
            headers = headers,
        )

    suspend fun postForm(
        url: String,
        items: List<Pair<String, String>>,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult {
        val encoded = items.joinToString("&") { (key, value) ->
            "${formPercentEncode(key)}=${formPercentEncode(value)}"
        }
        return postFormEncoded(url = url, encodedBody = encoded, headers = headers)
    }

    suspend fun postFormEncoded(
        url: String,
        encodedBody: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult =
        postRaw(
            url = url,
            body = encodedBody.toByteArray(Charsets.UTF_8),
            contentType = FORM_CONTENT_TYPE,
            headers = headers,
        )

    suspend fun postRaw(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult {
        val effectiveContentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?: contentType
        val mediaType = effectiveContentType.toMediaTypeOrNull()
            ?: throw ChaoxingError.InvalidInput("无效的 Content-Type: $effectiveContentType")
        val requestBody = body.toRequestBody(mediaType)
        val headersWithContentType = LinkedHashMap<String, String>(headers.size + 1)
        headersWithContentType["Content-Type"] = effectiveContentType
        headersWithContentType.putAll(headers)
        return execute(
            url = url,
            method = "POST",
            body = requestBody,
            headers = headersWithContentType,
        )
    }

    suspend fun postMultipartImage(
        url: String,
        puid: Int,
        filename: String,
        imageBytes: ByteArray,
    ): HttpResult {
        val safeFilename = filename.ifBlank { "photo.jpg" }
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("puid", puid.toString())
            .addFormDataPart(
                "file",
                safeFilename,
                imageBytes.toRequestBody(JPEG_MEDIA_TYPE),
            )
            .build()
        return execute(
            url = url,
            method = "POST",
            body = multipartBody,
            headers = emptyMap(),
        )
    }

    private suspend fun execute(
        url: String,
        method: String,
        body: RequestBody?,
        headers: Map<String, String>,
    ): HttpResult {
        currentCoroutineContext().ensureActive()
        if (isClientInvalidated()) {
            throw CancellationException("ChaoxingHttpClient has been invalidated")
        }
        val initialUrl = url.toHttpUrlOrNull()
            ?: throw ChaoxingError.InvalidInput("无效的 URL: $url")

        val (firstRequest, requestGeneration) = prepareInitialRequest(
            url = initialUrl,
            method = method,
            body = body,
            extraHeaders = headers,
        )

        var currentRequest = firstRequest
        var redirectCount = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val rawResponse = awaitCall(currentRequest)
            currentCoroutineContext().ensureActive()

            if (isClientInvalidated()) {
                throw CancellationException("ChaoxingHttpClient has been invalidated")
            }
            ensureGenerationOrThrow(requestGeneration)

            val responseUrl = rawResponse.url
            val statusCode = rawResponse.statusCode

            if (isRedirectStatus(statusCode)) {
                val location = rawResponse.headers["Location"]?.trim()?.takeIf { it.isNotEmpty() }
                val destination = location?.let { responseUrl.resolve(it) }
                if (destination != null) {
                    val nextMethod = redirectMethodFor(statusCode, currentRequest.method)
                    val allowed = ChaoxingCookiePolicy.allowsRedirect(
                        from = responseUrl,
                        to = destination,
                        method = nextMethod,
                        allowLoopbackForTesting = allowLoopbackHttpForTesting,
                    )
                    if (allowed) {
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw ChaoxingError.NetworkError(
                                statusCode = statusCode,
                                message = "HTTP 重定向次数超过上限 ($MAX_REDIRECTS)",
                            )
                        }
                        receiveCookies(
                            url = responseUrl,
                            headers = rawResponse.headers,
                            statusCode = statusCode,
                            expectedGeneration = requestGeneration,
                        )
                        currentRequest = buildRedirectedRequest(
                            previousRequest = currentRequest,
                            sourceUrl = responseUrl,
                            destinationUrl = destination,
                            statusCode = statusCode,
                            nextMethod = nextMethod,
                            expectedGeneration = requestGeneration,
                        )
                        redirectCount += 1
                        continue
                    }
                }
            }

            receiveCookies(
                url = responseUrl,
                headers = rawResponse.headers,
                statusCode = statusCode,
                expectedGeneration = requestGeneration,
            )
            return HttpResult(
                statusCode = statusCode,
                body = rawResponse.body,
                headers = rawResponse.headers,
                finalUrl = responseUrl.toString(),
            )
        }
    }

    private suspend fun awaitCall(request: Request): RawResponse =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            val rejectedAsInvalidated = synchronized(lock) {
                if (isInvalidated) {
                    true
                } else {
                    activeCalls.add(call)
                    false
                }
            }
            if (rejectedAsInvalidated) {
                call.cancel()
                continuation.resumeWithException(
                    CancellationException("ChaoxingHttpClient has been invalidated"),
                )
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                synchronized(lock) {
                    activeCalls.remove(call)
                }
                call.cancel()
            }

            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        val invalidated = synchronized(lock) {
                            activeCalls.remove(call)
                            isInvalidated
                        }
                        if (!continuation.isActive) return
                        if (continuation.isCancelled || invalidated) {
                            continuation.resumeWithException(
                                CancellationException(e.message ?: "HTTP 请求已取消", e),
                            )
                        } else {
                            continuation.resumeWithException(
                                ChaoxingError.NetworkError(
                                    statusCode = null,
                                    message = e.message ?: "网络请求失败",
                                ),
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { okResponse ->
                                val bytes = okResponse.body?.bytes() ?: ByteArray(0)
                                val invalidated = synchronized(lock) {
                                    activeCalls.remove(call)
                                    isInvalidated
                                }
                                if (!continuation.isActive) return
                                if (invalidated || continuation.isCancelled) {
                                    continuation.resumeWithException(
                                        CancellationException("HTTP 请求已取消"),
                                    )
                                } else {
                                    continuation.resume(
                                        RawResponse(
                                            statusCode = okResponse.code,
                                            body = bytes,
                                            headers = okResponse.headers,
                                            url = okResponse.request.url,
                                        ),
                                    )
                                }
                            }
                        } catch (e: IOException) {
                            val invalidated = synchronized(lock) {
                                activeCalls.remove(call)
                                isInvalidated
                            }
                            if (!continuation.isActive) return
                            if (continuation.isCancelled || invalidated) {
                                continuation.resumeWithException(
                                    CancellationException(e.message ?: "HTTP 请求已取消", e),
                                )
                            } else {
                                continuation.resumeWithException(
                                    ChaoxingError.NetworkError(
                                        statusCode = response.code,
                                        message = e.message ?: "读取 HTTP 响应失败",
                                    ),
                                )
                            }
                        } catch (t: Throwable) {
                            synchronized(lock) {
                                activeCalls.remove(call)
                            }
                            if (continuation.isActive) {
                                continuation.resumeWithException(t)
                            }
                        }
                    }
                },
            )
        }

    private fun prepareInitialRequest(
        url: HttpUrl,
        method: String,
        body: RequestBody?,
        extraHeaders: Map<String, String>,
    ): Pair<Request, Long> {
        val builder = Request.Builder()
            .url(url)
            .method(method, body)

        var hasCustomUserAgent = false
        for ((key, value) in extraHeaders) {
            if (key.equals("Cookie", ignoreCase = true)) continue
            if (key.equals("User-Agent", ignoreCase = true)) {
                hasCustomUserAgent = true
            }
            builder.header(key, value)
        }

        return synchronized(lock) {
            if (isInvalidated) {
                throw CancellationException("ChaoxingHttpClient has been invalidated")
            }
            if (!hasCustomUserAgent) {
                builder.header("User-Agent", _userAgent)
            }
            ChaoxingCookiePolicy.buildCookieHeader(
                cookies = cookies,
                url = url,
                allowLoopbackForTesting = allowLoopbackHttpForTesting,
            )?.let { cookieHeader ->
                builder.header("Cookie", cookieHeader)
            }
            builder.build() to generation
        }
    }

    private fun buildRedirectedRequest(
        previousRequest: Request,
        sourceUrl: HttpUrl,
        destinationUrl: HttpUrl,
        statusCode: Int,
        nextMethod: String,
        expectedGeneration: Long,
    ): Request {
        val builder = previousRequest.newBuilder()
            .url(destinationUrl)

        val preserveBody = (statusCode == 307 || statusCode == 308) &&
            nextMethod != "GET" &&
            nextMethod != "HEAD"
        val nextBody = if (preserveBody) previousRequest.body else null
        builder.method(nextMethod, nextBody)
        if (!preserveBody) {
            builder.removeHeader("Transfer-Encoding")
            builder.removeHeader("Content-Length")
            builder.removeHeader("Content-Type")
        }

        if (!sourceUrl.host.equals(destinationUrl.host, ignoreCase = true) || sourceUrl.port != destinationUrl.port) {
            builder.removeHeader("Authorization")
            builder.removeHeader("Proxy-Authorization")
        }

        return synchronized(lock) {
            if (isInvalidated) {
                throw CancellationException("ChaoxingHttpClient has been invalidated")
            }
            if (generation != expectedGeneration) {
                throw ChaoxingError.SessionExpired("账号会话已变更，请重试")
            }
            builder.removeHeader("Cookie")
            ChaoxingCookiePolicy.buildCookieHeader(
                cookies = cookies,
                url = destinationUrl,
                allowLoopbackForTesting = allowLoopbackHttpForTesting,
            )?.let { cookieHeader ->
                builder.header("Cookie", cookieHeader)
            }
            builder.build()
        }
    }

    private fun receiveCookies(
        url: HttpUrl,
        headers: Headers,
        statusCode: Int,
        expectedGeneration: Long,
    ) {
        if (!ChaoxingCookiePolicy.isTrusted(url, allowLoopbackHttpForTesting)) return
        val parsed = Cookie.parseAll(url, headers)
        if (parsed.isEmpty()) return

        val incoming = parsed.mapNotNull { okCookie ->
            val domain = ChaoxingCookiePolicy.domain(okCookie.domain)
            if (!ChaoxingCookiePolicy.domainMatches(url.host, domain)) return@mapNotNull null
            okCookie.toStoredCookie(domain)
        }
        if (incoming.isEmpty()) return

        synchronized(lock) {
            if (isInvalidated || generation != expectedGeneration) return
            val isLoginHost = url.host.equals(LOGIN_HOST, ignoreCase = true) ||
                (allowLoopbackHttpForTesting && ChaoxingCookiePolicy.isLoopbackHost(url.host))
            val isLogin = isLoginHost && url.encodedPath == LOGIN_PATH && statusCode in 200..299
            val merged = ChaoxingCookiePolicy.merging(
                existing = if (isLogin) emptyList() else cookies,
                incoming = incoming,
                allowLoopbackForTesting = allowLoopbackHttpForTesting,
            )
            cookies = merged
            _session = _session.copy(cookies = merged)
        }
    }

    private fun isClientInvalidated(): Boolean =
        synchronized(lock) { isInvalidated }

    private fun ensureGenerationOrThrow(expectedGeneration: Long) {
        synchronized(lock) {
            if (isInvalidated) {
                throw CancellationException("ChaoxingHttpClient has been invalidated")
            }
            if (generation != expectedGeneration) {
                throw ChaoxingError.SessionExpired("账号会话已变更，请重试")
            }
        }
    }

    private fun finishRefresh(refreshId: Long, outcome: Result<ChaoxingAccountSession>) {
        val waiters = synchronized(lock) {
            val current = refreshState
            if (current == null || current.id != refreshId) {
                return
            }
            generation += 1L
            refreshState = null
            current.waiters.values.toList()
        }
        outcome.fold(
            onSuccess = { refreshedSession ->
                for (waiter in waiters) {
                    waiter.complete(refreshedSession)
                }
            },
            onFailure = { error ->
                for (waiter in waiters) {
                    waiter.completeExceptionally(error)
                }
            },
        )
    }

    private fun cancelRefreshWaiter(waiterId: Long) {
        val (jobToCancel, waiterToCancel) = synchronized(lock) {
            val current = refreshState ?: return
            val removed = current.waiters.remove(waiterId) ?: return
            if (current.waiters.isEmpty()) {
                generation += 1L
                refreshState = null
                current.job to removed
            } else {
                null to removed
            }
        }
        val cause = CancellationException("Reauthentication waiter cancelled")
        jobToCancel?.cancel(cause)
        waiterToCancel.completeExceptionally(cause)
    }

    private fun cancelRefreshState(cancelled: RefreshState?, cause: CancellationException) {
        if (cancelled == null) return
        cancelled.job.cancel(cause)
        for (waiter in cancelled.waiters.values) {
            waiter.completeExceptionally(cause)
        }
    }

    private fun Cookie.toStoredCookie(normalizedDomain: String): ChaoxingStoredCookie {
        val expiresIso = if (persistent) {
            val clampedInstant = if (expiresAt <= 0L) {
                Instant.EPOCH
            } else {
                Instant.ofEpochMilli(minOf(expiresAt, MAX_COOKIE_EPOCH_MS))
            }
            ChaoxingModelDefaults.formatIso8601(clampedInstant)
        } else {
            null
        }
        return ChaoxingStoredCookie(
            name = name,
            value = value,
            domain = normalizedDomain,
            path = path,
            isSecure = secure,
            expiresAt = expiresIso,
            hostOnly = hostOnly,
        )
    }

    private fun isRedirectStatus(statusCode: Int): Boolean =
        statusCode == 300 ||
            statusCode == 301 ||
            statusCode == 302 ||
            statusCode == 303 ||
            statusCode == 307 ||
            statusCode == 308

    private fun redirectMethodFor(statusCode: Int, currentMethod: String): String {
        val normalized = currentMethod.uppercase(Locale.ROOT)
        return when (statusCode) {
            307, 308 -> normalized
            else -> if (normalized == "HEAD") "HEAD" else "GET"
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 10
        private const val LOGIN_HOST = "passport2.chaoxing.com"
        private const val LOGIN_PATH = "/fanyalogin"
        private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
        private const val MAX_COOKIE_EPOCH_MS = 253_402_300_799_000L
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        private val HEX_DIGITS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
        )

        fun defaultOkHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .build()

        internal fun createForLoopbackTesting(
            session: ChaoxingAccountSession,
            userAgent: String = ChaoxingClientPreset.DEFAULT.userAgent,
            sharedBaseClient: OkHttpClient = defaultOkHttpClient(),
        ): ChaoxingHttpClient =
            ChaoxingHttpClient(
                session = session,
                userAgent = userAgent,
                sharedBaseClient = sharedBaseClient,
                allowLoopbackHttpForTesting = true,
            )

        /**
         * Appends RFC 3986 form-encoded query parameters to [base] while encoding `+` as `%2B`
         * so Chaoxing's server-side form decoder preserves literal plus signs.
         */
        fun encodeChaoxingUrl(base: String, params: List<Pair<String, String>>): String {
            val parsed = base.toHttpUrlOrNull()
                ?: throw ChaoxingError.InvalidInput("无效的 URL: $base")
            if (params.isEmpty()) return parsed.toString()

            val encodedParams = params.joinToString("&") { (key, value) ->
                "${formPercentEncode(key)}=${formPercentEncode(value)}"
            }
            val parsedString = parsed.toString()
            val fragmentIndex = parsedString.indexOf('#')
            val baseWithoutFragment = if (fragmentIndex >= 0) {
                parsedString.substring(0, fragmentIndex)
            } else {
                parsedString
            }
            val fragmentSuffix = if (fragmentIndex >= 0) {
                parsedString.substring(fragmentIndex)
            } else {
                ""
            }
            val separator = when {
                parsed.encodedQuery == null -> "?"
                baseWithoutFragment.endsWith("?") || baseWithoutFragment.endsWith("&") -> ""
                else -> "&"
            }
            return "$baseWithoutFragment$separator$encodedParams$fragmentSuffix"
        }

        /**
         * Percent-encodes all UTF-8 bytes outside RFC 3986 unreserved characters (`A-Za-z0-9-._~`).
         */
        fun formPercentEncode(s: String): String {
            val bytes = s.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(bytes.size)
            for (byte in bytes) {
                val code = byte.toInt() and 0xFF
                if (isUnreservedByte(code)) {
                    sb.append(code.toChar())
                } else {
                    sb.append('%')
                    sb.append(HEX_DIGITS[code ushr 4])
                    sb.append(HEX_DIGITS[code and 0x0F])
                }
            }
            return sb.toString()
        }

        private fun isUnreservedByte(code: Int): Boolean =
            (code in 'A'.code..'Z'.code) ||
                (code in 'a'.code..'z'.code) ||
                (code in '0'.code..'9'.code) ||
                code == '-'.code ||
                code == '.'.code ||
                code == '_'.code ||
                code == '~'.code
    }
}
