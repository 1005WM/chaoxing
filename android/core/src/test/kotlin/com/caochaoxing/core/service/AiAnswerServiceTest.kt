package com.caochaoxing.core.service

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.*

class AiAnswerServiceTest {
    @Test
    fun mapsJudgmentWordsToTheActualOptionOrder() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"{\"answer\":\"正确\",\"explanation\":\"完整陈述成立\"}"}}]}""".toResponseBody()).build()
        }.build()
        val answer = AiAnswerService(http).answer("https://model.example/v1", "test", "vision",
            AiQuestion("1", "判断", "下列陈述是否成立", listOf("错误", "正确")))
        assertEquals("B", answer.answer)
    }

    @Test
    fun sendsImagesAsMultimodalContentWithOptionLabels() = runBlocking {
        val image = "data:image/png;base64,aGVsbG8="
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            val payload = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals("vision-model", payload["model"]!!.jsonPrimitive.content)
            val parts = payload["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
            assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertFalse(parts[0].jsonObject["text"]!!.jsonPrimitive.content.contains(image))
            assertEquals("题干图片 1", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("image_url", parts[2].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(image, parts[2].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"{\"answer\":\"B\",\"explanation\":\"根据题图\"}"}}]}""".toResponseBody()).build()
        }.build()
        val answer = AiAnswerService(http).answer("https://model.example/v1", "test", "vision-model",
            AiQuestion("1", "单选", "看图选择", listOf("A 图", "B 图"), true, listOf(AiQuestionImage("题干图片 1", image))))
        assertEquals("B", answer.answer)
    }

    @Test
    fun missingImageAndUnresolvedPrivateUrlsFailBeforeModelRequest() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { error("No request should be made") }.build()
        val service = AiAnswerService(http)
        val missing = runCatching { service.answer("https://model.example/v1", "key", "vision", AiQuestion("1", "文字题", "图片题", hasImage = true)) }
        assertTrue(missing.isFailure)
        val remote = runCatching { service.answer("https://model.example/v1", "key", "vision", AiQuestion("1", "文字题", "图片题", images = listOf(AiQuestionImage("题图", "https://private.chaoxing.com/image")))) }
        assertTrue(remote.isFailure)
        val partial = runCatching { service.answer("https://model.example/v1", "key", "vision", AiQuestion("1", "单选", "题干", readError = "题干截图不完整")) }
        assertTrue(partial.isFailure)
    }

    @Test
    fun readsModelsAndKeepsAuthorizationOnConfiguredHost() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://model.example/v1/models", chain.request().url.toString())
            assertEquals("Bearer test-key", chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[{"id":"model-b"},{"id":"model-a"},{"id":"model-a"}]}""".toResponseBody()).build()
        }.build()
        assertEquals(listOf("model-a", "model-b"), AiAnswerService(http).models("https://model.example/v1/", "test-key"))
    }

    @Test
    fun validatesModelAnswersAgainstRealOptions() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"{\"answer\":\"Z\",\"explanation\":\"test\"}"}}]}""".toResponseBody()).build()
        }.build()
        val result = AiAnswerService(http).answer("https://model.example/v1", "test", "model", AiQuestion("1", "单选", "先进先出", listOf("栈", "队列")))
        assertTrue(result.answer.isBlank())
        assertTrue(result.explanation.contains("\"answer\":\"Z\""))
        assertTrue(result.notice.isNotBlank())
    }

    private val question = AiQuestion("1", "单选", "选择正确答案", listOf("A项", "B项", "C项"))

    private fun modelReply(content: JsonElement, finish: String = "stop") = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                put("finish_reason", finish)
                putJsonObject("message") { put("content", content) }
            }
        }
    }

    @Test
    fun recoversFencedJsonWithUnescapedLatexWithoutChangingFormula() {
        val reply = """附注 {提示}
            |```json
            |{"answer":"B","explanation":"公式为 \(\frac{\theta}{x_i}\)，条件为 \(x_i\ge 1\)。"}
            |```
            |附注 {结束}
        """.trimMargin()
        val answer = readAiReply(modelReply(JsonPrimitive(reply)), question)
        assertEquals("B", answer.answer)
        assertEquals("公式为 \\(\\frac{\\theta}{x_i}\\)，条件为 \\(x_i\\ge 1\\)。", answer.explanation)
    }

    @Test
    fun readsExplicitPlainTextAnswerAndKeepsExplanation() {
        val text = "**答案：B**\n密度的乘积就是似然函数。"
        val parts = buildJsonArray { addJsonObject { put("type", "text"); put("text", text) } }
        val answer = readAiReply(modelReply(parts), question)
        assertEquals("B", answer.answer)
        assertTrue(answer.explanation.contains("密度的乘积就是似然函数。"))
    }

    @Test
    fun showsAmbiguousAndTruncatedRepliesWithoutGuessingAnOption() {
        val uncertain = "我在选项 A 和 B 之间不确定。"
        val rawAnswer = readAiReply(modelReply(JsonPrimitive(uncertain)), question)
        assertEquals("", rawAnswer.answer)
        assertEquals(uncertain, rawAnswer.explanation)
        val partial = """{"answer":"B","explanation":"密度乘积"""
        val partialAnswer = readAiReply(modelReply(JsonPrimitive(partial), "length"), question)
        assertEquals("B", partialAnswer.answer)
        assertEquals(partial, partialAnswer.explanation)
        assertTrue(partialAnswer.notice.contains("截断"))
    }

    @Test
    fun normalizesEndpointsAndRejectsCredentialsInUrl() {
        val service = AiAnswerService()
        assertEquals("https://model.example/v1/models", service.endpoint("https://model.example/v1/chat/completions", "models"))
        assertThrows(IllegalArgumentException::class.java) { service.endpoint("http://model.example/v1", "models") }
        assertThrows(IllegalArgumentException::class.java) { service.endpoint("https://user:password@model.example/v1", "models") }
    }
}
