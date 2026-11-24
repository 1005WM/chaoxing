package com.caochaoxing.core.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val answerLine = Regex("""(?m)^[ \t*#]*[【\[]?(?:建议答案|正确答案|参考答案|答案|Answer)[】\]]?\**[ \t]*[:：][ \t]*(.+)$""", RegexOption.IGNORE_CASE)
private val conclusion = Regex("""(?:因此选|所以选|故选|应选|本题选)\s*((?:(?:选项)?\s*[A-Za-z][、,，\s]*)+)项?[。.!！* \t]*$""")
private val answerField = Regex(""""answer"\s*:\s*"([^"\r\n\\]{1,100})"""")
private val choiceToken = Regex("""[A-Za-z](?:[、,，\s]*[A-Za-z])*""")
private val ambiguousTexEscapes = Regex("""(?:beta|boldsymbol|begin|frac|nabla|neq|ne|nu|not|rho|right|rm|rangle|rightarrow|theta|times|tau|text[a-z]*|tfrac|tilde|to|under[a-z]*|uparrow|upsilon)\b""")

internal fun readAiReply(response: JsonElement, question: AiQuestion): AiAnswer {
    val choice = ((response as? JsonObject)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
    val message = choice?.get("message") as? JsonObject
    val finish = choice?.get("finish_reason").stringValue()
    val content = message?.get("content").replyText()?.takeIf(String::isNotBlank)
        ?: choice?.get("text").stringValue()?.takeIf(String::isNotBlank)
        ?: message?.get("refusal").stringValue()?.takeIf(String::isNotBlank)
        ?: error(if (finish == "length") "模型回复被截断，未返回可显示的内容，请重试本题" else "模型未返回可显示的答案")
    val raw = content.trim()
    val finishNotice = when (finish) {
        "length" -> "模型回复被截断，请核对已有内容。"
        "content_filter" -> "模型回复可能不完整，请核对已有内容。"
        else -> ""
    }
    val structured = objectsIn(raw).mapNotNull { candidate ->
        val repaired = repairMathEscapes(candidate)
        (runCatching { Json.parseToJsonElement(repaired) }.getOrNull() as? JsonObject)
            ?.takeIf { "answer" in it }
    }.take(2).toList().singleOrNull()
    val supplied = structured?.get("answer")
    val structuredAnswer = when (supplied) {
        is JsonArray -> supplied.mapNotNull { it.stringValue() }.takeIf { it.size == supplied.size }?.joinToString(",")
        else -> supplied.stringValue()
    }
    val explicit = if (structured == null) {
        val candidates = answerLine.findAll(raw).map { it.groupValues[1] }.toList()
        when {
            candidates.isNotEmpty() -> candidates.singleOrNull()
            else -> answerField.findAll(raw).map { it.groupValues[1] }.toList().singleOrNull()
                ?: conclusion.find(raw)?.groupValues?.get(1)
                ?: raw.takeIf { question.options.isNotEmpty() && it.length <= 50 }
        }
    } else null
    val selection = (structuredAnswer ?: explicit)?.let { normalizeAnswer(it, question) }
    // Keep the reply visible even when it doesn't contain an unambiguous answer.
    if (selection == null) return AiAnswer("", raw.forDisplay(),
        finishNotice.ifEmpty { "未能确认标准答案，已保留模型回复。" })
    val explanation = structured?.get("explanation").stringValue()
        ?: structured?.get("reason").stringValue()
        ?: if (structured == null && raw != selection) raw else ""
    return AiAnswer(selection, explanation.forDisplay(), finishNotice)
}

private fun JsonElement?.stringValue() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonElement?.replyText(): String? = when (this) {
    is JsonArray -> mapNotNull { part -> part.stringValue() ?: (part as? JsonObject)?.get("text").stringValue() }.joinToString("\n")
    is JsonObject -> toString()
    else -> stringValue()
}

private fun normalizeAnswer(text: String, question: AiQuestion): String? {
    val answer = text.replace("**", "").trim().trim('`').trim()
        .removePrefix("选项").removePrefix("选择").trim()
        .trimEnd('。', '.', '！', '!', '项').trim()
    if (answer.isBlank()) return null
    if (question.options.isEmpty() || answer == "需人工确认") return answer
    if (question.type.contains("判断")) {
        judgmentMeaning(answer)?.let { meaning ->
            val index = question.options.indexOfFirst { judgmentMeaning(it) == meaning }
            if (index >= 0) return ('A' + index).toString()
        }
    }
    if (!choiceToken.matches(answer)) return null
    val letters = answer.uppercase().filter { it in 'A'..'Z' }
    if (letters.any { it !in 'A'..('A' + question.options.lastIndex) } || letters.toSet().size != letters.length) return null
    if ((question.type.contains("单选") || question.type.contains("判断")) && letters.length != 1) return null
    return letters.toCharArray().sorted().joinToString("")
}

private fun judgmentMeaning(text: String): Boolean? = when (text.trim().lowercase()) {
    "正确", "对", "是", "true", "t", "√", "✓" -> true
    "错误", "错", "否", "false", "f", "×", "✗" -> false
    else -> null
}

private fun objectsIn(text: String): Sequence<String> = sequence {
    var start = 0
    var depth = 0
    var quoted = false
    var escaped = false
    for ((index, char) in text.withIndex()) {
        if (quoted) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> quoted = false
            }
        } else when (char) {
            '{' -> { if (depth++ == 0) start = index }
            '}' -> if (depth > 0 && --depth == 0) yield(text.substring(start, index + 1))
            '"' -> if (depth > 0) quoted = true
        }
    }
}

private fun repairMathEscapes(text: String): String = buildString(text.length) {
    var quoted = false
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        if (char == '\\' && quoted && index < text.length) {
            val next = text[index]
            val validUnicode = next != 'u' || (index + 4 < text.length && (index + 1..index + 4).all { text[it].digitToIntOrNull(16) != null })
            val ambiguousTex = next in "bfnrtu" && ambiguousTexEscapes.matchAt(text, index) != null
            // Preserve doubled JSON escapes and recover bare LaTeX such as \(, \theta or \frac.
            if (next !in "\"\\/bfnrtu" || !validUnicode || ambiguousTex) append('\\')
            append(char).append(next)
            index++
        } else {
            if (char == '"') quoted = !quoted
            when {
                quoted && char == '\n' -> append("\\n")
                quoted && char == '\r' -> append("\\r")
                quoted && char == '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

private fun String.forDisplay() = if (length <= 12_000) this else take(8_000) + "\n…\n" + takeLast(4_000)
