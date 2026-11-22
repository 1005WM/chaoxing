// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens.signers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.CaptchaChallengeRequest
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxSecondaryButton
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.components.CxWellBox
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.crypto.ChaoxingCaptchaSolver
import com.caochaoxing.core.crypto.PixelImage
import com.caochaoxing.core.model.ChaoxingCaptchaData
import com.caochaoxing.core.service.ChaoxingCaptchaService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Full-screen modal slider captcha verification dialog matching `IOSCaptchaSheetView`, supporting
 * manual horizontal dragging as well as automatic on-device edge correlation via [ChaoxingCaptchaSolver].
 */
@Composable
fun CaptchaSheetDialog(
    appState: AndroidAppState,
    challenge: CaptchaChallengeRequest,
    onDismiss: () -> Unit,
) {
    key(challenge.id) {
        var isVerifyingExternal by remember { mutableStateOf(false) }
        var dismissHandler by remember { mutableStateOf<() -> Unit>({}) }

        Dialog(
            onDismissRequest = {
                if (!isVerifyingExternal) {
                    dismissHandler()
                }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !isVerifyingExternal,
                dismissOnClickOutside = false,
            ),
        ) {
            CaptchaSheetContent(
                appState = appState,
                challenge = challenge,
                onVerifyingStateChange = { verifying -> isVerifyingExternal = verifying },
                registerDismissHandler = { handler -> dismissHandler = handler },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun CaptchaSheetContent(
    appState: AndroidAppState,
    challenge: CaptchaChallengeRequest,
    onVerifyingStateChange: (Boolean) -> Unit,
    registerDismissHandler: (() -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var captcha by remember(challenge.id) { mutableStateOf<ChaoxingCaptchaData?>(null) }
    var shadeBitmap by remember(challenge.id) { mutableStateOf<Bitmap?>(null) }
    var cutoutBitmap by remember(challenge.id) { mutableStateOf<Bitmap?>(null) }
    var shadeImageBitmap by remember(challenge.id) { mutableStateOf<ImageBitmap?>(null) }
    var cutoutImageBitmap by remember(challenge.id) { mutableStateOf<ImageBitmap?>(null) }

    var dragOffsetPx by remember(challenge.id) { mutableFloatStateOf(0f) }
    var isLoading by remember(challenge.id) { mutableStateOf(false) }
    var isVerifying by remember(challenge.id) { mutableStateOf(false) }
    var isSolving by remember(challenge.id) { mutableStateOf(false) }
    var errorMessage by remember(challenge.id) { mutableStateOf<String?>(null) }
    var hasFinished by remember(challenge.id) { mutableStateOf(false) }
    var didAutoAttempt by remember(challenge.id) { mutableStateOf(false) }
    var requestVersion by remember(challenge.id) { mutableLongStateOf(0L) }
    var activeJob by remember(challenge.id) { mutableStateOf<Job?>(null) }

    var latestDisplayWidthPx by remember { mutableFloatStateOf(0f) }
    var latestDisplayScale by remember { mutableFloatStateOf(1f) }
    var latestMaxSliderOffsetPx by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(isVerifying) {
        onVerifyingStateChange(isVerifying)
    }

    fun cancelAndDismiss() {
        if (hasFinished) return
        hasFinished = true
        requestVersion += 1L
        activeJob?.cancel()
        activeJob = null
        appState.cancelCaptcha(
            id = challenge.id,
            cause = CancellationException("用户取消了滑块验证码"),
        )
        onDismiss()
    }

    LaunchedEffect(challenge.id) {
        registerDismissHandler { cancelAndDismiss() }
    }

    DisposableEffect(challenge.id) {
        appState.captchaSheetDidAppear(challenge.id)
        onDispose {
            requestVersion += 1L
            activeJob?.cancel()
            activeJob = null
            if (!hasFinished) {
                hasFinished = true
                appState.cancelCaptcha(
                    id = challenge.id,
                    cause = CancellationException("用户关闭了验证码弹窗"),
                )
            }
        }
    }

    suspend fun fetchAndDecodeCaptcha(): Boolean {
        if (hasFinished) return false
        val version = ++requestVersion
        isLoading = true
        errorMessage = null
        dragOffsetPx = 0f

        return try {
            val result = ChaoxingCaptchaService.fetchCaptcha(
                client = challenge.client,
                refererUrl = challenge.refererUrl,
            )
            val (decodedShade, decodedCutout) = withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
                val shade = BitmapFactory.decodeByteArray(
                    result.shadeImageBytes,
                    0,
                    result.shadeImageBytes.size,
                    opts,
                )
                val cutout = BitmapFactory.decodeByteArray(
                    result.cutoutImageBytes,
                    0,
                    result.cutoutImageBytes.size,
                    opts,
                )
                shade to cutout
            }
            if (requestVersion != version || hasFinished) return false
            if (decodedShade == null || decodedCutout == null) {
                errorMessage = "验证码图片解码失败，请点击换一张"
                return false
            }

            captcha = result.captcha
            shadeBitmap = decodedShade
            cutoutBitmap = decodedCutout
            shadeImageBitmap = decodedShade.asImageBitmap()
            cutoutImageBitmap = decodedCutout.asImageBitmap()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestVersion == version && !hasFinished) {
                errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "获取滑块验证码失败"
            }
            false
        } finally {
            if (requestVersion == version) {
                isLoading = false
            }
        }
    }

    suspend fun verifyCurrentOffset(overrideXPosition: Int?) {
        val currentCaptcha = captcha ?: return
        if (isVerifying || hasFinished) return
        val version = requestVersion
        isVerifying = true
        errorMessage = null

        val xPosition = overrideXPosition ?: (dragOffsetPx / maxOf(latestDisplayScale, 0.01f)).roundToInt()
        try {
            val validate = ChaoxingCaptchaService.verifyCaptcha(
                client = challenge.client,
                captcha = currentCaptcha,
                xPosition = xPosition,
                refererUrl = challenge.refererUrl,
            )
            if (requestVersion != version || hasFinished) return
            if (validate != null) {
                hasFinished = true
                isVerifying = false
                appState.completeCaptcha(challenge.id, validate)
            } else {
                isVerifying = false
                val reloaded = fetchAndDecodeCaptcha()
                if (reloaded && !hasFinished) {
                    errorMessage = "滑块位置不准确，已刷新图片，请重试"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestVersion == version && !hasFinished) {
                errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "验证码校验失败"
            }
        } finally {
            isVerifying = false
        }
    }

    suspend fun autoSolveAndVerify(silentOnFailure: Boolean) {
        if (hasFinished || isVerifying || isSolving || isLoading) return
        val bg = shadeBitmap ?: return
        val cut = cutoutBitmap ?: return
        val version = requestVersion
        var estimatedX: Int? = null
        isSolving = true
        try {
            estimatedX = withContext(Dispatchers.Default) {
                solveCaptchaXPosition(bg, cut)
            }
            if (requestVersion != version || hasFinished || estimatedX == null) {
                if (requestVersion == version && !hasFinished && estimatedX == null && !silentOnFailure) {
                    errorMessage = "自动识别未能定位缺口，请手动拖动"
                }
                return
            }
            val effectiveScale = if (latestDisplayWidthPx > 0f && bg.width > 0) {
                latestDisplayWidthPx / bg.width.toFloat()
            } else {
                latestDisplayScale
            }
            val targetPx = (estimatedX.toFloat() * effectiveScale)
                .coerceIn(0f, latestMaxSliderOffsetPx)
            Animatable(dragOffsetPx).animateTo(
                targetValue = targetPx,
                animationSpec = tween(durationMillis = 180),
            ) {
                dragOffsetPx = value
            }
        } finally {
            isSolving = false
        }
        if (requestVersion == version && !hasFinished && estimatedX != null) {
            verifyCurrentOffset(estimatedX)
        }
    }

    LaunchedEffect(challenge.id) {
        activeJob?.cancel()
        val job = scope.launch {
            val loaded = fetchAndDecodeCaptcha()
            if (loaded && !hasFinished && appState.uiState.value.autoSolveCaptcha && !didAutoAttempt) {
                didAutoAttempt = true
                autoSolveAndVerify(silentOnFailure = true)
            }
        }
        activeJob = job
    }

    val isBusy = isLoading || isVerifying || isSolving
    val currentIsBusy by rememberUpdatedState(isBusy)
    val currentVerifyCallback by rememberUpdatedState(newValue = {
        activeJob?.cancel()
        activeJob = scope.launch {
            verifyCurrentOffset(null)
        }
    })

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = CxPalette.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "滑块安全核验",
                        style = CxTypography.title,
                        color = CxPalette.ink,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxPalette.canvas,
                    titleContentColor = CxPalette.ink,
                ),
                navigationIcon = {
                    TextButton(onClick = { cancelAndDismiss() }) {
                        Text(
                            text = "取消",
                            color = CxPalette.accent,
                            fontWeight = FontWeight.Medium,
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "拖动下方滑块拼合缺口，或使用本地边缘算法自动定位",
                style = MaterialTheme.typography.bodySmall,
                color = CxPalette.inkSecondary,
                textAlign = TextAlign.Center,
            )

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val displayWidthDp = minOf(320.dp, maxWidth)
                val displayWidthPx = with(density) { displayWidthDp.toPx() }
                val thumbSizeDp = 40.dp
                val thumbMarginDp = 2.dp
                val trackHeightDp = 44.dp
                val maxSliderOffsetPx = with(density) {
                    maxOf(1f, (displayWidthDp - thumbSizeDp - thumbMarginDp * 2).toPx())
                }

                val currentShadeImg = shadeImageBitmap
                val currentShadeBmp = shadeBitmap
                val currentCutoutImg = cutoutImageBitmap
                val currentCutoutBmp = cutoutBitmap
                val computedScale = if (currentShadeBmp != null && currentShadeBmp.width > 0) {
                    displayWidthPx / currentShadeBmp.width.toFloat()
                } else {
                    latestDisplayScale
                }

                SideEffect {
                    latestDisplayWidthPx = displayWidthPx
                    latestMaxSliderOffsetPx = maxSliderOffsetPx
                    latestDisplayScale = computedScale
                }

                if (currentShadeImg != null && currentShadeBmp != null && currentShadeBmp.width > 0) {
                    val scaleFactor = computedScale
                    val displayHeightDp =
                        displayWidthDp * (currentShadeBmp.height.toFloat() / currentShadeBmp.width.toFloat())

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = displayWidthDp, height = displayHeightDp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, CxPalette.border, RoundedCornerShape(12.dp)),
                        ) {
                            Image(
                                bitmap = currentShadeImg,
                                contentDescription = "滑块背景图",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )

                            if (currentCutoutImg != null && currentCutoutBmp != null) {
                                val cutoutWidthDp = with(density) {
                                    (currentCutoutBmp.width.toFloat() * scaleFactor).toDp()
                                }
                                val cutoutHeightDp = with(density) {
                                    (currentCutoutBmp.height.toFloat() * scaleFactor).toDp()
                                }
                                Image(
                                    bitmap = currentCutoutImg,
                                    contentDescription = "滑块拼图块",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .offset { IntOffset(dragOffsetPx.roundToInt(), 0) }
                                        .size(width = cutoutWidthDp, height = cutoutHeightDp),
                                )
                            }

                            if (isBusy) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(CxPalette.surface.copy(alpha = 0.65f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(color = CxPalette.accent)
                                }
                            }
                        }

                        val currentMaxOffset by rememberUpdatedState(maxSliderOffsetPx)
                        val thumbOffsetPx = dragOffsetPx.coerceIn(0f, maxSliderOffsetPx)
                        val thumbStartPx = with(density) { thumbMarginDp.toPx() }
                        val fillWidthDp = with(density) {
                            maxOf(
                                trackHeightDp.toPx(),
                                minOf(displayWidthPx, thumbOffsetPx + trackHeightDp.toPx()),
                            ).toDp()
                        }

                        Box(
                            modifier = Modifier
                                .size(width = displayWidthDp, height = trackHeightDp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(CxPalette.surfaceSecondary)
                                .border(1.dp, CxPalette.border, RoundedCornerShape(22.dp)),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = "向右滑动完成核验",
                                style = CxTypography.mono(11.5.sp),
                                color = CxPalette.inkTertiary,
                                modifier = Modifier.align(Alignment.Center),
                            )

                            Box(
                                modifier = Modifier
                                    .size(width = fillWidthDp, height = trackHeightDp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(CxPalette.accent.copy(alpha = 0.22f)),
                            )

                            Surface(
                                modifier = Modifier
                                    .offset {
                                        IntOffset((thumbStartPx + thumbOffsetPx).roundToInt(), 0)
                                    }
                                    .size(thumbSizeDp)
                                    .shadow(4.dp, CircleShape)
                                    .pointerInput(challenge.id) {
                                        var dragging = false
                                        detectHorizontalDragGestures(
                                            onDragStart = {
                                                dragging = !currentIsBusy
                                            },
                                            onHorizontalDrag = { change, dragAmount ->
                                                if (!dragging || currentIsBusy) return@detectHorizontalDragGestures
                                                change.consume()
                                                dragOffsetPx = (dragOffsetPx + dragAmount)
                                                    .coerceIn(0f, currentMaxOffset)
                                            },
                                            onDragEnd = {
                                                if (!dragging) return@detectHorizontalDragGestures
                                                dragging = false
                                                if (!currentIsBusy) {
                                                    currentVerifyCallback()
                                                }
                                            },
                                            onDragCancel = {
                                                dragging = false
                                            },
                                        )
                                    },
                                shape = CircleShape,
                                color = CxPalette.accent,
                                border = BorderStroke(0.5.dp, CxPalette.accent),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = "拖动滑块",
                                        tint = CxPalette.onAccent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                } else if (isLoading) {
                    CxWellBox(
                        modifier = Modifier
                            .size(width = displayWidthDp, height = 160.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(color = CxPalette.accent)
                                Text(
                                    text = "正在获取拼图图像...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.inkSecondary,
                                )
                            }
                        }
                    }
                }
            }

            errorMessage?.let { msg ->
                CxBadge(
                    text = msg,
                    tone = CxTone.CINNABAR,
                    icon = Icons.Rounded.WarningAmber,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CxPrimaryButton(
                    onClick = {
                        activeJob?.cancel()
                        activeJob = scope.launch {
                            autoSolveAndVerify(silentOnFailure = false)
                        }
                    },
                    enabled = !isBusy && shadeBitmap != null && cutoutBitmap != null,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("自动识别缺口")
                }

                CxSecondaryButton(
                    onClick = {
                        activeJob?.cancel()
                        activeJob = scope.launch {
                            fetchAndDecodeCaptcha()
                        }
                    },
                    enabled = !isBusy,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("换一张")
                }
            }
        }
    }
}

private fun solveCaptchaXPosition(shade: Bitmap, cutout: Bitmap): Int? {
    val bgW = shade.width
    val bgH = shade.height
    val cutW = cutout.width
    val cutH = cutout.height
    if (bgW <= 0 || bgH <= 0 || cutW <= 0 || cutH <= 0) return null

    var scale = 1.0
    val maxDim = maxOf(bgW, bgH, cutW, cutH)
    if (maxDim > PixelImage.MAX_DIMENSION) {
        scale = minOf(scale, PixelImage.MAX_DIMENSION.toDouble() / maxDim.toDouble())
    }
    val bgPixelsCount = bgW.toLong() * bgH.toLong()
    if (bgPixelsCount > PixelImage.MAX_PIXELS) {
        scale = minOf(scale, sqrt(PixelImage.MAX_PIXELS.toDouble() / bgPixelsCount.toDouble()))
    }
    val cutPixelsCount = cutW.toLong() * cutH.toLong()
    if (cutPixelsCount > PixelImage.MAX_PIXELS) {
        scale = minOf(scale, sqrt(PixelImage.MAX_PIXELS.toDouble() / cutPixelsCount.toDouble()))
    }

    var workingShade = shade
    var workingCutout = cutout
    var scaledShade: Bitmap? = null
    var scaledCutout: Bitmap? = null

    return try {
        if (scale < 1.0) {
            var targetBgW = maxOf(1, (bgW * scale).toInt())
            var targetBgH = maxOf(1, (bgH * scale).toInt())
            while (targetBgW > PixelImage.MAX_DIMENSION ||
                targetBgH > PixelImage.MAX_DIMENSION ||
                targetBgW.toLong() * targetBgH.toLong() > PixelImage.MAX_PIXELS
            ) {
                scale *= 0.98
                targetBgW = maxOf(1, (bgW * scale).toInt())
                targetBgH = maxOf(1, (bgH * scale).toInt())
            }
            val targetCutW = maxOf(1, (cutW * scale).toInt())
            val targetCutH = maxOf(1, (cutH * scale).toInt())
            scaledShade = Bitmap.createScaledBitmap(shade, targetBgW, targetBgH, true)
            scaledCutout = Bitmap.createScaledBitmap(cutout, targetCutW, targetCutH, true)
            workingShade = scaledShade
            workingCutout = scaledCutout
        }

        val shadePixel = workingShade.toPixelImage()
        val cutoutPixel = workingCutout.toPixelImage()
        val estimatedScaledX = ChaoxingCaptchaSolver.estimateXPosition(shadePixel, cutoutPixel)
            ?: return null

        val effectiveScaleX = workingShade.width.toDouble() / bgW.toDouble()
        (estimatedScaledX.toDouble() / effectiveScaleX).roundToInt()
    } finally {
        if (scaledShade != null && scaledShade !== shade && !scaledShade.isRecycled) {
            scaledShade.recycle()
        }
        if (scaledCutout != null && scaledCutout !== cutout && !scaledCutout.isRecycled) {
            scaledCutout.recycle()
        }
    }
}

private fun Bitmap.toPixelImage(): PixelImage {
    val w = width
    val h = height
    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)
    return PixelImage(width = w, height = h, argbPixels = pixels)
}
