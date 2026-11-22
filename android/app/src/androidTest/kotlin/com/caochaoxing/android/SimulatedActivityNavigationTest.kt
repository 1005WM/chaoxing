// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
package com.caochaoxing.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.LiveAppStateDependencies
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import com.caochaoxing.core.model.*
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import com.caochaoxing.core.store.PersistenceStore
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimulatedActivityNavigationTest {
    @Test
    fun openActivitiesAndReturnWithoutSubmitting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        // Launch the host only on a clean install so its normal startup cannot restore a live account.
        assumeTrue(runBlocking { app.persistenceStore.load() }.accounts.isEmpty())
        val automation = instrumentation.uiAutomation
        val course = ChaoxingCourse("交互设计基础", "陈老师", 41L, 52, "")
        val pending = ChaoxingSignActivity(
            id = 61, endTimeMs = System.currentTimeMillis() + 3_600_000, userStatus = 0, otherId = "0", isLook = false, type = 2, activeType = 2,
            nameOne = "课堂练习签到", status = 1, nameFour = "", courseId = course.courseId,
            classId = course.classId, courseName = course.courseName, extContent = "",
        )
        val closed = pending.copy(id = 62, nameOne = "上节课签到", status = 2, endTimeMs = null)
        val signed = pending.copy(id = 63, nameOne = "实验课签到", userStatus = 1)
        val account = ChaoxingAccountSession("mock-student", "", "模拟同学", deviceCode = "mock")
        val store = object : PersistenceStore {
            var snapshot = ChaoxingAppStateSnapshot(accounts = listOf(account))
            override suspend fun load() = snapshot
            override suspend fun save(snapshot: ChaoxingAppStateSnapshot) { this.snapshot = snapshot }
            override suspend fun archiveAndReset(): File = error("Unexpected reset")
        }
        val requests = AtomicInteger()
        val detailReads = AtomicInteger()
        val deps = LiveAppStateDependencies(
            sharedHttpClient = OkHttpClient.Builder().addInterceptor {
                requests.incrementAndGet()
                throw IOException("Network disabled for this fixture")
            }.build(),
            onFetchCourses = {
                listOf(
                    course,
                    ChaoxingCourse("数据结构", "林老师", 42, 53, ""),
                    ChaoxingCourse("离散数学", "周老师", 43, 54, ""),
                )
            },
            onFetchCourseActivities = { _, _ -> listOf(closed, signed, pending) },
            onPrepareSign = { _, activity ->
                when (activity.id) {
                    pending.id -> ChaoxingSignActivityStatus.READY_TO_SIGN
                    signed.id -> ChaoxingSignActivityStatus.ALREADY_SIGNED
                    else -> ChaoxingSignActivityStatus.EXPIRED
                }
            },
            onFetchSignDetail = { _, id, classId, courseId ->
                if (detailReads.getAndIncrement() == 0) throw IOException("Simulated offline response")
                ChaoxingSignDetailMetadata(id, pending.signType, "", false, false, false, false,
                    false, signOutInfo = ChaoxingSignOutInfo(classId = classId, courseId = courseId))
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val state = AndroidAppState(store, deps, scope)
        fun descendants(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
            if (node != null) {
                yield(node)
                for (i in 0 until node.childCount) yieldAll(descendants(node.getChild(i)))
            }
        }
        fun node(text: String) = descendants(automation.rootInActiveWindow).firstOrNull {
            it.text?.toString() == text || it.contentDescription?.toString() == text
        }
        fun waitFor(text: String): AccessibilityNodeInfo {
            val start = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - start < 10000) {
                node(text)?.let { return it }
                SystemClock.sleep(100)
            }
            error("Missing UI element: $text")
        }
        fun click(text: String) {
            var target: AccessibilityNodeInfo? = waitFor(text)
            while (target != null && !target.isClickable) target = target.parent
            check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot open $text" }
            automation.waitForIdle(250, 5000)
        }
        fun capture(name: String) {
            if (InstrumentationRegistry.getArguments().getString("capture") != "true") return
            SystemClock.sleep(550) // Wait for the navigation crossfade before capturing.
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(app.cacheDir, "fixture-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        try {
            runBlocking { state.loadFromDisk() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor("登录学习通")
                capture("login")
                scenario.onActivity { it.setContent { CaochaoxingTheme { CaochaoxingMainRoot(state) } } }
                waitFor(course.courseName)
                capture("courses")
                click(course.courseName)
                waitFor(pending.nameOne)
                fun top(text: String): Int {
                    val bounds = android.graphics.Rect()
                    waitFor(text).getBoundsInScreen(bounds)
                    return bounds.top
                }
                assertTrue(top(pending.nameOne) < top(closed.nameOne))
                assertTrue(top(pending.nameOne) < top(signed.nameOne))
                capture("activities")
                click(pending.nameOne)
                waitFor("重新加载")
                assertNull(node("提交签到"))
                click("重新加载")
                waitFor("当前账号待签到")
                waitFor("提交签到")
                capture("pending")
                click("返回")
                click(closed.nameOne)
                waitFor("活动已截止")
                capture("closed")
                assertNull("Expired activity still offers submission", node("提交签到"))
                click("返回")
                click(signed.nameOne)
                waitFor("当前账号已签到")
                assertNull("Signed activity still offers submission", node("提交签到"))
                click("返回")
                waitFor("刷新活动")
                automation.waitForIdle(600, 5000)
                click("返回")
                waitFor("我的课程")
                assertTrue(state.uiState.value.signHistory.isEmpty())
                assertEquals(0, requests.get())
            }
        } finally {
            scope.cancel()
        }
    }
}
