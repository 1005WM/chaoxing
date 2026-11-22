// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.screens.signers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.media.AndroidImageObfuscator
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxSecondaryButton
import com.caochaoxing.android.ui.components.CxWellBox
import com.caochaoxing.android.ui.screens.rememberAppPersistenceScope
import com.caochaoxing.android.ui.theme.CxPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private const val MAX_PICKED_IMAGE_BYTES = 16 * 1024 * 1024
private const val MAX_PREVIEW_DIMENSION = 1024

/**
 * Photo check-in payload variant matching `IOSPhotoSubmission`.
 */
sealed interface PhotoSubmission {
    data object ProfileAvatar : PhotoSubmission

    class ImageBytes(val data: ByteArray) : PhotoSubmission {
        override fun equals(other: Any?): Boolean =
            this === other || (other is ImageBytes && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()
    }

    data class ObjectId(val objectId: String?) : PhotoSubmission
}

/**
 * Photo check-in panel supporting VisualMedia album selection, bounded <= 1024px preview decoding,
 * optional per-account JPEG pixel perturbation toggle, profile avatar fallback, and optional
 * photo-less submission when [requiresPhoto] is false.
 */
@Composable
fun PhotoSignPanel(
    appState: AndroidAppState,
    requiresPhoto: Boolean = true,
    isSigning: Boolean,
    enabled: Boolean = true,
    onSubmit: (PhotoSubmission) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appScope = rememberAppPersistenceScope()

    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isLoadingImage by remember { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var imageRequestId by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            loadJob?.cancel()
            loadJob = null
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loadJob?.cancel()
        val requestId = ++imageRequestId
        isLoadingImage = true
        previewBitmap = null
        imageBytes = null

        loadJob = scope.launch {
            try {
                val (rawBytes, decodedPreview) = loadBoundedImageAndPreview(context, uri)
                if (imageRequestId != requestId) return@launch
                if (rawBytes == null || decodedPreview == null) {
                    appState.setErrorMessage("无法读取所选照片，请重新选择")
                    return@launch
                }
                imageBytes = rawBytes
                previewBitmap = decodedPreview
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (imageRequestId == requestId) {
                    appState.setErrorMessage(e.message?.takeIf { it.isNotBlank() } ?: "无法读取所选照片，请重新选择")
                }
            } finally {
                if (imageRequestId == requestId) {
                    isLoadingImage = false
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val currentPreview = previewBitmap
        if (currentPreview != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, CxPalette.border, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = currentPreview,
                    contentDescription = "已选择的签到照片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                )
            }
        } else {
            CxWellBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(136.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoadingImage) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = CxPalette.accent,
                            )
                            Text(
                                text = "正在读取照片…",
                                style = MaterialTheme.typography.bodySmall,
                                color = CxPalette.inkSecondary,
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddPhotoAlternate,
                                contentDescription = null,
                                tint = CxPalette.inkTertiary,
                                modifier = Modifier.size(30.dp),
                            )
                            Text(
                                text = "尚未选择现场照片",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = CxPalette.inkSecondary,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .toggleable(
                    value = uiState.enableImageObfuscation,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { checked ->
                        appScope.launch { appState.setImageObfuscationEnabled(checked) }
                    },
                )
                .padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "图片像素防重微扰（多账号独立生成哈希）",
                style = MaterialTheme.typography.bodySmall,
                color = CxPalette.ink,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = uiState.enableImageObfuscation,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CxPalette.onAccent,
                    checkedTrackColor = CxPalette.accent,
                    uncheckedThumbColor = CxPalette.inkSecondary,
                    uncheckedTrackColor = CxPalette.surfaceSecondary,
                    uncheckedBorderColor = CxPalette.border,
                ),
            )
        }

        CxSecondaryButton(
            onClick = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            enabled = enabled && !isSigning && !isLoadingImage,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("从系统相册选取图片")
        }

        CxPrimaryButton(
            onClick = {
                val bytes = imageBytes ?: return@CxPrimaryButton
                onSubmit(PhotoSubmission.ImageBytes(bytes))
            },
            enabled = enabled && !isSigning && !isLoadingImage && imageBytes != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isLoadingImage) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = CxPalette.onAccent,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Upload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "上传图片并签到",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CxSecondaryButton(
                onClick = { onSubmit(PhotoSubmission.ProfileAvatar) },
                enabled = enabled && !isSigning && !isLoadingImage,
                compact = true,
                modifier = Modifier.weight(1f),
            ) {
                Text("使用底库头像")
            }

            if (!requiresPhoto) {
                CxSecondaryButton(
                    onClick = { onSubmit(PhotoSubmission.ObjectId(null)) },
                    enabled = enabled && !isSigning && !isLoadingImage,
                    compact = true,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("无图片直接签到")
                }
            }
        }
    }
}

private suspend fun loadBoundedImageAndPreview(
    context: Context,
    uri: Uri,
): Pair<ByteArray?, ImageBitmap?> = withContext(Dispatchers.IO) {
    val rawBytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_PICKED_IMAGE_BYTES) {
                error("所选图片超过 16 MB，请压缩或更换图片")
            }
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    } ?: return@withContext null to null

    if (rawBytes.isEmpty()) return@withContext null to null

    val previewBitmap = decodeBoundedPreviewBitmap(rawBytes) ?: return@withContext null to null
    rawBytes to previewBitmap.asImageBitmap()
}

private fun decodeBoundedPreviewBitmap(rawBytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = AndroidImageObfuscator.calculateInSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxDimension = MAX_PREVIEW_DIMENSION,
        )
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) ?: return null
    val orientation = try {
        ByteArrayInputStream(rawBytes).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                setRotate(180f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(270f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            else -> Unit
        }
    }
    if (matrix.isIdentity) {
        return decoded
    }

    return try {
        val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (oriented !== decoded && !decoded.isRecycled) {
            decoded.recycle()
        }
        oriented
    } catch (_: OutOfMemoryError) {
        decoded
    }
}
