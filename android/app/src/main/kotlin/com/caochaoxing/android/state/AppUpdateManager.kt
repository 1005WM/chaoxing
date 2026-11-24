package com.caochaoxing.android.state

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.caochaoxing.android.BuildConfig
import com.caochaoxing.core.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

enum class UpdatePhase(val label: String) {
    IDLE("空闲"), CHECKING("检查中"), CURRENT("已是最新版本"), AVAILABLE("发现新版本"),
    DOWNLOADING("下载中"), READY("下载完成，等待安装"), CHECK_FAILED("检查失败"), DOWNLOAD_FAILED("下载失败")
}

data class AppUpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val release: UpdateRelease? = null,
    val size: Long = 0, val received: Long = 0, val message: String = "")

class AppUpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val service: AppUpdateService = AppUpdateService(),
    private val latestRelease: suspend () -> UpdateRelease = service::latest,
) {
    private val _state = MutableStateFlow(AppUpdateState())
    val state = _state.asStateFlow()
    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible = _dialogVisible.asStateFlow()
    private var startupChecked = false
    private var manualDialogOpened = false
    private var job: Job? = null
    private var cached: UpdateRelease? = null
    private var checkedAt = 0L
    private val apk = File(context.cacheDir, "updates/update.apk")
    private val partial = File(context.cacheDir, "updates/update.part")

    fun checkOnLaunch() {
        if (startupChecked) return
        startupChecked = true
        if (_state.value.phase == UpdatePhase.IDLE) check(announce = true)
    }

    fun openDialog() {
        manualDialogOpened = true
        _dialogVisible.value = true
        if (_state.value.phase in listOf(UpdatePhase.IDLE, UpdatePhase.CURRENT, UpdatePhase.CHECK_FAILED)) check()
    }

    fun closeDialog() {
        _dialogVisible.value = false
    }

    fun check() = check(announce = false)

    private fun check(announce: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = AppUpdateState(UpdatePhase.CHECKING)
            try {
                val now = android.os.SystemClock.elapsedRealtime()
                val release = cached?.takeIf { now - checkedAt < 300_000 } ?: latestRelease().also {
                    cached = it
                    checkedAt = now
                }
                val newer = AppVersion.parse(release.version) > AppVersion.parse(BuildConfig.VERSION_NAME)
                if (!newer || (!UpdateSource.INCLUDE_PRERELEASE && (release.prerelease || AppVersion.parse(release.version).prerelease.isNotEmpty()))) {
                    _state.value = AppUpdateState(UpdatePhase.CURRENT)
                } else {
                    val asset = service.androidAsset(release, Build.SUPPORTED_ABIS.toList())
                    _state.value = AppUpdateState(UpdatePhase.AVAILABLE, release, asset.size)
                    if (announce && !manualDialogOpened) _dialogVisible.value = true
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = AppUpdateState(UpdatePhase.CHECK_FAILED, message = e.message ?: "读取更新信息失败，请重试") }
        }
    }

    fun download() {
        if (job?.isActive == true) return
        val release = _state.value.release ?: return
        job = scope.launch {
            try {
                var asset = service.androidAsset(release, Build.SUPPORTED_ABIS.toList())
                _state.value = AppUpdateState(UpdatePhase.DOWNLOADING, release, asset.size)
                try { downloadAsset(asset) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (!release.fromMirror) throw e
                    _state.value = _state.value.copy(received = 0, message = "镜像下载失败，正在切换 GitHub")
                    val github = service.githubLatest()
                    require(AppVersion.parse(github.version) == AppVersion.parse(release.version)) { "镜像和 GitHub 版本不同，请重新检查更新" }
                    asset = service.androidAsset(github, Build.SUPPORTED_ABIS.toList())
                    downloadAsset(asset)
                }
                _state.value = AppUpdateState(UpdatePhase.READY, release, apk.length(), apk.length())
            } catch (e: CancellationException) {
                _state.value = AppUpdateState(UpdatePhase.AVAILABLE, release, message = "下载已取消，可重新下载")
                throw e
            } catch (e: Exception) {
                _state.value = AppUpdateState(UpdatePhase.DOWNLOAD_FAILED, release, message = e.message ?: "下载失败，请重试")
            } finally { withContext(NonCancellable + Dispatchers.IO) { if (partial.exists()) partial.delete() } }
        }
    }

    private suspend fun downloadAsset(asset: UpdateAsset) {
        UpdateHttp(600).get(asset.url) { response ->
            val body = response.body ?: error("下载内容为空")
            val expected = asset.size.takeIf { it > 0 } ?: body.contentLength().coerceAtLeast(0)
            require(expected <= 1_500_000_000) { "安装包过大" }
            require(partial.parentFile?.mkdirs() == true || partial.parentFile?.isDirectory == true) { "无法创建下载目录" }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastProgress = 0L
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        received += length
                        require(received <= 1_500_000_000) { "安装包过大" }
                        output.write(buffer, 0, length)
                        digest.update(buffer, 0, length)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress > 150) {
                            lastProgress = now
                            _state.value = _state.value.copy(received = received, size = expected)
                        }
                    }
                }
            }
            require(received > 0 && (expected == 0L || expected == received)) { "安装包下载不完整，请重新下载" }
            asset.sha256?.takeIf { it.isNotBlank() }?.let { sha ->
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(Regex("[a-fA-F0-9]{64}").matches(sha) && actual.equals(sha, true)) { "SHA256 校验失败，请重新下载" }
            }
            validateApk(partial)
            java.nio.file.Files.move(partial.toPath(), apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("下载文件不是有效的 APK")
        require(archive.packageName == context.packageName) { "安装包标识与当前应用不一致，无法覆盖安装" }
        require(AppVersion.parse(archive.versionName.orEmpty()) == AppVersion.parse(_state.value.release?.version.orEmpty())) { "安装包版本与更新信息不一致，请重新检查" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val oldSignatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.signingCertificateHistory else installed.signatures
        val newSignatures = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.signingCertificateHistory else archive.signatures
        require(!oldSignatures.isNullOrEmpty() && !newSignatures.isNullOrEmpty() && oldSignatures.any { it in newSignatures }) {
            "安装包签名与当前版本不同，请使用相同签名的更新包"
        }
        val remoteCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val localCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(remoteCode > localCode) { "安装包构建号未增加，无法作为更新安装" }
    }

    fun cancel() { job?.cancel() }
    fun needsInstallPermission(): Boolean = !context.packageManager.canRequestPackageInstalls()
    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    fun install() {
        try {
            require(apk.isFile) { "安装包已被清理，请重新下载" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { _state.value = _state.value.copy(message = e.message ?: "无法打开系统安装器") }
    }
}
