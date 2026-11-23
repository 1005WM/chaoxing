package com.caochaoxing.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.state.*
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import com.caochaoxing.android.ui.screens.AiDraftSheet
import com.caochaoxing.core.service.AiAnswerService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AiQuestionMediaTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as CaochaoxingApp
    private lateinit var host: MainActivity
    private fun colorImage(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle() }.toByteArray()
    }
    private fun withPage(html: String, block: (WebView) -> Unit) {
        val ready = ArrayBlockingQueue<WebView>(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var web: WebView? = null
            scenario.onActivity { activity ->
                host = activity
                activity.setContent { CaochaoxingTheme { AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    WebView(context).apply {
                        web = this
                        settings.javaScriptEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) { ready.offer(view) }
                            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? =
                                if(request.url.path == "/fixture-lazy-blue.png") android.webkit.WebResourceResponse("image/png",null,colorImage(Color.BLUE).inputStream()) else null
                        }
                        loadDataWithBaseURL("https://mooc1.chaoxing.com/fixture", "<meta name='viewport' content='width=device-width,initial-scale=1'>" + html, "text/html", "UTF-8", null)
                    }
                }) } }
            }
            try { block(checkNotNull(ready.poll(10, TimeUnit.SECONDS))) }
            finally { instrumentation.runOnMainSync { web?.stopLoading(); web?.destroy() } }
        }
    }

    @Test fun readsLatexMathmlAndMathJaxInsteadOfDroppingFormula() {
        val html = """<p>题量:1</p><article class="singleQuesId"><h3 class="mark_name">1. 单选题</h3>
            <p>求参数的矩估计 <span data-latex="\\theta=2\\bar{X}"><svg width="80" height="40"><path d="M1 1L50 20"/></svg></span></p>
            <ul class="mark_letter"><li>A. <math><mfrac><mn>1</mn><mn>2</mn></mfrac></math></li>
            <li>B. <mjx-container><svg><g data-mml-node="math"><g data-mml-node="msup"><g data-mml-node="mi"><path data-c="58"/></g><g data-mml-node="mn"><path data-c="32"/></g></g></g></svg></mjx-container></li></ul>
            <div class="analysis">PRIVATE_ANSWER</div><input type="hidden" value="PRIVATE_SESSION"></article>"""
        withPage(html) { view ->
            val q = runBlocking { app.aiStudyController.readPage(view) }.questions.single()
            assertTrue(q.text.contains("求参数的矩估计"))
            assertTrue(q.text.contains("theta=2"))
            assertTrue(q.options[0].contains("(1)/(2)"))
            assertTrue(q.options[1].contains("(X)^(2)"))
            assertTrue(q.images.isEmpty())
            assertFalse(q.text.contains("PRIVATE_"))
            assertEquals("", q.readError)
        }
    }

    @Test fun sendsStemAndOptionSvgPixelsWithCompleteTextAndCorrectLabels() {
        val html = """<p>题量:1</p><svg width="0" height="0"><defs><path id="stemPath" d="M0 0H90V35H0Z"/></defs></svg>
          <article class="singleQuesId"><h3 class="mark_name">1. 单选题</h3><p>根据红色题干图，选择蓝色选项。</p>
          <svg aria-hidden="true" width="90" height="35" viewBox="0 0 90 35"><use href="#stemPath" fill="red"/></svg>
          <ul class="mark_letter"><li>A. <svg width="90" height="35"><rect width="90" height="35" fill="blue"/></svg></li><li>B. 其他</li></ul></article>"""
        withPage(html) { view ->
            val q = runBlocking { app.aiStudyController.readPage(view) }.questions.single()
            assertEquals("", q.readError)
            assertEquals(listOf("题干图片 1", "选项 A 图片 1"), q.images.map { it.label })
            val colors = q.images.map { image ->
                assertTrue(image.source.startsWith("data:image/png;base64,"))
                val bytes = Base64.decode(image.source.substringAfter(','), Base64.DEFAULT)
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2).also { bitmap.recycle() }
            }
            assertEquals(Color.RED, colors[0])
            assertEquals(Color.BLUE, colors[1])
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
                val parts = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonArray
                assertTrue(parts[0].jsonObject.getValue("text").jsonPrimitive.content.contains("根据红色题干图"))
                assertEquals("题干图片 1", parts[1].jsonObject.getValue("text").jsonPrimitive.content)
                assertEquals("选项 A 图片 1", parts[3].jsonObject.getValue("text").jsonPrimitive.content)
                assertEquals(2, parts.count { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" })
                assertEquals(q.images[0].source, parts[2].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
                assertEquals(q.images[1].source, parts[4].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
                assertFalse(parts.toString().contains("mooc1.chaoxing.com"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"choices":[{"message":{"content":"{\"answer\":\"A\",\"explanation\":\"fixture\"}"}}]}""".toResponseBody()).build()
            }.build()
            val answer = runBlocking { AiAnswerService(http).answer("https://model.example/v1", "fixture", "vision", AiQuestionImageReader().prepare(q)) }
            assertEquals("A", answer.answer)
            instrumentation.runOnMainSync { host.setContent { CaochaoxingTheme { AiDraftSheet("fixture-preview", "图文识别预览", listOf(q)) {} } } }
            val automation = instrumentation.uiAutomation
            fun nodes(node: android.view.accessibility.AccessibilityNodeInfo?): Sequence<android.view.accessibility.AccessibilityNodeInfo> = sequence {
                if(node != null) {yield(node);for(i in 0 until node.childCount)yieldAll(nodes(node.getChild(i)))}
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            var clicked = false
            var imageVisible = false
            while(android.os.SystemClock.uptimeMillis() < deadline && !imageVisible) {
                if(!clicked) nodes(automation.rootInActiveWindow).firstOrNull { it.text?.toString() == "查看识别内容" }?.let { target ->
                    var button = target
                    while(!button.isClickable) button = checkNotNull(button.parent)
                    clicked = button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                imageVisible = nodes(automation.rootInActiveWindow).any { it.contentDescription?.toString() == "题干图片 1" }
                android.os.SystemClock.sleep(100)
            }
            assertTrue(imageVisible)
            automation.waitForIdle(400, 5000)
            val preview = checkNotNull(automation.takeScreenshot())
            File(app.cacheDir, "ai-extracted-media-preview.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
            preview.recycle()
        }
    }

    @Test fun capturesAllOffscreenOptionsAndRestoresPageWithoutLeakingOtherContent() {
        val placeholder = "data:image/png;base64," + Base64.encodeToString(colorImage(Color.WHITE),Base64.NO_WRAP)
        val html = """<style>body{margin:0}article{padding:12px}li{list-style:none;padding:0;margin:0}ul{padding:0;margin:0}</style>
          <header style="position:sticky;top:0;height:300px;background:#00ff00">PRIVATE_OTHER_CONTENT</header><p>题量:1</p>
          <article class="singleQuesId"><h3 class="mark_name">1. 单选题</h3><p>请结合整张题干图和全部选项选择。</p>
          <div style="height:900px;background-image:linear-gradient(red,red)"></div><ul class="mark_letter">
          <li style="height:200px">A. 第一选项</li><li style="height:200px">B. 第二选项</li><li style="height:200px">C. 第三选项</li>
          <li style="height:300px">D. 最后一个选项<img style="width:100%;height:280px" data-src="https://mooc1.chaoxing.com/fixture-lazy-blue.png" src="$placeholder"></li></ul>
          <div class="analysis" style="height:300px;background:#ff00ff">PRIVATE_ANSWER</div><textarea>PRIVATE_INPUT</textarea></article>"""
        withPage(html) { view ->
            val raw = ArrayBlockingQueue<String>(1)
            instrumentation.runOnMainSync { view.evaluateJavascript("window.scrollTo(0,150);window.scrollY") { raw.offer(it) } }
            val initial = raw.poll(5, TimeUnit.SECONDS)
            val q = runBlocking { app.aiStudyController.readPage(view) }.questions.single()
            assertEquals("", q.readError)
            assertTrue(q.images.size >= 2)
            assertEquals(4, q.options.size)
            var red = false; var blue = false
            q.images.forEachIndexed { index, image ->
                assertTrue(image.label.startsWith("完整题目截图"))
                val bytes = Base64.decode(image.source.substringAfter(','), Base64.DEFAULT)
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                File(app.cacheDir, "whole-question-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
                    val color = bitmap.getPixel(x,y)
                    assertNotEquals(Color.GREEN, color)
                    assertNotEquals(Color.MAGENTA, color)
                    if (color == Color.RED) red = true
                    if (color == Color.BLUE) blue = true
                }
                bitmap.recycle()
            }
            assertTrue(red); assertTrue(blue)
            instrumentation.runOnMainSync { view.evaluateJavascript("window.scrollY") { raw.offer(it) } }
            assertEquals(initial, raw.poll(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { view.evaluateJavascript("document.querySelector('.analysis').style.visibility") { raw.offer(it) } }
            assertEquals("\"\"", raw.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test fun capturesDetachedChapterAndReleasesPrivateDisplay() {
        val ready = ArrayBlockingQueue<WebView>(1)
        var web: WebView? = null
        val manager = app.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val before = manager.displays.map { it.displayId }.toSet()
        instrumentation.runOnMainSync {
            web = WebView(app).apply {
                settings.javaScriptEnabled = true
                layout(0, 0, 1080, 1600)
                webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String) {ready.offer(view)} }
                loadDataWithBaseURL("https://mooc1.chaoxing.com/fixture", """<article class="singleQuesId"><h3 class="mark_name">1. 单选题</h3><div style="width:300px;height:300px;background-image:linear-gradient(red,red)"></div><ul class="mark_letter"><li>A. 甲</li><li>B. 乙</li></ul></article>""", "text/html", "UTF-8", null)
            }
        }
        try {
            val q = runBlocking { app.aiStudyController.readPage(checkNotNull(ready.poll(10,TimeUnit.SECONDS))) }.questions.single()
            assertEquals("", q.readError)
            assertTrue(q.images.isNotEmpty())
            assertTrue(q.images.first().label.startsWith("完整题目截图"))
            val bytes = Base64.decode(q.images.first().source.substringAfter(','), Base64.DEFAULT)
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertTrue((0 until bitmap.height step 8).any { y -> (0 until bitmap.width step 8).any { x -> bitmap.getPixel(x,y) == Color.RED } })
            bitmap.recycle()
            assertEquals(before, manager.displays.map { it.displayId }.toSet())
        } finally { instrumentation.runOnMainSync { web?.destroy() } }
    }
}
