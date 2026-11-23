package com.caochaoxing.android.state

import android.content.Context
import android.webkit.WebView
import com.caochaoxing.core.model.ChaoxingChapterAttachment
import com.caochaoxing.core.service.*
import com.caochaoxing.android.ui.screens.isAllowedAssessmentUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AiConfig(val url: String = "", val key: String = "", val model: String = "", val followStudy: Boolean = false)

data class AiDraft(val title: String, val questions: List<AiQuestion> = emptyList(), val answers: Map<String, AiAnswer> = emptyMap(),
    val errors: Map<String, String> = emptyMap(), val running: Boolean = false, val message: String = "")

/** Rendered questions only. No answer inputs, scripts, credentials or hidden fields are sent to AI. */
internal const val EXTRACT_AI_QUESTIONS = """
(function(){
 var result=[];
 var excluded='script,style,noscript,textarea,button,input,iframe,[hidden],[aria-hidden="true"],.mark_answer,.answer-analysis,.analysis,.correctAnswer,.teacherComment,[contenteditable="true"]';
 function questionText(node,doc){
   var copy=node.cloneNode(true);
   var originals=Array.from(node.querySelectorAll('*')), clones=Array.from(copy.querySelectorAll('*'));
   originals.forEach(function(e,index){
     var style=doc.defaultView.getComputedStyle(e);
     if(style.display==='none' || style.visibility==='hidden') clones[index].remove();
   });
   Array.from(copy.querySelectorAll(excluded)).forEach(function(e){e.remove();});
   Array.from(copy.querySelectorAll('br')).forEach(function(e){e.replaceWith(doc.createTextNode('\n'));});
   Array.from(copy.querySelectorAll('p,div,h1,h2,h3,h4,li,tr')).forEach(function(e){e.appendChild(doc.createTextNode('\n'));});
   return (copy.textContent || '').replace(/[ \t]+/g,' ').replace(/\n\s*\n/g,'\n').trim();
 }
 function picture(element, doc){
   if(element.tagName.toLowerCase()==='canvas') {
     try { return element.toDataURL('image/png'); } catch(e) { return ''; }
   }
   var lazy=element.getAttribute('data-original') || element.getAttribute('data-src');
   var raw=lazy || element.currentSrc || element.getAttribute('src') || '';
   var source=''; try { source=new URL(raw,doc.baseURI).href; } catch(e) {}
   if(element.complete && element.naturalWidth>0 && (!lazy || source===element.currentSrc)) {
     try {
       var scale=Math.min(1,2048/Math.max(element.naturalWidth,element.naturalHeight));
       var canvas=doc.createElement('canvas');
       canvas.width=Math.max(1,Math.round(element.naturalWidth*scale));
       canvas.height=Math.max(1,Math.round(element.naturalHeight*scale));
       var context=canvas.getContext('2d'); context.fillStyle='white'; context.fillRect(0,0,canvas.width,canvas.height);
       context.drawImage(element,0,0,canvas.width,canvas.height);
       return canvas.toDataURL('image/jpeg',0.92);
     } catch(e) {}
   }
   return source;
 }
 function scan(doc){
   var strong='.questionLi.singleQuesId,.questionLi[data],[data-question-id]';
   var generic='.TiMu,.questionLi,.question-item,.questionItem';
   var nodes=Array.from(doc.querySelectorAll(strong));
   nodes=nodes.filter(function(e){return !e.querySelector(strong);});
   Array.from(doc.querySelectorAll(generic)).forEach(function(e){
     if(e.matches(strong) || e.closest(strong) || e.querySelector(strong)) return;
     if(e.querySelector(generic)) return;
     nodes.push(e);
   });
   nodes.forEach(function(node){
     var title=node.querySelector('.Zy_TItle, .mark_name, .question-title, .questionTitle, h3') || node;
     var text=questionText(node,doc);
     var choices=Array.from(node.querySelectorAll('.Zy_ulTop li, .answerList li, .question-options li, .answerBg li, ul.mark_letter > li.workTextWrap, .mark_letter > li, .answerBg'));
     choices=choices.filter(function(e){return !e.querySelector('.answerBg, .answerBg li, .mark_letter > li, .Zy_ulTop li');});
     if(!choices.length) choices=Array.from(node.querySelectorAll('label'));
     choices=choices.filter(function(e){return e.innerText.trim() || e.querySelector('img,svg,canvas');});
     var options=choices.map(function(e){return e.innerText.trim().replace(/^[A-Z][.、．\s]+/, '') || '图片选项';});
     var images=[];
     var stemImages=Array.from(node.querySelectorAll('img,canvas')).filter(function(e){
       return !choices.some(function(choice){return choice.contains(e);}) && !e.closest(excluded);
     });
     stemImages.forEach(function(e,index){images.push({label:'题干图片 '+(index+1),source:picture(e,doc)});});
     choices.forEach(function(choice,index){Array.from(choice.querySelectorAll('img,canvas')).forEach(function(e,n){
       images.push({label:'选项 '+String.fromCharCode(65+index)+' 图片 '+(n+1),source:picture(e,doc)});
     });});
     var hasImage=images.length>0 || !!title.querySelector('svg') || choices.some(function(e){return !!e.querySelector('svg');});
     if(!text && !hasImage) return;
     if(!text) text='图片题';
     var typeNode=node.querySelector('input[name^="type"],input[id^="type"],.question-type,.type_tit,.mark_type');
     var typeValue=typeNode && typeNode.value;
     var typeText=(typeNode && typeNode.innerText || '')+' '+title.innerText;
     var type=typeValue==='3' || /判断/.test(typeText)?'判断':typeValue==='1' || /多选/.test(typeText)?'多选':typeValue==='0' || /单选/.test(typeText)?'单选':'文字题';
     if(type==='文字题' && /判断题/.test(text)) type='判断';
     if(/题量\s*[:：]\s*\d+/.test(text) && !options.length && !hasImage) return;
     if(type==='文字题' && !options.length && !hasImage &&
       !node.querySelector('.Zy_TItle,.mark_name,.question-title,.questionTitle,.stem,.question-stem,.question-content,textarea,[contenteditable="true"]')) return;
     if(type==='判断' && !options.length) options=['正确','错误'];
     result.push({id:String(result.length),type:type,text:text,options:options,hasImage:hasImage,images:images.slice(0,7)});
   });
   Array.from(doc.querySelectorAll('iframe')).forEach(function(frame){try{if(frame.contentDocument)scan(frame.contentDocument);}catch(e){}});
 }
 scan(document); return JSON.stringify(result.slice(0,100));
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

    suspend fun readPage(view: WebView): List<AiQuestion> = pageReader.fromView(view)

    private suspend fun readQuestions(url: String): List<AiQuestion> = pageReader.fromUrl(url)
}
