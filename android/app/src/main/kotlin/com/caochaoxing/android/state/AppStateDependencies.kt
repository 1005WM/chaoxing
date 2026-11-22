// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.state

import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingEasemobIMConfig
import com.caochaoxing.core.model.ChaoxingGroupSignActivity
import com.caochaoxing.core.model.ChaoxingIMGroup
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignActivityStatus
import com.caochaoxing.core.model.ChaoxingSignDetailMetadata
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.service.ChaoxingActivityService
import com.caochaoxing.core.service.ChaoxingAuthService
import com.caochaoxing.core.service.ChaoxingCourseService
import com.caochaoxing.core.service.ChaoxingGroupService
import com.caochaoxing.core.signer.ChaoxingSignExecutor
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/**
 * Shared OkHttp client factory ensuring all per-account [ChaoxingHttpClient] instances
 * reuse one connection pool and dispatcher rather than spinning up separate thread pools.
 */
object AppStateHttpClientFactory {
    val sharedBaseClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChaoxingHttpClient.defaultOkHttpClient()
    }

    fun createClient(
        session: ChaoxingAccountSession,
        userAgent: String,
        sharedBaseClient: OkHttpClient = this.sharedBaseClient,
    ): ChaoxingHttpClient = ChaoxingHttpClient(
        session = session,
        userAgent = userAgent,
        sharedBaseClient = sharedBaseClient,
    )
}

/**
 * Injectable service dependencies for [AndroidAppState], enabling pure-JVM unit testing.
 */
interface AppStateDependencies {
    val sharedHttpClient: OkHttpClient
        get() = AppStateHttpClientFactory.sharedBaseClient

    fun createHttpClient(
        session: ChaoxingAccountSession,
        userAgent: String,
    ): ChaoxingHttpClient = AppStateHttpClientFactory.createClient(
        session = session,
        userAgent = userAgent,
        sharedBaseClient = sharedHttpClient,
    )

    suspend fun login(
        phoneNumber: String,
        password: String,
        userAgent: String,
        isPrimary: Boolean,
    ): ChaoxingAccountSession

    suspend fun fetchCourses(client: ChaoxingHttpClient): List<ChaoxingCourse>

    suspend fun fetchIMConfig(client: ChaoxingHttpClient): ChaoxingEasemobIMConfig

    suspend fun fetchGroups(
        client: ChaoxingHttpClient,
        imConfig: ChaoxingEasemobIMConfig,
    ): List<ChaoxingIMGroup>

    suspend fun fetchCourseActivities(
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
    ): List<ChaoxingSignActivity>

    suspend fun fetchGroupActivities(
        client: ChaoxingHttpClient,
        group: ChaoxingIMGroup,
        imConfig: ChaoxingEasemobIMConfig,
    ): List<ChaoxingGroupSignActivity>

    suspend fun fetchSignDetail(
        client: ChaoxingHttpClient,
        activeId: Long,
        fallbackClassId: Int,
        fallbackCourseId: Long,
    ): ChaoxingSignDetailMetadata

    suspend fun prepareSign(
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
    ): ChaoxingSignActivityStatus

    suspend fun sleep(delayMs: Long)
}

/**
 * Production implementation of [AppStateDependencies] backed by `:core` services and a shared [OkHttpClient].
 */
class LiveAppStateDependencies(
    override val sharedHttpClient: OkHttpClient = AppStateHttpClientFactory.sharedBaseClient,
    private val onLogin: suspend (phoneNumber: String, password: String, userAgent: String, isPrimary: Boolean) -> ChaoxingAccountSession =
        { phoneNumber, password, userAgent, isPrimary ->
            ChaoxingAuthService.login(
                phoneNumber = phoneNumber,
                password = password,
                userAgent = userAgent,
                isPrimary = isPrimary,
            )
        },
    private val onFetchCourses: suspend (ChaoxingHttpClient) -> List<ChaoxingCourse> =
        { client -> ChaoxingCourseService.fetchCourses(client) },
    private val onFetchIMConfig: suspend (ChaoxingHttpClient) -> ChaoxingEasemobIMConfig =
        { client -> ChaoxingGroupService.getEasemobConfig(client) },
    private val onFetchGroups: suspend (ChaoxingHttpClient, ChaoxingEasemobIMConfig) -> List<ChaoxingIMGroup> =
        { client, imConfig -> ChaoxingGroupService.fetchJoinedGroups(client, imConfig) },
    private val onFetchCourseActivities: suspend (ChaoxingHttpClient, ChaoxingCourse) -> List<ChaoxingSignActivity> =
        { client, course -> ChaoxingActivityService.fetchCourseActivities(client, course) },
    private val onFetchGroupActivities: suspend (ChaoxingHttpClient, ChaoxingIMGroup, ChaoxingEasemobIMConfig) -> List<ChaoxingGroupSignActivity> =
        { client, group, imConfig -> ChaoxingGroupService.fetchGroupSignActivities(client, group, imConfig) },
    private val onFetchSignDetail: suspend (ChaoxingHttpClient, Long, Int, Long) -> ChaoxingSignDetailMetadata =
        { client, activeId, fallbackClassId, fallbackCourseId ->
            ChaoxingActivityService.fetchSignDetail(
                client = client,
                activeId = activeId,
                fallbackClassId = fallbackClassId,
                fallbackCourseId = fallbackCourseId,
            )
        },
    private val onPrepareSign: suspend (ChaoxingHttpClient, ChaoxingSignActivity) -> ChaoxingSignActivityStatus =
        { client, activity -> ChaoxingSignExecutor.preSign(client, activity) },
    private val onSleep: suspend (Long) -> Unit = { delayMs -> delay(delayMs) },
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
    ): ChaoxingSignDetailMetadata = onFetchSignDetail(client, activeId, fallbackClassId, fallbackCourseId)

    override suspend fun prepareSign(
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
    ): ChaoxingSignActivityStatus = onPrepareSign(client, activity)

    override suspend fun sleep(delayMs: Long) = onSleep(delayMs)
}
