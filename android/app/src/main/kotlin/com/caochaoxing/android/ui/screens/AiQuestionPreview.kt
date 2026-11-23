package com.caochaoxing.android.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.caochaoxing.core.service.AiQuestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AiQuestionPreview(questions: List<AiQuestion>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("识别内容") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            questions.forEachIndexed { index, question ->
                item("text-$index") {
                    Text(question.text)
                    question.options.forEachIndexed { n, option -> if (!question.text.contains(option)) Text("${'A' + n}  $option") }
                    if (question.readError.isNotBlank()) Text(question.readError, color = MaterialTheme.colorScheme.error)
                }
                question.images.forEachIndexed { n, image ->
                    item("image-$index-$n") {
                        Text(image.label, style = MaterialTheme.typography.bodySmall)
                        var bitmap by remember(image.source) { mutableStateOf<android.graphics.Bitmap?>(null) }
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
                        }
                        bitmap?.let { Image(it.asImageBitmap(), image.label, Modifier.fillMaxWidth().heightIn(max = 420.dp)) }
                    }
                }
                item("divider-$index") { HorizontalDivider() }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
