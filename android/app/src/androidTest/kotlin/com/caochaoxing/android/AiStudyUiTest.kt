package com.caochaoxing.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.state.AiConfig
import com.caochaoxing.android.state.AiStudyController
import com.caochaoxing.android.state.EXTRACT_AI_QUESTIONS
import com.caochaoxing.android.state.AiQuestionImageReader
import com.caochaoxing.android.ui.screens.*
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import com.caochaoxing.core.service.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiStudyUiTest {
    @Test
    fun retainsWholeJudgmentStatementInstructionsAndStemImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        val image = "data:image/png;base64," + testImage()
        val result = java.util.concurrent.ArrayBlockingQueue<String>(1)
        var web: android.webkit.WebView? = null
        instrumentation.runOnMainSync {
            web = android.webkit.WebView(app).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        view.evaluateJavascript(EXTRACT_AI_QUESTIONS) { result.offer(it) }
                    }
                }
                loadDataWithBaseURL("https://fixture.chaoxing.com/", """<div class="TiMu"><h3>判断题</h3><p>请结合下图判断，单位为厘米。</p><div class="stem">图中两个三角形不一定全等。</div><p><img src="$image"></p><label><input type="radio">错误</label><label><input type="radio">正确</label><input name="type1" value="3" type="hidden"><input type="hidden" value="credential"><div class="analysis">参考答案不要提取</div><div style="display:none">隐藏答案</div></div><div class="TiMu"><input name="type2" type="hidden" value="3"><p>队列遵循先进先出的原则。</p></div>""", "text/html", "UTF-8", null)
            }
        }
        try {
            val questions = app.aiStudyController.decodeQuestions(checkNotNull(result.poll(10, java.util.concurrent.TimeUnit.SECONDS)))
            val first = questions.first()
            assertEquals("判断", first.type)
            assertTrue(first.text.contains("请结合下图判断，单位为厘米"))
            assertTrue(first.text.contains("不一定全等"))
            assertEquals(listOf("错误", "正确"), first.options)
            assertEquals(1, first.images.size)
            assertFalse(first.text.contains("参考答案"))
            assertFalse(first.text.contains("credential"))
            assertFalse(first.text.contains("隐藏答案"))
            assertEquals("判断", questions[1].type)
            assertEquals(listOf("正确", "错误"), questions[1].options)
            assertTrue(questions[1].text.contains("先进先出"))
        } finally { instrumentation.runOnMainSync { web?.destroy() } }
    }

    private fun testImage(): String {
        val bitmap = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawText("2 + 2 = ?", 20f, 70f, android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 36f })
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
    }

    @Test
    fun extractsImageOnlyStemAndRetainsImageOptionOrder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        val image = "data:image/png;base64," + testImage()
        val result = java.util.concurrent.ArrayBlockingQueue<String>(1)
        var web: android.webkit.WebView? = null
        instrumentation.runOnMainSync {
            web = android.webkit.WebView(app).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        view.evaluateJavascript(EXTRACT_AI_QUESTIONS) { result.offer(it) }
                    }
                }
                loadDataWithBaseURL("https://fixture.chaoxing.com/", """<div class="TiMu"><h3 class="Zy_TItle"><img src="$image"></h3><ul class="Zy_ulTop"><li>A.<img src="$image"></li><li>B.<img src="$image"></li></ul></div>""", "text/html", "UTF-8", null)
            }
        }
        try {
            val questions = app.aiStudyController.decodeQuestions(checkNotNull(result.poll(10, java.util.concurrent.TimeUnit.SECONDS)))
            val question = questions.single()
            assertTrue(question.text.contains("A."))
            assertEquals(2, question.options.size)
            assertEquals(listOf("题干图片 1", "选项 A 图片 1", "选项 B 图片 1"), question.images.map { it.label })
            val prepared = runBlocking { AiQuestionImageReader().prepare(question) }
            assertTrue(prepared.images.all { it.source.startsWith("data:image/jpeg;base64,") })
        } finally { instrumentation.runOnMainSync { web?.destroy() } }
    }

    @Test
    fun downloadsPrivateImageWithoutLeakingSessionOnRedirect() = runBlocking {
        val bytes = android.util.Base64.decode(testImage(), android.util.Base64.NO_WRAP)
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertNull(chain.request().header("Authorization"))
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            if (chain.request().url.host == "image.chaoxing.com") {
                assertEquals("session=fixture", chain.request().header("Cookie"))
                builder.code(302).message("Redirect").header("Location", "https://cdn.example/image.png").body("".toResponseBody()).build()
            } else {
                assertNull(chain.request().header("Cookie"))
                assertNull(chain.request().header("Referer"))
                builder.code(200).message("OK").body(bytes.toResponseBody()).build()
            }
        }.build()
        val reader = AiQuestionImageReader(http) { "session=fixture" }
        val question = AiQuestion("1", "文字题", "看图", hasImage = true, images = listOf(AiQuestionImage("题干图", "https://image.chaoxing.com/private.png")))
        val prepared = reader.prepare(question)
        assertEquals(2, requests)
        assertTrue(prepared.images.single().source.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun extractsRenderedQuestionsWithoutHiddenCredentials() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        val result = java.util.concurrent.ArrayBlockingQueue<String>(1)
        var web: android.webkit.WebView? = null
        instrumentation.runOnMainSync {
            web = android.webkit.WebView(app).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        view.evaluateJavascript(EXTRACT_AI_QUESTIONS) { result.offer(it) }
                    }
                }
                loadDataWithBaseURL("https://fixture.chaoxing.com/", """<div class="TiMu"><h3 class="Zy_TItle">单选 先进先出</h3><ul class="Zy_ulTop"><li>A. 栈</li><li>B. 队列</li></ul><input type="hidden" value="secret-cookie"></div>""", "text/html", "UTF-8", null)
            }
        }
        try {
            val raw = checkNotNull(result.poll(10, java.util.concurrent.TimeUnit.SECONDS))
            val questions = app.aiStudyController.decodeQuestions(raw)
            assertEquals(1, questions.size)
            assertEquals(listOf("栈", "队列"), questions.first().options)
            assertFalse(raw.contains("secret-cookie"))
        } finally { instrumentation.runOnMainSync { web?.destroy() } }
    }

    @Test
    fun discoversModelsAndShowsGeneratedDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        assumeTrue(runBlocking { app.persistenceStore.load() }.accounts.isEmpty())
        val controller = app.aiStudyController
        val original = controller.config.value
        assumeTrue(original.key.isBlank())
        val apiField = AiStudyController::class.java.getDeclaredField("api").apply { isAccessible = true }
        val originalApi = apiField.get(controller)
        val fake = AiAnswerService(OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.encodedPath.endsWith("models")) {
                """{"data":[{"id":"study-model"},{"id":"study-model-pro"}]}"""
            } else {
                """{"choices":[{"message":{"content":"{\"answer\":\"B\",\"explanation\":\"队列从队尾入队，从队首出队，因此先进入的元素先离开。\"}"}}]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build())
        apiField.set(controller, fake)
        val automation = instrumentation.uiAutomation
        fun descendants(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
            if (node != null) {
                yield(node)
                for (i in 0 until node.childCount) yieldAll(descendants(node.getChild(i)))
            }
        }
        fun waitFor(text: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 12_000
            while (SystemClock.uptimeMillis() < deadline) {
                descendants(automation.rootInActiveWindow).firstOrNull { it.text?.toString()?.contains(text) == true }?.let { return it }
                SystemClock.sleep(100)
            }
            error("Missing element $text")
        }
        fun click(text: String) {
            var node = waitFor(text)
            while (!node.isClickable) node = checkNotNull(node.parent)
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun capture(name: String) {
            automation.waitForIdle(500, 5000)
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(app.cacheDir, "ai-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    controller.save(AiConfig("https://ai.example/v1", "demo-key"))
                    activity.setContent { CaochaoxingTheme { Column(Modifier.fillMaxSize().padding(top = 36.dp)) { AiSettingsCard() } } }
                }
                click("AI 设置")
                waitFor("已获取 2 个模型")
                waitFor("study-model")
                click("保存 AI 设置")
                assertEquals("study-model", controller.config.value.model)
                capture("settings")
                scenario.onActivity { activity ->
                    controller.save(controller.config.value.copy(followStudy = true))
                    controller.generate("fixture-draft", "第 3 章 栈和队列", listOf(AiQuestion("q1", "单选", "哪种结构遵循先进先出？", listOf("栈", "队列"))))
                    activity.setContent { CaochaoxingTheme { Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 44.dp)) { Text("数据结构  /  看课"); AiFollowStudyCard() } } }
                }
                waitFor("生成结束")
                capture("follow")
                click("第 3 章 栈和队列")
                waitFor("建议答案")
                waitFor("队列从队尾入队")
                capture("answer")
            }
        } finally {
            instrumentation.runOnMainSync { controller.save(original); controller.cancel("fixture-draft") }
            apiField.set(controller, originalApi)
        }
    }
}
