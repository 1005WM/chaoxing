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
            val questions = runBlocking { reader.fromView(page) }.questions
            assertEquals(1, opened.get())
            assertEquals(5, questions.size)
            assertTrue(questions.all { it.type == "单选" && it.options.size == 4 && it.images.size == 5 })
            assertTrue(questions.all { it.text.contains("均匀分布") && !it.text.contains("巩固作业") })
            assertEquals(listOf("题干图片 1", "选项 A 图片 1", "选项 B 图片 1", "选项 C 图片 1", "选项 D 图片 1"), questions[0].images.map { it.label })
            val changed = ArrayBlockingQueue<String>(1)
            instrumentation.runOnMainSync { page.evaluateJavascript("document.querySelector('p').innerText='题量:6 满分:100'") { changed.offer(it) } }
            checkNotNull(changed.poll(5, TimeUnit.SECONDS))
            val partial = runBlocking { reader.fromView(page) }
            assertEquals(5, partial.questions.size)
            assertTrue(partial.warning.contains("页面共 6 道题，已识别 5 道"))
        } finally { instrumentation.runOnMainSync { root?.destroy() } }
    }

    private fun question(number: Int, css: String = "questionLi") = """<article class="$css"><h3 class="mark_name">$number. 单选题</h3><p class="stem">第 $number 题的完整题干</p><ul class="mark_letter"><li>A. 选项甲</li><li>B. 选项乙</li></ul></article>"""

    private fun withPage(html: String, verify: (WebView, AiQuestionPageReader) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        val ready = ArrayBlockingQueue<WebView>(1)
        var web: WebView? = null
        instrumentation.runOnMainSync {
            web = WebView(app).apply {
                settings.javaScriptEnabled = true
                layout(0, 0, 1080, 1920)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { ready.offer(view) }
                }
                loadDataWithBaseURL("https://mooc1.chaoxing.com/fixture", html, "text/html", "UTF-8", null)
            }
        }
        try { verify(checkNotNull(ready.poll(10, TimeUnit.SECONDS)), AiQuestionPageReader(app, app.appState, app.aiStudyController::decodeQuestions)) }
        finally { instrumentation.runOnMainSync { web?.destroy() } }
    }

    @Test fun separatesFirstQuestionFromNestedSiblingsAndFindsCustomContainer() {
        val first = """<h3 class="mark_name">1. 单选题</h3><p class="stem">第 1 题的完整题干</p><ul class="mark_letter"><li>A. 选项甲</li><li>B. 选项乙</li></ul>"""
        val custom = """<article class="customQuestion"><input type="hidden" name="type5" value="0"><p>第 5 题的完整题干</p><ul class="mark_letter"><li>A. 选项甲</li><li>B. 选项乙</li></ul></article>"""
        withPage("<p>题量:5</p><div class='TiMu'>" + first + (2..4).joinToString("") { question(it) } + "</div>" + custom) { view, reader ->
            val result = runBlocking { reader.fromView(view) }
            assertEquals(5, result.questions.size)
            assertEquals("", result.warning)
            result.questions.forEachIndexed { index, q ->
                assertTrue(q.text.contains("第 ${index + 1} 题的完整题干"))
                assertEquals(2, q.options.size)
                assertEquals(1, Regex("完整题干").findAll(q.text).count())
            }
        }
    }

    @Test fun scrollsToLoadMissingQuestionAndRestoresPosition() {
        val last = question(5, "customQuestion")
        val html = "<p>题量:5</p><div id='questions' style='height:180px;overflow:auto'>" +
            (1..4).joinToString("") { question(it) } + "<div style='height:800px'></div></div>" +
            """<script>var container=document.getElementById('questions');container.addEventListener('scroll',function(){if(container.scrollTop>0 && !container.dataset.loaded){container.dataset.loaded='yes';setTimeout(function(){container.insertAdjacentHTML('beforeend', '$last');},650);}});</script>"""
        withPage(html) { view, reader ->
            val result = runBlocking { reader.fromView(view) }
            assertEquals(5, result.questions.size)
            assertTrue(result.questions.last().text.contains("第 5 题"))
            val position = ArrayBlockingQueue<String>(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view.evaluateJavascript("document.getElementById('questions').scrollTop") { position.offer(it) }
            }
            assertEquals("0", position.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test fun returnsFourOfFiveQuestionsWithoutBlocking() {
        withPage("<p>题量:5</p>" + (1..4).joinToString("") { question(it) }) { view, reader ->
            val result = runBlocking { reader.fromView(view) }
            assertEquals(4, result.questions.size)
            assertTrue(result.warning.contains("页面共 5 道题，已识别 4 道"))
        }
        withPage("<p>题量:5</p><div class='questionLi'>1 2 3 4 5</div>") { view, reader ->
            val error = runCatching { runBlocking { reader.fromView(view) } }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("未读取到题干和选项"))
        }
    }
}
