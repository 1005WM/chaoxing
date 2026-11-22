// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.network

import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingSchool
import com.caochaoxing.core.model.ChaoxingStoredCookie
import com.caochaoxing.core.model.ChaoxingUserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ChaoxingHttpClientTest {
    private fun syntheticSession(
        phoneNumber: String = "13800000001",
        cookies: List<ChaoxingStoredCookie> = emptyList(),
    ): ChaoxingAccountSession =
        ChaoxingAccountSession(
            phoneNumber = phoneNumber,
            encryptedPassword = "FAKE_ENCRYPTED_PASSWORD",
            name = "测试用户",
            uid = 100,
            puid = 200,
            configuredFid = 300,
            deviceCode = "FAKE_DEVICE_CODE",
            cookies = cookies,
        )

    private suspend inline fun <reified T : Throwable> assertSuspendsThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (thrown: Throwable) {
            if (thrown is T) return thrown
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${thrown::class.java.name}: ${thrown.message}", thrown)
        }
        throw AssertionError("Expected ${T::class.java.simpleName} to be thrown, but completed normally")
    }

    @Test
    fun `non chaoxing or non https urls never receive chaoxing cookies`() = runBlocking {
        val now = Instant.parse("2026-11-16T12:00:00Z")
        val cookies = listOf(
            ChaoxingStoredCookie(
                name = "sid",
                value = "secret",
                domain = "mobilelearn.chaoxing.com",
                path = "/sign",
                isSecure = true,
                hostOnly = true,
            ),
            ChaoxingStoredCookie(
                name = "expired",
                value = "bad",
                domain = "chaoxing.com",
                expiresAt = "2020-01-01T00:00:00Z",
            ),
            ChaoxingStoredCookie(
                name = "root",
                value = "top",
                domain = "chaoxing.com",
                path = "/",
                isSecure = true,
            ),
            ChaoxingStoredCookie(
                name = "deep",
                value = "inner",
                domain = "mobilelearn.chaoxing.com",
                path = "/sign/submit",
                isSecure = true,
                hostOnly = true,
            ),
        )

        assertTrue(ChaoxingCookiePolicy.isTrusted("https://chaoxing.com/home".toHttpUrl()))
        assertTrue(ChaoxingCookiePolicy.isTrusted("https://mobilelearn.chaoxing.com/sign".toHttpUrl()))
        assertFalse(ChaoxingCookiePolicy.isTrusted("http://mobilelearn.chaoxing.com/sign".toHttpUrl()))
        assertFalse(ChaoxingCookiePolicy.isTrusted("https://evilchaoxing.com/sign".toHttpUrl()))
        assertFalse(ChaoxingCookiePolicy.isTrusted("http://127.0.0.1:8080/sign".toHttpUrl()))

        val blockedUrls = listOf(
            "https://evilchaoxing.com/sign",
            "http://mobilelearn.chaoxing.com/sign",
            "http://127.0.0.1:8080/sign",
            "https://foreign.invalid/sign/submit",
        )
        for (rawUrl in blockedUrls) {
            assertNull(ChaoxingCookiePolicy.buildCookieHeader(cookies, rawUrl.toHttpUrl(), now = now))
        }

        // HostOnly and path boundary checks on trusted URLs
        assertEquals(
            "root=top",
            ChaoxingCookiePolicy.buildCookieHeader(cookies, "https://mobilelearn.chaoxing.com/signature".toHttpUrl(), now = now),
        )
        assertEquals(
            "root=top",
            ChaoxingCookiePolicy.buildCookieHeader(cookies, "https://mobilelearn.chaoxing.com/sign%2Fhidden".toHttpUrl(), now = now),
        )
        assertEquals(
            "root=top",
            ChaoxingCookiePolicy.buildCookieHeader(cookies, "https://sub.mobilelearn.chaoxing.com/sign".toHttpUrl(), now = now),
        )
        // Longest path first: /sign/submit -> /sign -> /
        assertEquals(
            "deep=inner; sid=secret; root=top",
            ChaoxingCookiePolicy.buildCookieHeader(cookies, "https://mobilelearn.chaoxing.com/sign/submit".toHttpUrl(), now = now),
        )

        // Production client (allowLoopbackHttpForTesting = false) must never send cookies to or accept cookies from HTTP loopback
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "sid=injected; Path=/")
                    .setBody("ok"),
            )
            val prodClient = ChaoxingHttpClient(
                session = syntheticSession(
                    cookies = listOf(
                        ChaoxingStoredCookie(name = "sid", value = "chaoxing-secret", domain = "chaoxing.com", isSecure = true),
                    ),
                ),
            )
            val result = prodClient.get(server.url("/test").toString())
            assertEquals(200, result.statusCode)
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertNull(recorded.getHeader("Cookie"))
            assertEquals(listOf("chaoxing-secret"), prodClient.exportCookies().map { it.value })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cookie merging validates fields preserves domain scopes and deletes expired entries`() = runBlocking {
        val merged = ChaoxingCookiePolicy.merging(
            existing = listOf(
                ChaoxingStoredCookie(name = "sid", value = "one", domain = "one.chaoxing.com"),
                ChaoxingStoredCookie(name = "sid", value = "two", domain = "two.chaoxing.com"),
            ),
            incoming = listOf(
                ChaoxingStoredCookie(name = "sid", value = "updated", domain = ".one.chaoxing.com"),
                ChaoxingStoredCookie(name = "bad;name", value = "x", domain = "chaoxing.com"),
                ChaoxingStoredCookie(name = "badval", value = "a\nb", domain = "chaoxing.com"),
                ChaoxingStoredCookie(name = "evil", value = "steal", domain = "evilchaoxing.com"),
                ChaoxingStoredCookie(name = "badpath", value = "x", domain = "chaoxing.com", path = "relative"),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals("updated", merged.first { it.domain == "one.chaoxing.com" }.value)
        assertEquals("two", merged.first { it.domain == "two.chaoxing.com" }.value)

        val server = MockWebServer()
        server.start()
        try {
            val host = server.url("/").host
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "sid=deleted; Max-Age=0; Path=/")
                    .setBody("{}"),
            )
            val client = ChaoxingHttpClient.createForLoopbackTesting(
                session = syntheticSession(
                    cookies = listOf(
                        ChaoxingStoredCookie(name = "sid", value = "old", domain = host, hostOnly = true),
                    ),
                ),
            )
            assertEquals(1, client.exportCookies().size)
            client.get(server.url("/test").toString())
            assertTrue(client.exportCookies().isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fanyalogin HTTP 200 clears old cookies and merges incoming Set-Cookie`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val host = server.url("/").host
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "sid=fresh; Path=/")
                    .addHeader("Set-Cookie", "UID=200; Path=/")
                    .setBody("""{"status":true}"""),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("home"))

            val client = ChaoxingHttpClient.createForLoopbackTesting(
                session = syntheticSession(
                    cookies = listOf(
                        ChaoxingStoredCookie(name = "old_sid", value = "stale", domain = host, hostOnly = true),
                        ChaoxingStoredCookie(name = "legacy", value = "keep_me_not", domain = "chaoxing.com"),
                    ),
                ),
            )

            val loginResult = client.postFormEncoded(
                url = server.url("/fanyalogin").toString(),
                encodedBody = "fid=-1&uname=test",
            )
            assertEquals(200, loginResult.statusCode)
            val loginRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", loginRequest.method)
            assertEquals("old_sid=stale", loginRequest.getHeader("Cookie"))

            val exported = client.exportCookies()
            assertEquals(setOf("UID", "sid"), exported.map { it.name }.toSet())
            assertFalse(exported.any { it.name == "old_sid" || it.name == "legacy" })

            client.get(server.url("/home").toString())
            val homeRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            val sentCookie = homeRequest.getHeader("Cookie")!!
            assertTrue(sentCookie.contains("sid=fresh"))
            assertTrue(sentCookie.contains("UID=200"))
            assertFalse(sentCookie.contains("old_sid"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `redirect policy blocks unsafe transitions and manual redirect strips cross-port Authorization`() = runBlocking {
        val source = "https://passport2.chaoxing.com/fanyalogin".toHttpUrl()
        assertFalse(ChaoxingCookiePolicy.allowsRedirect(source, "https://evilchaoxing.com/capture".toHttpUrl(), "GET"))
        assertFalse(ChaoxingCookiePolicy.allowsRedirect(source, "http://passport2.chaoxing.com/capture".toHttpUrl(), "GET"))
        assertFalse(ChaoxingCookiePolicy.allowsRedirect(source, "https://user:pass@i.chaoxing.com/home".toHttpUrl(), "GET"))
        assertFalse(ChaoxingCookiePolicy.allowsRedirect(source, "https://i.chaoxing.com/capture".toHttpUrl(), "POST"))
        assertTrue(ChaoxingCookiePolicy.allowsRedirect(source, "https://i.chaoxing.com/home".toHttpUrl(), "GET"))
        assertTrue(ChaoxingCookiePolicy.allowsRedirect(source, "https://passport2.chaoxing.com/next".toHttpUrl(), "POST"))

        val server1 = MockWebServer()
        val server2 = MockWebServer()
        server1.start()
        server2.start()
        try {
            val host1 = server1.url("/").host
            // 1. Cross-port 302 redirect: strip Authorization/Proxy-Authorization and rebuild Cookie
            server1.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Set-Cookie", "hop=from1; Path=/")
                    .addHeader("Location", server2.url("/final").toString()),
            )
            server2.enqueue(MockResponse().setResponseCode(200).setBody("arrived"))

            val client = ChaoxingHttpClient.createForLoopbackTesting(
                session = syntheticSession(
                    cookies = listOf(
                        ChaoxingStoredCookie(name = "scoped", value = "only_start", domain = host1, path = "/start", hostOnly = true),
                    ),
                ),
            )

            val result = client.get(
                url = server1.url("/start").toString(),
                headers = mapOf(
                    "Authorization" to "Bearer secret-token",
                    "Proxy-Authorization" to "Basic dXNlcjpwYXNz",
                ),
            )
            assertEquals(200, result.statusCode)
            assertEquals("arrived", result.bodyAsString())
            assertEquals(server2.url("/final").toString(), result.finalUrl)

            val req1 = server1.takeRequest(5, TimeUnit.SECONDS)!!
            val req2 = server2.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Bearer secret-token", req1.getHeader("Authorization"))
            assertNull(req2.getHeader("Authorization"))
            assertNull(req2.getHeader("Proxy-Authorization"))
            assertEquals("hop=from1", req2.getHeader("Cookie"))

            // 2. Untrusted external redirect must NOT be followed
            server1.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "https://evilchaoxing.com/capture"),
            )
            val blocked = client.get(server1.url("/untrusted").toString())
            assertEquals(302, blocked.statusCode)
            assertEquals(2, server1.requestCount)

            // 3. Cross-origin 307 POST redirect must NOT be followed
            server1.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .addHeader("Location", server2.url("/capture").toString()),
            )
            val blockedPost = client.postForm(server1.url("/submit").toString(), listOf("k" to "v"))
            assertEquals(307, blockedPost.statusCode)
            assertEquals(1, server2.requestCount)

            // 4. Exceeding max 10 redirect hops fails safely
            repeat(11) { hop ->
                server1.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", server1.url("/loop/${hop + 1}").toString()),
                )
            }
            assertSuspendsThrows<ChaoxingError.NetworkError> {
                client.get(server1.url("/loop/0").toString())
            }
        } finally {
            server1.shutdown()
            server2.shutdown()
        }
    }

    @Test
    fun `concurrent reauthenticate coalesces into a single underlying operation`() = runBlocking {
        val client = ChaoxingHttpClient(session = syntheticSession())
        val invocations = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        withTimeout(5_000) {
            val first = async(Dispatchers.Default) {
                client.reauthenticate {
                    invocations.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    syntheticSession(phoneNumber = "13800000001").copy(uid = 777)
                }
            }
            entered.await()

            val second = async(Dispatchers.Default) {
                client.reauthenticate {
                    invocations.incrementAndGet()
                    syntheticSession(phoneNumber = "13800000001").copy(uid = 888)
                }
            }
            while (client.activeRefreshWaiterCountForTesting() < 2) {
                delay(5)
            }
            release.complete(Unit)

            val results = awaitAll(first, second)
            assertEquals(1, invocations.get())
            assertEquals(listOf(777, 777), results.map { it.uid })
        }
    }

    @Test
    fun `cancelling one reauthenticate waiter keeps second waiter alive while cancelling last waiter stops job`() = runBlocking {
        val client = ChaoxingHttpClient(session = syntheticSession())

        // Part 1: Cancel waiter 1 while waiter 2 remains attached
        val invocations = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        withTimeout(5_000) {
            val first = async(Dispatchers.Default) {
                client.reauthenticate {
                    invocations.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    syntheticSession().copy(uid = 501)
                }
            }
            entered.await()

            val second = async(Dispatchers.Default) {
                client.reauthenticate {
                    invocations.incrementAndGet()
                    syntheticSession().copy(uid = 502)
                }
            }
            while (client.activeRefreshWaiterCountForTesting() < 2) {
                delay(5)
            }
            first.cancel()
            assertSuspendsThrows<CancellationException> { first.await() }

            release.complete(Unit)
            assertEquals(501, second.await().uid)
            assertEquals(1, invocations.get())
        }

        // Part 2: Cancelling the only waiter must cancel the underlying transport job
        val server = MockWebServer()
        val requestEntered = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val profileRequested = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/fanyalogin") {
                    requestEntered.countDown()
                    releaseServer.await(5, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(200).setBody("""{"status":true}""")
                }
                profileRequested.set(true)
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }
        server.start()
        try {
            val testClient = ChaoxingHttpClient.createForLoopbackTesting(
                session = syntheticSession(),
            )
            val job = launch(Dispatchers.Default) {
                testClient.reauthenticate {
                    testClient.postFormEncoded(server.url("/fanyalogin").toString(), "fid=-1")
                    testClient.get(server.url("/userLogin4Uname").toString())
                    syntheticSession()
                }
            }
            assertTrue(requestEntered.await(5, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            releaseServer.countDown()
            delay(50)
            assertFalse(profileRequested.get())
        } finally {
            releaseServer.countDown()
            server.shutdown()
        }
    }

    @Test
    fun `stale responses after clearCookies or reauthenticate cannot mutate cookies`() = runBlocking {
        val server = MockWebServer()
        val oldEntered = CountDownLatch(1)
        val oldReleased = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/old" -> {
                        oldEntered.countDown()
                        oldReleased.await(5, TimeUnit.SECONDS)
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Set-Cookie", "sid=stale; Path=/")
                            .setBody("old")
                    }
                    "/fanyalogin" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Set-Cookie", "sid=fresh; Path=/")
                        .setBody("""{"status":true}""")
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }
        server.start()
        try {
            val client = ChaoxingHttpClient.createForLoopbackTesting(session = syntheticSession())

            supervisorScope {
                val staleJob = async(Dispatchers.Default) {
                    client.get(server.url("/old").toString())
                }
                assertTrue(oldEntered.await(5, TimeUnit.SECONDS))

                val refreshed = client.reauthenticate {
                    client.postFormEncoded(server.url("/fanyalogin").toString(), "fid=-1")
                    val ctx = client.authenticationContext()
                    client.applyAuthenticatedProfile(
                        profile = ChaoxingUserProfile(
                            uid = 1,
                            puid = 1,
                            fid = 300,
                            name = "测试用户",
                            uname = "u",
                            avatarURL = "",
                            phoneNumber = ctx.session.phoneNumber,
                            imEncryptedPassword = "",
                            clientId = null,
                            schools = listOf(ChaoxingSchool(300, "示例大学")),
                        ),
                        matching = ctx.session,
                        generation = ctx.generation,
                    )
                }
                assertEquals(1, refreshed.uid)
                oldReleased.countDown()

                assertSuspendsThrows<ChaoxingError.SessionExpired> { staleJob.await() }
            }
            assertEquals("fresh", client.exportCookies().first { it.name == "sid" }.value)

            // Also verify clearCookies cancels in-flight reauthenticate and rejects stale response
            val clearEntered = CountDownLatch(1)
            val clearReleased = CountDownLatch(1)
            val clearServer = MockWebServer()
            clearServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    clearEntered.countDown()
                    clearReleased.await(5, TimeUnit.SECONDS)
                    return MockResponse()
                        .setResponseCode(200)
                        .addHeader("Set-Cookie", "sid=resurrected; Path=/")
                        .setBody("""{"status":true}""")
                }
            }
            clearServer.start()
            try {
                val clearingClient = ChaoxingHttpClient.createForLoopbackTesting(
                    session = syntheticSession(),
                )
                supervisorScope {
                    val refreshJob = async(Dispatchers.Default) {
                        clearingClient.reauthenticate {
                            clearingClient.postFormEncoded(clearServer.url("/fanyalogin").toString(), "fid=-1")
                            syntheticSession()
                        }
                    }
                    assertTrue(clearEntered.await(5, TimeUnit.SECONDS))
                    clearingClient.clearCookies()
                    assertSuspendsThrows<CancellationException> { refreshJob.await() }
                }
                clearReleased.countDown()
                delay(40)
                assertTrue(clearingClient.exportCookies().isEmpty())
            } finally {
                clearReleased.countDown()
                clearServer.shutdown()
            }
        } finally {
            oldReleased.countDown()
            server.shutdown()
        }
    }

    @Test
    fun `applyAuthenticatedProfile preserves tags and rejects mismatched session or generation`() {
        val client = ChaoxingHttpClient(session = syntheticSession())
        val context = client.authenticationContext()
        client.updateTags(listOf("主力", "一班"))

        val profile = ChaoxingUserProfile(
            uid = 11,
            puid = 22,
            fid = 404,
            name = "新姓名",
            uname = "uname",
            avatarURL = "https://example.invalid/a.jpg",
            phoneNumber = context.session.phoneNumber,
            imEncryptedPassword = "im",
            clientId = "cid",
            schools = listOf(ChaoxingSchool(fid = 300, name = "示例大学")),
        )
        val committed = client.applyAuthenticatedProfile(
            profile = profile,
            matching = context.session,
            generation = context.generation,
        )
        assertEquals(listOf("主力", "一班"), committed.tags)
        assertEquals("300", committed.cookies.first { it.name == "fid" }.value)

        client.updateConfiguredFid(999)
        assertThrows(ChaoxingError.SessionExpired::class.java) {
            client.applyAuthenticatedProfile(
                profile = profile,
                matching = context.session,
                generation = context.generation,
            )
        }

        val otherSession = syntheticSession(phoneNumber = "13900000009")
        client.updateSession(otherSession)
        assertThrows(ChaoxingError.SessionExpired::class.java) {
            client.applyAuthenticatedProfile(profile = profile, matching = context.session)
        }
        assertEquals("13900000009", client.session.phoneNumber)
    }

    @Test
    fun `invalidate throws CancellationException on subsequent requests and cancels in-flight calls`() = runBlocking {
        val server = MockWebServer()
        val slowEntered = CountDownLatch(1)
        val slowReleased = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/slow") {
                    slowEntered.countDown()
                    slowReleased.await(5, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(200).setBody("late")
                }
                return MockResponse().setResponseCode(200).setBody("fast-ok")
            }
        }
        server.start()
        try {
            val sharedOkHttp = ChaoxingHttpClient.defaultOkHttpClient()
            val victim = ChaoxingHttpClient.createForLoopbackTesting(
                session = syntheticSession(),
                sharedBaseClient = sharedOkHttp,
            )
            val sibling = ChaoxingHttpClient.createForLoopbackTesting(
                session = syntheticSession(phoneNumber = "13800000002"),
                sharedBaseClient = sharedOkHttp,
            )

            supervisorScope {
                val inFlight = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    victim.get(server.url("/slow").toString())
                }
                assertTrue(slowEntered.await(5, TimeUnit.SECONDS))
                victim.invalidate()
                assertSuspendsThrows<CancellationException> { inFlight.await() }
            }

            val countBeforePostInvalidation = server.requestCount
            assertSuspendsThrows<CancellationException> { victim.get(server.url("/after").toString()) }
            assertSuspendsThrows<CancellationException> { victim.postForm(server.url("/after").toString(), listOf("a" to "b")) }
            assertSuspendsThrows<CancellationException> { victim.reauthenticate { syntheticSession() } }
            assertEquals(countBeforePostInvalidation, server.requestCount)

            // Sibling client sharing the same OkHttpClient must remain unaffected
            val siblingResult = sibling.get(server.url("/sibling").toString())
            assertEquals(200, siblingResult.statusCode)
            assertEquals("fast-ok", siblingResult.bodyAsString())
        } finally {
            slowReleased.countDown()
            server.shutdown()
        }
    }

    @Test
    fun `encodeChaoxingUrl encodes plus as %2B and form or multipart requests match protocol`() = runBlocking {
        val encoded = ChaoxingHttpClient.encodeChaoxingUrl(
            base = "https://mobilelearn.chaoxing.com/pptSign/stuSignajax",
            params = listOf("name" to "A+B", "validate" to "a+b%20"),
        )
        assertEquals(
            "https://mobilelearn.chaoxing.com/pptSign/stuSignajax?name=A%2BB&validate=a%2Bb%2520",
            encoded,
        )

        val appended = ChaoxingHttpClient.encodeChaoxingUrl(
            base = "https://mobilelearn.chaoxing.com/pptSign/analysis?vs=1&DB_STRATEGY=RANDOM",
            params = listOf("aid" to "42+1"),
        )
        assertEquals(
            "https://mobilelearn.chaoxing.com/pptSign/analysis?vs=1&DB_STRATEGY=RANDOM&aid=42%2B1",
            appended,
        )
        assertEquals("synthetic%2Ba%2Fb%3D", ChaoxingHttpClient.formPercentEncode("synthetic+a/b="))

        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("form-ok"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"objectId":"obj-1"}"""))
            val client = ChaoxingHttpClient.createForLoopbackTesting(session = syntheticSession())

            client.postForm(
                url = server.url("/submit").toString(),
                items = listOf("ext" to "a+b/c=", "note" to "hi world"),
            )
            val formReq = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("ext=a%2Bb%2Fc%3D&note=hi%20world", formReq.body.readUtf8())
            assertTrue(formReq.getHeader("Content-Type")?.startsWith("application/x-www-form-urlencoded") == true)

            val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
            val uploadResult = client.postMultipartImage(
                url = server.url("/upload").toString(),
                puid = 200,
                filename = "sign.jpg",
                imageBytes = imageBytes,
            )
            assertEquals(200, uploadResult.statusCode)
            val uploadReq = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(uploadReq.getHeader("Content-Type")?.startsWith("multipart/form-data") == true)
            val rawBytes = uploadReq.body.readByteArray()
            val rawLatin1 = String(rawBytes, Charsets.ISO_8859_1)
            assertTrue(rawLatin1.contains("name=\"puid\""))
            assertTrue(rawLatin1.contains("200"))
            assertTrue(rawLatin1.contains("filename=\"sign.jpg\""))
            assertTrue(rawLatin1.contains("image/jpeg"))
            assertTrue(rawLatin1.contains(String(imageBytes, Charsets.ISO_8859_1)))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `okhttp callTimeout maps to NetworkError instead of CancellationException`() = runBlocking {
        val server = MockWebServer()
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(200).setBody("too-late")
            }
        }
        server.start()
        try {
            val shortTimeoutOkHttp = OkHttpClient.Builder()
                .callTimeout(100, TimeUnit.MILLISECONDS)
                .build()
            val client = ChaoxingHttpClient.createForLoopbackTesting(
                session = syntheticSession(),
                sharedBaseClient = shortTimeoutOkHttp,
            )
            assertSuspendsThrows<ChaoxingError.NetworkError> {
                client.get(server.url("/timeout").toString())
            }
        } finally {
            release.countDown()
            server.shutdown()
        }
    }
}
