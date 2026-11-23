package com.caochaoxing.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.state.*
import com.caochaoxing.android.ui.screens.CourseListScreen
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppUpdateProgressUiTest {
    @Test fun progressIsOnlyVisibleInsideUpdateDialog() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        @Suppress("UNCHECKED_CAST")
        val state = AppUpdateManager::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(app.updateManager) as MutableStateFlow<AppUpdateState>
        val previous = state.value
        val automation = instrumentation.uiAutomation
        fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
            if (node != null) { yield(node); for (i in 0 until node.childCount) yieldAll(nodes(node.getChild(i))) }
        }
        fun waitFor(text: String): AccessibilityNodeInfo {
            val until = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < until) {
                nodes(automation.rootInActiveWindow).firstOrNull { it.text?.toString()?.contains(text) == true }?.let { return it }
                SystemClock.sleep(100)
            }
            error("Missing $text")
        }
        fun click(text: String) {
            var node = waitFor(text)
            while (!node.isClickable) node = checkNotNull(node.parent)
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    state.value = AppUpdateState(UpdatePhase.DOWNLOADING, size = 20L * 1048576, received = 8L * 1048576)
                    activity.setContent { CaochaoxingTheme { CourseListScreen(app.appState, {}, {}, {}, {}) } }
                }
                click("下载 40%")
                waitFor("下载更新 40%")
                waitFor("8.0 MB / 20.0 MB")
                assertTrue(nodes(automation.rootInActiveWindow).any { node -> node.rangeInfo?.current?.let { it in 0.39f..0.41f } == true })
                automation.waitForIdle(400, 5000)
                val bitmap = checkNotNull(automation.takeScreenshot())
                File(app.cacheDir, "update-progress.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                click("关闭")
                waitFor("我的课程")
                assertFalse(nodes(automation.rootInActiveWindow).any { it.text?.toString()?.contains("8.0 MB / 20.0 MB") == true })
                assertFalse(nodes(automation.rootInActiveWindow).any { it.rangeInfo != null })
            }
        } finally { instrumentation.runOnMainSync { state.value = previous } }
    }
}
