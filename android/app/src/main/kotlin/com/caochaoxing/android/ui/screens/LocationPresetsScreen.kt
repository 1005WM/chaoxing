// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.location.ExternalMapLauncher
import com.caochaoxing.android.location.SystemLocationProvider
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxFlowRow
import com.caochaoxing.android.ui.components.CxHairlineDivider
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxSecondaryButton
import com.caochaoxing.android.ui.components.CxSectionHeader
import com.caochaoxing.android.ui.components.CxSurfaceCard
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.components.CxWellBox
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.crypto.CoordinateConverter
import com.caochaoxing.core.crypto.ExternalCoordinateParser
import com.caochaoxing.core.model.CoordinateSystem
import com.caochaoxing.core.service.NominatimGeocoder
import com.caochaoxing.core.service.NominatimPlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

private fun formatCoord6(value: Double): String = String.format(Locale.US, "%.6f", value)

/**
 * Location preset management screen with a full preset editor dialog supporting OSM Nominatim search,
 * system GPS fixes, external map app launchers, clipboard coordinate parsing, and 3-way CRS conversion.
 */
@Composable
fun LocationPresetsScreen(
    appState: AndroidAppState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appScope = rememberAppPersistenceScope()
    var showEditorDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CxPalette.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "常用地点",
                        style = CxTypography.title,
                        color = CxPalette.ink,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxPalette.canvas,
                    titleContentColor = CxPalette.ink,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showEditorDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "添加地点",
                            tint = CxPalette.accent,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "preset_add_banner") {
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CxIconTile(
                                icon = Icons.Rounded.Place,
                                tone = CxTone.ACCENT,
                                size = 38.dp,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = "新增地点预设",
                                    style = CxTypography.headline,
                                    color = CxPalette.ink,
                                )
                                Text(
                                    text = "支持 OSM 搜索、GPS 定位、地图剪贴板识别与 BD-09/GCJ-02/WGS-84 转换",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.inkSecondary,
                                )
                            }
                        }

                        CxPrimaryButton(
                            onClick = { showEditorDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("添加新地点")
                        }
                    }
                }
            }

            item(key = "presets_header") {
                CxSectionHeader(
                    title = "已保存地点",
                    trailing = "${uiState.locationPresets.size} 处",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (uiState.locationPresets.isEmpty()) {
                item(key = "presets_empty") {
                    CxSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        showBorder = true,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LocationOn,
                                contentDescription = null,
                                tint = CxPalette.inkTertiary,
                                modifier = Modifier.size(30.dp),
                            )
                            Text(
                                text = "尚未保存任何地点",
                                style = CxTypography.headline,
                                color = CxPalette.ink,
                            )
                            Text(
                                text = "点击上方「添加新地点」搜索或导入位置坐标",
                                style = MaterialTheme.typography.bodySmall,
                                color = CxPalette.inkSecondary,
                            )
                        }
                    }
                }
            } else {
                items(
                    items = uiState.locationPresets,
                    key = { it.id },
                ) { preset ->
                    CxSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        showBorder = true,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CxIconTile(
                                icon = Icons.Rounded.Place,
                                tone = CxTone.SAGE,
                                size = 36.dp,
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = preset.name,
                                    style = CxTypography.headline,
                                    color = CxPalette.ink,
                                )
                                Text(
                                    text = preset.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.inkSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "BD-09 ${formatCoord6(preset.latitude)}, ${formatCoord6(preset.longitude)}",
                                    style = CxTypography.mono(11.sp),
                                    color = CxPalette.inkTertiary,
                                )
                            }

                            IconButton(
                                onClick = {
                                    val launched = ExternalMapLauncher.openAmap(
                                        context = context,
                                        lat = preset.latitude,
                                        lon = preset.longitude,
                                        label = preset.name,
                                        sourceSystem = CoordinateSystem.BD09,
                                    ) || ExternalMapLauncher.openBaiduMap(
                                        context = context,
                                        lat = preset.latitude,
                                        lon = preset.longitude,
                                        label = preset.name,
                                        sourceSystem = CoordinateSystem.BD09,
                                    ) || ExternalMapLauncher.openGeoFallback(
                                        context = context,
                                        lat = preset.latitude,
                                        lon = preset.longitude,
                                        label = preset.name,
                                        sourceSystem = CoordinateSystem.BD09,
                                    )
                                    if (!launched) {
                                        appState.setErrorMessage("未检测到可用的地图应用")
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = "在地图中查看 ${preset.name}",
                                    tint = CxPalette.accent,
                                )
                            }

                            IconButton(
                                onClick = {
                                    appScope.launch { appState.removeLocationPreset(preset) }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "删除 ${preset.name}",
                                    tint = CxPalette.cinnabar,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditorDialog) {
        LocationPresetEditorDialog(
            appState = appState,
            onDismiss = { showEditorDialog = false },
        )
    }
}

@Composable
private fun LocationPresetEditorDialog(
    appState: AndroidAppState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val appScope = rememberAppPersistenceScope()
    val locationProvider = remember(context) { SystemLocationProvider(context) }

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<NominatimPlace>>(emptyList()) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    var selectedSystem by remember { mutableStateOf(CoordinateSystem.BD09) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var isLocating by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            searchJob?.cancel()
            searchJob = null
        }
    }

    val parsedCoordinate = remember(latText, lonText) {
        val lat = latText.trim().toDoubleOrNull()
        val lon = lonText.trim().toDoubleOrNull()
        if (lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
            lat to lon
        } else {
            null
        }
    }

    val previewBd09 = remember(parsedCoordinate, selectedSystem) {
        parsedCoordinate?.let { (lat, lon) ->
            CoordinateConverter.toBd09(lat, lon, selectedSystem)
        }
    }

    fun showFeedback(message: String, isError: Boolean = false) {
        feedbackMessage = message
        feedbackIsError = isError
    }

    fun executeNominatimSearch() {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        isSearching = true
        feedbackMessage = null
        searchJob = scope.launch {
            try {
                val results = NominatimGeocoder.search(trimmed)
                searchResults = results
                if (results.isEmpty()) {
                    showFeedback("未找到匹配地点，请更换关键词或使用地图选点/手动填写坐标", isError = true)
                } else {
                    showFeedback("找到 ${results.size} 个开放地图候选地点，点击即可填入")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFeedback("地点搜索失败：${e.message ?: "请检查网络连接"}", isError = true)
            } finally {
                isSearching = false
            }
        }
    }

    fun fetchCurrentGpsLocation() {
        if (isLocating) return
        isLocating = true
        feedbackMessage = null
        scope.launch {
            try {
                val (lat, lon) = locationProvider.fetchCurrentWgs84Location()
                selectedSystem = CoordinateSystem.WGS84
                latText = formatCoord6(lat)
                lonText = formatCoord6(lon)
                if (name.isBlank()) {
                    name = "当前定位"
                }
                if (address.isBlank()) {
                    address = "系统定位获取 (WGS-84)"
                }
                showFeedback("已获取当前 GPS 坐标 (WGS-84)，请核对名称与地址后保存")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFeedback(e.message ?: "获取当前定位失败", isError = true)
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

    fun recognizeClipboard() {
        val clip = clipboardManager.getText()?.text?.trim().orEmpty()
        if (clip.isEmpty()) {
            showFeedback("剪贴板中没有文本内容", isError = true)
            return
        }
        val parsed = ExternalCoordinateParser.parse(clip, assumedSystem = selectedSystem)
        if (parsed == null) {
            showFeedback(
                "未从剪贴板识别到坐标（支持高德/百度/腾讯/OSM 分享文本、链接或经纬度）",
                isError = true,
            )
            return
        }
        selectedSystem = parsed.sourceSystem
        latText = formatCoord6(parsed.latitude)
        lonText = formatCoord6(parsed.longitude)
        val parsedLabel = parsed.label?.trim().orEmpty()
        if (parsedLabel.isNotEmpty()) {
            if (name.isBlank()) {
                name = parsedLabel
            }
            if (address.isBlank()) {
                address = parsedLabel
            }
        }
        showFeedback("已识别 ${parsed.sourceSystem.displayName} 坐标")
    }

    fun savePreset() {
        val bd = previewBd09 ?: return
        val cleanName = name.trim()
        val cleanAddress = address.trim()
        if (cleanName.isEmpty() || cleanAddress.isEmpty() || isSaving) return
        isSaving = true
        appScope.launch {
            try {
                val saved = appState.addLocationPreset(
                    name = cleanName,
                    address = cleanAddress,
                    latitude = bd.latitude,
                    longitude = bd.longitude,
                )
                if (saved) {
                    onDismiss()
                } else {
                    showFeedback(
                        appState.uiState.value.errorMessage ?: "保存失败，请重试",
                        isError = true,
                    )
                }
            } finally {
                isSaving = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = CxPalette.canvas,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "添加地点预设",
                            style = CxTypography.title,
                            color = CxPalette.ink,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = CxPalette.canvas,
                        titleContentColor = CxPalette.ink,
                    ),
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭",
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CxSectionHeader(title = "OSM 地点搜索")
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("搜索学校、教学楼或地标") },
                                singleLine = true,
                                colors = cxOutlinedTextFieldColors(),
                                shape = RoundedCornerShape(10.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { executeNominatimSearch() }),
                            )
                            CxPrimaryButton(
                                onClick = { executeNominatimSearch() },
                                enabled = searchQuery.trim().isNotEmpty() && !isSearching,
                            ) {
                                if (isSearching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = CxPalette.onAccent,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isSearching) "搜索中" else "搜索")
                            }
                        }

                        if (searchResults.isNotEmpty()) {
                            CxHairlineDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                searchResults.forEach { place ->
                                    CxWellBox(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                name = place.name
                                                address = place.displayName
                                                latText = formatCoord6(place.latitude)
                                                lonText = formatCoord6(place.longitude)
                                                selectedSystem = place.coordinateSystem
                                                searchResults = emptyList()
                                                showFeedback("已填入「${place.name}」(WGS-84)")
                                            },
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = place.name,
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
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
                                                text = "WGS-84 ${formatCoord6(place.latitude)}, ${formatCoord6(place.longitude)}",
                                                style = CxTypography.mono(10.5.sp),
                                                color = CxPalette.inkTertiary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                CxSectionHeader(title = "定位、剪贴板与外部地图")
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CxSecondaryButton(
                                onClick = { requestCurrentLocation() },
                                enabled = !isLocating,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (isLocating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
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
                                Text(if (isLocating) "定位中…" else "当前 GPS")
                            }

                            CxSecondaryButton(
                                onClick = { recognizeClipboard() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("识别剪贴板")
                            }
                        }

                        CxHairlineDivider()

                        Text(
                            text = "打开外部地图选点并复制分享坐标：",
                            style = MaterialTheme.typography.bodySmall,
                            color = CxPalette.inkSecondary,
                        )
                        val launchLabel = name.trim().ifEmpty { searchQuery.trim() }.ifEmpty { null }
                        val launchLat = previewBd09?.latitude
                        val launchLon = previewBd09?.longitude

                        CxFlowRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                            CxSecondaryButton(
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
                                compact = true,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Map,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("高德地图")
                            }

                            CxSecondaryButton(
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
                                compact = true,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Map,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("百度地图")
                            }

                            CxSecondaryButton(
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
                                compact = true,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Map,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("腾讯地图")
                            }

                            CxSecondaryButton(
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
                                compact = true,
                            ) {
                                Text("通用地图")
                            }
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

                CxSectionHeader(title = "坐标与地点信息")
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "输入坐标系（保存时自动转换为超星 BD-09）",
                            style = MaterialTheme.typography.labelLarge,
                            color = CxPalette.inkSecondary,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CoordinateSystemPill(
                                title = "BD-09",
                                subtitle = "百度",
                                selected = selectedSystem == CoordinateSystem.BD09,
                                onClick = { selectedSystem = CoordinateSystem.BD09 },
                                modifier = Modifier.weight(1f),
                            )
                            CoordinateSystemPill(
                                title = "GCJ-02",
                                subtitle = "高德/腾讯",
                                selected = selectedSystem == CoordinateSystem.GCJ02,
                                onClick = { selectedSystem = CoordinateSystem.GCJ02 },
                                modifier = Modifier.weight(1f),
                            )
                            CoordinateSystemPill(
                                title = "WGS-84",
                                subtitle = "GPS/OSM",
                                selected = selectedSystem == CoordinateSystem.WGS84,
                                onClick = { selectedSystem = CoordinateSystem.WGS84 },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("名称") },
                            placeholder = { Text("如：第一教学楼 / 实验楼 A301") },
                            singleLine = true,
                            colors = cxOutlinedTextFieldColors(),
                            shape = RoundedCornerShape(10.dp),
                        )

                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("地址") },
                            placeholder = { Text("签到提交时显示的详细地址") },
                            colors = cxOutlinedTextFieldColors(),
                            shape = RoundedCornerShape(10.dp),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = latText,
                                onValueChange = { latText = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("纬度") },
                                placeholder = { Text("−90 ~ 90") },
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
                                value = lonText,
                                onValueChange = { lonText = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("经度") },
                                placeholder = { Text("−180 ~ 180") },
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

                        CxWellBox(modifier = Modifier.fillMaxWidth()) {
                            if (previewBd09 != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = "将保存的百度坐标 (BD-09)",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = CxPalette.inkSecondary,
                                        )
                                        if (selectedSystem != CoordinateSystem.BD09) {
                                            CxBadge(text = "已转换", tone = CxTone.ACCENT)
                                        }
                                    }
                                    Text(
                                        text = "${formatCoord6(previewBd09.latitude)}, ${formatCoord6(previewBd09.longitude)}",
                                        style = CxTypography.mono(13.sp, weight = FontWeight.SemiBold),
                                        color = CxPalette.ink,
                                    )
                                }
                            } else {
                                Text(
                                    text = "请输入有效坐标：纬度 −90～90，经度 −180～180",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.inkSecondary,
                                )
                            }
                        }

                        CxPrimaryButton(
                            onClick = { savePreset() },
                            enabled = previewBd09 != null &&
                                name.trim().isNotEmpty() &&
                                address.trim().isNotEmpty() &&
                                !isSaving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = CxPalette.onAccent,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(if (isSaving) "正在保存…" else "保存地点")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoordinateSystemPill(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) CxPalette.accent.copy(alpha = 0.13f) else CxPalette.surfaceSecondary,
        border = BorderStroke(
            width = if (selected) 1.2.dp else 0.8.dp,
            color = if (selected) CxPalette.accent else CxPalette.border,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = CxTypography.mono(12.sp, weight = FontWeight.SemiBold),
                color = if (selected) CxPalette.accent else CxPalette.ink,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) CxPalette.accent else CxPalette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
