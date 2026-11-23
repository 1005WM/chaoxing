package com.caochaoxing.android.state

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import android.webkit.CookieManager
import com.caochaoxing.core.service.AiQuestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/** Image traffic has no model credentials. Cookies are scoped to the requested Chaoxing host. */
internal class AiQuestionImageReader(
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS).build(),
    private val cookies: (String) -> String? = { CookieManager.getInstance().getCookie(it) },
) {
    suspend fun prepare(question: AiQuestion): AiQuestion = withContext(Dispatchers.IO) {
        require(!question.hasImage || question.images.isNotEmpty()) { "未提取到题图，请等图片加载后重新打开 AI 作答" }
        require(question.images.size <= 6) { "单题最多支持 6 张题图，请分题处理" }
        var total = 0
        val images = question.images.map { image ->
            val bytes = if (image.source.startsWith("data:image/")) {
                require(image.source.substringBefore(',').endsWith(";base64")) { "图片编码格式不支持" }
                require(image.source.length <= 7_000_000) { "题图过大" }
                runCatching { Base64.decode(image.source.substringAfter(','), Base64.DEFAULT) }.getOrElse { error("题图编码无效") }
            } else read(image.source)
            val encoded = encode(bytes)
            total += encoded.length
            require(total <= 12_000_000) { "本题图片总量过大，请减少题图后重试" }
            image.copy(source = encoded)
        }
        question.copy(images = images)
    }

    private suspend fun read(source: String): ByteArray {
        var address = source.toHttpUrlOrNull() ?: error("题图地址无效，请重新读取题目")
        repeat(6) {
            require(address.isHttps && address.username.isEmpty() && address.password.isEmpty()) { "题图需要使用 HTTPS 地址" }
            val builder = Request.Builder().url(address)
            if (address.host == "chaoxing.com" || address.host.endsWith(".chaoxing.com")) {
                cookies(address.toString())?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
                builder.header("Referer", "https://mooc1.chaoxing.com/")
            }
            val result = fetch(builder.build())
            if (result.redirect != null) {
                address = address.resolve(result.redirect) ?: error("题图跳转地址无效")
            } else return checkNotNull(result.bytes)
        }
        error("题图跳转次数过多，请重新打开作业")
    }

    private data class ImageResponse(val bytes: ByteArray? = null, val redirect: String? = null)

    private suspend fun fetch(request: Request): ImageResponse = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("题图下载失败，请检查网络后重试"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val result = if (response.isRedirect) {
                            ImageResponse(redirect = response.header("Location") ?: error("题图缺少跳转地址"))
                        } else {
                            require(response.isSuccessful) { "题图读取失败 HTTP ${response.code}，请刷新题目后重试" }
                            val body = response.body ?: error("题图内容为空")
                            require(body.contentLength() <= 5_000_000) { "题图超过 5 MB" }
                            val buffer = okio.Buffer()
                            val input = body.source()
                            while (buffer.size <= 5_000_000 && input.read(buffer, 8192) != -1L) { }
                            require(buffer.size in 1..5_000_000) { "题图为空或超过 5 MB" }
                            ImageResponse(bytes = buffer.readByteArray())
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            }
        })
    }

    private fun encode(bytes: ByteArray): String {
        require(bytes.size <= 5_000_000) { "题图超过 5 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码题图，请刷新题目图片后重试" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 3072) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("无法读取图片像素")
        try {
            val scale = minOf(1.0, 2048.0 / max(bitmap.width, bitmap.height))
            val output = Bitmap.createBitmap(max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(output)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, output.width, output.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                val stream = ByteArrayOutputStream()
                check(output.compress(Bitmap.CompressFormat.JPEG, 92, stream)) { "题图转换失败" }
                return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } finally { output.recycle() }
        } finally { bitmap.recycle() }
    }
}
