package com.caochaoxing.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.ui.screens.AiMathExplanation
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AiMathExplanationTest {
    @Test fun rendersStructuredMathAndLegacyScriptsInTheOfflineView() {
        val input = """似然函数是样本联合密度。
            |\[ L(\theta) = \frac{\theta^n}{\prod_{i=1}^{n} x_i^{\theta+1}} \]
            |并且 x_i^{θ+1} ≥ 1。
            |物理示例 \( F = G\frac{m_1m_2}{r^2} \)，以及 \( \nabla\cdot\vec{E}=\rho/\varepsilon_0 \)。
        """.trimMargin()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val replies = LinkedBlockingQueue<String>()
        fun webView(view: View): WebView? = when (view) {
            is WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { webView(view.getChildAt(it)) }
            else -> null
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { CaochaoxingTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
                        Text("AI 答题解析")
                        AiMathExplanation(input)
                    }
                } }
            }
            fun evaluate(script: String): String? {
                scenario.onActivity { activity ->
                    webView(activity.window.decorView)?.evaluateJavascript(script) { replies.offer(it) }
                }
                return replies.poll(350, TimeUnit.MILLISECONDS)
            }
            val deadline = SystemClock.uptimeMillis() + 12_000
            var rendered = false
            while (!rendered && SystemClock.uptimeMillis() < deadline) {
                rendered = evaluate("""
                        document.querySelectorAll('.katex').length === 4 &&
                        document.querySelectorAll('mfrac').length === 2 &&
                        !!document.querySelector('msubsup') &&
                        innerHeight >= document.querySelector('#answer').getBoundingClientRect().height
                    """.trimIndent()) == "true"
            }
            assertTrue("公式结构、旧写法或显示高度不正确", rendered)
            instrumentation.uiAutomation.waitForIdle(350, 5000)
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(instrumentation.targetContext.cacheDir, "ai-math-explanation.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            assertTrue(evaluate("""
                showExplanation('L(θ)=∏ f(x_i)=∏(θ/x_i^{θ+1})=θ^n/∏ x_i^{θ+1}，且 x_i ≥ 1。<b>原文</b>', '#222', 16);
                document.querySelectorAll('.katex').length === 2 &&
                document.body.textContent.includes('<b>原文</b>') && document.querySelector('#answer b') === null
            """.trimIndent()) == "true")
        }
    }
}
