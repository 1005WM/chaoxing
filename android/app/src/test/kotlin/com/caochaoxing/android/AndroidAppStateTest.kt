// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.BatchSignDisposition
import com.caochaoxing.android.state.BatchSignResult
import com.caochaoxing.android.state.resultDescription
import com.caochaoxing.android.state.AppStateDependencies
import com.caochaoxing.android.state.AppStateHttpClientFactory
import com.caochaoxing.android.state.LiveAppStateDependencies
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingClientPreset
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingCustomClientProfile
import com.caochaoxing.core.model.ChaoxingEasemobIMConfig
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingGroupSignActivity
import com.caochaoxing.core.model.ChaoxingIMGroup
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignActivityStatus
import com.caochaoxing.core.model.ChaoxingSignDetailMetadata
import com.caochaoxing.core.model.ChaoxingSignOutInfo
import com.caochaoxing.core.model.ChaoxingSignType
import com.caochaoxing.core.model.ChaoxingStoredCookie
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.signer.ChaoxingSignOutcome
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import com.caochaoxing.core.store.ChaoxingSignHistoryEntry
import com.caochaoxing.core.store.PersistenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAppStateTest {

    private class InMemoryPersistenceStore(
        initial: ChaoxingAppStateSnapshot = ChaoxingAppStateSnapshot(),
    ) : PersistenceStore {
        var snapshot: ChaoxingAppStateSnapshot = initial
        var loadFailure: Throwable? = null
        var saveFailure: Throwable? = null
        var archiveFailure: Throwable? = null
        var onLoadBarrier: (suspend () -> Unit)? = null
        var loadCalls: Int = 0
        var saveCalls: Int = 0

        override suspend fun load(): ChaoxingAppStateSnapshot {
            loadCalls += 1
            onLoadBarrier?.invoke()
            loadFailure?.let { throw it }
            return snapshot
        }

        override suspend fun save(snapshot: ChaoxingAppStateSnapshot) {
            saveCalls += 1
            saveFailure?.let { throw it }
            this.snapshot = snapshot
        }

        override suspend fun archiveAndReset(): File {
            archiveFailure?.let { throw it }
            loadFailure = null
            snapshot = ChaoxingAppStateSnapshot()
            return File("/tmp/state.recovery-test.enc")
        }
    }

    private inner class FakeAppStateDependencies(
        override val sharedHttpClient: OkHttpClient = ChaoxingHttpClient.defaultOkHttpClient(),
        var onLogin: suspend (String, String, String, Boolean) -> ChaoxingAccountSession = { phone, _, _, isPrimary ->
            sampleAccount(phone = phone, isPrimary = isPrimary)
        },
        var onFetchCourses: suspend (ChaoxingHttpClient) -> List<ChaoxingCourse> = { emptyList() },
        var onFetchIMConfig: suspend (ChaoxingHttpClient) -> ChaoxingEasemobIMConfig = {
            ChaoxingEasemobIMConfig(accessToken = "im-token-1", uuid = "uuid-1", username = "u-1")
        },
        var onFetchGroups: suspend (ChaoxingHttpClient, ChaoxingEasemobIMConfig) -> List<ChaoxingIMGroup> = { _, _ ->
            emptyList()
        },
        var onFetchCourseActivities: suspend (ChaoxingHttpClient, ChaoxingCourse) -> List<ChaoxingSignActivity> = { _, _ ->
            emptyList()
        },
        var onFetchGroupActivities: suspend (ChaoxingHttpClient, ChaoxingIMGroup, ChaoxingEasemobIMConfig) -> List<ChaoxingGroupSignActivity> = { _, _, _ ->
            emptyList()
        },
        var onFetchSignDetail: suspend (ChaoxingHttpClient, Long, Int, Long) -> ChaoxingSignDetailMetadata = { _, activeId, classId, courseId ->
            ChaoxingSignDetailMetadata(
                activeId = activeId,
                signType = ChaoxingSignType.LOCATION,
                title = "详情活动 #$activeId",
                isCaptchaRequired = false,
                isFaceRequired = false,
                isPositionRequired = true,
                isPhotoRequired = false,
                isRefreshQRCode = false,
                signOutInfo = ChaoxingSignOutInfo(
                    classId = classId,
                    courseId = courseId,
                ),
            )
        },
        var onPrepareSign: suspend (ChaoxingHttpClient, ChaoxingSignActivity) -> ChaoxingSignActivityStatus = { _, _ ->
            ChaoxingSignActivityStatus.READY_TO_SIGN
        },
        var onSleep: suspend (Long) -> Unit = {},
    ) : AppStateDependencies {
        override suspend fun login(
            phoneNumber: String,
            password: String,
            userAgent: String,
            isPrimary: Boolean,
        ): ChaoxingAccountSession = onLogin(phoneNumber, password, userAgent, isPrimary)

        override suspend fun fetchCourses(client: ChaoxingHttpClient): List<ChaoxingCourse> =
            onFetchCourses(client)

        override suspend fun fetchIMConfig(client: ChaoxingHttpClient): ChaoxingEasemobIMConfig =
            onFetchIMConfig(client)

        override suspend fun fetchGroups(
            client: ChaoxingHttpClient,
            imConfig: ChaoxingEasemobIMConfig,
        ): List<ChaoxingIMGroup> = onFetchGroups(client, imConfig)

        override suspend fun fetchCourseActivities(
            client: ChaoxingHttpClient,
            course: ChaoxingCourse,
        ): List<ChaoxingSignActivity> = onFetchCourseActivities(client, course)

        override suspend fun fetchGroupActivities(
            client: ChaoxingHttpClient,
            group: ChaoxingIMGroup,
            imConfig: ChaoxingEasemobIMConfig,
        ): List<ChaoxingGroupSignActivity> = onFetchGroupActivities(client, group, imConfig)

        override suspend fun fetchSignDetail(
            client: ChaoxingHttpClient,
            activeId: Long,
            fallbackClassId: Int,
            fallbackCourseId: Long,
        ): ChaoxingSignDetailMetadata =
            onFetchSignDetail(client, activeId, fallbackClassId, fallbackCourseId)

        override suspend fun prepareSign(
            client: ChaoxingHttpClient,
            activity: ChaoxingSignActivity,
        ): ChaoxingSignActivityStatus = onPrepareSign(client, activity)

        override suspend fun sleep(delayMs: Long) = onSleep(delayMs)
    }

    @Test
    fun setTagsAndAddTagSyncClientPoolAndPersistAcrossRestart() = runTest {
        val store = InMemoryPersistenceStore()
        val deps = FakeAppStateDependencies()
        val state = AndroidAppState(store = store, dependencies = deps, appScope = backgroundScope)

        state.loginPrimary(phoneNumber = "13800000001", password = "password123")
        val initialClient = state.existingClientFor("13800000001")
        assertNotNull(initialClient)

        state.setTags(
            phoneNumber = "13800000001",
            tags = listOf("  宿舍 ", "计科1班", "宿舍", "   ", "计科1班 "),
        )
        assertEquals(
            listOf("宿舍", "计科1班"),
            state.uiState.value.accounts.single().tags,
        )
        assertEquals(
            listOf("宿舍", "计科1班"),
            initialClient!!.session.tags,
        )

        state.addTag(phoneNumber = "13800000001", tag = "  主力账号 ")
        // Adding duplicate tag with surrounding whitespace must remain deduplicated.
        state.addTag(phoneNumber = "13800000001", tag = "宿舍")

        val expectedTags = listOf("宿舍", "计科1班", "主力账号")
        assertEquals(expectedTags, state.uiState.value.accounts.single().tags)
        assertEquals(expectedTags, initialClient.session.tags)
        assertEquals(listOf("主力账号", "宿舍", "计科1班"), state.allAccountTags)

        assertTrue(state.saveToDisk())
        assertEquals(expectedTags, store.snapshot.accounts.single().tags)

        // Restart into a brand-new AndroidAppState reading from the same PersistenceStore.
        val restartedState = AndroidAppState(store = store, dependencies = deps, appScope = backgroundScope)
        restartedState.loadFromDisk()

        assertEquals(expectedTags, restartedState.uiState.value.accounts.single().tags)
        assertEquals(
            expectedTags,
            restartedState.existingClientFor("13800000001")?.session?.tags,
        )
    }

    @Test
    fun importBackupJsonInitializesIndependentClientsAndMergesBindings() = runTest {
        val existingPreset = ChaoxingLocationPreset(
            id = "preset-existing",
            name = "旧教",
            address = "海淀区学院路",
            latitude = 39.98,
            longitude = 116.35,
        )
        val initialAccount = sampleAccount(
            phone = "13800000001",
            name = "一号旧",
            isPrimary = true,
            tags = listOf("旧分组"),
            cookieValue = "uid-old",
        )
        val store = InMemoryPersistenceStore(
            initial = ChaoxingAppStateSnapshot(
                accounts = listOf(initialAccount),
                locationPresets = listOf(existingPreset),
                starredCourseIds = listOf(100L),
            ),
        )
        val deps = FakeAppStateDependencies()
        val state = AndroidAppState(store = store, dependencies = deps, appScope = backgroundScope)
        state.loadFromDisk()

        val importedPreset = ChaoxingLocationPreset(
            id = "preset-imported",
            name = "主楼",
            address = "海淀区中关村",
            latitude = 39.99,
            longitude = 116.32,
        )
        val importedSnapshot = ChaoxingAppStateSnapshot(
            accounts = listOf(
                sampleAccount(
                    phone = "13800000001",
                    name = "一号新",
                    isPrimary = false,
                    tags = listOf("导入分组", "  旧分组 "),
                    cookieValue = "uid-new-1",
                ),
                sampleAccount(
                    phone = "13800000002",
                    name = "二号",
                    isPrimary = false,
                    tags = listOf("小号"),
                    cookieValue = "uid-new-2",
                ),
            ),
            locationPresets = listOf(importedPreset),
            batchSignDelayMs = 2500,
            autoSolveCaptcha = false,
            courseLocationBindings = mapOf("5001" to importedPreset),
            starredCourseIds = listOf(5001L),
        )
        val backupJson = importedSnapshot.encodedBackupJson(includeAccounts = true)

        state.importBackupJson(backupJson)

        val ui = state.uiState.value
        assertEquals(2, ui.accounts.size)
        assertTrue(ui.accounts.first { it.phoneNumber == "13800000001" }.isPrimary)
        assertFalse(ui.accounts.first { it.phoneNumber == "13800000002" }.isPrimary)
        assertEquals(
            listOf("旧分组", "导入分组"),
            ui.accounts.first { it.phoneNumber == "13800000001" }.tags,
        )
        assertEquals(importedPreset, state.boundLocation(5001L))
        assertEquals(setOf(100L, 5001L), ui.starredCourseIds)
        assertEquals(2500, ui.batchSignDelayMs)
        assertFalse(ui.autoSolveCaptcha)

        // All imported accounts must IMMEDIATELY have distinct ChaoxingHttpClient instances in clientPool.
        val client1 = state.existingClientFor("13800000001")
        val client2 = state.existingClientFor("13800000002")
        assertNotNull(client1)
        assertNotNull(client2)
        assertNotSame(client1, client2)
        assertEquals("uid-new-1", client1!!.exportCookies().first { it.name == "UID" }.value)
        assertEquals("uid-new-2", client2!!.exportCookies().first { it.name == "UID" }.value)

        // Invalid latitude in backup must throw ChaoxingError.InvalidInput and leave state untouched.
        val badPreset = importedPreset.copy(latitude = 95.0)
        val badBackupJson = ChaoxingAppStateSnapshot(
            locationPresets = listOf(badPreset),
        ).encodedBackupJson(includeAccounts = false)
        var invalidRejected = false
        try {
            state.importBackupJson(badBackupJson)
        } catch (_: ChaoxingError.InvalidInput) {
            invalidRejected = true
        }
        assertTrue(invalidRejected)
        assertEquals(2, state.uiState.value.accounts.size)
    }

    @Test
    fun handleIncomingIntentRoutesSignAndQrAndRejectsInvalidActiveId() = runTest {
        val store = InMemoryPersistenceStore(
            initial = ChaoxingAppStateSnapshot(
                accounts = listOf(sampleAccount("13800000001", isPrimary = true)),
            ),
        )
        val deps = FakeAppStateDependencies(
            onFetchSignDetail = { _, activeId, classId, courseId ->
                ChaoxingSignDetailMetadata(
                    activeId = activeId,
                    signType = ChaoxingSignType.PASSWORD,
                    title = "高数点名",
                    isCaptchaRequired = false,
                    isFaceRequired = false,
                    isPositionRequired = false,
                    isPhotoRequired = false,
                    isRefreshQRCode = false,
                    signOutInfo = ChaoxingSignOutInfo(
                        classId = classId,
                        courseId = courseId,
                    ),
                )
            },
        )
        val state = AndroidAppState(store = store, dependencies = deps, appScope = backgroundScope)
        state.loadFromDisk()
        state.setSelectedTab(3)

        state.handleIncomingIntent("caochaoxing://sign?activeId=12345&courseId=100&classId=200")
        assertEquals(AndroidAppState.TAB_COURSES, state.uiState.value.selectedTab)
        val popped = state.uiState.value.deepLinkActivity
        assertNotNull(popped)
        assertEquals(12345L, popped!!.id)
        assertEquals(100L, popped.courseId)
        assertEquals(200, popped.classId)
        assertEquals("高数点名", popped.nameOne)
        assertEquals(ChaoxingSignType.PASSWORD, popped.signType)

        state.clearDeepLinkActivity()
        assertNull(state.uiState.value.deepLinkActivity)

        // Invalid activeId=0 must report a clear error and NOT pop up an activity.
        state.handleIncomingIntent("caochaoxing://sign?activeId=0&courseId=100&classId=200")
        assertNull(state.uiState.value.deepLinkActivity)
        assertNotNull(state.uiState.value.errorMessage)
        assertTrue(state.uiState.value.errorMessage!!.contains("正数"))

        state.clearErrorMessage()

        // caochaoxing://qr?... routes to QR tab and preserves '+' inside decoded payload.
        state.handleIncomingIntent("caochaoxing://qr?data=SIGNIN%3Aaid%3D88%26enc%3Dabc%2B123")
        assertEquals(AndroidAppState.TAB_QR, state.uiState.value.selectedTab)
        assertEquals("SIGNIN:aid=88&enc=abc+123", state.uiState.value.pendingQrCodeInput)

        // Cold-start deferred deep link during restoration is consumed right after loadFromDisk().
        val coldState = AndroidAppState(
            store = store,
            dependencies = deps,
            appScope = backgroundScope,
            initialRestoring = true,
        )
        coldState.handleIncomingIntent("caochaoxing://sign?activeId=777&courseId=10&classId=20")
        assertNull(coldState.uiState.value.deepLinkActivity)
        coldState.loadFromDisk()
        assertEquals(AndroidAppState.TAB_COURSES, coldState.uiState.value.selectedTab)
        assertEquals(777L, coldState.uiState.value.deepLinkActivity?.id)
    }

    @Test
    fun switchActiveAccountInvalidatesInFlightRequestsAndClearsScheduledOperations() = runTest {
        val accountA = sampleAccount("13800000001", name = "账号A", isPrimary = true)
        val accountB = sampleAccount("13800000002", name = "账号B", isPrimary = false)
        val courseA = ChaoxingCourse("课程A", "老师A", courseId = 101L, classId = 201, imageURL = "")
        val courseB = ChaoxingCourse("课程B", "老师B", courseId = 102L, classId = 202, imageURL = "")

        val coursesEnteredA = CompletableDeferred<Unit>()
        val coursesGateA = CompletableDeferred<List<ChaoxingCourse>>()
        val radarEnteredA = CompletableDeferred<Unit>()
        val radarGateA = CompletableDeferred<List<ChaoxingSignActivity>>()
        val sessionTaskStarted = CompletableDeferred<Unit>()
        val sessionTaskCancelled = CompletableDeferred<Boolean>()

        val store = InMemoryPersistenceStore(
            initial = ChaoxingAppStateSnapshot(
                accounts = listOf(accountA, accountB),
                starredCourseIds = listOf(courseA.courseId),
            ),
        )
        var initialCourseLoad = true
        val deps = FakeAppStateDependencies(
            onFetchCourses = { client ->
                when (client.session.phoneNumber) {
                    accountA.phoneNumber -> {
                        if (initialCourseLoad) {
                            initialCourseLoad = false
                            listOf(courseA)
                        } else {
                            coursesEnteredA.complete(Unit)
                            coursesGateA.await()
                        }
                    }
                    accountB.phoneNumber -> listOf(courseB)
                    else -> emptyList()
                }
            },
            onFetchCourseActivities = { _, _ ->
                radarEnteredA.complete(Unit)
                radarGateA.await()
            },
            onFetchGroups = { _, _ -> listOf(ChaoxingIMGroup(id = "grp-1", name = "群聊A")) },
        )

        val state = AndroidAppState(store = store, dependencies = deps, appScope = backgroundScope)
        state.loadFromDisk()
        state.refreshGroups()
        assertEquals(listOf(courseA), state.uiState.value.courses)
        assertEquals(1, state.uiState.value.groups.size)
        assertNotNull(state.uiState.value.imConfig)

        // Start in-flight Account A operations: course refresh, radar scan, captcha dialog, session task.
        val refreshJobA = launch { state.refreshCourses() }
        val radarJobA = launch { state.scanPendingActivitiesRadar() }
        val captchaClientA = state.clientFor(accountA)
        val captchaDeferredA = async {
            try {
                state.requestCaptchaValidation(captchaClientA, "https://mobilelearn.chaoxing.com/preSign")
                false
            } catch (_: CancellationException) {
                true
            }
        }
        val sessionTaskA = state.launchSessionTask {
            try {
                sessionTaskStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            } catch (e: CancellationException) {
                sessionTaskCancelled.complete(true)
                throw e
            }
        }

        coursesEnteredA.await()
        radarEnteredA.await()
        sessionTaskStarted.await()
        runCurrent()
        assertTrue(state.uiState.value.isScanningRadar)
        assertNotNull(state.uiState.value.captchaChallenge)

        // Switch to Account B while Account A operations are in-flight.
        state.switchActiveAccount(accountB)
        runCurrent()

        assertTrue(sessionTaskA.isCancelled)
        assertTrue(sessionTaskCancelled.await())
        assertTrue(captchaDeferredA.await())
        assertNull(state.uiState.value.captchaChallenge)
        assertTrue(state.uiState.value.groups.isEmpty())
        assertNull(state.uiState.value.imConfig)
        assertTrue(state.uiState.value.pendingRadarItems.isEmpty())
        assertFalse(state.uiState.value.isScanningRadar)
        assertEquals(listOf(courseB), state.uiState.value.courses)

        // Even if Account A's old suspended network futures later resolve, they must NOT pollute Account B.
        coursesGateA.complete(listOf(courseA))
        radarGateA.complete(
            listOf(
                ChaoxingSignActivity(
                    id = 9001L,
                    userStatus = 0,
                    otherId = "0",
                    isLook = false,
                    type = 2,
                    activeType = 2,
                    nameOne = "旧账号待签",
                    status = 1,
                    nameFour = "",
                    courseId = courseA.courseId,
                    classId = courseA.classId,
                    courseName = courseA.courseName,
                    extContent = "{}",
                ),
            ),
        )
        testScheduler.advanceUntilIdle()
        refreshJobA.join()
        radarJobA.join()

        assertEquals(accountB.phoneNumber, state.uiState.value.activePhoneNumber)
        assertEquals(listOf(courseB), state.uiState.value.courses)
        assertTrue(state.uiState.value.pendingRadarItems.isEmpty())
        assertNull(state.uiState.value.errorMessage)
    }

    @Test
    fun radarHistoryBatchSignCaptchaAndCorruptStoreRecoveryBehaveCorrectly() = runTest {
        val account1 = sampleAccount("13800000001", name = "甲", isPrimary = true)
        val account2 = sampleAccount("13800000002", name = "乙", isPrimary = false)
        val courseStarred = ChaoxingCourse("高数", "张老师", 10L, 20, "")
        val courseOther = ChaoxingCourse("英语", "李老师", 11L, 21, "")
        val pendingAct = ChaoxingSignActivity(
            id = 3001L,
            startTimeMs = 1700000000000L,
            userStatus = 0,
            otherId = "4",
            isLook = false,
            type = 2,
            activeType = 2,
            nameOne = "位置签到",
            status = 1,
            nameFour = "",
            courseId = 10L,
            classId = 20,
            courseName = "高数",
            extContent = "{}",
        )
        val signedAct = pendingAct.copy(id = 3002L, userStatus = 1, nameOne = "已签到活动")

        val store = InMemoryPersistenceStore(
            initial = ChaoxingAppStateSnapshot(
                accounts = listOf(account1, account2),
                starredCourseIds = listOf(courseStarred.courseId),
            ),
        )
        val scannedCourseIds = mutableListOf<Long>()
        val deps = FakeAppStateDependencies(
            onFetchCourses = { listOf(courseStarred, courseOther) },
            onFetchCourseActivities = { _, course ->
                scannedCourseIds += course.courseId
                if (course.courseId == courseStarred.courseId) {
                    listOf(pendingAct, signedAct)
                } else {
                    emptyList()
                }
            },
        )
        val state = AndroidAppState(store = store, dependencies = deps, appScope = backgroundScope)
        state.loadFromDisk()

        // Radar should scan only starred courses when starredCourseIds is non-empty.
        state.scanPendingActivitiesRadar()
        assertEquals(listOf(courseStarred.courseId), scannedCourseIds)
        assertEquals(1, state.uiState.value.pendingRadarItems.size)
        assertEquals(courseStarred, state.uiState.value.pendingRadarItems.single().course)
        assertEquals(pendingAct, state.uiState.value.pendingRadarItems.single().activity)

        // Sign history must cap at 100 entries with newest first.
        for (i in 1..105) {
            state.recordSignHistory(
                activity = pendingAct.copy(id = i.toLong(), nameOne = "记录$i"),
                token = state.sessionToken,
                results = listOf(BatchSignResult.from(account1, Result.success(ChaoxingSignOutcome(true, "ok-$i")))),
            )
        }
        assertEquals(100, state.uiState.value.signHistory.size)
        assertEquals(105L, state.uiState.value.signHistory.first().activeId)
        assertEquals(6L, state.uiState.value.signHistory.last().activeId)

        // Batch sign with CaptchaRequired -> suspends on requestCaptchaValidation until completeCaptcha is called.
        val batchDeferred = async {
            state.executeSignBatch(
                activity = pendingAct,
                selectedPhones = setOf(account1.phoneNumber, account2.phoneNumber),
            ) { _, account, captchaValidate, enc2 ->
                if (account.phoneNumber == account1.phoneNumber && captchaValidate == null) {
                    throw ChaoxingError.CaptchaRequired(enc2 = "enc2-token")
                }
                if (account.phoneNumber == account1.phoneNumber) {
                    assertEquals("solved-validate", captchaValidate)
                    assertEquals("enc2-token", enc2)
                }
                ChaoxingSignOutcome(success = true, message = "成功-${account.name}")
            }
        }
        runCurrent()
        val challenge = state.uiState.value.captchaChallenge
        assertNotNull(challenge)
        state.completeCaptcha(challenge!!.id, "solved-validate")
        val results = batchDeferred.await()
        assertEquals(2, results.size)
        assertTrue(results.all { it.disposition == BatchSignDisposition.ACCEPTED })

        // Corrupt store blocks saveToDisk() until recoverLocalState() is invoked.
        val failingStore = InMemoryPersistenceStore().apply {
            loadFailure = IOException("corrupt state.enc")
        }
        val blockedState = AndroidAppState(store = failingStore, dependencies = deps, appScope = backgroundScope)
        blockedState.loadFromDisk()
        assertTrue(blockedState.uiState.value.persistenceBlocked)
        assertFalse(blockedState.saveToDisk())
        assertEquals(0, failingStore.saveCalls)

        blockedState.recoverLocalState()
        assertFalse(blockedState.uiState.value.persistenceBlocked)
        assertTrue(blockedState.saveToDisk())
        assertEquals(1, failingStore.saveCalls)
    }

    @Test
    fun groupActivitiesRefreshImConfigCaptchaQueueOrderAndAccountMutationsWorkCleanly() = runTest {
        val account1 = sampleAccount("13800000001", name = "一号", isPrimary = true)
        val account2 = sampleAccount("13800000002", name = "二号", isPrimary = false)
        val course = ChaoxingCourse("物理", "王老师", 701L, 801, "")
        val group = ChaoxingIMGroup(id = "group-701", name = "物理教学群")
        val groupActivity = ChaoxingGroupSignActivity(
            activeId = 4401L,
            title = "群签到",
            classId = 801,
            courseId = 701L,
            courseName = "物理",
            startTimeTitle = "10:00",
            activeTypeName = "普通签到",
            isUnsupportedLegacySign = false,
            messageTimestampMs = 1700001000000L,
        )

        var imFetchCount = 0
        var groupActivityFetchCount = 0
        val store = InMemoryPersistenceStore(
            initial = ChaoxingAppStateSnapshot(accounts = listOf(account1, account2)),
        )
        val deps = FakeAppStateDependencies(
            onFetchIMConfig = {
                imFetchCount += 1
                ChaoxingEasemobIMConfig(
                    accessToken = "im-token-$imFetchCount",
                    uuid = "uuid-$imFetchCount",
                    username = "user-$imFetchCount",
                )
            },
            onFetchGroupActivities = { _, _, imConfig ->
                groupActivityFetchCount += 1
                if (groupActivityFetchCount == 1) {
                    assertEquals("im-token-1", imConfig.accessToken)
                    throw ChaoxingError.SessionExpired("IM token expired")
                }
                assertEquals("im-token-2", imConfig.accessToken)
                listOf(groupActivity)
            },
            onFetchCourseActivities = { _, c ->
                listOf(
                    ChaoxingSignActivity(
                        id = 5501L,
                        userStatus = 0,
                        otherId = "0",
                        isLook = false,
                        type = 2,
                        activeType = 2,
                        nameOne = "课堂点名",
                        status = 1,
                        nameFour = "",
                        courseId = c.courseId,
                        classId = c.classId,
                        courseName = c.courseName,
                        extContent = "{}",
                    ),
                )
            },
        )
        val state = AndroidAppState(store = store, dependencies = deps, appScope = backgroundScope)
        state.loadFromDisk()

        // Course & group activity loaders update uiState and retry IM token transparently on SessionExpired.
        val loadedCourseActs = state.loadCourseActivities(course)
        assertEquals(1, loadedCourseActs.size)
        assertEquals(loadedCourseActs, state.uiState.value.courseActivities[course.id])

        val loadedGroupActs = state.loadGroupActivities(group)
        assertEquals(listOf(groupActivity), loadedGroupActs)
        assertEquals(2, imFetchCount)
        assertEquals(2, groupActivityFetchCount)

        // Location binding, unbinding, and course star toggling persist cleanly.
        val preset = ChaoxingLocationPreset(name = "教学楼A", address = "校园路1号", latitude = 31.2, longitude = 121.4)
        state.bindLocation(course.courseId, preset)
        assertEquals(preset, state.boundLocation(course.courseId))
        state.toggleStarCourse(course.courseId)
        assertTrue(course.courseId in state.uiState.value.starredCourseIds)
        state.unbindLocation(course.courseId)
        assertNull(state.boundLocation(course.courseId))

        // Backup export excludes account credentials when includeAccounts = false.
        val presetsOnlyJson = state.exportBackupJson(includeAccounts = false)
        assertTrue(ChaoxingAppStateSnapshot.decodedBackupJson(presetsOnlyJson).accounts.isEmpty())

        // Multi-captcha FIFO queue presents challenges sequentially and completes/cancels accurately.
        val client = state.clientFor(account1)
        val firstCaptcha = async { state.requestCaptchaValidation(client, "https://ref/1") }
        val secondCaptcha = async {
            try {
                state.requestCaptchaValidation(client, "https://ref/2")
            } catch (_: CancellationException) {
                "CANCELLED"
            }
        }
        runCurrent()

        val firstPresented = state.uiState.value.captchaChallenge
        assertNotNull(firstPresented)
        assertEquals("https://ref/1", firstPresented!!.refererUrl)
        state.captchaSheetDidAppear(firstPresented.id)
        state.completeCaptcha(firstPresented.id, "validate-1")
        assertEquals("validate-1", firstCaptcha.await())

        runCurrent()
        val secondPresented = state.uiState.value.captchaChallenge
        assertNotNull(secondPresented)
        assertEquals("https://ref/2", secondPresented!!.refererUrl)
        state.captchaSheetDidAppear(secondPresented.id)
        state.captchaSheetDidDismiss(secondPresented.id)
        assertEquals("CANCELLED", secondCaptcha.await())
        assertNull(state.uiState.value.captchaChallenge)

        // updateFid updates configuredFid on both accounts and clientPool.
        state.updateFid(account1, 9999)
        assertEquals(9999, state.uiState.value.activeAccount?.configuredFid)
        assertEquals(9999, state.existingClientFor(account1.phoneNumber)?.session?.configuredFid)

        // removeAccount drops account2 and disposes its client from pool.
        state.removeAccount(account2)
        assertEquals(1, state.uiState.value.accounts.size)
        assertNull(state.existingClientFor(account2.phoneNumber))

        // LiveAppStateDependencies constructs cleanly in pure JVM with shared OkHttpClient.
        val liveState = AndroidAppState(
            store = InMemoryPersistenceStore(),
            dependencies = LiveAppStateDependencies(),
            appScope = backgroundScope,
        )
        assertNull(liveState.activeClient)
    }

    @Test
    fun blockedPersistenceAndRestoringGuardPreventInMemoryStateAndClientPoolMutations() = runTest {
        val initialPreset = ChaoxingLocationPreset(
            id = "preset-init",
            name = "图书馆",
            address = "校园路8号",
            latitude = 39.95,
            longitude = 116.31,
        )
        val otherPreset = ChaoxingLocationPreset(
            id = "preset-other",
            name = "体育馆",
            address = "校园路9号",
            latitude = 39.96,
            longitude = 116.32,
        )
        val initialProfile = ChaoxingCustomClientProfile(
            id = "profile-init",
            name = "初始UA",
            userAgent = "InitialUA/1.0",
        )
        val initialHistory = ChaoxingSignHistoryEntry(
            id = "hist-1",
            activeId = 8001L,
            activityName = "早八签到",
            courseName = "数据结构",
            signTypeRaw = "0",
            successCount = 1,
            totalCount = 1,
            summary = "ok",
        )
        val initialAccount = sampleAccount(
            phone = "13800000001",
            name = "测试号",
            isPrimary = true,
            tags = listOf("初始标签"),
        )
        val store = InMemoryPersistenceStore(
            initial = ChaoxingAppStateSnapshot(
                accounts = listOf(initialAccount),
                locationPresets = listOf(initialPreset),
                selectedClientPreset = ChaoxingClientPreset.DEFAULT.rawValue,
                customUserAgent = "",
                customClientProfiles = listOf(initialProfile),
                selectedCustomClientId = initialProfile.id,
                enableLocationJitter = true,
                batchSignDelayMs = 1500,
                enableImageObfuscation = true,
                autoSolveCaptcha = true,
                signHistory = listOf(initialHistory),
                courseLocationBindings = mapOf("1001" to initialPreset),
                starredCourseIds = listOf(1001L),
            ),
        )
        val deps = FakeAppStateDependencies()
        val state = AndroidAppState(store = store, dependencies = deps, appScope = backgroundScope)
        state.loadFromDisk()

        // A successful restore refreshes courses and saves the updated session once.
        val savesBeforeFailure = store.saveCalls
        assertEquals(1, savesBeforeFailure)

        // Put state into persistenceBlocked == true while keeping baseline loaded state in memory.
        store.loadFailure = IOException("corrupt disk")
        state.loadFromDisk()
        assertTrue(state.uiState.value.persistenceBlocked)
        val baseline = state.uiState.value
        val baselinePoolClient = state.existingClientFor("13800000001")!!
        val baselineUa = baselinePoolClient.userAgent
        val expectedBlockedMessage = "本地状态尚未成功读取，已阻止修改并覆盖原文件"

        // None of the persistable mutations may alter uiState or clientPool while persistenceBlocked == true.
        state.setTags("13800000001", listOf("被篡改"))
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.accounts, state.uiState.value.accounts)
        assertEquals(listOf("初始标签"), baselinePoolClient.session.tags)

        state.clearErrorMessage()
        state.addTag("13800000001", "新增标签")
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.accounts, state.uiState.value.accounts)
        assertEquals(listOf("初始标签"), baselinePoolClient.session.tags)

        state.clearErrorMessage()
        state.bindLocation(2002L, otherPreset)
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.courseLocationBindings, state.uiState.value.courseLocationBindings)

        state.clearErrorMessage()
        state.unbindLocation(1001L)
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.courseLocationBindings, state.uiState.value.courseLocationBindings)

        state.clearErrorMessage()
        state.toggleStarCourse(1001L)
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.starredCourseIds, state.uiState.value.starredCourseIds)

        state.clearErrorMessage()
        assertFalse(state.addLocationPreset("新地点", "新地址", 31.0, 121.0))
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.locationPresets, state.uiState.value.locationPresets)

        state.clearErrorMessage()
        state.removeLocationPreset(initialPreset)
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.locationPresets, state.uiState.value.locationPresets)

        state.clearErrorMessage()
        state.selectClientPreset(ChaoxingClientPreset.XUE_ZAI_XI_DIAN)
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.selectedClientPreset, state.uiState.value.selectedClientPreset)
        assertEquals(baselineUa, baselinePoolClient.userAgent)

        state.clearErrorMessage()
        state.setCustomUserAgent("MutatedUA/2.0")
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.customUserAgent, state.uiState.value.customUserAgent)
        assertEquals(baselineUa, baselinePoolClient.userAgent)

        state.clearErrorMessage()
        state.addCustomClientProfile("新配置", "NewUA/3.0")
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.customClientProfiles, state.uiState.value.customClientProfiles)
        assertEquals(baselineUa, baselinePoolClient.userAgent)

        state.clearErrorMessage()
        state.removeCustomClientProfile(initialProfile)
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.customClientProfiles, state.uiState.value.customClientProfiles)

        state.clearErrorMessage()
        state.setLocationJitterEnabled(false)
        state.setBatchSignDelayMs(5000)
        state.setImageObfuscationEnabled(false)
        state.setAutoSolveCaptcha(false)
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.enableLocationJitter, state.uiState.value.enableLocationJitter)
        assertEquals(baseline.batchSignDelayMs, state.uiState.value.batchSignDelayMs)
        assertEquals(baseline.enableImageObfuscation, state.uiState.value.enableImageObfuscation)
        assertEquals(baseline.autoSolveCaptcha, state.uiState.value.autoSolveCaptcha)

        val act = ChaoxingSignActivity(
            id = 9001L,
            userStatus = 0,
            otherId = "0",
            isLook = false,
            type = 2,
            activeType = 2,
            nameOne = "新签到",
            status = 1,
            nameFour = "",
            courseId = 1001L,
            classId = 1,
            courseName = "数据结构",
            extContent = "",
        )
        state.clearErrorMessage()
        state.recordSignHistory(act, state.sessionToken, emptyList(), stopped = true)
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.signHistory, state.uiState.value.signHistory)

        state.clearErrorMessage()
        state.clearSignHistory()
        assertEquals(expectedBlockedMessage, state.uiState.value.errorMessage)
        assertEquals(baseline.signHistory, state.uiState.value.signHistory)
        assertEquals(savesBeforeFailure, store.saveCalls)

        // Also verify isRestoring == true blocks mutations before load finishes.
        val loadEntered = CompletableDeferred<Unit>()
        val loadRelease = CompletableDeferred<Unit>()
        val restoringStore = InMemoryPersistenceStore(
            initial = ChaoxingAppStateSnapshot(starredCourseIds = listOf(10L)),
        ).apply {
            onLoadBarrier = {
                loadEntered.complete(Unit)
                loadRelease.await()
            }
        }
        val restoringState = AndroidAppState(store = restoringStore, dependencies = deps, appScope = backgroundScope)
        val loadJob = launch { restoringState.loadFromDisk() }
        loadEntered.await()
        assertTrue(restoringState.uiState.value.isRestoring)

        restoringState.toggleStarCourse(999L)
        restoringState.bindLocation(999L, otherPreset)
        assertEquals(expectedBlockedMessage, restoringState.uiState.value.errorMessage)
        assertTrue(restoringState.uiState.value.starredCourseIds.isEmpty())
        assertTrue(restoringState.uiState.value.courseLocationBindings.isEmpty())

        loadRelease.complete(Unit)
        loadJob.join()
        assertFalse(restoringState.uiState.value.isRestoring)
        assertEquals(setOf(10L), restoringState.uiState.value.starredCourseIds)
    }

    @Test
    fun setTagsTrimsPhonesAndCaptchaDismissIgnoresUnappearedPromotedChallenge() = runTest {
        val account = sampleAccount("13800000001", name = "一号", isPrimary = true)
        val store = InMemoryPersistenceStore(
            initial = ChaoxingAppStateSnapshot(accounts = listOf(account)),
        )
        val state = AndroidAppState(
            store = store,
            dependencies = FakeAppStateDependencies(),
            appScope = backgroundScope,
        )
        state.loadFromDisk()

        // Phone numbers with leading/trailing whitespace match cleanly in setTags / addTag.
        state.setTags("  13800000001 ", listOf("A班"))
        assertEquals(listOf("A班"), state.uiState.value.accounts.single().tags)
        assertEquals(listOf("A班"), state.existingClientFor("13800000001")?.session?.tags)

        state.addTag(" 13800000001\t", "B组")
        assertEquals(listOf("A班", "B组"), state.uiState.value.accounts.single().tags)
        assertEquals(listOf("A班", "B组"), state.existingClientFor("13800000001")?.session?.tags)

        // When completeCaptcha promotes the next queued challenge, a late dismiss(id = null)
        // from the previous dialog must NOT cancel the newly-promoted waiting challenge.
        val client = state.clientFor(account)
        val first = async { state.requestCaptchaValidation(client, "https://ref/first") }
        val second = async {
            try {
                state.requestCaptchaValidation(client, "https://ref/second")
            } catch (_: CancellationException) {
                "DISMISSED"
            }
        }
        runCurrent()

        val firstReq = state.uiState.value.captchaChallenge!!
        state.captchaSheetDidAppear(firstReq.id)
        state.completeCaptcha(firstReq.id, "v-first")
        assertEquals("v-first", first.await())

        runCurrent()
        val secondReq = state.uiState.value.captchaChallenge
        assertNotNull(secondReq)
        assertEquals("https://ref/second", secondReq!!.refererUrl)

        // Unappeared challenge must survive captchaSheetDidDismiss(id = null).
        state.captchaSheetDidDismiss(id = null)
        runCurrent()
        assertEquals(secondReq.id, state.uiState.value.captchaChallenge?.id)
        assertFalse(second.isCompleted)

        // Once the second challenge appears, captchaSheetDidDismiss(id = null) dismisses it cleanly.
        state.captchaSheetDidAppear(secondReq.id)
        state.captchaSheetDidDismiss(id = null)
        assertEquals("DISMISSED", second.await())
        assertNull(state.uiState.value.captchaChallenge)

        // LiveAppStateDependencies shares AppStateHttpClientFactory.sharedBaseClient.
        assertSame(AppStateHttpClientFactory.sharedBaseClient, LiveAppStateDependencies().sharedHttpClient)
    }

    @Test
    fun failedActivityRefreshKeepsCachedActivitiesAndSuccessfulRetryClearsFailure() = runTest {
        val account = sampleAccount("13800000001", isPrimary = true)
        val course = ChaoxingCourse("物理", "教师", 701L, 801, "")
        val group = ChaoxingIMGroup(id = "test-group", name = "教学群")
        val store = InMemoryPersistenceStore(initial = ChaoxingAppStateSnapshot(accounts = listOf(account)))
        val deps = FakeAppStateDependencies()
        val state = AndroidAppState(store = store, dependencies = deps, appScope = backgroundScope)
        state.loadFromDisk()
        deps.onFetchCourseActivities = { _, _ -> throw IOException("offline") }
        deps.onFetchGroupActivities = { _, _, _ -> throw IOException("offline") }
        state.loadCourseActivities(course)
        state.loadGroupActivities(group)
        assertTrue(course.id in state.uiState.value.failedCourseActivityIds)
        assertTrue(group.id in state.uiState.value.failedGroupActivityIds)
        assertFalse(course.id in state.uiState.value.courseActivities)

        deps.onFetchCourseActivities = { _, _ -> emptyList() }
        deps.onFetchGroupActivities = { _, _, _ -> emptyList() }
        state.loadCourseActivities(course)
        state.loadGroupActivities(group)
        assertTrue(state.uiState.value.failedCourseActivityIds.isEmpty())
        assertTrue(state.uiState.value.failedGroupActivityIds.isEmpty())
        assertTrue(course.id in state.uiState.value.courseActivities)
        assertTrue(group.id in state.uiState.value.groupActivities)
        deps.onFetchCourseActivities = { _, _ -> throw IOException("offline") }
        state.loadCourseActivities(course)
        assertTrue(course.id in state.uiState.value.failedCourseActivityIds)
        assertTrue(course.id in state.uiState.value.courseActivities)
    }

    private val resultTestActivity = ChaoxingSignActivity(
        id = 901, userStatus = 0, otherId = "0", isLook = false, type = 2, activeType = 2,
        nameOne = "合成活动", status = 1, nameFour = "", courseId = 1, classId = 2, courseName = "合成课程", extContent = "",
    )

    @Test
    fun preExistingAndAcceptedAndUnconfirmedResultsStayDistinctInHistory() = runTest {
        val phones = listOf("prior", "server-already-signed", "accepted", "unconfirmed", "failed")
        val accounts = phones.map { sampleAccount(it) }
        val store = InMemoryPersistenceStore(ChaoxingAppStateSnapshot(accounts = accounts))
        val deps = FakeAppStateDependencies()
        val state = AndroidAppState(store, deps, backgroundScope)
        state.loadFromDisk()
        val preSignedClient = state.clientFor(accounts.first())
        deps.onPrepareSign = { client, _ ->
            if (client === preSignedClient) ChaoxingSignActivityStatus.ALREADY_SIGNED else ChaoxingSignActivityStatus.READY_TO_SIGN
        }
        val operationCalls = mutableListOf<String>()
        val token = state.sessionToken
        val results = state.executeSignBatch(resultTestActivity, phones.toSet()) { _, account, _, _ ->
            operationCalls += account.phoneNumber
            when (account.phoneNumber) {
                "server-already-signed" -> throw ChaoxingError.AlreadySigned()
                "accepted" -> ChaoxingSignOutcome(true, "synthetic acknowledgement")
                "unconfirmed" -> ChaoxingSignOutcome(false, "synthetic ambiguous response")
                "failed" -> throw IOException("synthetic offline response")
                else -> error("Unexpected operation")
            }
        }
        assertEquals(phones.drop(1), operationCalls)
        assertEquals(
            listOf(BatchSignDisposition.ALREADY_SIGNED, BatchSignDisposition.ALREADY_SIGNED,
                BatchSignDisposition.ACCEPTED, BatchSignDisposition.FAILED_OR_UNCONFIRMED, BatchSignDisposition.FAILED_OR_UNCONFIRMED),
            results.map { it.disposition },
        )
        state.recordSignHistory(resultTestActivity, token, results)
        val entry = store.snapshot.signHistory.single()
        assertEquals(1, entry.successCount)
        assertEquals(2, entry.alreadySignedCount)
        assertEquals(5, entry.totalCount)
        assertEquals("本次获接受 1 · 此前已签 2 · 失败或未确认 2", entry.resultDescription)
        assertEquals(entry.resultDescription, state.uiState.value.statusMessage)
        assertEquals("旧版合并统计 3/5（本次获接受与此前已签未区分）",
            entry.copy(successCount = 3, alreadySignedCount = null).resultDescription)
        assertEquals("历史记录的计数无效", entry.copy(alreadySignedCount = Int.MAX_VALUE).resultDescription)
    }

    @Test
    fun cancelledBatchRecordsOnlyReturnedResultsAndCannotWriteIntoNewSession() = runTest {
        val pending = sampleAccount("pending", isPrimary = true)
        val accepted = sampleAccount("accepted")
        val store = InMemoryPersistenceStore(ChaoxingAppStateSnapshot(accounts = listOf(pending, accepted), batchSignDelayMs = 0))
        val state = AndroidAppState(store, FakeAppStateDependencies(), backgroundScope)
        state.loadFromDisk()
        val token = state.sessionToken
        val waitForResult = CompletableDeferred<Unit>()
        var returned = emptyList<BatchSignResult>()
        val job = launch {
            state.executeSignBatch(resultTestActivity, setOf(pending.phoneNumber, accepted.phoneNumber), { returned = it }) { _, account, _, _ ->
                if (account.phoneNumber == pending.phoneNumber) waitForResult.await()
                ChaoxingSignOutcome(true, "synthetic acknowledgement")
            }
        }
        runCurrent()
        assertEquals(listOf(accepted.phoneNumber), returned.map { it.phoneNumber })
        job.cancel()
        job.join()
        state.recordSignHistory(resultTestActivity, token, returned, stopped = true)
        val entry = store.snapshot.signHistory.single()
        assertEquals(1, entry.totalCount)
        assertEquals(1, entry.successCount)
        assertEquals(0, entry.alreadySignedCount)
        assertEquals("已停止，仅统计已返回的结果", entry.summary)
        state.recordSignHistory(resultTestActivity, token, emptyList(), stopped = true)
        assertEquals(listOf(entry), state.uiState.value.signHistory)

        state.switchActiveAccount(accepted)
        val newStatus = state.uiState.value.statusMessage
        state.recordSignHistory(resultTestActivity, token, returned, stopped = true)
        assertEquals(newStatus, state.uiState.value.statusMessage)
        assertEquals(listOf(entry), state.uiState.value.signHistory)
        assertEquals(listOf(entry), store.snapshot.signHistory)
    }

    private fun sampleAccount(
        phone: String,
        name: String = "测试用户",
        isPrimary: Boolean = false,
        tags: List<String> = emptyList(),
        cookieValue: String = "uid-$phone",
    ): ChaoxingAccountSession = ChaoxingAccountSession(
        phoneNumber = phone,
        encryptedPassword = "enc-pw-$phone",
        name = name,
        uid = 10001,
        puid = 10001,
        configuredFid = 123,
        deviceCode = "device-$phone",
        cookies = listOf(
            ChaoxingStoredCookie(
                name = "UID",
                value = cookieValue,
                domain = "chaoxing.com",
                isSecure = true,
            ),
        ),
        isPrimary = isPrimary,
        tags = tags,
    )
}
