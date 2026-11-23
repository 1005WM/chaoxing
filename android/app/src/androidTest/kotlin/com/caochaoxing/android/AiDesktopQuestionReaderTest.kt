package com.caochaoxing.android

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.state.AiQuestionPageReader
import com.caochaoxing.android.state.EXTRACT_AI_QUESTIONS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AiDesktopQuestionReaderTest {
    @Test
    fun skipsAssignmentShellAndReadsFiveQuestionsFromCrossOriginFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        val image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2qSAAAAAASUVORK5CYII="
        val frameUrl = "https://mooc1-2.chaoxing.com/mooc-ans/work/doHomeWorkNew?workId=fixture"
        val questionsHtml = "<div class='TiMu'>" + (1..5).joinToString("") { number ->
            """<div class="questionLi singleQuesId" data="$number"><h3 class="mark_name">$number. (单选题)</h3><p class="stem">设总体服从均匀分布，请求参数的矩估计。第 $number 题</p><img src="$image"><ul class="mark_letter">""" +
                ('A'..'D').joinToString("") { """<li class="workTextWrap"><span>$it.</span><img src="$image"></li>""" } + "</ul></div>"
        } + "</div>"
        val shell = """<div class="TiMu"><h3>矩估计巩固作业（必做）</h3><p>题量:5 满分:100</p><div class="questionLi">1 2 3 4 5</div><iframe src="$frameUrl"></iframe></div>"""
        val ready = ArrayBlockingQueue<WebView>(1)
        val opened = AtomicInteger()
        var root: WebView? = null
        instrumentation.runOnMainSync {
            root = WebView(app).apply {
                settings.javaScriptEnabled = true
                settings.userAgentString = "DesktopQuestionReaderTest"
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { ready.offer(view) }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/html", "UTF-8", (if (request.url.toString() == frameUrl) questionsHtml else "").byteInputStream())
                }
                loadDataWithBaseURL("https://mooc1.chaoxing.com/fixture", shell, "text/html", "UTF-8", null)
            }
        }
        try {
            val page = checkNotNull(ready.poll(10, TimeUnit.SECONDS))
            val raw = ArrayBlockingQueue<String>(1)
            instrumentation.runOnMainSync { page.evaluateJavascript(EXTRACT_AI_QUESTIONS) { raw.offer(it) } }
            assertTrue(app.aiStudyController.decodeQuestions(checkNotNull(raw.poll(5, TimeUnit.SECONDS))).isEmpty())
            val reader = AiQuestionPageReader(app, app.appState, app.aiStudyController::decodeQuestions) {
                object : WebView(app) {
                    override fun loadUrl(url: String) {
                        assertEquals(frameUrl, url)
                        assertEquals("DesktopQuestionReaderTest", settings.userAgentString)
                        opened.incrementAndGet()
                        loadDataWithBaseURL(url, questionsHtml, "text/html", "UTF-8", null)
                    }
                }
            }
            val questions = runBlocking { reader.fromView(page) }
            assertEquals(1, opened.get())
            assertEquals(5, questions.size)
            assertTrue(questions.all { it.type == "单选" && it.options.size == 4 && it.images.size == 5 })
            assertTrue(questions.all { it.text.contains("均匀分布") && !it.text.contains("巩固作业") })
            assertEquals(listOf("题干图片 1", "选项 A 图片 1", "选项 B 图片 1", "选项 C 图片 1", "选项 D 图片 1"), questions[0].images.map { it.label })
            val changed = ArrayBlockingQueue<String>(1)
            instrumentation.runOnMainSync { page.evaluateJavascript("document.querySelector('p').innerText='题量:6 满分:100'") { changed.offer(it) } }
            checkNotNull(changed.poll(5, TimeUnit.SECONDS))
            val error = runCatching { runBlocking { reader.fromView(page) } }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("页面显示 6 道题，当前识别到 5 道"))
        } finally { instrumentation.runOnMainSync { root?.destroy() } }
    }
}
