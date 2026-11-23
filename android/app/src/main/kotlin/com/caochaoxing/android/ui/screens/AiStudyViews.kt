@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.caochaoxing.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.caochaoxing.android.CaochaoxingApp
import com.caochaoxing.android.state.*
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.core.service.AiQuestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun AiSettingsCard() {
    val controller = CaochaoxingApp.from(LocalContext.current).aiStudyController
    val config by controller.config.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var url by remember(config) { mutableStateOf(config.url) }
    var key by remember(config) { mutableStateOf(config.key) }
    var model by remember(config) { mutableStateOf(config.model) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(controller.loadError.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(url, key, expanded) {
        models = emptyList()
        loading = false
        if (!expanded || url.isBlank() || key.isBlank()) return@LaunchedEffect
        delay(800)
        loading = true
        message = ""
        try {
            models = controller.api.models(url, key)
            if (model.isBlank()) model = models.first()
            message = "已获取 ${models.size} 个模型"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "模型获取失败，可手动填写" }
        finally { loading = false }
    }
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = CxPalette.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起 AI 设置" else "AI 设置") }
            if (expanded) {
                Text("兼容 Chat Completions 接口")
                OutlinedTextField(url, { url = it }, label = { Text("接口地址 URL") }, placeholder = { Text("https://服务地址/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (models.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { menu = true }) { Text("选择模型") }
                        DropdownMenu(menu, { menu = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                            models.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { model = name; menu = false }) }
                        }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                Text("题干、选项和题图将发送到所填 AI 服务，Key 加密保存在本机。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    try {
                        controller.save(config.copy(url = url.trim(), key = key.trim(), model = model.trim()))
                        message = "已保存"
                    } catch (e: Exception) { message = e.message ?: "保存失败" }
                }, enabled = !loading && url.isNotBlank() && key.isNotBlank() && model.isNotBlank()) { Text("保存 AI 设置") }
            }
        }
    }
}

@Composable
internal fun AiDraftSheet(id: String, title: String, questions: List<AiQuestion>?, readError: String = "", readWarning: String = "", onDismiss: () -> Unit) {
    val controller = CaochaoxingApp.from(LocalContext.current).aiStudyController
    val drafts by controller.drafts.collectAsState()
    val draft = drafts[id]
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AI 作答", style = MaterialTheme.typography.titleLarge)
            Text(title, style = MaterialTheme.typography.titleMedium)
            AiSettingsCard()
            if (readError.isNotBlank()) Text(readError, color = CxPalette.cinnabar)
            if (readWarning.isNotBlank()) Text(readWarning, style = MaterialTheme.typography.bodyMedium)
            if (questions != null && questions.isNotEmpty() && readWarning.isBlank()) Text("已识别 ${questions.size} 道题", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.generate(id, title, questions) }, enabled = draft?.running != true && readError.isBlank()) { Text("生成答案") }
                if (draft?.running == true) TextButton(onClick = { controller.cancel(id) }) { Text("停止") }
            }
            draft?.let {
                if (it.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("${it.message}  ${it.answers.size} / ${it.questions.size}")
                it.questions.forEach { question ->
                    HorizontalDivider()
                    Text(question.text, style = MaterialTheme.typography.titleMedium)
                    if (question.images.isNotEmpty()) Text("本题包含 ${question.images.size} 张题图", style = MaterialTheme.typography.bodySmall)
                    question.options.forEachIndexed { index, option -> Text("${'A' + index}  $option") }
                    it.answers[question.id]?.let { answer ->
                        var edited by remember(id, question.id, answer) { mutableStateOf(answer.answer) }
                        OutlinedTextField(edited, { edited = it }, label = { Text("建议答案") }, modifier = Modifier.fillMaxWidth())
                        Text(answer.explanation)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(edited)) }) { Text("复制答案") }
                    }
                    it.errors[question.id]?.let { error -> Text(error, color = CxPalette.cinnabar) }
                    TextButton(onClick = { controller.generate(id, title, it.questions, retryId = question.id) }, enabled = !it.running) { Text("重试本题") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun AiFollowStudyCard() {
    val controller = CaochaoxingApp.from(LocalContext.current).aiStudyController
    val config by controller.config.collectAsState()
    val drafts by controller.drafts.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("随看课生成答案", Modifier.weight(1f))
            Switch(config.followStudy, { enabled ->
                try { controller.save(config.copy(followStudy = enabled)); if (!enabled) controller.stopFollowing() }
                catch (e: Exception) { message = e.message ?: "保存失败" }
            })
        }
        if (message.isNotBlank()) Text(message)
        AiSettingsCard()
        drafts.forEach { (id, draft) ->
            TextButton(onClick = { selected = id }) { Text("${draft.title}  ${draft.answers.size}/${draft.questions.size}  ${draft.message}") }
        }
    }
    selected?.let { id -> AiDraftSheet(id, drafts[id]?.title ?: "章节测验", null) { selected = null } }
}
