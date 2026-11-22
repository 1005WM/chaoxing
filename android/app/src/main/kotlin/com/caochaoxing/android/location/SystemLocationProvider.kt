// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal enum class LocationAccessLevel {
    NONE,
    COARSE_ONLY,
    FINE,
}

internal data class LocationCandidate(
    val latitude: Double,
    val longitude: Double,
    val timeMs: Long,
    val accuracyMeters: Float?,
)

/**
 * Queries Android's native [LocationManager] for a one-shot WGS-84 fix (no background tracking,
 * no proprietary SDKs), falling back to the freshest valid cached [LocationManager.getLastKnownLocation].
 */
class SystemLocationProvider(context: Context) {
    private val appContext: Context = context.applicationContext ?: context

    suspend fun fetchCurrentWgs84Location(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Pair<Double, Double> {
        val accessLevel = currentLocationAccessLevel()
        if (accessLevel == LocationAccessLevel.NONE) {
            throw SecurityException("定位权限未开启，请在系统设置中授予位置权限")
        }

        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: throw IllegalStateException("当前设备不支持系统定位服务")

        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            throw IllegalStateException("系统定位开关未开启，请先打开设备位置信息")
        }

        val candidateProviders = selectCandidateProviders(
            accessLevel = accessLevel,
            supportsFused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
        val activeEnabledProviders = candidateProviders.filter { provider ->
            provider != PROVIDER_PASSIVE && isProviderEnabledSafe(locationManager, provider)
        }

        if (timeoutMs > 0L && activeEnabledProviders.isNotEmpty()) {
            val activeFix = fetchActiveLocationWithTimeout(
                locationManager = locationManager,
                providers = activeEnabledProviders,
                timeoutMs = timeoutMs,
            )
            if (activeFix != null) {
                return activeFix.latitude to activeFix.longitude
            }
        }

        val fallback = bestLastKnownLocation(locationManager, candidateProviders)
        if (fallback != null) {
            return fallback.latitude to fallback.longitude
        }

        if (activeEnabledProviders.isEmpty()) {
            throw IllegalStateException("当前无可用的定位提供器，请检查系统定位设置或改用地图选点")
        }
        throw IllegalStateException("获取当前位置超时，请移步开阔区域重试或改用地图选点")
    }

    internal fun hasLocationPermission(): Boolean = currentLocationAccessLevel() != LocationAccessLevel.NONE

    private fun currentLocationAccessLevel(): LocationAccessLevel {
        val fineGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return resolveLocationAccess(fineGranted = fineGranted, coarseGranted = coarseGranted)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchActiveLocationWithTimeout(
        locationManager: LocationManager,
        providers: List<String>,
        timeoutMs: Long,
    ): Location? = withTimeoutOrNull(timeoutMs) {
        supervisorScope {
            val result = CompletableDeferred<Location?>()
            val jobs = providers.map { provider ->
                launch {
                    val location = awaitProviderLocation(locationManager, provider)
                    if (location != null && isValidWgs84Coordinate(location.latitude, location.longitude)) {
                        result.complete(location)
                    }
                }
            }
            launch {
                jobs.joinAll()
                result.complete(null)
            }
            try {
                result.await()
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitProviderLocation(
        locationManager: LocationManager,
        provider: String,
    ): Location? = suspendCancellableCoroutine { continuation ->
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation {
            cancellationSignal.cancel()
        }
        try {
            LocationManagerCompat.getCurrentLocation(
                locationManager,
                provider,
                cancellationSignal,
                ContextCompat.getMainExecutor(appContext),
            ) { location ->
                if (continuation.isActive) {
                    continuation.resume(location)
                }
            }
        } catch (_: SecurityException) {
            if (continuation.isActive) continuation.resume(null)
        } catch (_: IllegalArgumentException) {
            if (continuation.isActive) continuation.resume(null)
        } catch (_: RuntimeException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnownLocation(
        locationManager: LocationManager,
        providers: List<String>,
    ): LocationCandidate? {
        val candidates = providers.mapNotNull { provider ->
            val loc = try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            loc?.let {
                LocationCandidate(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    timeMs = it.time,
                    accuracyMeters = if (it.hasAccuracy()) it.accuracy else null,
                )
            }
        }
        return selectBestLocationCandidate(candidates)
    }

    private fun isProviderEnabledSafe(locationManager: LocationManager, provider: String): Boolean = try {
        locationManager.isProviderEnabled(provider)
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: SecurityException) {
        false
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 10_000L
        internal const val PROVIDER_GPS: String = "gps"
        internal const val PROVIDER_NETWORK: String = "network"
        internal const val PROVIDER_PASSIVE: String = "passive"
        internal const val PROVIDER_FUSED: String = "fused"

        internal fun resolveLocationAccess(
            fineGranted: Boolean,
            coarseGranted: Boolean,
        ): LocationAccessLevel = when {
            fineGranted -> LocationAccessLevel.FINE
            coarseGranted -> LocationAccessLevel.COARSE_ONLY
            else -> LocationAccessLevel.NONE
        }

        internal fun selectCandidateProviders(
            accessLevel: LocationAccessLevel,
            supportsFused: Boolean,
        ): List<String> = when (accessLevel) {
            LocationAccessLevel.NONE -> emptyList()
            LocationAccessLevel.COARSE_ONLY -> buildList {
                if (supportsFused) add(PROVIDER_FUSED)
                add(PROVIDER_NETWORK)
            }
            LocationAccessLevel.FINE -> buildList {
                if (supportsFused) add(PROVIDER_FUSED)
                add(PROVIDER_GPS)
                add(PROVIDER_NETWORK)
                add(PROVIDER_PASSIVE)
            }
        }

        internal fun isValidWgs84Coordinate(latitude: Double, longitude: Double): Boolean =
            latitude.isFinite() &&
                longitude.isFinite() &&
                latitude in -90.0..90.0 &&
                longitude in -180.0..180.0 &&
                !(latitude == 0.0 && longitude == 0.0)

        internal fun selectBestLocationCandidate(candidates: List<LocationCandidate>): LocationCandidate? =
            candidates
                .filter { isValidWgs84Coordinate(it.latitude, it.longitude) }
                .maxWithOrNull { a, b ->
                    val timeCmp = a.timeMs.compareTo(b.timeMs)
                    if (timeCmp != 0) {
                        timeCmp
                    } else {
                        val accA = a.accuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: Float.MAX_VALUE
                        val accB = b.accuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: Float.MAX_VALUE
                        accB.compareTo(accA)
                    }
                }
    }
}
