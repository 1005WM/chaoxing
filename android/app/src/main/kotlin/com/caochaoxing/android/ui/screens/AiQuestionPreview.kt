package com.caochaoxing.android.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.core.service.AiQuestion
import com.caochaoxing.core.service.AiQuestionImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AiQuestionPreview(questions: List<AiQuestion>, onDismiss: () -> Unit) {
    AlertDialog(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f), properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss, title = { Text("识别内容") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(questions) { index, question -> AiQuestionCard(question, index + 1) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

/** Display only: keep the original question and model request unchanged. */
internal fun questionCardStem(question: AiQuestion): String {
    val note = "题目内容以完整题目截图为准。"
    val original = question.text.removeSuffix("\n$note").trim()
    var stem = original
    for (index in question.options.indices.reversed()) {
        val option = question.options[index].trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        val suffix = Regex("(?:^|\\n)\\s*(?:${'A' + index}\\s*[.、．]?\\s*)?$option\\s*$").find(stem)
        if (suffix == null) { stem = original; break }
        stem = stem.substring(0, suffix.range.first).trimEnd()
    }
    val lines = stem.lines()
    val heading = Regex("^(?:\\d+[.、．]\\s*)?[（(【\\[]?(?:单选|多选|判断|填空|文字)题?[）)】\\]]?$")
    if (lines.size > 1 && heading.matches(lines.first().trim())) stem = lines.drop(1).joinToString("\n").trim()
    return stem.ifBlank { original }
}

private val imageMarker = Regex("\\[((?:题干图片|选项 [A-Z] 图片) \\d+)\\]")
private val optionImageLabel = Regex("^选项 ([A-Z]) 图片 \\d+$")
private const val FULL_QUESTION_IMAGE = "完整题目截图"

@Composable
private fun QuestionContent(text: String, images: List<AiQuestionImage>, onOpenImage: (AiQuestionImage) -> Unit) {
    val textStyle = MaterialTheme.typography.bodyLarge
    val remaining = images.associateByTo(linkedMapOf()) { it.label }
    var start = 0
    for (marker in imageMarker.findAll(text)) {
        val before = text.substring(start, marker.range.first).trim()
        if (before.isNotEmpty()) Text(before, color = CxPalette.ink, style = textStyle)
        remaining.remove(marker.groupValues[1])?.let { image ->
            key(image.label) { AiQuestionPicture(image) { onOpenImage(image) } }
        }
        start = marker.range.last + 1
    }
    val tail = text.substring(start).trim()
    if (tail.isNotEmpty()) Text(tail, color = CxPalette.ink, style = textStyle)

    for (image in remaining.values) {
        key(image.label) {
            if (image.label.startsWith(FULL_QUESTION_IMAGE)) {
                Text(image.label.substringBefore('（').replace(FULL_QUESTION_IMAGE, "整题截图"),
                    color = CxPalette.inkSecondary, style = MaterialTheme.typography.bodySmall)
            }
            AiQuestionPicture(image) { onOpenImage(image) }
        }
    }
}

@Composable
internal fun AiQuestionCard(question: AiQuestion, number: Int, footer: (@Composable ColumnScope.() -> Unit)? = null) {
    val imagesByOption = remember(question.images, question.options.size) {
        question.images.groupBy { image ->
            optionImageLabel.matchEntire(image.label)?.groupValues?.get(1)?.first()?.minus('A')
                ?.takeIf { it in question.options.indices }
        }
    }
    var openImage by remember(question.images) { mutableStateOf<AiQuestionImage?>(null) }
    openImage?.let { AiQuestionImageDialog(it) { openImage = null } }
    Surface(shape = RoundedCornerShape(16.dp), color = CxPalette.surface,
        border = BorderStroke(1.dp, CxPalette.border), modifier = Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.fillMaxWidth().background(CxPalette.surfaceSecondary).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("第 $number 题", color = CxPalette.accent, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (question.type.endsWith("题")) question.type else "${question.type}题", color = CxPalette.inkSecondary, style = MaterialTheme.typography.bodySmall)
                }
                val stem = remember(question) { questionCardStem(question) }
                val images = imagesByOption[null].orEmpty()
                QuestionContent(if (stem == "图片题" && images.isNotEmpty()) "" else stem, images) { openImage = it }
            }
            if (question.options.isNotEmpty() || question.readError.isNotBlank() || footer != null) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    question.options.forEachIndexed { index, option ->
                        if (index > 0) HorizontalDivider(color = CxPalette.border.copy(alpha = 0.5f))
                        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Surface(shape = RoundedCornerShape(8.dp), color = CxPalette.canvas) {
                                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Text("${'A' + index}", color = CxPalette.inkSecondary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
                            }
                            Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val images = imagesByOption[index].orEmpty()
                                QuestionContent(if (option == "图片选项" && images.isNotEmpty()) "" else option, images) { openImage = it }
                            }
                        }
                    }
                    if (question.readError.isNotBlank()) Text(question.readError, color = CxPalette.cinnabar, style = MaterialTheme.typography.bodyMedium)
                    footer?.invoke(this)
                }
            }
        }
    }
}

@Composable
private fun AiQuestionPicture(image: AiQuestionImage, onClick: () -> Unit) {
    val result by questionBitmap(image, 1024)
    val bitmap = result?.getOrNull()
    if (bitmap != null) {
        Image(bitmap, image.label, Modifier.fillMaxWidth().heightIn(max = 260.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "查看原图", onClick = onClick))
    } else if (result != null) {
        Text("图片暂时无法显示", color = CxPalette.inkSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun questionBitmap(image: AiQuestionImage, maxEdge: Int): State<Result<ImageBitmap>?> {
    val result = remember(image.source, maxEdge) { mutableStateOf<Result<ImageBitmap>?>(null) }
    LaunchedEffect(image.source, maxEdge) {
        result.value = withContext(Dispatchers.Default) {
            runCatching {
                require(image.source.startsWith("data:image/") && image.source.length <= 7_000_000)
                val bytes = Base64.decode(image.source.substringAfter(','), Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
                checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })).asImageBitmap()
            }
        }
    }
    return result
}

@Composable
private fun AiQuestionImageDialog(image: AiQuestionImage, onDismiss: () -> Unit) {
    val result by questionBitmap(image, 2048)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f), shape = RoundedCornerShape(16.dp)) {
            Column {
                var zoom by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                var viewport by remember { mutableStateOf(IntSize.Zero) }
                val bitmap = result?.getOrNull()
                val fit = if (bitmap == null) 0f else minOf(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
                val width = (bitmap?.width ?: 0) * fit
                val height = (bitmap?.height ?: 0) * fit
                fun panLimit(scale: Float) = Offset(
                    ((width * scale - viewport.width) / 2).coerceAtLeast(0f),
                    ((height * scale - viewport.height) / 2).coerceAtLeast(0f),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(image.label.substringBefore('（').replace(FULL_QUESTION_IMAGE, "整题截图"),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        zoom = if (zoom > 1f) 1f else 2f
                        offset = panLimit(zoom)
                    }, enabled = fit > 0f) { Text(if (zoom > 1f) "复位" else "放大") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().onSizeChanged { size ->
                    viewport = size
                    zoom = 1f
                    offset = Offset.Zero
                }
                    .background(Color.White), contentAlignment = Alignment.Center) {
                    if (bitmap != null && fit > 0f) {
                        val transform = rememberTransformableState { scale, motion, _ ->
                            zoom = (zoom * scale).coerceIn(1f, 5f)
                            val limit = panLimit(zoom)
                            offset = Offset((offset.x + motion.x).coerceIn(-limit.x, limit.x),
                                (offset.y + motion.y).coerceIn(-limit.y, limit.y))
                        }
                        Box(Modifier.fillMaxSize().transformable(transform), contentAlignment = Alignment.Center) {
                            val density = LocalDensity.current
                            Image(bitmap, image.label, with(density) { Modifier.size(width.toDp(), height.toDp()) }
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = offset.x
                                    translationY = offset.y
                                })
                        }
                    } else if (result == null || bitmap != null) {
                        CircularProgressIndicator()
                    } else {
                        Text("图片暂时无法显示", color = Color.DarkGray)
                    }
                }
            }
        }
    }
}
