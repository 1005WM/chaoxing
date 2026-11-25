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
import com.caochaoxing.android.state.AssessmentOverviewController
import com.caochaoxing.android.state.LiveAppStateDependencies
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingAssessmentItem
import com.caochaoxing.core.model.ChaoxingAssessmentKind
import com.caochaoxing.core.model.ChaoxingAssessmentSummary
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import com.caochaoxing.core.store.PersistenceStore
import java.io.File
import java.io.IOException
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseLayoutTest {
    @Test fun coursesOpenTodoAndReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        assumeTrue(runBlocking { app.persistenceStore.load() }.accounts.isEmpty())
        val automation = instrumentation.uiAutomation
        val courses = listOf(
            ChaoxingCourse("高等数学", "张老师", 11L, 21, ""),
            ChaoxingCourse("程序设计基础", "李老师", 12L, 22, ""),
            ChaoxingCourse("大学英语", "王老师", 13L, 23, ""),
            ChaoxingCourse("大学物理", "赵老师", 14L, 24, ""),
        )
        val today = ZonedDateTime.now().with(LocalTime.of(23, 59, 59))
        fun work(course: ChaoxingCourse, title: String, deadline: ZonedDateTime) =
            ChaoxingAssessmentItem(
                kind = ChaoxingAssessmentKind.WORK,
                title = title,
                statusText = "未交",
                courseId = course.courseId,
                classId = course.classId,
                courseName = course.courseName,
                deadlineAtMs = deadline.toInstant().toEpochMilli(),
            )
        val works = listOf(
            work(courses[0], "函数极限练习", today),
            work(courses[0], "微分计算作业", today.plusDays(1)),
            work(courses[1], "循环结构编程练习", today.minusDays(1)),
            work(courses[2], "Unit 3 课后作业", today.plusDays(2)),
            work(courses[3], "牛顿运动定律", today.plusDays(3)),
        )
        val account = ChaoxingAccountSession("layout-fixture", "", "示例账号", deviceCode = "layout-fixture")
        val store = object : PersistenceStore {
            override suspend fun load() = ChaoxingAppStateSnapshot(accounts = listOf(account))
            override suspend fun save(snapshot: ChaoxingAppStateSnapshot) = Unit
            override suspend fun archiveAndReset(): File = error("Unexpected reset")
        }
        val dependencies = LiveAppStateDependencies(
            sharedHttpClient = OkHttpClient.Builder().addInterceptor { throw IOException("Offline UI fixture") }.build(),
            onFetchCourses = { courses },
            onFetchCourseActivities = { _, _ -> emptyList() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val state = AndroidAppState(store, dependencies, scope)
        val overview = AssessmentOverviewController(
            clientProvider = { state.activeClient },
            scope = scope,
            fetchSummary = { _, course -> ChaoxingAssessmentSummary(works.filter { it.courseId == course.courseId }, emptyList()) },
        )
        fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
            if (node != null) {
                yield(node)
                for (index in 0 until node.childCount) yieldAll(nodes(node.getChild(index)))
            }
        }
        fun waitFor(text: String): AccessibilityNodeInfo {
            val until = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < until) {
                nodes(automation.rootInActiveWindow).firstOrNull {
                    it.text?.toString() == text || it.contentDescription?.toString() == text
                }?.let { return it }
                SystemClock.sleep(100)
            }
            error("Missing UI element: $text")
        }
        fun click(text: String) {
            val target = generateSequence(waitFor(text)) { it.parent }.first { it.isClickable }
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun capture(name: String) {
            if (InstrumentationRegistry.getArguments().getString("capture") != "true") return
            SystemClock.sleep(500)
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(app.cacheDir, "layout-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        try {
            runBlocking { state.loadFromDisk() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    overview.onAccountChanged(account.phoneNumber)
                    activity.setContent {
                        CaochaoxingTheme {
                            CaochaoxingMainRoot(state, assessmentOverviewController = overview)
                        }
                    }
                }
                waitFor("5 项未完成 / 1 项逾期")
                waitFor("大学物理")
                capture("courses")
                click("作业待办")
                waitFor("5 项待处理 / 1 项逾期")
                waitFor("已逾期")
                if (ZonedDateTime.now().isBefore(today)) waitFor("今日截止")
                capture("todo")
                if (ZonedDateTime.now().isBefore(today)) {
                    click("查看作业")
                    waitFor("课程详情")
                    click("返回")
                    waitFor("5 项待处理 / 1 项逾期")
                }
                click("返回")
                waitFor("我的课程")
                click("设置")
                waitFor("当前账号")
                capture("settings")
            }
        } finally {
            scope.cancel()
        }
    }
}
