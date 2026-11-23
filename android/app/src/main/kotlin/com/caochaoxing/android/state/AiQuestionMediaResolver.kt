package com.caochaoxing.android.state

import android.webkit.WebView
import com.caochaoxing.core.service.AiQuestion
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

internal class AiQuestionMediaResolver {
    private val json = Json { ignoreUnknownKeys = true }
    private val screenshot = AiQuestionScreenshot()
    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { c ->
        view.evaluateJavascript(script) { if (c.isActive) c.resume(it) }
    }

    suspend fun resolve(view: WebView, raw: String, questions: List<AiQuestion>): List<AiQuestion> {
        val metadata = json.parseToJsonElement(json.decodeFromString<String>(raw)).jsonArray
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000
        var encodedBytes = 0L
        return try { questions.map { question ->
            val meta = metadata.first { it.jsonObject["id"]?.jsonPrimitive?.content == question.id }.jsonObject
            var fallback = meta["needsScreenshot"]?.jsonPrimitive?.booleanOrNull == true
            try {
                require(android.os.SystemClock.uptimeMillis() < deadline) { "图文读取超时，请分题打开后重试" }
                require(encodedBytes < 24_000_000) { "本页图片较多，请分题打开后重试" }
                val images = question.images.map { image ->
                    if (!image.source.startsWith("data:image/svg+xml;base64,")) image else {
                        val converted = rasterize(view, image.source)
                        if (converted == null) { fallback = true; image } else image.copy(source = converted)
                    }
                }
                if (fallback) warmImages(view, meta.getValue("captureKey").jsonPrimitive.content)
                val resolved = if (fallback) question.copy(text = question.text + "\n题目内容以完整题目截图为准。", hasImage = true, images = screenshot.capture(view, meta.getValue("captureKey").jsonPrimitive.content, question.text))
                else question.copy(images = images)
                encodedBytes += resolved.images.sumOf { it.source.length.toLong() }
                require(encodedBytes <= 24_000_000) { "本页图片较多，请分题打开后重试" }
                resolved
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { question.copy(readError = "题图未能完整读取：${e.message ?: "请重新打开题目后重试"}") }
        } } finally { withContext(NonCancellable) { withTimeoutOrNull(1000) { evaluate(view, "delete window.__cxAiQuestionNodes;") } } }
    }

    private suspend fun warmImages(view: WebView, key: String) {
        evaluate(view, """(function(key){
          window.__cxAiWarmResult=null;
          var entry=window.__cxAiQuestionNodes[key],doc=entry.doc,work=[];
          if(doc.fonts)work.push(doc.fonts.ready);
          function wait(image){return image.decode().catch(function(){throw new Error('image');});}
          Array.from(entry.node.querySelectorAll('img')).filter(function(image){return !image.closest(entry.excluded) && !entry.others.some(function(e){return e.contains(image);});}).forEach(function(image){
            var lazy=image.getAttribute('data-original') || image.getAttribute('data-src') || image.getAttribute('origin-src');
            image.loading='eager';if(lazy && image.currentSrc!==new URL(lazy,doc.baseURI).href)image.src=lazy;
            work.push(wait(image));
          });
          [entry.node].concat(Array.from(entry.node.querySelectorAll('*'))).forEach(function(e){
            if(e.closest(entry.excluded) || entry.others.some(function(other){return other.contains(e);}))return;
            ['', '::before', '::after'].forEach(function(p){
              var background=doc.defaultView.getComputedStyle(e,p || null).backgroundImage;
              var regex=/url\(["']?([^"')]+)["']?\)/g,match;
              while((match=regex.exec(background))!==null){var image=new doc.defaultView.Image();image.src=match[1];work.push(wait(image));}
            });
          });
          Promise.all(work).then(function(){window.__cxAiWarmResult='ready';},function(){window.__cxAiWarmResult='failed';});
        })(${json.encodeToString(key)});""")
        try {
            val success = withTimeoutOrNull(5000) {
                while (true) {
                    val result = evaluate(view, "window.__cxAiWarmResult")
                    if (result != "null") return@withTimeoutOrNull result == "\"ready\""
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            require(success == true) { "题图尚未完整加载，请稍后重新读取" }
        } finally { withContext(NonCancellable) { withTimeoutOrNull(1000) { evaluate(view, "delete window.__cxAiWarmResult;") } } }
    }

    private suspend fun rasterize(view: WebView, source: String): String? {
        evaluate(view, """(function(source){
          window.__cxAiSvgResult=null;
          var image=new Image();window.__cxAiSvgImage=image;image.onload=function(){try{
            if(!image.naturalWidth || !image.naturalHeight || image.naturalWidth*image.naturalHeight>16000000)throw new Error('size');
            var scale=Math.min(2,2048/Math.max(image.naturalWidth,image.naturalHeight)),canvas=document.createElement('canvas');
            canvas.width=Math.max(1,Math.ceil(image.naturalWidth*scale));canvas.height=Math.max(1,Math.ceil(image.naturalHeight*scale));
            var context=canvas.getContext('2d');context.fillStyle='white';context.fillRect(0,0,canvas.width,canvas.height);context.drawImage(image,0,0,canvas.width,canvas.height);
            window.__cxAiSvgResult=canvas.toDataURL('image/png');
          }catch(e){window.__cxAiSvgResult='failed';}};
          image.onerror=function(){window.__cxAiSvgResult='failed';};image.src=source;
        })(${json.encodeToString(source)});""")
        return try {
            withTimeoutOrNull(3000) {
                while (true) {
                    val value = evaluate(view, "window.__cxAiSvgResult")
                    if (value != "null") return@withTimeoutOrNull json.decodeFromString<String>(value).takeIf { it.startsWith("data:image/png;base64,") }
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } finally { withContext(NonCancellable) { withTimeoutOrNull(1000) {
            evaluate(view, "if(window.__cxAiSvgImage){window.__cxAiSvgImage.onload=window.__cxAiSvgImage.onerror=null;window.__cxAiSvgImage.src='';}delete window.__cxAiSvgImage;delete window.__cxAiSvgResult;")
        } } }
    }
}
