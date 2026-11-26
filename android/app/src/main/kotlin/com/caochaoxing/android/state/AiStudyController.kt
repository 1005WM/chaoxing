package com.caochaoxing.android.state

import android.content.Context
import android.webkit.WebView
import com.caochaoxing.core.model.ChaoxingChapterAttachment
import com.caochaoxing.core.service.*
import com.caochaoxing.android.ui.screens.isAllowedAssessmentUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

@Serializable
data class AiConfig(val url: String = "", val key: String = "", val model: String = "", val followStudy: Boolean = false)

data class AiDraft(val title: String, val questions: List<AiQuestion> = emptyList(), val answers: Map<String, AiAnswer> = emptyMap(),
    val errors: Map<String, String> = emptyMap(), val running: Boolean = false, val message: String = "")

/** Rendered questions only. No answer inputs, scripts, credentials or hidden fields are sent to AI. */
internal val EXTRACT_AI_QUESTIONS = """
(function(){
 var result=[];
 var captureMedia=true;
 var mediaBytes=0;
 window.__cxAiQuestionNodes={};
 var excluded='script:not([type^="math/tex"]),style,noscript,textarea,button,input,iframe,[hidden],.mark_answer,.answer-analysis,.analysis,.correctAnswer,.teacherComment,[contenteditable="true"]';
 // AI_DOM_MEDIA
 function scan(doc){
   var strong='.singleQuesId,.questionLi[data],[data-question-id]';
   var generic='.TiMu,.questionLi,.question-item,.questionItem';
   var headings='.Zy_TItle,.mark_name,.question-title,.questionTitle';
   var types='input[name^="type"],input[id^="type"],input[id^="answertype"]';
   var nodes=Array.from(doc.querySelectorAll(strong));
   nodes=nodes.filter(function(e){return !e.querySelector(strong);});
   Array.from(doc.querySelectorAll(generic)).forEach(function(e){
     if(e.matches(strong) || e.closest(strong) || e.querySelector(strong)) return;
     if(e.querySelector(generic)) return;
     nodes.push(e);
   });
   // Some desktop pages use a different container for the first or last question.
   Array.from(doc.querySelectorAll(headings+','+types)).forEach(function(title){
     if(nodes.some(function(e){return e.contains(title);})) return;
     var node=title.closest(strong+','+generic);
     if(!node){
       node=title.parentElement;
       var marker=title.matches('input')?types:headings;
       while(node && node.parentElement && node.parentElement!==doc.body && node.parentElement.querySelectorAll(marker).length===1) node=node.parentElement;
     }
     if(node && node!==doc.body && nodes.indexOf(node)<0) nodes.push(node);
   });
   nodes.sort(function(a,b){return a===b?0:(a.compareDocumentPosition(b)&4?-1:1);});
   nodes.forEach(function(node){
     function owned(e){return !nodes.some(function(other){return other!==node && node.contains(other) && other.contains(e);});}
     var title=Array.from(node.querySelectorAll(headings+',h3')).find(owned) || node;
     var labels=new Map();
     var choices=Array.from(node.querySelectorAll('.Zy_ulTop li, .answerList li, .question-options li, .answerBg li, ul.mark_letter > li.workTextWrap, .mark_letter > li, .answerBg, .Zy_ulTk .answer_p, .Zy_ulTk .after'));
     choices=choices.filter(owned);
     choices=choices.filter(function(e){return !choices.some(function(other){return other!==e && e.contains(other);});});
     if(!choices.length) choices=Array.from(node.querySelectorAll('label')).filter(owned);
     choices=choices.filter(function(e){return e.innerText.trim() || e.querySelector('img,svg,canvas');});
     var images=[];
     function visuals(root){return Array.from(root.querySelectorAll('img,svg,canvas')).filter(function(e){
       if(!owned(e) || e.closest(excluded) || e.parentElement.closest('svg,canvas')) return false;
       for(var f=e;f && f!==node;f=f.parentElement) if(formula(f)) return false;
       return true;
     });}
     var stemImages=visuals(node).filter(function(e){return !choices.some(function(c){return c.contains(e);});});
     function add(e,label){labels.set(e,label);var source=mediaBytes>=24000000?'cx-capture:budget':picture(e,doc);mediaBytes+=source.length;images.push({label:label,source:source});}
     stemImages.forEach(function(e,index){add(e,'题干图片 '+(index+1));});
     choices.forEach(function(choice,index){visuals(choice).forEach(function(e,n){add(e,'选项 '+String.fromCharCode(65+index)+' 图片 '+(n+1));});});
     var text=questionText(node,doc,nodes,labels);
     var options=choices.map(function(e){return questionText(e,doc,[],labels).replace(/^[A-Z][.、．\s]+/, '') || '图片选项';});
     var hasImage=images.length>0;
     var needsScreenshot=images.length>6 || images.some(function(i){return !i.source || !/^data:image\/(png|jpeg|svg\+xml);base64,/.test(i.source);});
     [node].concat(Array.from(node.querySelectorAll('*'))).filter(owned).forEach(function(e){
       if(e.closest(excluded))return;
       var style=doc.defaultView.getComputedStyle(e);
       if(style.display==='none' || style.visibility==='hidden')return;
       if(style.backgroundImage!=='none' || /font-cxsecret/.test(style.fontFamily)) needsScreenshot=true;
       if(e.matches('math,mjx-container,.MathJax,[role="math"]') && !formula(e))needsScreenshot=true;
       ['::before','::after'].forEach(function(p){var pseudo=doc.defaultView.getComputedStyle(e,p);if(pseudo.backgroundImage!=='none' || (pseudo.content && !/^(none|normal|"")$/.test(pseudo.content)))needsScreenshot=true;});
     });
     hasImage=hasImage || needsScreenshot;
     if(!text && !hasImage) return;
     if(!text) text='图片题';
     var typeNode=Array.from(node.querySelectorAll(types+',.question-type,.type_tit,.mark_type')).find(owned);
     var typeValue=typeNode && typeNode.value;
     var typeText=(typeNode && typeNode.innerText || '')+' '+title.innerText;
     var type=typeValue==='3' || /判断/.test(typeText)?'判断':typeValue==='1' || /多选/.test(typeText)?'多选':typeValue==='0' || /单选/.test(typeText)?'单选':'文字题';
     if(type==='文字题' && /判断题/.test(text)) type='判断';
     if(/题量\s*[:：]\s*\d+/.test(text) && !options.length && !hasImage) return;
     if(type==='文字题' && !options.length && !hasImage &&
       !node.querySelector('.Zy_TItle,.mark_name,.question-title,.questionTitle,.stem,.question-stem,.question-content,textarea,[contenteditable="true"]')) return;
     if(type==='判断' && !options.length) options=['正确','错误'];
     result.push({id:String(result.length),type:type,text:text,options:options,hasImage:hasImage,images:images.slice(0,7),captureKey:'q'+result.length,needsScreenshot:needsScreenshot});
     window.__cxAiQuestionNodes['q'+(result.length-1)]={node:node,doc:doc,text:text,others:nodes.filter(function(e){return e!==node && node.contains(e);}),excluded:excluded};
   });
   Array.from(doc.querySelectorAll('iframe')).forEach(function(frame){try{if(frame.contentDocument)scan(frame.contentDocument);}catch(e){}});
 }
 scan(document); return JSON.stringify(result.slice(0,100));
})();
""".replace("// AI_DOM_MEDIA", AI_DOM_MEDIA)

internal val COUNT_AI_QUESTIONS = EXTRACT_AI_QUESTIONS.replace("var captureMedia=true;", "var captureMedia=false;")

/**
 * 答案回填脚本：把 AI 答案写回题目对应的选项或填空框。
 * 题目节点定位与 EXTRACT_AI_QUESTIONS 完全同源（相同选择器 + 相同排序），
 * 因此第 index 个节点精确对应读题时第 index 道题；跨页面题目由调用方按所属 frame 传入。
 * 参数占位：__INDEX__ 为题目序号，__ANSWER__ 为 JSON 转义后的答案字符串。
 * 返回一个 JSON 字符串：{ok:数量, choice:数量, fill:数量, skipped:数量, detail:[...]}。
 */
internal val APPLY_AI_ANSWER_JS = """
(function(){
 var index=__INDEX__;
 var answer=__ANSWER__;
 var out={ok:0,choice:0,fill:0,skipped:0,detail:[]};
 function nodesOf(doc,all,depth){
   if(depth>6)return;
   var strong='.singleQuesId,.questionLi[data],[data-question-id]';
   var generic='.TiMu,.questionLi,.question-item,.questionItem';
   var headings='.Zy_TItle,.mark_name,.question-title,.questionTitle';
   var types='input[name^="type"],input[id^="type"],input[id^="answertype"]';
   var nodes=Array.from(doc.querySelectorAll(strong));
   nodes=nodes.filter(function(e){return !e.querySelector(strong);});
   Array.from(doc.querySelectorAll(generic)).forEach(function(e){
     if(e.matches(strong)||e.closest(strong)||e.querySelector(strong))return;
     if(e.querySelector(generic))return;
     nodes.push(e);
   });
   Array.from(doc.querySelectorAll(headings+','+types)).forEach(function(title){
     if(nodes.some(function(e){return e.contains(title);}))return;
     var node=title.closest(strong+','+generic);
     if(!node){
       node=title.parentElement;
       var marker=title.matches('input')?types:headings;
       while(node&&node.parentElement&&node.parentElement!==doc.body&&node.parentElement.querySelectorAll(marker).length===1)node=node.parentElement;
     }
     if(node&&node!==doc.body&&nodes.indexOf(node)<0)nodes.push(node);
   });
   nodes.sort(function(a,b){return a===b?0:(a.compareDocumentPosition(b)&4?-1:1);});
   nodes=Array.from(new Set(nodes)).filter(function(node){
     var text=(node.innerText||'').replace(/\s/g,'');
     var hasChoice=node.querySelector('.Zy_ulTop li,.answerList li,.question-options li,.answerBg li,label,[contenteditable="true"],textarea,input[type="text"]');
     return text.length>0||hasChoice;
   });
   nodes.forEach(function(node){all.push({node:node,doc:doc});});
   Array.from(doc.querySelectorAll('iframe')).forEach(function(frame){try{if(frame.contentDocument&&frame.contentDocument.body)nodesOf(frame.contentDocument,all,depth+1);}catch(e){}});
 }
 var all=[];
 var cache=window.__cxAiQuestionNodes;
 var cached=cache?Object.keys(cache).filter(function(k){return /^q\d+$/.test(k);})
   .sort(function(a,b){return parseInt(a.slice(1))-parseInt(b.slice(1));})
   .map(function(k){return {node:cache[k].node,doc:cache[k].doc};})
   .filter(function(x){return x.node&&x.node.isConnected;}):[];
 if(cached.length)all=cached; else nodesOf(document,all,0);
 if(index<0||index>=all.length){out.detail.push('题目序号'+index+'不存在，共'+all.length+'题（页面已刷新请重新读题）');return JSON.stringify(out);}
 var item=all[index];var node=item.node;var doc=item.doc||node.ownerDocument;
 var win=doc.defaultView||window;
 function fire(el,types){types.forEach(function(t){try{el.dispatchEvent(new win.Event(t,{bubbles:true}));}catch(e){try{el.dispatchEvent(new Event(t,{bubbles:true}));}catch(_){}}});}
 function setText(el,value){
   try{
     if(el.matches('textarea,input[type="text"],input:not([type])')){
       var proto=el.tagName==='TEXTAREA'?win.HTMLTextAreaElement.prototype:win.HTMLInputElement.prototype;
       Object.getOwnPropertyDescriptor(proto,'value').set.call(el,value);
     }else if(el.isContentEditable){el.textContent=value;}
     else if(el.matches('iframe')){try{var bd=el.contentDocument&&el.contentDocument.body;if(bd)bd.innerHTML=value;}catch(e){return false;}}
     else{el.textContent=value;}
     fire(el,['input','change','blur','keyup']);return true;
   }catch(e){return false;}
 }
 var letters=String(answer).toUpperCase().replace(/[^A-Z]/g,'');
 var isChoice=/^[A-Z]+$/.test(String(answer).trim().toUpperCase());
 var sel='.Zy_ulTop li, .answerList li, .question-options li, .answerBg li, ul.mark_letter > li.workTextWrap, .mark_letter > li, label, [mark_letter], .answer_p, .mark_name + ul li';
 var choices=Array.from(node.querySelectorAll(sel));
 choices=choices.filter(function(e){return !choices.some(function(o){return o!==e&&o.contains(e);});});
 if(!choices.length)choices=Array.from(node.querySelectorAll('li')).filter(function(e){return e.querySelector('input')||/\b[A-D][.、．]/.test(e.innerText||'');});
 choices=choices.filter(function(e){var t=(e.innerText||'').trim();return t.length>0||e.querySelector('input,img,svg');});
 var clickTarget=function(li){
   var input=li.querySelector('input[type=radio],input[type=checkbox]');
   if(input){
     if(input.checked)return true; // 已选中，避免多选被反选
     try{input.click();}catch(e){try{li.click();}catch(_){}}
   }else{
     var marked=(li.className||'').indexOf('active')>=0||li.getAttribute('aria-checked')==='true';
     if(marked)return true;
     try{li.click();}catch(_){}
   }
   return true;
 };
 function judgeByMeaning(text){
   var v=String(text).trim();
   var yes=/^(正确|对|是|√|✓|true|t|yes)$/i.test(v);
   var no=/^(错误|错|否|×|✗|false|f|no)$/i.test(v);
   if(!yes&&!no)return -1;
   for(var i=0;i<choices.length;i++){var m=/^(正确|对|是|√|✓|true|t|yes)$/i.test((choices[i].innerText||'').trim())?1:(/^(错误|错|否|×|✗|false|f|no)$/i.test((choices[i].innerText||'').trim())?0:-1);if(m===(yes?1:0))return i;}
   return yes?0:(no?1:-1);
 }
 var typeText=node.querySelector('.mark_type,.question-type,.type_tit')?(node.querySelector('.mark_type,.question-type,.type_tit').innerText||''):'';
 var isJudge=/判断/.test(typeText)||choices.length===2&&choices.every(function(c){return judgeByMeaning(c.innerText)!==-1;});
 var applied=false;
 if(choices.length&&(isChoice||isJudge)){
   var indexes=[];
   if(isJudge&&!isChoice){var j=judgeByMeaning(answer);if(j>=0)indexes=[j];}
   else{letters.split('').forEach(function(ch){var i=ch.charCodeAt(0)-65;if(i>=0&&i<choices.length)indexes.push(i);});}
   indexes=indexes.filter(function(v,i,a){return a.indexOf(v)===i;});
   indexes.forEach(function(i){if(clickTarget(choices[i])){out.choice++;applied=true;out.detail.push('选项'+(String.fromCharCode(65+i)));}});
   var withInput=indexes.filter(function(i){return !!choices[i].querySelector('input[type=radio],input[type=checkbox]');});
   var confirmed=withInput.filter(function(i){return choices[i].querySelector('input').checked;});
   if(withInput.length>0&&confirmed.length===0){
     out.skipped+=withInput.length;out.choice=0;applied=false;out.detail.push('选项点击后未生效');
   }
 }
 if(!applied){
   var parts=String(answer).split(/[\n,，、;；|]+/).map(function(s){return s.trim();}).filter(function(s){return s.length>0;});
   var editors=[];
   Array.from(node.querySelectorAll('textarea,[contenteditable="true"],input[type="text"],input:not([type][value])')).forEach(function(e){if(e.offsetParent!==null||e.matches('[contenteditable="true"]'))editors.push(e);});
   Array.from(node.querySelectorAll('iframe')).forEach(function(f){try{if(f.contentDocument&&f.contentDocument.body&&f.contentDocument.body.isContentEditable)editors.push(f);}catch(e){}});
   editors=editors.filter(function(e,i,a){return a.indexOf(e)===i;});
   if(editors.length){
     if(editors.length===1)setText(editors[0],String(answer));
     else parts.forEach(function(p,i){if(i<editors.length&&setText(editors[i],p))out.fill++;});
     if(editors.length===1)out.fill=1;
     applied=out.fill>0;
     out.detail.push('填空'+out.fill+'处');
   }
 }
 out.ok=applied?1:0;
 return JSON.stringify(out);
})();
"""

class AiStudyController(private val context: Context, private val appState: AndroidAppState, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val preferences = context.getSharedPreferences("ai-settings", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(AiConfig())
    val config = _config.asStateFlow()
    private val _drafts = MutableStateFlow<Map<String, AiDraft>>(emptyMap())
    val drafts = _drafts.asStateFlow()
    val api = AiAnswerService()
    private val imageReader = AiQuestionImageReader()
    private val pageReader = AiQuestionPageReader(context, appState, ::decodeQuestions)
    private val jobs = mutableMapOf<String, Job>()
    private val automatic = mutableSetOf<String>()
    private val mutex = Mutex()
    private var account: String? = null
    var loadError: String? = null
        private set

    init {
        try {
            preferences.getString("config", null)?.let {
                val bytes = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                _config.value = json.decodeFromString(String(AesGcmCipher.decrypt(bytes, key()), Charsets.UTF_8))
            }
        } catch (_: Exception) { loadError = "AI 配置读取失败，请重新填写并保存" }
    }

    private fun key() = EncryptedFilePersistenceStore.getOrCreateKeyStoreSecretKey("com.caochaoxing.ai.v1")

    fun save(config: AiConfig) {
        val bytes = AesGcmCipher.encrypt(json.encodeToString(config).toByteArray(Charsets.UTF_8), key())
        check(preferences.edit().putString("config", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).commit()) {
            "AI 设置保存失败"
        }
        _config.value = config
        loadError = null
    }

    fun onAccountChanged(phone: String?) {
        if (account == phone) return
        account = phone
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        automatic.clear()
        _drafts.value = emptyMap()
    }

    fun stopFollowing() {
        automatic.toList().forEach { jobs[it]?.cancel() }
        automatic.clear()
    }

    fun enqueue(attachment: ChaoxingChapterAttachment, retryAfterVideo: Boolean = false) {
        if (!_config.value.followStudy || attachment.isPassed || !attachment.isJob) return
        val url = attachment.externalUrl ?: return
        if (!isAllowedAssessmentUrl(url)) return
        val existing = _drafts.value[url]
        if (existing != null && !(retryAfterVideo && !existing.running && existing.questions.isEmpty())) return
        generate(url, attachment.name, automaticRequest = true)
    }

    fun decodeQuestions(raw: String): List<AiQuestion> {
        val encoded = json.decodeFromString<String>(raw)
        val questions = json.decodeFromString<List<AiQuestion>>(encoded).filter { it.text.isNotBlank() }.take(100)
        require(questions.all { it.text.length <= 32_000 && it.options.size <= 26 }) { "题目内容过长，请分题读取" }
        return questions
    }

    fun generate(id: String, title: String, questions: List<AiQuestion>? = null, automaticRequest: Boolean = false, retryId: String? = null) {
        if (jobs[id]?.isActive == true) return
        val config = _config.value
        if (config.url.isBlank() || config.key.isBlank() || config.model.isBlank()) {
            _drafts.value = _drafts.value + (id to AiDraft(title, message = "请先配置 AI 接口、Key 和模型"))
            return
        }
        if (automaticRequest) automatic += id
        val phone = account
        jobs[id] = scope.launch {
            try {
                val old = _drafts.value[id]
                _drafts.value = _drafts.value + (id to (old ?: AiDraft(title)).copy(running = true, message = "等待生成"))
                mutex.withLock {
                    val list = questions ?: readQuestions(id)
                    require(list.isNotEmpty()) { "未识别到题目。请打开题目页后重试，锁定测验需先完成前置学习" }
                    var draft = (old?.takeIf { it.questions == list } ?: AiDraft(title, list)).copy(running = true, message = "正在生成")
                    _drafts.value = _drafts.value + (id to draft)
                    for (question in list) {
                        ensureActive()
                        if (retryId != null && question.id != retryId) continue
                        if (retryId == null && question.id in draft.answers) continue
                        try {
                            if (question.images.isNotEmpty()) {
                                _drafts.value = _drafts.value + (id to draft.copy(message = "正在读取题图"))
                            }
                            val prepared = imageReader.prepare(question)
                            val answer = api.answer(config.url, config.key, config.model, prepared)
                            ensureActive()
                            draft = draft.copy(answers = draft.answers + (question.id to answer), errors = draft.errors - question.id)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { draft = draft.copy(errors = draft.errors + (question.id to (e.message ?: "生成失败"))) }
                        if (phone != account) return@withLock
                        _drafts.value = _drafts.value + (id to draft)
                    }
                    _drafts.value = _drafts.value + (id to draft.copy(running = false, message = "生成结束"))
                }
            } catch (e: CancellationException) {
                if (phone == account) _drafts.value[id]?.let { _drafts.value = _drafts.value + (id to it.copy(running = false, message = "已停止")) }
                throw e
            } catch (e: Exception) {
                if (phone == account) _drafts.value = _drafts.value + (id to (_drafts.value[id] ?: AiDraft(title)).copy(running = false, message = e.message ?: "生成失败"))
            } finally { automatic.remove(id) }
        }
    }

    fun cancel(id: String) { jobs[id]?.cancel() }

    suspend fun readPage(view: WebView): AiQuestionPage = pageReader.fromView(view)

    fun applyAnswerScript(index: Int, answer: String): String = buildApplyAnswerScript(index, answer)

    fun parseApplyResult(raw: String?): ApplyResult = parseAiApplyResult(raw)

    /**
     * 回填全部：按 [pairs] 顺序逐题回填，每题间隔 [intervalMs] 毫秒，单题失败不中断、跳过继续。
     * [onEach] 在每题完成后回调（题号从 1 开始、结果、是否成功），用于实时进度提示。
     */
    suspend fun applyAllAnswers(
        view: WebView,
        pairs: List<Pair<Int, String>>,
        intervalMs: Long = 1500L,
        onEach: ((position: Int, total: Int, result: ApplyResult) -> Unit)? = null,
    ): BatchApplySummary {
        val total = pairs.size
        var ok = 0
        var skipped = 0
        val failed = mutableListOf<Int>()
        pairs.forEachIndexed { i, (index, answer) ->
            val position = i + 1
            if (i > 0) delay(intervalMs)
            val result = runCatching {
                val script = buildApplyAnswerScript(index, answer)
                val raw = view.evaluateJavascriptSuspend(script)
                parseAiApplyResult(raw)
            }.getOrDefault(ApplyResult(false, detail = listOf("回填异常")))
            if (result.ok) ok++ else { skipped++; failed.add(position) }
            onEach?.invoke(position, total, result)
        }
        return BatchApplySummary(total = total, ok = ok, skipped = skipped, failedPositions = failed)
    }

    private suspend fun readQuestions(url: String): List<AiQuestion> = pageReader.fromUrl(url).questions
}

/** 把 WebView.evaluateJavascript 的回调式 API 包装成挂起函数。 */
private suspend fun WebView.evaluateJavascriptSuspend(script: String): String? =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { value -> cont.resumeWith(Result.success(value)) }
    }

/** 汇总批量回填结果。失败题号从 1 开始，便于展示「第 N 题」。 */
data class BatchApplySummary(
    val total: Int = 0,
    val ok: Int = 0,
    val skipped: Int = 0,
    val failedPositions: List<Int> = emptyList(),
) {
    val message: String get() = when {
        total == 0 -> "没有可回填的答案"
        skipped == 0 -> "已回填全部 $ok 题"
        else -> "已回填 $ok 题，跳过 $skipped 题" +
            (failedPositions.take(6).joinToString("、") { "第${it}题" }.let { "（$it${if (failedPositions.size > 6) " 等" else ""}）" })
    }
}

/** 生成把答案写回第 [index] 道题的 JS。答案用 JSON 编码，避免引号/换行破坏脚本。 */
internal fun buildApplyAnswerScript(index: Int, answer: String): String {
    val encoded = Json.Default.encodeToString(answer)
    return APPLY_AI_ANSWER_JS.replace("__INDEX__", index.toString()).replace("__ANSWER__", encoded)
}

/** 解析回填脚本返回的 JSON，Fail-safe：解析失败视为未成功。 */
internal fun parseAiApplyResult(raw: String?): ApplyResult {
    val body = raw?.trim()?.removeSurrounding("\"")
        ?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: return ApplyResult(false)
    val obj = runCatching { Json.Default.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return ApplyResult(false)
    val ok = (obj["ok"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull?.toIntOrNull() == 1) } ?: false
    val choice = (obj["choice"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
    val fill = (obj["fill"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
    val skipped = (obj["skipped"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
    val detail = (obj["detail"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    return ApplyResult(ok, choice, fill, skipped, detail)
}

/** 单题回填结果。ok 表示是否真的写入了控件；choice/fill 为写入数量。 */
data class ApplyResult(
    val ok: Boolean = false,
    val choice: Int = 0,
    val fill: Int = 0,
    val skipped: Int = 0,
    val detail: List<String> = emptyList(),
) {
    val message: String get() = when {
        ok && fill > 0 -> "已填入 $fill 处填空"
        ok && choice > 0 -> "已勾选 ${choice} 个选项"
        else -> "未能回填：" + (detail.firstOrNull() ?: "题目无可写入的控件")
    }
}
