package com.caochaoxing.android

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntroductionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.getSharedPreferences("introduction", Context.MODE_PRIVATE)
    private val automation = instrumentation.uiAutomation

    private fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node != null) {
            yield(node)
            for (index in 0 until node.childCount) yieldAll(nodes(node.getChild(index)))
        }
    }

    private fun find(text: String) = nodes(automation.rootInActiveWindow).firstOrNull {
        it.text?.toString() == text || it.contentDescription?.toString() == text
    }

    private fun waitFor(text: String): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            find(text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing UI element: $text")
    }

    private fun click(text: String) {
        val node = generateSequence(waitFor(text)) { it.parent }.first { it.isClickable }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        automation.waitForIdle(300, 5000)
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture") != "true") return
        SystemClock.sleep(400)
        val bitmap = checkNotNull(automation.takeScreenshot())
        File(context.cacheDir, "introduction-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun fourPagesCompleteOnceAndSurviveRecreation() {
        val previous = preferences.getBoolean("completed", false)
        assertTrue(preferences.edit().putBoolean("completed", false).commit())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val titles = listOf(
                    "欢迎使用\nCaochaoxing", "AI 答题\n让思路更清楚",
                    "刷课系统\n学习进度随时看", "签到系统\n课堂活动及时处理",
                )
                titles.forEachIndexed { index, title ->
                    waitFor(title)
                    waitFor("第 ${index + 1} 页，共 4 页")
                    capture((index + 1).toString())
                    if (index == 1) {
                        scenario.recreate()
                        waitFor(title)
                    }
                    click(if (index == 3) "开始使用" else "下一页")
                }
                val until = SystemClock.uptimeMillis() + 5000
                while (!preferences.getBoolean("completed", false) && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
                assertTrue(preferences.getBoolean("completed", false))
                scenario.recreate()
                automation.waitForIdle(350, 5000)
                assertNull(find(titles.first()))
                assertNull(find("跳过"))
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                automation.waitForIdle(350, 5000)
                assertNull(find("欢迎使用\nCaochaoxing"))
            }
        } finally {
            preferences.edit().putBoolean("completed", previous).commit()
        }
    }

    @Test fun skippingIsAlsoRemembered() {
        val previous = preferences.getBoolean("completed", false)
        assertTrue(preferences.edit().putBoolean("completed", false).commit())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                click("跳过")
                val until = SystemClock.uptimeMillis() + 5000
                while (!preferences.getBoolean("completed", false) && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
                assertTrue(preferences.getBoolean("completed", false))
                scenario.recreate()
                automation.waitForIdle(350, 5000)
                assertNull(find("跳过"))
            }
        } finally {
            preferences.edit().putBoolean("completed", previous).commit()
        }
    }
}
