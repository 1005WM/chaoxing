package com.caochaoxing.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.ui.screens.AiQuestionPreview
import com.caochaoxing.android.ui.screens.AiDraftSheet
import com.caochaoxing.android.ui.screens.AiAnswerContent
import com.caochaoxing.android.ui.screens.AiQuestionCard
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import com.caochaoxing.core.service.AiQuestion
import com.caochaoxing.core.service.AiQuestionImage
import com.caochaoxing.core.service.AiAnswer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AiQuestionCardsScreenshotTest {
    private fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node != null) {
            yield(node)
            for (index in 0 until node.childCount) yieldAll(nodes(node.getChild(index)))
        }
    }

    @Test fun fallbackScreenshotReplacesBrokenTextAndKeepsTheModelReplyVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val bitmap = Bitmap.createBitmap(960, 305, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 33f; color = android.graphics.Color.BLACK }
        listOf("总体 X 的概率密度为 f(x)，其中 x ≥ 1。", "独立抽取 X₁，X₂，…，Xₙ，似然函数为（ ）。",
            "A.  θⁿ × ∏ Xᵢ", "B.  ∏ f(Xᵢ)", "C.  f(X₁ + X₂ + … + Xₙ)", "D.  以上都不对").forEachIndexed { index, row ->
            canvas.drawText(row, 32f, 44f + 45f * index, paint)
        }
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        val screenshot = AiQuestionImage("完整题目截图 1（含题干和选项，按顺序阅读）",
            "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
        val question = AiQuestion("fixture-3", "单选", "3.\n（单选题）\n残缺文字\n，\n则\n题目内容以完整题目截图为准。",
            listOf("", "B 残片", "", "以上都不对"), hasImage = true, images = listOf(screenshot))
        val answer = mutableStateOf(AiAnswer("B", "独立样本的似然函数等于各样本点密度的乘积。"))
        fun waitFor(label: String) {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (nodes(automation.rootInActiveWindow).any { it.text?.toString() == label || it.contentDescription?.toString() == label }) return
                SystemClock.sleep(100)
            }
            throw AssertionError("Missing element $label")
        }
        fun capture(name: String) {
            if (InstrumentationRegistry.getArguments().getString("capture") != "true") return
            automation.waitForIdle(350, 5000)
            val screen = checkNotNull(automation.takeScreenshot())
            File(instrumentation.targetContext.cacheDir, name).outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screen.recycle()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { CaochaoxingTheme {
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
                    Text("AI 作答", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 14.dp))
                    AiQuestionCard(question, 3) { AiAnswerContent(answer.value, onCopy = {}) }
                }
            } } }
            waitFor(screenshot.label)
            waitFor("建议答案")
            assertFalse(nodes(automation.rootInActiveWindow).any { it.text?.toString() in listOf("残缺文字", "B 残片", "A", "C") })
            capture("ai-compact-question.png")
            scenario.onActivity {
                answer.value = AiAnswer("", "我倾向于 B，但截图里的第二个条件看不清，需要再确认。", "未能确认标准答案，已保留模型回复。")
            }
            waitFor("模型原始回复")
            waitFor(answer.value.explanation)
            capture("ai-format-fallback.png")
        }
    }

    @Test fun showsSeparateCardsAndKeepsStemAndOptionsDistinct() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        val firstStem = "设总体 X 在 (0, θ) 上服从均匀分布，参数 θ 的矩估计为（ ）。"
        val secondStem = "样本均值不一定等于总体均值。"
        val questions = listOf(
            AiQuestion("1", "单选", "1. 单选题\n$firstStem\nA. X̄\nB. X̄ − 1\nC. 2X̄ − 1\nD. 2X̄", listOf("X̄", "X̄ − 1", "2X̄ − 1", "2X̄")),
            AiQuestion("2", "判断", "2. 判断题\n$secondStem\nA. 正确\nB. 错误", listOf("正确", "错误")),
        )
        val automation = instrumentation.uiAutomation
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { CaochaoxingTheme { AiQuestionPreview(questions) {} } } }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline && nodes(automation.rootInActiveWindow).none { it.text?.toString() == "第 2 题" }) SystemClock.sleep(100)
            val text = nodes(automation.rootInActiveWindow).mapNotNull { it.text?.toString() }.toList()
            assertTrue(text.contains("第 1 题"))
            assertTrue(text.contains("第 2 题"))
            assertTrue(text.contains(firstStem))
            assertTrue(text.contains(secondStem))
            assertTrue(text.contains("2X̄ − 1"))
            assertFalse(text.any { it.contains("\nA.") })
            automation.waitForIdle(400, 5000)
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(app.cacheDir, "question-cards-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun answerSheetDisplaysOriginalsInOrderBeforeGeneratingAndOpensZoom() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        val automation = instrumentation.uiAutomation
        fun formula(label: String, expression: String): AiQuestionImage {
            val bitmap = Bitmap.createBitmap(720, 112, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawText(expression, 32f, 79f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 66f })
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            return AiQuestionImage(label, "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
        }
        val stemImage = formula("题干图片 1", "−θ₀ ≤ Xᵢ ≤ θ₀")
        val optionBImage1 = formula("选项 B 图片 1", "2X̄ − 1")
        val optionBImage2 = formula("选项 B 图片 2", "θ̂ = 2X̄ − 1")
        val optionB = "[${optionBImage1.label}] 等价写法 [${optionBImage2.label}]"
        val prefix = "设总体满足"
        val suffix = "求参数的矩估计。"
        val question = AiQuestion("fixture-1", "单选", "$prefix [${stemImage.label}] $suffix\nA. 样本均值\nB. $optionB",
            listOf("样本均值", optionB), hasImage = true, images = listOf(stemImage, optionBImage1, optionBImage2))
        val fallback = formula("完整题目截图 1（含题干和选项，按顺序阅读）", "X̄ = (X₁ + X₂) / 2")
        val second = AiQuestion("fixture-2", "判断", "判断下列陈述。\n题目内容以完整题目截图为准。", hasImage = true, images = listOf(fallback))
        fun waitFor(label: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                nodes(automation.rootInActiveWindow).firstOrNull {
                    it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
                }?.let { return it }
                SystemClock.sleep(100)
            }
            throw AssertionError("Missing visible element $label")
        }
        fun bounds(label: String) = Rect().also { waitFor(label).getBoundsInScreen(it) }
        fun click(label: String) {
            var node = waitFor(label)
            while (!node.isClickable) node = checkNotNull(node.parent)
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun capture(name: String) {
            if (InstrumentationRegistry.getArguments().getString("capture") != "true") return
            automation.waitForIdle(400, 5000)
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(app.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { CaochaoxingTheme {
                    AiDraftSheet("fixture-inline-originals", "矩估计练习", listOf(question, second)) {}
                } }
            }
            val order = listOf(prefix, stemImage.label, suffix, "A", optionBImage1.label, "等价写法", optionBImage2.label)
            val positions = order.map { bounds(it).top }
            assertTrue(positions.zipWithNext().all { (above, below) -> above < below })
            val optionLabel = bounds("B")
            val firstOptionImage = bounds(optionBImage1.label)
            assertTrue(optionLabel.right < firstOptionImage.left && optionLabel.top < firstOptionImage.bottom &&
                optionLabel.bottom > firstOptionImage.top)
            assertFalse(app.aiStudyController.drafts.value.containsKey("fixture-inline-originals"))
            capture("ai-inline-originals.png")
            click(optionBImage2.label)
            waitFor("放大")
            click("放大")
            waitFor("复位")
            capture("ai-zoom-original.png")
            click("关闭")
            waitFor("AI 作答")
            val list = nodes(automation.rootInActiveWindow).first {
                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
            }
            assertTrue(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            assertTrue(bounds("整题截图 1").top < bounds(fallback.label).top)
        }
    }
}
