package com.caochaoxing.android.state

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.caochaoxing.core.service.AiQuestionImage
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.floor

/** Uses GPU pixels, including SVG and page fonts, in ordered question-only tiles. */
internal class AiQuestionScreenshot {
    private val json = Json
    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { c ->
        view.evaluateJavascript(script) { if (c.isActive) c.resume(it) }
    }
    private suspend fun objectValue(view: WebView, script: String): JsonObject =
        json.parseToJsonElement(json.decodeFromString<String>(evaluate(view, script))).jsonObject.also {
            require(it["error"] == null) { it["error"]?.jsonPrimitive?.content ?: "截图失败" }
        }

    suspend fun capture(view: WebView, key: String, text: String): List<AiQuestionImage> {
        val surface = QuestionCaptureWindow(view)
        val images = mutableListOf<AiQuestionImage>()
        try {
            val window = surface.open()
            val box = objectValue(view, START_QUESTION_CAPTURE.replace("CAPTURE_ARGS", json.encodeToString(listOf(key, text))))
            val height = box.getValue("height").jsonPrimitive.double
            var offset = 0.0
            while (offset < height - 0.5) {
                currentCoroutineContext().ensureActive()
                require(images.size < 6) { "题目超过 6 张截图，请分题打开后重试" }
                val tile = objectValue(view, "window.__cxQuestionCaptureTile($offset)")
                delay(180)
                withTimeoutOrNull(500) { suspendCancellableCoroutine<Unit> { c ->
                    view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { if (c.isActive) c.resume(Unit) }
                    })
                } }
                val ratio = view.width / tile.getValue("viewportWidth").jsonPrimitive.double
                val location = IntArray(2).also(view::getLocationInWindow)
                val rect = Rect(
                    location[0] + floor(tile.getValue("x").jsonPrimitive.double * ratio).toInt(),
                    location[1] + floor(tile.getValue("y").jsonPrimitive.double * ratio).toInt(),
                    location[0] + ceil((tile.getValue("x").jsonPrimitive.double + tile.getValue("width").jsonPrimitive.double) * ratio).toInt(),
                    location[1] + ceil((tile.getValue("y").jsonPrimitive.double + tile.getValue("height").jsonPrimitive.double) * ratio).toInt())
                require(rect.width() > 0 && rect.height() > 0 && rect.left >= location[0] - 1 && rect.top >= location[1] - 1 &&
                    rect.right <= location[0] + view.width + 1 && rect.bottom <= location[1] + view.height + 1) { "截图超出题目页面范围" }
                rect.intersect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                val scale = minOf(1.0, 2048.0 / maxOf(rect.width(), rect.height()))
                val bitmap = Bitmap.createBitmap(ceil(rect.width() * scale).toInt(), ceil(rect.height() * scale).toInt(), Bitmap.Config.ARGB_8888)
                var pendingCopy = false
                var recycleAfterCopy = false
                try {
                    val copied = CompletableDeferred<Int>()
                    PixelCopy.request(window, rect, bitmap, {
                        pendingCopy = false
                        copied.complete(it)
                        if (recycleAfterCopy) bitmap.recycle()
                    }, Handler(Looper.getMainLooper()))
                    pendingCopy = !copied.isCompleted
                    require(withTimeout(2500) { copied.await() } == PixelCopy.SUCCESS) { "无法读取网页画面，请重新打开题目" }
                    require((0 until bitmap.height step 4).any { y -> (0 until bitmap.width step 4).any { x -> bitmap.getPixel(x,y) != Color.WHITE } }) { "题目截图为空，请重新读取" }
                    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    require(bytes.size <= 5_000_000) { "题目截图过大，请分题读取" }
                    images += AiQuestionImage("完整题目截图 ${images.size + 1}（含题干和选项，按顺序阅读）",
                        "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
                } finally { if (pendingCopy) recycleAfterCopy = true else bitmap.recycle() }
                val advance = tile.getValue("advance").jsonPrimitive.double
                require(advance > 1) { "题目未能完整滚动，请重新打开题目" }
                offset += advance
            }
            return images
        } finally {
            withContext(NonCancellable) { withTimeoutOrNull(1000) {
                evaluate(view, "if(window.__cxQuestionCaptureRestore)window.__cxQuestionCaptureRestore();")
            } }
            surface.close()
        }
    }
}

/** A private display is allocated only for a detached chapter/frame viewer, without screen recording. */
private class QuestionCaptureWindow(private val view: WebView) {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var container: FrameLayout? = null

    suspend fun open(): Window {
        if (view.isAttachedToWindow) {
            var context: Context = view.context
            while (context is ContextWrapper && context !is Activity) context = context.baseContext
            return (context as? Activity)?.window ?: error("无法定位题目窗口")
        }
        val width = view.width.coerceAtLeast(1080)
        val height = view.height.coerceAtLeast(1600)
        val sink = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader = it }
        sink.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val manager = view.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val target = manager.createVirtualDisplay("Question", width, height, view.resources.displayMetrics.densityDpi,
            sink.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) ?: error("无法创建题目渲染窗口")
        display = target
        val panel = Presentation(view.context, target.display).also { presentation = it }
        panel.window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        val root = FrameLayout(panel.context).also { container = it }
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel.setContentView(root)
        panel.show()
        panel.window?.setLayout(width, height)
        delay(250)
        return checkNotNull(panel.window)
    }

    fun close() {
        container?.removeView(view)
        presentation?.dismiss()
        display?.release()
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
    }
}

private const val START_QUESTION_CAPTURE = """
(function(args){try{
 var entry=(window.__cxAiQuestionNodes || {})[args[0]];
 if(!entry || !entry.node.isConnected || entry.text!==args[1])throw new Error('题目内容已变化，请重新读取');
 var node=entry.node,doc=entry.doc,win=doc.defaultView,saved=[],styles=[],marker;
 function remember(e){if(e && !saved.some(function(p){return p.e===e;}))saved.push({e:e,x:e.scrollLeft,y:e.scrollTop});}
 for(var current=win;current;){
   remember(current.document.scrollingElement);
   for(var a=current===win?node:current.document.body;a;a=a.parentElement)remember(a);
   if(current===window)break;
   for(var a=current.frameElement;a;a=a.parentElement)remember(a);
   current=current.parent;
 }
 window.__cxQuestionCaptureRestore=function(){
   if(marker)marker.remove();
   styles.reverse().forEach(function(p){if(p.style===null)p.e.removeAttribute('style');else p.e.setAttribute('style',p.style);});
   saved.forEach(function(p){p.e.scrollLeft=p.x;p.e.scrollTop=p.y;});
   delete window.__cxQuestionCaptureTile;delete window.__cxQuestionCaptureRestore;
 };
 function hide(e){styles.push({e:e,style:e.getAttribute('style')});e.style.setProperty('visibility','hidden','important');}
 Array.from(node.querySelectorAll(entry.excluded)).concat(entry.others).forEach(hide);
 for(var local=win,branch=node;;){
   Array.from(local.document.querySelectorAll('*')).forEach(function(e){if(e!==branch && !branch.contains(e) && !e.contains(branch) && /^(fixed|sticky)$/.test(local.getComputedStyle(e).position))hide(e);});
   if(local===window)break;branch=local.frameElement;local=local.parent;
 }
 var rects=[];
 function visit(e){
   if(e.nodeType===3){if(e.textContent.trim()){var range=doc.createRange();range.selectNodeContents(e);rects.push.apply(rects,Array.from(range.getClientRects()));}return;}
   if(e.nodeType!==1 || (e!==node && (entry.others.indexOf(e)>=0 || e.matches(entry.excluded))))return;
   var style=win.getComputedStyle(e);if(style.display==='none' || style.visibility==='hidden')return;
   if(e.matches('img') && (!e.complete || !e.naturalWidth))throw new Error('题图尚未加载完成，请稍后重新读取');
   var pseudo=['::before','::after'].some(function(p){var s=win.getComputedStyle(e,p);return s.backgroundImage!=='none' || (s.content && !/^(none|normal|"")$/.test(s.content));});
   if(e.matches('img,svg,canvas,math') || style.backgroundImage!=='none' || pseudo){rects.push(e.getBoundingClientRect());return;}
   Array.from(e.childNodes).forEach(visit);
 }
 visit(node);rects=rects.filter(function(r){return r.width>0 && r.height>0;});
 if(!rects.length)throw new Error('未找到可截图的题干和选项');
 var n=node.getBoundingClientRect(),left=Math.min.apply(null,rects.map(function(r){return r.left;})),top=Math.min.apply(null,rects.map(function(r){return r.top;}));
 var width=Math.max.apply(null,rects.map(function(r){return r.right;}))-left,height=Math.max.apply(null,rects.map(function(r){return r.bottom;}))-top;
 var dx=left-n.left,dy=top-n.top;
 if(height>30000 || width>5000)throw new Error('题目截图范围过大，请分题打开');
 styles.push({e:node,style:node.getAttribute('style')});if(win.getComputedStyle(node).position==='static')node.style.position='relative';
 marker=doc.createElement('span');marker.style.cssText='position:absolute;width:1px;height:1px;opacity:0;pointer-events:none';node.appendChild(marker);
 window.__cxQuestionCaptureTile=function(offset){try{
   marker.style.left=dx+'px';marker.style.top=(dy+offset)+'px';marker.scrollIntoView({block:'start',inline:'nearest',behavior:'instant'});
   var r=node.getBoundingClientRect(),x=r.left+dx,y=r.top+dy+offset,w=width,h=height-offset,scale=1,local=win,element=node;
   function clip(l,t,right,bottom){var x2=Math.min(x+w,right),y2=Math.min(y+h,bottom);if(x<l-1 || y<t-1)throw new Error('题目部分内容被遮挡，请单独打开题目');w=x2-x;h=y2-y;}
   while(true){
     for(var p=element.parentElement;p;p=p.parentElement){var s=local.getComputedStyle(p);if(/auto|scroll|hidden|clip/.test(s.overflowX+' '+s.overflowY)){var b=p.getBoundingClientRect();clip(b.left,b.top,b.right,b.bottom);}}
     clip(0,0,local.innerWidth,local.innerHeight);
     if(local===window)break;
     var frame=local.frameElement,fr=frame.getBoundingClientRect(),factor=fr.width/frame.offsetWidth;
     x=fr.left+(x+frame.clientLeft)*factor;y=fr.top+(y+frame.clientTop)*factor;w*=factor;h*=factor;scale*=factor;local=local.parent;element=frame;
   }
   if(w<width*scale-2 || h<2)throw new Error('无法截取完整题目宽度，请切换电脑版后重试');
   return JSON.stringify({x:x,y:y,width:w,height:h,advance:h/scale,viewportWidth:window.innerWidth});
 }catch(e){return JSON.stringify({error:e.message});}};
 return JSON.stringify({width:width,height:height});
}catch(e){return JSON.stringify({error:e.message});}})(CAPTURE_ARGS);
"""
