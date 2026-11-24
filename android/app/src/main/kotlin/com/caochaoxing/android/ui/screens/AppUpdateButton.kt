package com.caochaoxing.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.caochaoxing.android.CaochaoxingApp
import com.caochaoxing.android.BuildConfig
import com.caochaoxing.android.state.AppUpdateManager
import com.caochaoxing.android.state.UpdatePhase
import java.util.Locale

@Composable
internal fun AppUpdateButton() {
    val manager = CaochaoxingApp.from(LocalContext.current).updateManager
    val state by manager.state.collectAsState()
    TextButton(onClick = manager::openDialog) {
        Text(when (state.phase) {
            UpdatePhase.CHECKING -> "检查中…"
            UpdatePhase.DOWNLOADING -> "查看更新"
            UpdatePhase.READY -> "安装更新"
            UpdatePhase.AVAILABLE -> "发现更新"
            else -> "检查更新"
        })
    }
}

@Composable
internal fun AppUpdateHost(manager: AppUpdateManager) {
    val show by manager.dialogVisible.collectAsState()
    val visibility = remember { MutableTransitionState(false) }
    SideEffect { visibility.targetState = show }
    LaunchedEffect(manager) { manager.checkOnLaunch() }
    if (show || visibility.currentState || !visibility.isIdle) {
        Dialog(onDismissRequest = manager::closeDialog) {
            AnimatedVisibility(visibleState = visibility, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
                AppUpdatePanel(manager)
            }
        }
    }
}

@Composable
private fun AppUpdatePanel(manager: AppUpdateManager) {
    val state by manager.state.collectAsState()
    var permissionError by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!manager.needsInstallPermission()) manager.install()
        else permissionError = "需要允许此应用安装更新包，可再次点击安装授权"
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(state.phase.label, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}")
                UpdateDownloadProgress(state)
                state.release?.let {
                    Text("最新版本 ${it.version}")
                    if (it.publishedAt.isNotBlank()) Text("发布于 ${it.publishedAt}")
                    Text(if (state.size > 0) "安装包 ${updateSize(state.size)}" else "安装包大小将在下载时显示")
                    if (it.notes.isNotBlank()) Text(it.notes)
                }
                if (state.phase == UpdatePhase.CHECKING) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.message.isNotBlank()) Text(state.message)
                if (permissionError.isNotBlank()) Text(permissionError)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (state.phase !in listOf(UpdatePhase.CURRENT, UpdatePhase.IDLE)) TextButton(onClick = manager::closeDialog) {
                    Text(if (state.phase == UpdatePhase.AVAILABLE) "稍后" else "关闭")
                }
                when (state.phase) {
                    UpdatePhase.AVAILABLE, UpdatePhase.DOWNLOAD_FAILED -> TextButton(onClick = manager::download) {
                        Text(if (state.phase == UpdatePhase.DOWNLOAD_FAILED) "重新下载" else "下载更新")
                    }
                    UpdatePhase.CHECK_FAILED -> TextButton(onClick = manager::check) { Text("重试") }
                    UpdatePhase.READY -> TextButton(onClick = {
                        permissionError = ""
                        if (manager.needsInstallPermission()) {
                            try { permission.launch(manager.permissionIntent()) }
                            catch (_: Exception) { permissionError = "无法打开安装授权，请到系统设置中允许安装未知应用" }
                        } else manager.install()
                    }) { Text("安装") }
                    UpdatePhase.DOWNLOADING -> TextButton(onClick = manager::cancel) { Text("取消下载") }
                    UpdatePhase.CURRENT, UpdatePhase.IDLE -> TextButton(onClick = manager::closeDialog) { Text("确定") }
                    UpdatePhase.CHECKING -> Unit
                }
            }
        }
    }
}

private fun updateSize(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)

@Composable
private fun UpdateDownloadProgress(state: com.caochaoxing.android.state.AppUpdateState) {
    if (state.phase != UpdatePhase.DOWNLOADING) return
    val fraction = if (state.size > 0) (state.received.toFloat() / state.size).coerceIn(0f, 1f) else null
    Text(if (fraction != null) "下载更新 ${(fraction * 100).toInt()}%" else "正在下载更新", style = MaterialTheme.typography.bodyMedium)
    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(Modifier.fillMaxWidth())
    Text("${updateSize(state.received)} / ${if (state.size > 0) updateSize(state.size) else "未知大小"}", style = MaterialTheme.typography.bodySmall)
}
