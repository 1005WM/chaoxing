package com.caochaoxing.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.ui.screens.AiQuestionPreview
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import com.caochaoxing.core.service.AiQuestion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiQuestionCardsScreenshotTest {
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
        fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
            if (node != null) { yield(node); for (index in 0 until node.childCount) yieldAll(nodes(node.getChild(index))) }
        }
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
}
