// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.caochaoxing.android.ui.screens.OtherUsersScreen
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PasswordRecreationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun loginDropsPasswordAndHidesItAfterRecreation() = checkForm(addAccount = false)

    @Test
    fun addAccountDropsPasswordAndHidesItAfterRecreation() = checkForm(addAccount = true)

    private fun checkForm(addAccount: Boolean) {
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        assumeTrue(runBlocking { app.persistenceStore.load() }.accounts.isEmpty())
        val marker = "fixture-account"
        val password = "SYNTHETIC-PASSWORD-ONLY"
        // Mount the same add-account form on both activity instances without signing in.
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (addAccount && activity is MainActivity && stage == Stage.CREATED) {
                activity.setContent { CaochaoxingTheme { OtherUsersScreen(app.appState, onBack = {}) } }
            }
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                setText(waitForNode { it.isEditable && !it.isPassword }, marker)
                setText(waitForNode { it.isEditable && it.isPassword }, password)
                var eye: AccessibilityNodeInfo? = waitForNode { it.contentDescription == "显示密码" }
                while (eye != null && !eye.isClickable) eye = eye.parent
                assertTrue(eye?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                waitForNode { it.isEditable && it.text?.toString() == password }
                waitForNode { it.contentDescription == "隐藏密码" }

                scenario.onActivity { activity ->
                    val saved = Bundle()
                    instrumentation.callActivityOnSaveInstanceState(activity, saved)
                    val parcel = Parcel.obtain()
                    val bytes = try {
                        parcel.writeBundle(saved)
                        parcel.marshall()
                    } finally {
                        parcel.recycle()
                    }
                    val savedStrings = listOf(bytes.toString(Charsets.UTF_16LE), bytes.toString(Charsets.UTF_8))
                    assertTrue("Non-secret form value should be saved", savedStrings.any { marker in it })
                    assertFalse("Password leaked into saved activity state", savedStrings.any { password in it })
                }

                scenario.recreate()
                waitForNode { it.isEditable && it.text?.toString() == marker }
                waitForNode { it.contentDescription == "显示密码" }
                val field = waitForNode { it.isEditable && it.isPassword }
                val text = field.text?.toString().orEmpty()
                assertTrue("Recreated password field must be empty", text.isEmpty() || field.isShowingHintText ||
                    text == "登录密码" || text == "学习通登录密码" || text == "密码")
                assertTrue(app.appState.uiState.value.accounts.isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
    }

    private fun waitForNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (matches(node)) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            search(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Password form did not reach the expected state")
    }
}
