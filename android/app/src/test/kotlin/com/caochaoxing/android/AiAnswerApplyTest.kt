package com.caochaoxing.android

import com.caochaoxing.android.state.ApplyResult
import com.caochaoxing.android.state.BatchApplySummary
import com.caochaoxing.android.state.buildApplyAnswerScript
import com.caochaoxing.android.state.parseAiApplyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAnswerApplyTest {

    @Test
    fun scriptReplacesPlaceholdersAndJsonEscapesAnswer() {
        val answer = "A\n\"B\"\\C、正确"
        val script = buildApplyAnswerScript(3, answer)
        assertTrue(script.contains("var index=3;"))
        assertFalse(script.contains("__INDEX__"))
        assertFalse(script.contains("__ANSWER__"))
        // 答案应作为合法 JSON 字符串字面量注入，换行与引号被转义
        assertTrue(script.contains("var answer=\"A\\n\\\"B\\\"\\\\C、正确\";"))
        // 核心选择器与回填指令仍在
        assertTrue(script.contains("Zy_ulTop li"))
        assertTrue(script.contains("dispatchEvent"))
        assertTrue(script.contains("click()") || script.contains(".click()"))
    }

    @Test
    fun scriptDoesNotBreakOnSingleQuoteOrBraces() {
        val script = buildApplyAnswerScript(0, "it's {x} 答案")
        assertFalse(script.contains("__ANSWER__"))
        assertTrue(script.contains("it's {x} 答案"))
    }

    @Test
    fun parseResultReadsChoiceFillSkippedAndDetail() {
        val raw = "{\"ok\":1,\"choice\":2,\"fill\":0,\"skipped\":0,\"detail\":[\"选项A\",\"选项C\"]}"
        val result = parseAiApplyResult(raw)
        assertTrue(result.ok)
        assertEquals(2, result.choice)
        assertEquals(0, result.fill)
        assertEquals(listOf("选项A", "选项C"), result.detail)
        assertEquals("已勾选 2 个选项", result.message)
    }

    @Test
    fun parseResultReadsFillMessage() {
        val result = parseAiApplyResult("{\"ok\":1,\"choice\":0,\"fill\":3,\"skipped\":0,\"detail\":[\"填空3处\"]}")
        assertTrue(result.ok)
        assertEquals("已填入 3 处填空", result.message)
    }

    @Test
    fun parseResultHandlesDoubleEncodedJsValue() {
        // WebView evaluateJavascript 对 JSON 字符串返回值会再包一层引号
        val raw = "\"{\\\"ok\\\":1,\\\"choice\\\":1,\\\"fill\\\":0,\\\"skipped\\\":0,\\\"detail\\\":[]}\""
        val result = parseAiApplyResult(raw)
        assertTrue(result.ok)
        assertEquals(1, result.choice)
    }

    @Test
    fun parseResultFailsSafeOnGarbageAndFailureFlag() {
        assertFalse(parseAiApplyResult(null).ok)
        assertFalse(parseAiApplyResult("").ok)
        assertFalse(parseAiApplyResult("not json").ok)
        val failed = parseAiApplyResult("{\"ok\":0,\"choice\":0,\"fill\":0,\"skipped\":1,\"detail\":[\"题目序号9不存在\"]}")
        assertFalse(failed.ok)
        assertEquals("未能回填：题目序号9不存在", failed.message)
        assertEquals(ApplyResult(false), ApplyResult(false))
    }

    @Test
    fun batchSummaryMessages() {
        assertEquals("没有可回填的答案", BatchApplySummary(total = 0).message)
        assertEquals("已回填全部 5 题", BatchApplySummary(total = 5, ok = 5).message)
        val partial = BatchApplySummary(total = 6, ok = 4, skipped = 2, failedPositions = listOf(2, 5))
        assertTrue(partial.message.startsWith("已回填 4 题，跳过 2 题"))
        assertTrue(partial.message.contains("第2题"))
        assertTrue(partial.message.contains("第5题"))
    }
}
