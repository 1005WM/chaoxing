// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.media

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * CameraX back-camera preview paired with throttled ZXing [QrCodeAnalyzer] QR detection
 * and live torch toggle support.
 */
@Composable
fun QrCodeCameraPreview(
    modifier: Modifier = Modifier,
    torchEnabled: Boolean = false,
    onQrDetected: (String) -> Unit,
    onCameraError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQrDetected by rememberUpdatedState(onQrDetected)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val currentTorchEnabled by rememberUpdatedState(torchEnabled)

    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasCameraPermission) {
        LaunchedEffect(Unit) {
            currentOnCameraError("相机权限未开启，请在系统设置中允许访问相机或改用相册识码")
        }
        Box(modifier = modifier.background(Color.Black))
        return
    }

    val previewView = remember(context) {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(boundCamera, torchEnabled) {
        val camera = boundCamera ?: return@LaunchedEffect
        if (camera.cameraInfo.hasFlashUnit()) {
            camera.cameraControl.enableTorch(torchEnabled)
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        var disposed = false
        var cameraProvider: ProcessCameraProvider? = null
        var imageAnalysis: ImageAnalysis? = null
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(
            {
                if (disposed) return@addListener
                try {
                    val provider = cameraProviderFuture.get()
                    if (disposed) return@addListener
                    cameraProvider = provider

                    if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        currentOnCameraError("未找到可用后置相机，请改用相册或粘贴二维码")
                        return@addListener
                    }

                    val preview = Preview.Builder()
                        .build()
                        .apply { setSurfaceProvider(previewView.surfaceProvider) }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build()

                    val analyzer = QrCodeAnalyzer { decoded ->
                        mainExecutor.execute {
                            if (!disposed) {
                                currentOnQrDetected(decoded)
                            }
                        }
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply { setAnalyzer(analysisExecutor, analyzer) }
                    imageAnalysis = analysis

                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    boundCamera = camera
                    if (camera.cameraInfo.hasFlashUnit()) {
                        camera.cameraControl.enableTorch(currentTorchEnabled)
                    }
                } catch (e: Exception) {
                    currentOnCameraError(
                        e.message?.takeIf { it.isNotBlank() }
                            ?: "无法启动相机预览，请改用相册或粘贴二维码",
                    )
                }
            },
            mainExecutor,
        )

        onDispose {
            disposed = true
            boundCamera = null
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}
