// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
package com.caochaoxing.android

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.screens.CourseListScreen
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in account-owner-authorized emulator check. Default test/CI runs skip this case.
 * Supply credentials only via the target app's private cache/read-only-login.json;
 * the transient file is deleted before authentication. Never pass credentials as runner args.
 * Reads only login, courses, groups and activity lists. Never opens the sign workspace,
 * requests pre-sign preparation, submits attendance or uploads files.
 */
@RunWith(AndroidJUnit4::class)
class AuthorizedReadOnlyTest {
    @Test
    fun authorizedLoginAndReadOnlyScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveReadOnly") == "true")
        assumeTrue("Dedicated emulator required", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val cache = instrumentation.targetContext.cacheDir
        val credentialFile = File(cache, "read-only-login.json")
        val credentials = if (credentialFile.exists()) {
            try { JSONObject(credentialFile.readText()) } finally { check(credentialFile.delete()) }
        } else null
        val output = File(cache, "read-only-results").apply { mkdirs() }
        val report = JSONObject()
        var stage = "restore"
        val app = ApplicationProvider.getApplicationContext<CaochaoxingApp>()
        val state = app.appState
        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(600)
            val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(output, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            frame.recycle()
        }
        try {
            runBlocking { withTimeout(30_000) { state.uiState.first { !it.isRestoring } } }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val historyCount = state.uiState.value.signHistory.size
                if (credentials != null) {
                    capture("login-light")
                    stage = "login"
                    runBlocking {
                        withTimeout(90_000) {
                            withContext(Dispatchers.Main) {
                                state.loginPrimary(credentials.getString("account"), credentials.getString("password"))
                            }
                        }
                    }
                    credentials.remove("password")
                    credentials.remove("account")
                }
                checkNotNull(state.uiState.value.activeAccount)
                check(state.uiState.value.errorMessage == null)
                report.put("authenticated", true)
                stage = "courses"
                runBlocking { withTimeout(60_000) { state.refreshCourses() } }
                check(state.uiState.value.errorMessage == null)
                report.put("courseCount", state.uiState.value.courses.size)
                state.setSelectedTab(AndroidAppState.TAB_COURSES)
                capture("courses-light")
                stage = "course_activities"
                state.uiState.value.courses.firstOrNull()?.let { course ->
                    val activities = runBlocking { withTimeout(60_000) { state.loadCourseActivities(course) } }
                    check(course.id !in state.uiState.value.failedCourseActivityIds && course.id in state.uiState.value.courseActivities)
                    report.put("sampleCourseActivityCount", activities.size)
                }
                stage = "groups"
                runBlocking { withTimeout(60_000) { state.refreshGroups() } }
                check(state.uiState.value.errorMessage == null)
                report.put("groupCount", state.uiState.value.groups.size)
                state.setSelectedTab(AndroidAppState.TAB_GROUPS)
                capture("groups-light")
                stage = "group_activities"
                state.uiState.value.groups.firstOrNull()?.let { group ->
                    val activities = runBlocking { withTimeout(60_000) { state.loadGroupActivities(group) } }
                    check(group.id !in state.uiState.value.failedGroupActivityIds && group.id in state.uiState.value.groupActivities)
                    report.put("sampleGroupActivityCount", activities.size)
                }
                stage = "settings"
                state.setSelectedTab(AndroidAppState.TAB_SETTINGS)
                capture("settings-light")
                stage = "dark_render"
                scenario.onActivity { activity ->
                    activity.setContent {
                        CaochaoxingTheme(darkTheme = true) {
                            CourseListScreen(
                                state,
                                onCourseClick = {},
                                onActivityClick = {},
                                onManageAccounts = {},
                                onOpenTodo = {},
                            )
                        }
                    }
                }
                capture("courses-dark")
                state.setSelectedTab(AndroidAppState.TAB_COURSES)
                scenario.recreate()
                stage = "encrypted_session"
                val persisted = runBlocking { app.persistenceStore.load() }
                assertTrue(persisted.accounts.any { it.phoneNumber == state.uiState.value.activePhoneNumber })
                assertEquals(historyCount, state.uiState.value.signHistory.size)
                report.put("encryptedSessionReadable", true)
                report.put("signHistoryUnchanged", true)
                report.put("success", true)
                capture("courses-final")
            }
        } catch (failure: Throwable) {
            report.put("success", false).put("failedStage", stage).put("errorType", failure.javaClass.simpleName)
            // No server responses or account values in JUnit output.
            throw AssertionError("Read-only check failed at $stage (${failure.javaClass.simpleName})")
        } finally {
            credentials?.remove("password")
            credentials?.remove("account")
            File(output, "report.json").writeText(report.toString(2))
        }
    }
}
