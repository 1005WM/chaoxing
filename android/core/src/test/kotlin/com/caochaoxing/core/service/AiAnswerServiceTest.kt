package com.caochaoxing.core.service

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AiAnswerServiceTest {
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
        val result = runCatching {
            AiAnswerService(http).answer("https://model.example/v1", "test", "model", AiQuestion("1", "单选", "先进先出", listOf("栈", "队列")))
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun normalizesEndpointsAndRejectsCredentialsInUrl() {
        val service = AiAnswerService()
        assertEquals("https://model.example/v1/models", service.endpoint("https://model.example/v1/chat/completions", "models"))
        assertThrows(IllegalArgumentException::class.java) { service.endpoint("http://model.example/v1", "models") }
        assertThrows(IllegalArgumentException::class.java) { service.endpoint("https://user:password@model.example/v1", "models") }
    }
}
