// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import android.app.Application
import android.content.Context
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.AssessmentController
import com.caochaoxing.android.state.AssessmentOverviewController
import com.caochaoxing.android.state.CourseStudyController
import com.caochaoxing.android.state.EncryptedFilePersistenceStore
import com.caochaoxing.android.state.LiveAppStateDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Application entry point initializing encrypted persistence and the global [AndroidAppState].
 */
class CaochaoxingApp : Application() {
    val updateManager by lazy { com.caochaoxing.android.state.AppUpdateManager(this, applicationScope) }
    lateinit var aiStudyController: com.caochaoxing.android.state.AiStudyController
        private set
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var persistenceStore: EncryptedFilePersistenceStore
        private set

    lateinit var appState: AndroidAppState
        private set

    lateinit var courseStudyController: CourseStudyController
        private set

    lateinit var assessmentController: AssessmentController
        private set

    lateinit var assessmentOverviewController: AssessmentOverviewController
        private set

    override fun onCreate() {
        super.onCreate()
        persistenceStore = EncryptedFilePersistenceStore(this)
        appState = AndroidAppState(
            store = persistenceStore,
            dependencies = LiveAppStateDependencies(),
            appScope = applicationScope,
            initialRestoring = true,
        )
        courseStudyController = CourseStudyController(
            appState = appState,
            scope = applicationScope,
        )
        aiStudyController = com.caochaoxing.android.state.AiStudyController(this, appState, applicationScope)
        courseStudyController.onQuizDiscovered = aiStudyController::enqueue
        courseStudyController.onWatchStopped = aiStudyController::stopFollowing
        assessmentController = AssessmentController(
            appState = appState,
            scope = applicationScope,
        )
        assessmentOverviewController = AssessmentOverviewController(
            appState = appState,
            scope = applicationScope,
        )
        applicationScope.launch {
            appState.uiState
                .map { it.activePhoneNumber }
                .distinctUntilChanged()
                .collect { phone ->
                    aiStudyController.onAccountChanged(phone)
                    courseStudyController.onAccountChanged(phone)
                    assessmentController.onAccountChanged(phone)
                    assessmentOverviewController.onAccountChanged(phone)
                }
        }
        applicationScope.launch {
            appState.loadFromDisk()
        }
    }

    companion object {
        fun from(context: Context): CaochaoxingApp =
            context.applicationContext as CaochaoxingApp
    }
}
