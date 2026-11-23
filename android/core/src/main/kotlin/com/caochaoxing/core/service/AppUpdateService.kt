package com.caochaoxing.core.service

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object UpdateSource {
    const val REPOSITORY = "1005WM/chaoxing"
    const val MIRROR_LATEST_URL = ""
    const val INCLUDE_PRERELEASE = false
}

data class AppVersion(private val numbers: List<BigInteger>, val prerelease: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        numbers.zip(other.numbers).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return if (prerelease.isEmpty() && other.prerelease.isEmpty()) 0 else if (prerelease.isEmpty()) 1 else -1
        }
        prerelease.zip(other.prerelease).forEach { (a, b) ->
            val x = a.toBigIntegerOrNull()
            val y = b.toBigIntegerOrNull()
            val result = when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        fun parse(raw: String): AppVersion {
            val match = Regex("^[vV]?(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?(?:\\.(0|[1-9][0-9]*))?(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
                .matchEntire(raw.trim()) ?: error("无法识别版本号 $raw")
            val pre = match.groupValues[4].split('.').filter { it.isNotEmpty() }
            require(pre.none { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) { "预发布版本号格式不正确" }
            return AppVersion((1..3).map { match.groupValues[it].ifEmpty { "0" }.toBigInteger() }, pre)
        }
    }
}

@Serializable
data class UpdateAsset(val platform: String = "", val arch: String = "universal", val url: String, val size: Long = 0, val sha256: String? = null)

@Serializable
data class UpdateRelease(val version: String, val notes: String = "", val assets: List<UpdateAsset> = emptyList(),
    val publishedAt: String = "", val prerelease: Boolean = false, val fromMirror: Boolean = false)

/** Anonymous update traffic uses its own client, with no account cookies or credentials. */
class UpdateHttp(timeoutSeconds: Long = 20) {
    private val client = OkHttpClient.Builder().callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .connectTimeout(minOf(timeoutSeconds, 10), TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followSslRedirects(false).addNetworkInterceptor { chain ->
            require(chain.request().url.isHttps) { "更新仅支持 HTTPS" }
            chain.proceed(chain.request())
        }.build()

    suspend fun <T> get(url: String, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || !parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            continuation.resumeWithException(IOException("更新地址必须是有效的 HTTPS 地址"))
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(Request.Builder().url(parsed).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Caochaoxing-update").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("连接超时或网络不可用，请重试"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        when (response.code) {
                            403, 429 -> error("更新源访问受限或请求已达上限，请稍后重试")
                            404 -> error("仓库尚无可用的正式发布版本")
                        }
                        require(response.isSuccessful) { "更新源返回 HTTP ${response.code}" }
                        val result = consume(response)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }
}

class AppUpdateService(private val mirror: String = UpdateSource.MIRROR_LATEST_URL) {
    private val json = Json { ignoreUnknownKeys = true }
    private suspend fun document(url: String, timeout: Long = 20): String = UpdateHttp(timeout).get(url) { response ->
        val source = response.body?.source() ?: error("更新源返回空内容")
        val buffer = okio.Buffer()
        while (buffer.size <= 1_000_000 && source.read(buffer, 8192) != -1L) { }
        require(buffer.size <= 1_000_000) { "更新信息过大" }
        buffer.readUtf8()
    }

    suspend fun latest(): UpdateRelease {
        if (mirror.isNotBlank()) {
            try {
                val release = json.decodeFromString<UpdateRelease>(document(mirror, 2))
                validate(release)
                if (UpdateSource.INCLUDE_PRERELEASE || (!release.prerelease && AppVersion.parse(release.version).prerelease.isEmpty())) return release.copy(fromMirror = true)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Fall back to the public release feed. */ }
        }
        return githubLatest()
    }

    suspend fun githubLatest(): UpdateRelease {
        val root = "https://api.github.com/repos/${UpdateSource.REPOSITORY}/releases"
        val raw = try { json.parseToJsonElement(document(if (UpdateSource.INCLUDE_PRERELEASE) root else "$root/latest")) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            try {
                return json.decodeFromString<UpdateRelease>(document("https://github.com/${UpdateSource.REPOSITORY}/releases/latest/download/latest.json"))
                    .copy(fromMirror = false).also(::validate)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { throw e }
        }
        val release = if (raw is JsonArray) raw.firstOrNull { it.jsonObject["draft"]?.jsonPrimitive?.booleanOrNull != true }
            ?.jsonObject ?: error("仓库尚无发布版本") else raw.jsonObject
        val assets = release["assets"]?.jsonArray.orEmpty().mapNotNull { item ->
            val asset = item.jsonObject
            val name = asset["name"]?.jsonPrimitive?.content.orEmpty().lowercase()
            if (!name.endsWith(".apk")) return@mapNotNull null
            val arch = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").firstOrNull { name.contains(it) } ?: "universal"
            UpdateAsset("android", arch, asset["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                asset["size"]?.jsonPrimitive?.longOrNull ?: 0,
                asset["digest"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"))
        }
        return UpdateRelease(release["tag_name"]?.jsonPrimitive?.content ?: error("发布缺少版本号"),
            release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(), assets,
            release["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            release["prerelease"]?.jsonPrimitive?.booleanOrNull == true).also(::validate)
    }

    private fun validate(release: UpdateRelease) {
        AppVersion.parse(release.version)
        require(release.assets.any { it.platform == "android" && it.url.startsWith("https://") }) { "发布中没有 Android 安装包" }
    }

    fun androidAsset(release: UpdateRelease, abis: List<String>): UpdateAsset =
        abis.firstNotNullOfOrNull { abi -> release.assets.firstOrNull { it.platform == "android" && it.arch == abi } }
            ?: release.assets.firstOrNull { it.platform == "android" && it.arch in listOf("universal", "all", "") }
            ?: error("发布中没有适配本机架构的安装包")
}
