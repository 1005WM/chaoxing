// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
package com.caochaoxing.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.caochaoxing.android.state.EncryptedFilePersistenceStore
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Runs against Android's actual Keystore/filesystem. Never logs in or submits attendance. */
@RunWith(AndroidJUnit4::class)
class AndroidRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun isolatedContext(directory: File): Context = object : ContextWrapper(context) {
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun keystoreRoundTripRejectsTamperingAndPreservesRecoveryCopy() = runBlocking {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "keystore-test-").toFile()
        try {
            val scoped = isolatedContext(directory)
            val store = EncryptedFilePersistenceStore(scoped)
            val snapshot = ChaoxingAppStateSnapshot(customUserAgent = "synthetic-runtime-marker", starredCourseIds = listOf(123L))
            store.save(snapshot)
            val file = File(directory, "caochaoxing/state.enc")
            val first = file.readBytes()
            assertFalse(first.toString(Charsets.UTF_8).contains("synthetic-runtime-marker"))
            assertEquals(snapshot, EncryptedFilePersistenceStore(scoped).load())
            store.save(snapshot)
            assertFalse("Each encryption must use a new IV", first.contentEquals(file.readBytes()))

            val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(damaged)
            assertTrue(runCatching { store.load() }.isFailure)
            assertTrue(runCatching { store.save(snapshot) }.isFailure)
            assertArrayEquals(damaged, file.readBytes())
            val backup = store.archiveAndReset()
            assertArrayEquals(damaged, backup.readBytes())
            assertEquals(ChaoxingAppStateSnapshot(), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun legacyPlaintextMigratesToKeystoreEncryption() = runBlocking {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "migration-test-").toFile()
        try {
            val legacy = File(directory, "caochaoxing/state.json")
            legacy.parentFile!!.mkdirs()
            val snapshot = ChaoxingAppStateSnapshot(customUserAgent = "synthetic-legacy-marker")
            legacy.writeBytes(snapshot.encodedBackupData(includeAccounts = true))
            val store = EncryptedFilePersistenceStore(isolatedContext(directory))
            assertEquals(snapshot, store.load())
            assertFalse(legacy.exists())
            assertTrue(File(directory, "caochaoxing/state.enc").isFile)
            assertEquals(snapshot, store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unauthenticatedDeepLinkAndActivityRecreationKeepLoginGate() = runBlocking {
        val app = context as CaochaoxingApp
        withTimeout(10_000) { app.appState.uiState.first { !it.isRestoring } }
        // This case is intended for a fresh emulator. Never alter an existing user's sessions.
        assumeTrue(app.appState.uiState.value.accounts.isEmpty())
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("caochaoxing://sign?activeId=12345&courseId=23456&classId=34567")
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertTrue(activity.window.decorView.isAttachedToWindow)
                assertNull(app.appState.uiState.value.activeAccount)
                assertTrue(app.appState.uiState.value.signHistory.isEmpty())
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
