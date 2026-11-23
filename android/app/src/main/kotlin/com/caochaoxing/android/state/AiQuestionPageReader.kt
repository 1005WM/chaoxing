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

data class AiQuestionPage(val questions: List<AiQuestion>, val expectedCount: Int) {
    val warning: String get() = if (expectedCount > questions.size)
        "页面共 $expectedCount 道题，已识别 ${questions.size} 道，将解答已识别的题目。其余题目可加载后重新读取" else ""
}

/** Cross-origin frame documents are opened in an isolated viewer with the current account. */
internal class AiQuestionPageReader(
    private val context: Context,
    private val appState: AndroidAppState,
    private val decode: (String) -> List<AiQuestion>,
    private val createWebView: () -> WebView = { WebView(context) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fromView(view: WebView): AiQuestionPage = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(35_000) {
            val visited = mutableSetOf<String>()
            view.url?.let { visited += it }
            collect(view, visited)
        }?.takeIf { it.questions.isNotEmpty() } ?: error("未读取到题干和选项。请先进入做题页面并等页面加载完成，再重新读取")
    }

    suspend fun fromUrl(url: String): AiQuestionPage = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(35_000) { open(url, mutableSetOf(url)) }
            ?.takeIf { it.questions.isNotEmpty() } ?: error("未读取到章节题目，请先完成前置学习或打开做题页面")
    }

    private suspend fun collect(view: WebView, visited: MutableSet<String>, expectedHint: Int = 0): AiQuestionPage {
        var expected = expectedCount(view).takeIf { it > 0 } ?: expectedHint
        var questions = emptyList<AiQuestion>()
        var frameUrls = emptyList<String>()
        var stable = 0
        try {
            for (attempt in 0..8) {
                val current = decode(evaluate(view, EXTRACT_AI_QUESTIONS))
                stable = if (current.size == questions.size) stable + 1 else 0
                if (current.size >= questions.size) questions = current
                frameUrls = frames(view)
                if (expected == 0) expected = expectedCount(view)
                if ((expected > 0 && questions.size >= expected) || frameUrls.isNotEmpty()) break
                if (expected == 0 && questions.isNotEmpty() && stable >= 2) break
                if (attempt == 8) break
                evaluate(view, SCROLL_AI_QUESTIONS.replace("SCROLL_STEP", (attempt + 1).toString()))
                delay(400)
            }
        } finally {
            withContext(NonCancellable) { withTimeoutOrNull(1000) { evaluate(view, RESTORE_AI_QUESTION_SCROLL) } }
        }
        val all = questions.toMutableList()
        var inferredTotal = questions.size
        for (url in frameUrls) {
            if (visited.size >= 8 || !visited.add(url) || !isAllowedAssessmentUrl(url)) continue
            val hint = if (questions.isEmpty() && frameUrls.size == 1) expected else 0
            val child = try {
                withTimeoutOrNull(if (all.isEmpty()) 10_000L else 4_000L) {
                    open(url, visited, view.settings.userAgentString, hint)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            if (child == null) continue
            all += child.questions
            inferredTotal += maxOf(child.expectedCount, child.questions.size)
        }
        return AiQuestionPage(all.mapIndexed { index, question ->
            question.copy(id = index.toString())
        }, if (expected > 0) expected else inferredTotal)
    }

    private suspend fun expectedCount(view: WebView): Int =
        evaluate(view, "(function(){var text=document.body?document.body.innerText:'';var match=text.match(/题量\\s*[:：]\\s*(\\d+)|共\\s*(\\d+)\\s*题/);return match?Number(match[1]||match[2]):0;})()")
            .toIntOrNull() ?: 0

    private suspend fun frames(view: WebView): List<String> {
        val encoded = json.decodeFromString<String>(evaluate(view, INACCESSIBLE_QUESTION_FRAMES))
        return json.decodeFromString<List<String>>(encoded).filter(::isAllowedAssessmentUrl)
    }

    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun open(url: String, visited: MutableSet<String>, userAgent: String? = null, expectedHint: Int = 0): AiQuestionPage {
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
            return collect(view, visited, expectedHint)
        } finally { view.stopLoading(); view.destroy() }
    }
}

// Trigger lazy content in the document and its question scroll containers, then restore position.
internal const val SCROLL_AI_QUESTIONS = """
(function(){
 function walk(doc){
   var win=doc.defaultView;
   var saved=win.__cxAiQuestionScroll || (win.__cxAiQuestionScroll=[]);
   var elements=new Set([doc.scrollingElement]);
   doc.querySelectorAll('.TiMu,.questionLi,.singleQuesId,.question-item,.questionItem,.mark_name,.Zy_TItle').forEach(function(node){
     for(var e=node.parentElement;e;e=e.parentElement) elements.add(e);
   });
   elements.forEach(function(e){
     if(!e || e.scrollHeight<=e.clientHeight+1) return;
     if(!saved.some(function(p){return p.element===e;})) saved.push({element:e,top:e.scrollTop});
     e.scrollTop=SCROLL_STEP>=8?e.scrollHeight:Math.min(e.scrollHeight,SCROLL_STEP*Math.max(200,e.clientHeight*0.85));
     e.dispatchEvent(new Event('scroll')); if(e===doc.scrollingElement) win.dispatchEvent(new Event('scroll'));
   });
   doc.querySelectorAll('img[loading="lazy"]').forEach(function(image){image.loading='eager';});
   doc.querySelectorAll('iframe').forEach(function(frame){try{if(frame.contentDocument)walk(frame.contentDocument);}catch(e){}});
 }
 walk(document); return true;
})();
"""

internal const val RESTORE_AI_QUESTION_SCROLL = """
(function(){
 function walk(doc){
   var saved=doc.defaultView.__cxAiQuestionScroll || [];
   saved.forEach(function(p){if(p.element.isConnected)p.element.scrollTop=p.top;});
   delete doc.defaultView.__cxAiQuestionScroll;
   doc.querySelectorAll('iframe').forEach(function(frame){try{if(frame.contentDocument)walk(frame.contentDocument);}catch(e){}});
 }
 walk(document); return true;
})();
"""

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
