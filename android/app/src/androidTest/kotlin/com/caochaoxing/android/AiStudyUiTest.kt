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
