package com.caochaoxing.android.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

private fun visibleQuestionText(text: String) = text.replace(Regex("\\[(?:题干图片|选项 [A-Z] 图片)\\s*\\d+\\]"), "").trim()

@Composable
internal fun AiQuestionCard(question: AiQuestion, number: Int, showImages: Boolean = true, footer: (@Composable ColumnScope.() -> Unit)? = null) {
    fun optionIndex(image: AiQuestionImage) = Regex("^选项 ([A-Z]) 图片").find(image.label)?.groupValues?.get(1)?.first()?.minus('A')?.takeIf { it in question.options.indices }
    val stemImages = question.images.filter { optionIndex(it) == null }
    Surface(shape = RoundedCornerShape(16.dp), color = CxPalette.surface,
        border = BorderStroke(1.dp, CxPalette.border), modifier = Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.fillMaxWidth().background(CxPalette.surfaceSecondary).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("第 $number 题", color = CxPalette.accent, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (question.type.endsWith("题")) question.type else "${question.type}题", color = CxPalette.inkSecondary, style = MaterialTheme.typography.bodySmall)
                }
                visibleQuestionText(questionCardStem(question)).takeIf { it.isNotBlank() }?.let { Text(it, color = CxPalette.ink, style = MaterialTheme.typography.bodyLarge) }
                if (!showImages && stemImages.isNotEmpty()) Text("题干含图", color = CxPalette.inkSecondary, style = MaterialTheme.typography.bodySmall)
                if (showImages) stemImages.forEach { image ->
                    if (image.label.startsWith("完整题目截图")) Text(image.label, color = CxPalette.inkSecondary, style = MaterialTheme.typography.bodySmall)
                    AiQuestionPicture(image)
                }
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
                                val images = question.images.filter { optionIndex(it) == index }
                                val text = visibleQuestionText(option)
                                if (text.isNotBlank() && (text != "图片选项" || images.isEmpty())) Text(text, color = CxPalette.ink, style = MaterialTheme.typography.bodyLarge)
                                if (showImages) images.forEach { AiQuestionPicture(it) }
                                else if (images.isNotEmpty() && (text.isBlank() || text == "图片选项")) Text("图片选项", color = CxPalette.inkSecondary, style = MaterialTheme.typography.bodyLarge)
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
private fun AiQuestionPicture(image: AiQuestionImage) {
    var bitmap by remember(image.source) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loaded by remember(image.source) { mutableStateOf(false) }
    LaunchedEffect(image.source) {
        bitmap = withContext(Dispatchers.Default) { runCatching {
            if (!image.source.startsWith("data:image/")) return@runCatching null
            val bytes = Base64.decode(image.source.substringAfter(','), Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() }
        loaded = true
    }
    bitmap?.let { Image(it.asImageBitmap(), image.label, Modifier.fillMaxWidth().heightIn(max = 420.dp)) }
        ?: if (loaded) Text("图片暂时无法显示", color = CxPalette.inkSecondary, style = MaterialTheme.typography.bodySmall) else Unit
}
