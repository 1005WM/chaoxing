package com.caochaoxing.android.state

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.caochaoxing.android.ui.screens.injectChaoxingCookies
import com.caochaoxing.android.ui.screens.isAllowedAssessmentUrl
import com.caochaoxing.core.service.AiQuestion
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/** Cross-origin frame documents are opened in an isolated viewer with the current account. */
internal class AiQuestionPageReader(
    private val context: Context,
    private val appState: AndroidAppState,
    private val decode: (String) -> List<AiQuestion>,
    private val createWebView: () -> WebView = { WebView(context) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fromView(view: WebView): List<AiQuestion> = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(35_000) {
            val visited = mutableSetOf<String>()
            view.url?.let { visited += it }
            val questions = collect(view, visited)
            verifyCount(view, questions)
            questions
        }?.takeIf { it.isNotEmpty() } ?: error("未读取到题干和选项。请先进入做题页面并等页面加载完成，再重新读取")
    }

    suspend fun fromUrl(url: String): List<AiQuestion> = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(35_000) { open(url, mutableSetOf(url)) }
            ?.takeIf { it.isNotEmpty() } ?: error("未读取到章节题目，请先完成前置学习或打开做题页面")
    }

    private suspend fun collect(view: WebView, visited: MutableSet<String>): List<AiQuestion> {
        var questions = decode(evaluate(view, EXTRACT_AI_QUESTIONS))
        var frames = frames(view)
        // Desktop question pages populate their content after the initial page load.
        if (questions.isEmpty() && frames.isEmpty()) {
            for (attempt in 0 until 8) {
                delay(500)
                questions = decode(evaluate(view, EXTRACT_AI_QUESTIONS))
                frames = frames(view)
                if (questions.isNotEmpty() || frames.isNotEmpty()) break
            }
        }
        val all = questions.toMutableList()
        for (url in frames) {
            if (visited.size >= 8 || !visited.add(url) || !isAllowedAssessmentUrl(url)) continue
            all += open(url, visited, view.settings.userAgentString)
        }
        return all.mapIndexed { index, question ->
            question.copy(id = index.toString())
        }
    }

    private suspend fun verifyCount(view: WebView, questions: List<AiQuestion>) {
        val count = evaluate(view, "(function(){var text=document.body?document.body.innerText:'';var match=text.match(/题量\\s*[:：]\\s*(\\d+)|共\\s*(\\d+)\\s*题/);return match?Number(match[1]||match[2]):0;})()")
            .toIntOrNull() ?: 0
        require(count == 0 || count == questions.size) { "页面显示 $count 道题，当前识别到 ${questions.size} 道。请等题目加载完成后重新读取" }
    }

    private suspend fun frames(view: WebView): List<String> {
        val encoded = json.decodeFromString<String>(evaluate(view, INACCESSIBLE_QUESTION_FRAMES))
        return json.decodeFromString<List<String>>(encoded).filter(::isAllowedAssessmentUrl)
    }

    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun open(url: String, visited: MutableSet<String>, userAgent: String? = null): List<AiQuestion> {
        require(isAllowedAssessmentUrl(url)) { "题目框架地址无效" }
        val loaded = CompletableDeferred<Unit>()
        val view = createWebView().apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            userAgent?.let { settings.userAgentString = it }
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                    !isAllowedAssessmentUrl(request.url.toString())
                override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
            }
        }
        try {
            injectChaoxingCookies(appState, url)
            view.loadUrl(url)
            loaded.await()
            val questions = collect(view, visited)
            verifyCount(view, questions)
            return questions
        } finally { view.stopLoading(); view.destroy() }
    }
}

internal const val INACCESSIBLE_QUESTION_FRAMES = """
(function(){
 var frames=[];
 function walk(doc){
   Array.from(doc.querySelectorAll('iframe')).forEach(function(frame){
     if(frame.closest('.edui-editor,.subEditor,[contenteditable="true"]')) return;
     try {
       var child=frame.contentDocument;
       if(child && child.body) { walk(child); return; }
     } catch(e) {}
     var src=frame.getAttribute('src');
     if(src) { try { frames.push(new URL(src,doc.baseURI).href); } catch(e) {} }
   });
 }
 walk(document); return JSON.stringify(frames);
})();
"""
