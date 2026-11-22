// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.screens.signers

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.location.ExternalMapLauncher
import com.caochaoxing.android.location.SystemLocationProvider
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.components.CxFlowRow
import com.caochaoxing.android.ui.components.CxHairlineDivider
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxSecondaryButton
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.components.CxWellBox
import com.caochaoxing.android.ui.screens.cxOutlinedTextFieldColors
import com.caochaoxing.android.ui.screens.rememberAppPersistenceScope
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.crypto.CoordinateConverter
import com.caochaoxing.core.crypto.ExternalCoordinateParser
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingLocationSignInfo
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignDetailMetadata
import com.caochaoxing.core.model.CoordinateSystem
import com.caochaoxing.core.service.NominatimGeocoder
import com.caochaoxing.core.service.NominatimPlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val LEAFLET_ASSET_URL = "file:///android_asset/leaflet_picker.html"
private const val JS_BRIDGE_NAME = "AndroidLeafletBridge"

private fun formatBd09Coord(value: Double): String = String.format(Locale.US, "%.6f", value)

private class LeafletJsBridge(
    private val mainHandler: Handler,
    private val isDisposed: () -> Boolean,
    private val onMapReady: () -> Unit,
    private val onCoordinateSelected: (Double, Double) -> Unit,
    private val onMapError: (String) -> Unit,
) {
    @JavascriptInterface
    fun onMapReady() {
        mainHandler.post {
            if (!isDisposed()) {
                onMapReady.invoke()
            }
        }
    }

    @JavascriptInterface
    fun onCoordinateSelected(lat: Double, lon: Double) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return
        }
        mainHandler.post {
            if (!isDisposed()) {
                onCoordinateSelected.invoke(lat, lon)
            }
        }
    }

    @JavascriptInterface
    fun onMapError(message: String?) {
        val text = message?.trim().takeIf { !it.isNullOrEmpty() } ?: "地图瓦片加载受限"
        mainHandler.post {
            if (!isDisposed()) {
                onMapError.invoke(text)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun configureLeafletWebSettings(settings: WebSettings) {
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.domStorageEnabled = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
}

/**
 * Location check-in panel inside [SignWorkspaceScreen] combining Chaoxing BD-09 geofence/presets,
 * zero-key OpenStreetMap Leaflet picking (WGS-84 <-> BD-09), Nominatim search, native GPS,
 * external Chinese map deep-linking, and clipboard multi-CRS import.
 */
@Composable
fun LocationSignPanel(
    appState: AndroidAppState,
    activity: ChaoxingSignActivity,
    detail: ChaoxingSignDetailMetadata?,
    isSigning: Boolean,
    enabled: Boolean = true,
    onSubmit: (ChaoxingLocationSignInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val appScope = rememberAppPersistenceScope()
    val locationProvider = remember(context) { SystemLocationProvider(context) }

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<NominatimPlace>>(emptyList()) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchRequestId by remember { mutableLongStateOf(0L) }

    var address by remember { mutableStateOf("") }
    var latitudeText by remember { mutableStateOf("") }
    var longitudeText by remember { mutableStateOf("") }
    var presetName by remember { mutableStateOf("") }
    var assumedSystem by remember { mutableStateOf(CoordinateSystem.GCJ02) }
    var isLocating by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }

    var mapReady by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var lastBridgeCoords by remember { mutableStateOf<Pair<String, String>?>(null) }

    val boundPreset = remember(activity.courseId, uiState.courseLocationBindings) {
        uiState.boundLocation(activity.courseId)
    }

    val parsedCoords = remember(latitudeText, longitudeText) {
        val lat = latitudeText.trim().toDoubleOrNull()
        val lon = longitudeText.trim().toDoubleOrNull()
        if (lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
            lat to lon
        } else {
            null
        }
    }

    val parsedLocation = remember(parsedCoords, address) {
        parsedCoords?.let { (lat, lon) ->
            val cleanAddress = address.trim()
            ChaoxingLocationSignInfo(
                latitude = lat,
                longitude = lon,
                address = cleanAddress.ifEmpty { "当前定位" },
            )
        }
    }

    fun showFeedback(message: String, isError: Boolean = false) {
        feedbackMessage = message
        feedbackIsError = isError
    }

    fun applyBd09Coordinates(
        latitude: Double,
        longitude: Double,
        addressText: String,
    ) {
        lastBridgeCoords = null
        latitudeText = formatBd09Coord(latitude)
        longitudeText = formatBd09Coord(longitude)
        address = addressText
    }

    LaunchedEffect(activity.id, detail?.activeId, enabled) {
        if (latitudeText.isBlank() || longitudeText.isBlank()) {
            val target = detail?.targetLocation
            val tLat = target?.latitude
            val tLon = target?.longitude
            when {
                boundPreset != null -> applyBd09Coordinates(
                    latitude = boundPreset.latitude,
                    longitude = boundPreset.longitude,
                    addressText = boundPreset.address,
                )

                tLat != null && tLon != null -> applyBd09Coordinates(
                    latitude = tLat,
                    longitude = tLon,
                    addressText = "教师指定位置",
                )

                (detail != null || enabled) && uiState.locationPresets.isNotEmpty() -> {
                    val first = uiState.locationPresets.first()
                    applyBd09Coordinates(
                        latitude = first.latitude,
                        longitude = first.longitude,
                        addressText = first.address,
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            searchJob?.cancel()
            searchJob = null
        }
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val disposedFlag = remember { AtomicBoolean(false) }

    val currentOnMapReady by rememberUpdatedState(newValue = {
        mapReady = true
    })
    val currentOnCoordinateSelected by rememberUpdatedState(newValue = { wgsLat: Double, wgsLon: Double ->
        val bd = CoordinateConverter.wgs84ToBd09(wgsLat, wgsLon)
        val formattedLat = formatBd09Coord(bd.latitude)
        val formattedLon = formatBd09Coord(bd.longitude)
        lastBridgeCoords = formattedLat to formattedLon
        latitudeText = formattedLat
        longitudeText = formattedLon
        if (address.isBlank()) {
            address = "地图选点"
        }
    })
    val currentOnMapError by rememberUpdatedState(newValue = { msg: String ->
        mapError = msg
    })

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    val webView = remember(context) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            configureLeafletWebSettings(settings)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    return url != LEAFLET_ASSET_URL
                }
            }
            addJavascriptInterface(
                LeafletJsBridge(
                    mainHandler = mainHandler,
                    isDisposed = { disposedFlag.get() },
                    onMapReady = { currentOnMapReady() },
                    onCoordinateSelected = { lat, lon -> currentOnCoordinateSelected(lat, lon) },
                    onMapError = { msg -> currentOnMapError(msg) },
                ),
                JS_BRIDGE_NAME,
            )
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
            loadUrl(LEAFLET_ASSET_URL)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            disposedFlag.set(true)
            mainHandler.removeCallbacksAndMessages(null)
            webView.setOnTouchListener(null)
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    LaunchedEffect(mapReady, parsedCoords) {
        if (!mapReady || disposedFlag.get()) return@LaunchedEffect
        val coords = parsedCoords ?: return@LaunchedEffect
        val trimmedPair = latitudeText.trim() to longitudeText.trim()
        if (lastBridgeCoords == trimmedPair) {
            return@LaunchedEffect
        }
        val wgs = CoordinateConverter.bd09ToWgs84(coords.first, coords.second)
        val title = address.trim().ifEmpty { "签到位置" }
        val escapedTitle = JSONObject.quote(title)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        webView.evaluateJavascript(
            "if (window.setMarker) { window.setMarker(${wgs.latitude}, ${wgs.longitude}, $escapedTitle); }",
            null,
        )
    }

    LaunchedEffect(mapReady, detail?.targetLocation) {
        if (!mapReady || disposedFlag.get()) return@LaunchedEffect
        val target = detail?.targetLocation
        val tLat = target?.latitude
        val tLon = target?.longitude
        if (tLat != null && tLon != null && tLat.isFinite() && tLon.isFinite() && tLat in -90.0..90.0 && tLon in -180.0..180.0) {
            val wgs = CoordinateConverter.bd09ToWgs84(tLat, tLon)
            val radius = (target.rangeMeters ?: 100).coerceAtLeast(10)
            webView.evaluateJavascript(
                "if (window.setFence) { window.setFence(${wgs.latitude}, ${wgs.longitude}, $radius); }",
                null,
            )
        } else {
            webView.evaluateJavascript(
                "if (window.clearFence) { window.clearFence(); }",
                null,
            )
        }
    }

    fun executeNominatimSearch() {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty() || !enabled) return
        searchJob?.cancel()
        val requestId = ++searchRequestId
        val token = appState.sessionToken
        isSearching = true
        feedbackMessage = null
        searchJob = scope.launch {
            try {
                val results = NominatimGeocoder.search(trimmed)
                if (searchRequestId != requestId || !appState.isCurrent(token)) return@launch
                searchResults = results
                if (results.isEmpty()) {
                    showFeedback("未找到匹配地点，请更换关键词或点击地图拾取坐标", isError = true)
                } else {
                    showFeedback("找到 ${results.size} 个开放地图候选地点，点击即可转为 BD-09")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (searchRequestId == requestId && appState.isCurrent(token)) {
                    showFeedback("地点搜索失败：${e.message ?: "请检查网络连接"}", isError = true)
                }
            } finally {
                if (searchRequestId == requestId) {
                    isSearching = false
                }
            }
        }
    }

    fun fetchCurrentGpsLocation() {
        if (isLocating || !enabled) return
        isLocating = true
        feedbackMessage = null
        val token = appState.sessionToken
        scope.launch {
            try {
                val (wgsLat, wgsLon) = locationProvider.fetchCurrentWgs84Location()
                if (!appState.isCurrent(token)) return@launch
                val bd = CoordinateConverter.wgs84ToBd09(wgsLat, wgsLon)
                applyBd09Coordinates(
                    latitude = bd.latitude,
                    longitude = bd.longitude,
                    addressText = address.trim().ifEmpty { "当前定位" },
                )
                showFeedback("已获取当前 GPS 位置并转换为 BD-09")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (appState.isCurrent(token)) {
                    showFeedback(e.message ?: "获取当前定位失败", isError = true)
                }
            } finally {
                isLocating = false
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.any { it }
        if (granted) {
            fetchCurrentGpsLocation()
        } else {
            showFeedback("定位权限未开启，请在系统设置中授予位置权限或改用地图选点", isError = true)
        }
    }

    fun requestCurrentLocation() {
        if (!enabled) return
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            fetchCurrentGpsLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    fun importFromClipboard() {
        if (!enabled) return
        val clip = clipboardManager.getText()?.text?.trim().orEmpty()
        if (clip.isEmpty()) {
            showFeedback("剪贴板中没有文本内容", isError = true)
            return
        }
        val parsed = ExternalCoordinateParser.parse(clip, assumedSystem = assumedSystem)
        if (parsed == null) {
            showFeedback(
                "未从剪贴板识别到坐标（支持高德/百度/腾讯/OSM 链接或经纬度）",
                isError = true,
            )
            return
        }
        assumedSystem = parsed.sourceSystem
        val bd = CoordinateConverter.toBd09(parsed.latitude, parsed.longitude, parsed.sourceSystem)
        val parsedLabel = parsed.label?.trim().orEmpty()
        val resolvedAddress = if (parsedLabel.isNotEmpty() &&
            (address.isBlank() || address in DEFAULT_PLACEHOLDER_ADDRESSES)
        ) {
            parsedLabel
        } else {
            address.trim().ifEmpty { parsedLabel.ifEmpty { "剪贴板导入位置" } }
        }
        applyBd09Coordinates(
            latitude = bd.latitude,
            longitude = bd.longitude,
            addressText = resolvedAddress,
        )
        showFeedback("已识别 ${parsed.sourceSystem.displayName} 并转换为 BD-09")
    }

    val targetLocation = detail?.targetLocation
    val targetLat = targetLocation?.latitude
    val targetLon = targetLocation?.longitude

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (targetLat != null && targetLon != null) {
            CxWellBox(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CxIconTile(
                        icon = Icons.Rounded.GpsFixed,
                        tone = CxTone.ACCENT,
                        size = 34.dp,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "教师电子围栏 · 半径 ${targetLocation.rangeMeters ?: 0}m",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = CxPalette.ink,
                        )
                        Text(
                            text = "BD-09 ${formatBd09Coord(targetLat)}, ${formatBd09Coord(targetLon)}",
                            style = CxTypography.mono(11.sp),
                            color = CxPalette.inkSecondary,
                        )
                    }
                    CxSecondaryButton(
                        onClick = {
                            applyBd09Coordinates(
                                latitude = targetLat,
                                longitude = targetLon,
                                addressText = address.ifBlank { "指定签到范围" },
                            )
                        },
                        enabled = enabled,
                        compact = true,
                    ) {
                        Text("采用中心点")
                    }
                }
            }
        }

        if (detail?.isFaceRequired == true) {
            CxWellBox(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Face,
                        contentDescription = null,
                        tint = CxPalette.plum,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = "已开启人脸核验：提交时自动为各账号拉取底库照片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = CxPalette.plum,
                    )
                }
            }
        }

        if (boundPreset != null) {
            CxWellBox(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = null,
                        tint = CxPalette.sage,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "课程绑定教室：${boundPreset.name}",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = CxPalette.sage,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = {
                            appScope.launch { appState.unbindLocation(activity.courseId) }
                        },
                        enabled = enabled,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LinkOff,
                            contentDescription = null,
                            tint = CxPalette.inkSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "解除绑定",
                            style = MaterialTheme.typography.labelMedium,
                            color = CxPalette.inkSecondary,
                        )
                    }
                }
            }
        }

        if (uiState.locationPresets.isNotEmpty()) {
            CxFlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalSpacing = 8.dp,
                verticalSpacing = 8.dp,
            ) {
                uiState.locationPresets.forEach { preset ->
                    Surface(
                        onClick = {
                            applyBd09Coordinates(
                                latitude = preset.latitude,
                                longitude = preset.longitude,
                                addressText = preset.address,
                            )
                        },
                        enabled = enabled,
                        shape = CircleShape,
                        color = CxPalette.surfaceSecondary,
                        border = BorderStroke(0.8.dp, CxPalette.border),
                    ) {
                        Row(
                            modifier = Modifier
                                .defaultMinSize(minHeight = 36.dp)
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Place,
                                contentDescription = null,
                                tint = CxPalette.accent,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = CxPalette.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索教学楼 / 校区地标") },
                singleLine = true,
                enabled = enabled,
                colors = cxOutlinedTextFieldColors(),
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { executeNominatimSearch() }),
            )
            CxSecondaryButton(
                onClick = { executeNominatimSearch() },
                enabled = enabled && !isSearching && searchQuery.trim().isNotEmpty(),
            ) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = CxPalette.accent,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isSearching) "检索中" else "检索")
            }
        }

        if (searchResults.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                searchResults.forEach { place ->
                    val bd = CoordinateConverter.wgs84ToBd09(place.latitude, place.longitude)
                    CxWellBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                applyBd09Coordinates(
                                    latitude = bd.latitude,
                                    longitude = bd.longitude,
                                    addressText = place.name.ifBlank { place.displayName },
                                )
                                searchResults = emptyList()
                                showFeedback("已填入「${place.name}」(BD-09)")
                            },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = place.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = CxPalette.ink,
                            )
                            Text(
                                text = place.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = CxPalette.inkSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "BD-09 ${formatBd09Coord(bd.latitude)}, ${formatBd09Coord(bd.longitude)}",
                                style = CxTypography.mono(10.5.sp),
                                color = CxPalette.inkTertiary,
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, CxPalette.border, RoundedCornerShape(12.dp)),
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )

            mapError?.let { errorPill ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    shape = CircleShape,
                    color = CxPalette.surface.copy(alpha = 0.94f),
                    border = BorderStroke(0.8.dp, CxPalette.amber.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WifiOff,
                            contentDescription = null,
                            tint = CxPalette.amber,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = errorPill,
                            style = MaterialTheme.typography.labelSmall,
                            color = CxPalette.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "轻点或拖动地图标记可精确拾取坐标",
                style = MaterialTheme.typography.labelSmall,
                color = CxPalette.inkTertiary,
            )
            if (parsedCoords != null) {
                Text(
                    text = "BD-09 ${formatBd09Coord(parsedCoords.first)}, ${formatBd09Coord(parsedCoords.second)}",
                    style = CxTypography.mono(10.5.sp),
                    color = CxPalette.inkSecondary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CxSecondaryButton(
                onClick = { requestCurrentLocation() },
                enabled = enabled && !isLocating,
                modifier = Modifier.weight(1f),
            ) {
                if (isLocating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = CxPalette.accent,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MyLocation,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(5.dp))
                Text(if (isLocating) "定位中…" else "当前定位 (GPS)")
            }

            CxSecondaryButton(
                onClick = { importFromClipboard() },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("从剪贴板导入")
            }
        }

        CxWellBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "剪贴板默认坐标系（自动识别高德/百度/腾讯 URL 时优先按来源）",
                    style = MaterialTheme.typography.labelSmall,
                    color = CxPalette.inkSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CompactCrsChip(
                        label = "BD-09",
                        selected = assumedSystem == CoordinateSystem.BD09,
                        enabled = enabled,
                        onClick = { assumedSystem = CoordinateSystem.BD09 },
                        modifier = Modifier.weight(1f),
                    )
                    CompactCrsChip(
                        label = "GCJ-02",
                        selected = assumedSystem == CoordinateSystem.GCJ02,
                        enabled = enabled,
                        onClick = { assumedSystem = CoordinateSystem.GCJ02 },
                        modifier = Modifier.weight(1f),
                    )
                    CompactCrsChip(
                        label = "WGS-84",
                        selected = assumedSystem == CoordinateSystem.WGS84,
                        enabled = enabled,
                        onClick = { assumedSystem = CoordinateSystem.WGS84 },
                        modifier = Modifier.weight(1f),
                    )
                }

                CxHairlineDivider()

                val launchLat = parsedLocation?.latitude ?: targetLat
                val launchLon = parsedLocation?.longitude ?: targetLon
                val launchLabel = address.ifBlank { activity.courseName }

                Text(
                    text = "在外部地图查看或选点：",
                    style = MaterialTheme.typography.labelSmall,
                    color = CxPalette.inkSecondary,
                )

                CxFlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalSpacing = 6.dp,
                    verticalSpacing = 6.dp,
                ) {
                    ExternalMapChipButton(
                        title = "高德",
                        enabled = enabled,
                        onClick = {
                            if (!ExternalMapLauncher.openAmap(
                                    context = context,
                                    lat = launchLat,
                                    lon = launchLon,
                                    label = launchLabel,
                                    sourceSystem = CoordinateSystem.BD09,
                                )
                            ) {
                                showFeedback("未安装高德地图", isError = true)
                            }
                        },
                    )
                    ExternalMapChipButton(
                        title = "百度",
                        enabled = enabled,
                        onClick = {
                            if (!ExternalMapLauncher.openBaiduMap(
                                    context = context,
                                    lat = launchLat,
                                    lon = launchLon,
                                    label = launchLabel,
                                    sourceSystem = CoordinateSystem.BD09,
                                )
                            ) {
                                showFeedback("未安装百度地图", isError = true)
                            }
                        },
                    )
                    ExternalMapChipButton(
                        title = "腾讯",
                        enabled = enabled,
                        onClick = {
                            if (!ExternalMapLauncher.openTencentMap(
                                    context = context,
                                    lat = launchLat,
                                    lon = launchLon,
                                    label = launchLabel,
                                    sourceSystem = CoordinateSystem.BD09,
                                )
                            ) {
                                showFeedback("未安装腾讯地图", isError = true)
                            }
                        },
                    )
                    ExternalMapChipButton(
                        title = "系统地图",
                        enabled = enabled,
                        onClick = {
                            if (!ExternalMapLauncher.openGeoFallback(
                                    context = context,
                                    lat = launchLat,
                                    lon = launchLon,
                                    label = launchLabel,
                                    sourceSystem = CoordinateSystem.BD09,
                                )
                            ) {
                                showFeedback("未找到系统地图应用", isError = true)
                            }
                        },
                    )
                }
            }
        }

        feedbackMessage?.let { msg ->
            CxWellBox(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (feedbackIsError) CxPalette.cinnabar else CxPalette.sage,
                )
            }
        }

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("签到显示地址") },
            placeholder = { Text("如：第六教学楼 302") },
            enabled = enabled,
            singleLine = true,
            colors = cxOutlinedTextFieldColors(),
            shape = RoundedCornerShape(10.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = latitudeText,
                onValueChange = {
                    lastBridgeCoords = null
                    latitudeText = it
                },
                modifier = Modifier.weight(1f),
                label = { Text("BD-09 纬度") },
                placeholder = { Text("−90 ~ 90") },
                enabled = enabled,
                singleLine = true,
                textStyle = CxTypography.mono(13.sp),
                colors = cxOutlinedTextFieldColors(),
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
            )
            OutlinedTextField(
                value = longitudeText,
                onValueChange = {
                    lastBridgeCoords = null
                    longitudeText = it
                },
                modifier = Modifier.weight(1f),
                label = { Text("BD-09 经度") },
                placeholder = { Text("−180 ~ 180") },
                enabled = enabled,
                singleLine = true,
                textStyle = CxTypography.mono(13.sp),
                colors = cxOutlinedTextFieldColors(),
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = presetName,
                onValueChange = { presetName = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("常用地点备注名（可选）") },
                enabled = enabled,
                singleLine = true,
                colors = cxOutlinedTextFieldColors(),
                shape = RoundedCornerShape(10.dp),
            )
            CxSecondaryButton(
                onClick = {
                    val loc = parsedLocation ?: return@CxSecondaryButton
                    val cleanName = presetName.trim().ifEmpty { loc.address }
                    appScope.launch {
                        val saved = appState.addLocationPreset(
                            name = cleanName,
                            address = loc.address,
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                        )
                        if (saved) {
                            presetName = ""
                        }
                    }
                },
                enabled = enabled && parsedLocation != null,
            ) {
                Text("存为预设")
            }
        }

        if (activity.courseId != 0L) {
            val courseLabel = activity.courseName.ifBlank { "本课程" }
            CxSecondaryButton(
                onClick = {
                    val loc = parsedLocation ?: return@CxSecondaryButton
                    val cleanName = presetName.trim().ifEmpty { loc.address }
                    val preset = ChaoxingLocationPreset(
                        name = cleanName,
                        address = loc.address,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                    )
                    appScope.launch {
                        appState.bindLocation(activity.courseId, preset)
                    }
                },
                enabled = enabled && parsedLocation != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PushPin,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "设为《$courseLabel》默认教室",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        CxPrimaryButton(
            onClick = {
                val loc = parsedLocation ?: return@CxPrimaryButton
                onSubmit(loc)
            },
            enabled = enabled && !isSigning && parsedLocation != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "提交位置签到",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun CompactCrsChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) CxPalette.accent.copy(alpha = 0.14f) else CxPalette.surface,
        border = BorderStroke(
            width = if (selected) 1.1.dp else 0.8.dp,
            color = if (selected) CxPalette.accent else CxPalette.border,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = CxTypography.mono(11.sp, weight = if (selected) FontWeight.Bold else FontWeight.Medium),
                color = if (selected) CxPalette.accent else CxPalette.inkSecondary,
            )
        }
    }
}

@Composable
private fun ExternalMapChipButton(
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = CxPalette.surface,
        border = BorderStroke(0.8.dp, CxPalette.border),
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 36.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Map,
                contentDescription = null,
                tint = CxPalette.accent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = CxPalette.ink,
            )
        }
    }
}

private val DEFAULT_PLACEHOLDER_ADDRESSES = setOf(
    "地图选点",
    "当前定位",
    "教师指定位置",
    "指定签到范围",
)
