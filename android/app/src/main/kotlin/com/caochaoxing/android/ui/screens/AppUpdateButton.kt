package com.caochaoxing.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.caochaoxing.android.CaochaoxingApp
import com.caochaoxing.android.BuildConfig
import com.caochaoxing.android.state.UpdatePhase
import java.util.Locale

@Composable
internal fun AppUpdateButton() {
    val manager = CaochaoxingApp.from(LocalContext.current).updateManager
    val state by manager.state.collectAsState()
    var show by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!manager.needsInstallPermission()) manager.install()
        else permissionError = "需要允许此应用安装更新包，可再次点击安装授权"
    }
    TextButton(onClick = { show = true; if (state.phase in listOf(UpdatePhase.IDLE, UpdatePhase.CURRENT, UpdatePhase.CHECK_FAILED)) manager.check() }) {
        Text(when (state.phase) {
            UpdatePhase.CHECKING -> "检查中…"
            UpdatePhase.DOWNLOADING -> if (state.size > 0) "下载 ${(state.received * 100 / state.size).coerceIn(0, 100)}%" else "下载中…"
            UpdatePhase.READY -> "安装更新"
            UpdatePhase.AVAILABLE -> "发现更新"
            else -> "检查更新"
        })
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text(state.phase.label) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }, confirmButton = {
        when (state.phase) {
            UpdatePhase.AVAILABLE, UpdatePhase.DOWNLOAD_FAILED -> TextButton(onClick = { manager.download() }) { Text(if (state.phase == UpdatePhase.DOWNLOAD_FAILED) "重新下载" else "下载更新") }
            UpdatePhase.CHECK_FAILED -> TextButton(onClick = { manager.check() }) { Text("重试") }
            UpdatePhase.READY -> TextButton(onClick = {
                permissionError = ""
                if (manager.needsInstallPermission()) {
                    try { permission.launch(manager.permissionIntent()) } catch (_: Exception) { permissionError = "无法打开安装授权，请到系统设置中允许安装未知应用" }
                } else manager.install()
            }) { Text("安装") }
            UpdatePhase.DOWNLOADING -> TextButton(onClick = { manager.cancel() }) { Text("取消下载") }
            else -> TextButton(onClick = { show = false }) { Text("确定") }
        }
    }, dismissButton = { if (state.phase !in listOf(UpdatePhase.CURRENT, UpdatePhase.IDLE)) TextButton(onClick = { show = false }) { Text("关闭") } })
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
