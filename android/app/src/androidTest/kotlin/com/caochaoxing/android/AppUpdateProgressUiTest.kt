package com.caochaoxing.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.state.*
import com.caochaoxing.android.ui.screens.AppUpdateHost
import com.caochaoxing.android.ui.screens.CourseListScreen
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import com.caochaoxing.core.service.UpdateAsset
import com.caochaoxing.core.service.UpdateRelease
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
                    activity.setContent { CaochaoxingTheme {
                        CourseListScreen(app.appState, {}, {}, {}, {})
                        AppUpdateHost(app.updateManager)
                    } }
                }
                click("查看更新")
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
        } finally { instrumentation.runOnMainSync { app.updateManager.closeDialog(); state.value = previous } }
    }

    @Test fun launchAnnouncesNewReleaseOnceAndStaysQuietWhenCurrentOrOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        val automation = instrumentation.uiAutomation
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val asset = UpdateAsset(platform = "android", url = "https://example.test/update.apk", size = 20_000_000)
        val available = AppUpdateManager(app, app.applicationScope, latestRelease = {
            reads.incrementAndGet()
            UpdateRelease("99.0.0", "测试更新说明", listOf(asset))
        })
        val current = AppUpdateManager(app, app.applicationScope, latestRelease = {
            UpdateRelease(BuildConfig.VERSION_NAME, assets = listOf(asset))
        })
        val offline = AppUpdateManager(app, app.applicationScope, latestRelease = { error("测试断网") })
        fun node(text: String, root: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
            if (root?.text?.toString() == text) return root
            return (0 until (root?.childCount ?: 0)).firstNotNullOfOrNull { node(text, root?.getChild(it)) }
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < deadline && !condition()) SystemClock.sleep(100)
            assertTrue(condition())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun show(manager: AppUpdateManager) = scenario.onActivity { activity ->
                activity.setContent { CaochaoxingTheme {
                    Text("我的课程", Modifier.systemBarsPadding().padding(20.dp))
                    AppUpdateHost(manager)
                } }
            }
            show(available)
            await { node("最新版本 99.0.0") != null && node("稍后") != null }
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(app.cacheDir, "update-on-launch.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            var later = checkNotNull(node("稍后"))
            while (!later.isClickable) later = checkNotNull(later.parent)
            assertTrue(later.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { node("我的课程") != null && node("最新版本 99.0.0") == null }
            show(available)
            instrumentation.waitForIdleSync()
            assertEquals(1, reads.get())
            assertFalse(available.dialogVisible.value)
            show(current)
            await { current.state.value.phase == UpdatePhase.CURRENT }
            assertFalse(current.dialogVisible.value)
            show(offline)
            await { offline.state.value.phase == UpdatePhase.CHECK_FAILED }
            assertFalse(offline.dialogVisible.value)
        }
    }
}
