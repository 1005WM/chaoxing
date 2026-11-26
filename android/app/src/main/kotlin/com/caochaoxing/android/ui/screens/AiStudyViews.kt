@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.caochaoxing.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.caochaoxing.android.CaochaoxingApp
import com.caochaoxing.android.state.*
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.core.service.AiQuestion
import com.caochaoxing.core.service.AiAnswer
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
internal fun AiDraftSheet(id: String, title: String, questions: List<AiQuestion>?, readError: String = "", readWarning: String = "",
    onApply: ((Int, String) -> Unit)? = null, onApplyAll: ((List<Pair<Int, String>>, () -> Unit) -> Unit)? = null, onDismiss: () -> Unit) {
    val controller = CaochaoxingApp.from(LocalContext.current).aiStudyController
    val drafts by controller.drafts.collectAsState()
    val draft = drafts[id]
    val shownQuestions = questions ?: draft?.questions.orEmpty()
    val matchingDraft = draft?.takeIf { it.questions.isEmpty() || it.questions == shownQuestions }
    // 收集每题编辑后的答案，供“回填全部”使用；原始答案作为初始值。
    val editedAnswers = remember(id, shownQuestions) { mutableStateMapOf<Int, String>() }
    var applyAllRunning by remember(id) { mutableStateOf(false) }
    var showPreview by remember(id) { mutableStateOf(false) }
    if (showPreview) AiQuestionPreview(shownQuestions) { showPreview = false }
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "controls") {
                // 保留滚出屏幕的表单，包括尚未保存的 Key，且不写入保存状态。
                val pin = LocalPinnableContainer.current
                DisposableEffect(pin) {
                    val handle = pin?.pin()
                    onDispose { handle?.release() }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("AI 作答", style = MaterialTheme.typography.titleLarge)
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    AiSettingsCard()
                    if (readError.isNotBlank()) Text(readError, color = CxPalette.cinnabar)
                    if (readWarning.isNotBlank()) Text(readWarning, style = MaterialTheme.typography.bodyMedium)
                    if (questions != null && questions.isNotEmpty() && readWarning.isBlank()) Text("已识别 ${questions.size} 道题", style = MaterialTheme.typography.bodyMedium)
                    if (shownQuestions.isNotEmpty()) TextButton(onClick = { showPreview = true }) { Text("查看识别内容") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { controller.generate(id, title, questions) }, enabled = draft?.running != true && readError.isBlank()) { Text("生成答案") }
                        if (draft?.running == true) TextButton(onClick = { controller.cancel(id) }) { Text("停止") }
                    }
                    if (onApplyAll != null) {
                        Button(
                            onClick = {
                                val pairs = shownQuestions.mapIndexedNotNull { index, q ->
                                    val answer = editedAnswers[index] ?: matchingDraft?.answers?.get(q.id)?.answer?.takeIf { it.isNotBlank() }
                                    answer?.let { index to it }
                                }
                                if (pairs.isNotEmpty()) {
                                    applyAllRunning = true
                                    onApplyAll(pairs) { applyAllRunning = false }
                                }
                            },
                            enabled = !applyAllRunning && draft?.running != true && matchingDraft?.answers?.isNotEmpty() == true,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (applyAllRunning) "回填中…（每题间隔 1.5 秒）" else "回填全部（${matchingDraft?.answers?.size ?: 0} 题）") }
                    }
                    matchingDraft?.let {
                        if (it.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("${it.message}  ${it.answers.size} / ${it.questions.size}")
                    }
                }
            }
            itemsIndexed(shownQuestions, key = { _, question -> "question:${question.id}" }) { index, question ->
                val result = matchingDraft?.takeIf { it.questions.isNotEmpty() }
                AiQuestionCard(question, index + 1, footer = result?.let { current ->
                    {
                        val answer = current.answers[question.id]
                        val error = current.errors[question.id]
                        if (answer != null || error != null) HorizontalDivider(color = CxPalette.border)
                        if (answer != null) key(id, answer) {
                            AiAnswerContent(answer,
                                onCopy = { clipboard.setText(AnnotatedString(it)) },
                                onApply = onApply?.let { apply -> { text -> apply(index, text) } },
                                onEditedChange = { text -> editedAnswers[index] = text })
                        }
                        if (error != null) Text(error, color = CxPalette.cinnabar)
                        TextButton(onClick = { controller.generate(id, title, current.questions, retryId = question.id) }, enabled = !current.running) { Text("重试本题") }
                    }
                })
            }
        }
    }
}

@Composable
internal fun AiAnswerContent(answer: AiAnswer, onCopy: (String) -> Unit, onApply: ((String) -> Unit)? = null,
    onEditedChange: ((String) -> Unit)? = null) {
    if (answer.notice.isNotBlank()) Text(answer.notice, color = CxPalette.inkSecondary, style = MaterialTheme.typography.bodyMedium)
    if (answer.answer.isBlank()) {
        Text("模型原始回复", style = MaterialTheme.typography.titleMedium)
        SelectionContainer { Text(answer.explanation, color = CxPalette.ink, style = MaterialTheme.typography.bodyLarge) }
        TextButton(onClick = { onCopy(answer.explanation) }) { Text("复制回复") }
        return
    }
    var edited by rememberSaveable(answer.answer) { mutableStateOf(answer.answer) }
    LaunchedEffect(edited) { onEditedChange?.invoke(edited) }
    var appliedMessage by remember(answer.answer) { mutableStateOf("") }
    OutlinedTextField(edited, { edited = it }, label = { Text("建议答案") }, modifier = Modifier.fillMaxWidth())
    if (answer.explanation.isNotBlank()) AiMathExplanation(answer.explanation)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onCopy(edited) }) { Text("复制答案") }
        if (onApply != null) {
            Button(onClick = {
                onApply(edited)
                appliedMessage = "已提交回填，请核对页面"
            }) { Text("回填到题目") }
        }
    }
    if (appliedMessage.isNotBlank()) Text(appliedMessage, color = CxPalette.inkSecondary, style = MaterialTheme.typography.bodySmall)
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
    selected?.let { id -> AiDraftSheet(id, drafts[id]?.title ?: "章节测验", null, onDismiss = { selected = null }) }
}
