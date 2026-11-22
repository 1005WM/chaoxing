// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.service

import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.CoordinateSystem
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Geographic place candidate returned by OpenStreetMap Nominatim in WGS-84 coordinates.
 */
@Serializable
data class NominatimPlace(
    val displayName: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val coordinateSystem: CoordinateSystem = CoordinateSystem.WGS84,
)

/**
 * Pure OpenStreetMap Nominatim geocoding search client isolated from Chaoxing cookies and sessions.
 */
object NominatimGeocoder {
    const val NOMINATIM_USER_AGENT: String = "Caochaoxing-Android/1.0 (OpenStreetMap Nominatim Geocoder)"
    const val ACCEPT_LANGUAGE: String = "zh-CN,zh;q=0.9,en;q=0.8"
    private const val RESULT_LIMIT = "8"

    private val sharedDefaultClient: OkHttpClient by lazy { defaultOkHttpClient() }

    fun defaultOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .build()

    suspend fun search(
        query: String,
        client: OkHttpClient = sharedDefaultClient,
    ): List<NominatimPlace> =
        search(ChaoxingEndpoints.PRODUCTION, query, client)

    suspend fun search(
        query: String,
        client: ChaoxingHttpClient,
    ): List<NominatimPlace> =
        search(ChaoxingEndpoints.PRODUCTION, query, client)

    internal suspend fun search(
        endpoints: ChaoxingEndpoints,
        query: String,
        client: OkHttpClient = sharedDefaultClient,
    ): List<NominatimPlace> {
        currentCoroutineContext().ensureActive()
        val trimmed = query.trim { it.isWhitespace() }
        if (trimmed.isEmpty()) return emptyList()

        val searchUrl = buildSearchUrl(endpoints.nominatimSearchUrl, trimmed)
        val isolatedClient = client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val request = Request.Builder()
            .url(searchUrl)
            .get()
            .header("User-Agent", NOMINATIM_USER_AGENT)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .header("Accept", "application/json")
            .build()

        val (statusCode, body) = awaitOkHttpCall(isolatedClient, request)
        if (statusCode !in 200..299) {
            throw ChaoxingError.NetworkError(statusCode = statusCode, message = "地点搜索请求失败")
        }
        return parsePlacesJson(body)
    }

    internal suspend fun search(
        endpoints: ChaoxingEndpoints,
        query: String,
        client: ChaoxingHttpClient,
    ): List<NominatimPlace> {
        currentCoroutineContext().ensureActive()
        val trimmed = query.trim { it.isWhitespace() }
        if (trimmed.isEmpty()) return emptyList()

        val searchUrl = buildSearchUrl(endpoints.nominatimSearchUrl, trimmed)
        val result = client.get(
            url = searchUrl,
            headers = mapOf(
                "User-Agent" to NOMINATIM_USER_AGENT,
                "Accept-Language" to ACCEPT_LANGUAGE,
                "Accept" to "application/json",
            ),
        )
        if (!result.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = result.statusCode, message = "地点搜索请求失败")
        }
        return parsePlacesJson(result.body)
    }

    private fun buildSearchUrl(baseUrl: String, trimmedQuery: String): String {
        val parsed = baseUrl.toHttpUrlOrNull()
            ?: throw ChaoxingError.InvalidInput("无效的 Nominatim URL: $baseUrl")
        return ChaoxingHttpClient.encodeChaoxingUrl(
            parsed.toString(),
            listOf(
                "q" to trimmedQuery,
                "format" to "jsonv2",
                "limit" to RESULT_LIMIT,
            ),
        )
    }

    private suspend fun awaitOkHttpCall(
        client: OkHttpClient,
        request: Request,
    ): Pair<Int, ByteArray> =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isActive) return
                        if (continuation.isCancelled) {
                            continuation.resumeWithException(
                                CancellationException(e.message ?: "地点搜索已取消", e),
                            )
                        } else {
                            continuation.resumeWithException(
                                ChaoxingError.NetworkError(
                                    statusCode = null,
                                    message = e.message ?: "地点搜索网络失败",
                                ),
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { okResponse ->
                                val bytes = okResponse.body?.bytes() ?: ByteArray(0)
                                if (!continuation.isActive) return
                                if (continuation.isCancelled) {
                                    continuation.resumeWithException(
                                        CancellationException("地点搜索已取消"),
                                    )
                                } else {
                                    continuation.resume(okResponse.code to bytes)
                                }
                            }
                        } catch (e: IOException) {
                            if (!continuation.isActive) return
                            if (continuation.isCancelled) {
                                continuation.resumeWithException(
                                    CancellationException(e.message ?: "地点搜索已取消", e),
                                )
                            } else {
                                continuation.resumeWithException(
                                    ChaoxingError.NetworkError(
                                        statusCode = response.code,
                                        message = e.message ?: "读取地点搜索响应失败",
                                    ),
                                )
                            }
                        } catch (t: Throwable) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(t)
                            }
                        }
                    }
                },
            )
        }

    internal fun parsePlacesJson(bytes: ByteArray): List<NominatimPlace> {
        val text = bytes.toString(Charsets.UTF_8).trim { it.isWhitespace() }
        if (text.isEmpty()) return emptyList()

        val array = try {
            chaoxingJson.parseToJsonElement(text) as? JsonArray
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw ChaoxingError.ParseFailed("地点搜索响应格式错误")

        val results = ArrayList<NominatimPlace>(array.size)
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val lat = obj.doubleOrStringDoubleOrNull("lat") ?: continue
            val lon = obj.doubleOrStringDoubleOrNull("lon") ?: continue
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                continue
            }
            val displayName = obj.stringOnlyOrNull("display_name")?.trim { it.isWhitespace() }.orEmpty()
            val rawName = obj.stringOnlyOrNull("name")?.trim { it.isWhitespace() }.orEmpty()
            val name = rawName.ifEmpty {
                displayName.substringBefore(",").trim { it.isWhitespace() }.ifEmpty {
                    String.format(Locale.US, "%.5f, %.5f", lat, lon)
                }
            }
            val resolvedDisplayName = displayName.ifEmpty { name }
            results.add(
                NominatimPlace(
                    displayName = resolvedDisplayName,
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    coordinateSystem = CoordinateSystem.WGS84,
                ),
            )
        }
        return results
    }
}
