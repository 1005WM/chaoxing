// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.state.AssessmentOverviewController
import com.caochaoxing.android.state.ManualAssessmentCompletionStore
import com.caochaoxing.android.state.completionKey
import com.caochaoxing.android.ui.screens.TodoListContent
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.*
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AssessmentTodoSwipeTest {
    @Test fun leftSwipeCompletesWorkPersistsAndSupportsUndoWithoutOpeningWeb() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val phone = "swipe-fixture-${UUID.randomUUID()}"
        val store = ManualAssessmentCompletionStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val course = ChaoxingCourse(courseName = "概率论与数理统计", teacherName = "老师", courseId = 1, classId = 2, imageURL = "")
        val work = ChaoxingAssessmentItem(ChaoxingAssessmentKind.WORK, "矩估计练习", "未交",
            courseId = 1, classId = 2, courseName = course.courseName,
            taskUrl = "https://mooc1.chaoxing.com/work?workId=1")
        val second = work.copy(title = "最大似然估计练习", taskUrl = "https://mooc1.chaoxing.com/work?workId=2")
        val exam = work.copy(kind = ChaoxingAssessmentKind.EXAM, title = "期中考试")
        val client = ChaoxingHttpClient(session = ChaoxingAccountSession(
            phoneNumber = phone, encryptedPassword = "fixture", name = "测试", uid = 1, puid = 2, deviceCode = "fixture"))
        val controller = AssessmentOverviewController(
            clientProvider = { client }, scope = scope,
            fetchSummary = { _, _ -> ChaoxingAssessmentSummary(listOf(work, second), listOf(exam)) },
            loadCompletedWorks = store::load, saveCompletedWorks = store::save,
        )
        val opens = AtomicInteger()
        fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
            if (node != null) { yield(node); for (i in 0 until node.childCount) yieldAll(nodes(node.getChild(i))) }
        }
        fun waitFor(predicate: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < until && !predicate()) SystemClock.sleep(100)
            assertTrue("界面未达到预期状态", predicate())
        }
        fun titleBounds(title: String): Rect = Rect().also { rect ->
            nodes(automation.rootInActiveWindow).first { it.text?.toString() == title }.getBoundsInScreen(rect)
        }
        fun swipe(title: String, left: Boolean, capture: Boolean = false) {
            val y = titleBounds(title).centerY().toFloat()
            val width = context.resources.displayMetrics.widthPixels
            val start = width * if (left) .85f else .15f
            val end = width * if (left) .15f else .85f
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, x: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(automation.injectInputEvent(event, true)); event.recycle()
            }
            event(MotionEvent.ACTION_DOWN, start)
            for (i in 1..20) {
                event(MotionEvent.ACTION_MOVE, start + (end - start) * i / 20)
                SystemClock.sleep(16)
                if (capture && i == 12) {
                    SystemClock.sleep(250)
                    val bitmap = checkNotNull(automation.takeScreenshot())
                    File(context.cacheDir, "assessment-swipe-preview.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            event(MotionEvent.ACTION_UP, end)
            automation.waitForIdle(400, 5000)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    controller.onAccountChanged(phone)
                    controller.load(listOf(course))
                    activity.setContent {
                        CaochaoxingTheme {
                            Column(Modifier.fillMaxSize().background(CxPalette.canvas).systemBarsPadding()) {
                                Text("未完成作业", modifier = Modifier.padding(20.dp), style = CxTypography.title, color = CxPalette.ink)
                                TodoListContent(controller.uiState.collectAsState().value,
                                    onCourseClick = {}, onOpenWeb = { opens.incrementAndGet() },
                                    onMarkCompleted = { controller.setWorkCompleted(it, true, phone) })
                            }
                        }
                    }
                }
                waitFor { nodes(automation.rootInActiveWindow).any { it.text?.toString() == work.title } }
                swipe(work.title, left = false)
                assertEquals(2, controller.uiState.value.pendingWorkCount)
                swipe(exam.title, left = true)
                assertEquals(1, controller.uiState.value.pendingExamCount)
                val opensBeforeLeftSwipe = opens.get()
                swipe(work.title, left = true, capture = true)
                waitFor { controller.uiState.value.pendingWorkCount == 1 }
                waitFor { nodes(automation.rootInActiveWindow).none { it.text?.toString() == work.title } }
                assertEquals(opensBeforeLeftSwipe, opens.get())
                assertTrue(ManualAssessmentCompletionStore(context).load(phone).contains(work.completionKey))
                instrumentation.runOnMainSync { assertTrue(controller.setWorkCompleted(work, false, phone)) }
                waitFor { nodes(automation.rootInActiveWindow).any { it.text?.toString() == work.title } }
                assertEquals(2, controller.uiState.value.pendingWorkCount)
                assertFalse(store.load(phone).contains(work.completionKey))
            }
        } finally {
            scope.cancel()
            store.save(phone, emptySet())
        }
    }
}
