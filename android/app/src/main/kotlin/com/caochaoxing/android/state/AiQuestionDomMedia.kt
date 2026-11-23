package com.caochaoxing.android.state

/** Formula sources are read before hidden accessibility copies are discarded. */
internal const val AI_DOM_MEDIA = """
 function mathText(e){
   var kind=(e.getAttribute && e.getAttribute('data-mml-node')) || e.localName;
   var children=Array.from(e.children || []).filter(function(c){return !c.matches('annotation,annotation-xml,defs');});
   var parts=children.map(mathText);
   if(kind==='mfrac') return '('+parts[0]+')/('+parts[1]+')';
   if(kind==='msup') return '('+parts[0]+')^('+parts[1]+')';
   if(kind==='msub') return '('+parts[0]+')_('+parts[1]+')';
   if(kind==='msubsup') return '('+parts[0]+')_('+parts[1]+')^('+parts[2]+')';
   if(kind==='msqrt') return 'sqrt('+parts.join('')+')';
   if(kind==='mroot') return 'root('+parts[0]+','+parts[1]+')';
   if(kind==='mover') return 'over('+parts[0]+','+parts[1]+')';
   if(kind==='munder') return 'under('+parts[0]+','+parts[1]+')';
   if(e.matches && e.matches('g[data-mml-node="mi"],g[data-mml-node="mn"],g[data-mml-node="mo"],g[data-mml-node="mtext"]'))
     return Array.from(e.querySelectorAll('[data-c]')).map(function(p){return String.fromCodePoint(parseInt(p.getAttribute('data-c'),16));}).join('');
   return parts.length?parts.join(kind==='mtr'?',':''):(e.textContent || '');
 }
 function formula(e){
   if(!e.matches || !e.matches('math,mjx-container,.MathJax,[data-latex],[data-tex],[role="math"],script[type^="math/tex"]')) return '';
   var annotation=e.querySelector('annotation[encoding="application/x-tex"]');
   var source=e.getAttribute('data-latex') || e.getAttribute('data-tex') ||
     (annotation && annotation.textContent) || (e.matches('script')?e.textContent:'') ||
     e.getAttribute('alttext') || (e.getAttribute('role')==='math'?e.getAttribute('aria-label'):'');
   if(source && !/^(math|equation|formula|公式|数学公式)$/i.test(source.trim())) return source.trim();
   var math=e.matches('math')?e:e.querySelector('math,g[data-mml-node="math"]');
   return math?mathText(math).trim():'';
 }
 function questionText(node,doc,nodes,labels){
   function walk(e){
     if(e.nodeType===3) return e.textContent;
     if(e.nodeType!==1 || (e!==node && nodes.indexOf(e)>=0)) return '';
     if(e.matches(excluded)) return '';
     var style=doc.defaultView.getComputedStyle(e);
     if((style.display==='none' || style.visibility==='hidden') && !e.matches('script[type^="math/tex"]')) return '';
     var expression=formula(e);
     if(expression) return ' '+expression+' ';
     if(labels.has(e)) return ' ['+labels.get(e)+'] ';
     if(e.matches('img,svg,canvas')) return '';
     if(e.matches('br')) return '\n';
     var text=Array.from(e.childNodes).map(walk).join('');
     return text+(/^(P|DIV|H[1-6]|LI|TR|SECTION)$/.test(e.tagName)?'\n':'');
   }
   return walk(node).replace(/[ \t]+/g,' ').replace(/\n\s*\n/g,'\n').trim();
 }
 function picture(element,doc){
   if(!captureMedia)return '';
   if(element.matches('canvas')) {try{return element.toDataURL('image/png');}catch(e){return 'cx-capture:canvas';}}
   if(element.matches('svg')) {
     try {
       var copy=element.cloneNode(true), originals=[element].concat(Array.from(element.querySelectorAll('*'))), clones=[copy].concat(Array.from(copy.querySelectorAll('*')));
       var defs=doc.createElementNS('http://www.w3.org/2000/svg','defs'), references=new Set();
       originals.forEach(function(e,i){
         if(e.matches('script,foreignObject,iframe,image,animate,set')) throw new Error('external SVG');
         Array.from(e.attributes).forEach(function(a){if(/^on/i.test(a.name))clones[i].removeAttribute(a.name);});
         var style=doc.defaultView.getComputedStyle(e);
         ['color','fill','stroke','stroke-width','font-family','font-size','font-weight','opacity','visibility','display'].forEach(function(name){clones[i].style.setProperty(name,style.getPropertyValue(name));});
         var ref=e.getAttribute('href') || e.getAttribute('xlink:href');
         if(ref){
           if(ref.charAt(0)!=='#') throw new Error('external SVG reference');
           if(!element.querySelector('[id="'+ref.slice(1).replace(/"/g,'')+'"]') && !references.has(ref)) {
             var target=doc.getElementById(ref.slice(1));
             if(!target || !target.matches('path,g,symbol') || target.querySelector('use,image,script,foreignObject')) throw new Error('missing SVG reference');
             defs.appendChild(target.cloneNode(true)); references.add(ref);
           }
         }
       });
       copy.insertBefore(defs,copy.firstChild);
       var rect=element.getBoundingClientRect(), width=rect.width || element.viewBox.baseVal.width, height=rect.height || element.viewBox.baseVal.height;
       if(!width || !height || width*height>16000000) throw new Error('SVG dimensions');
       copy.setAttribute('xmlns','http://www.w3.org/2000/svg');copy.setAttribute('width',width);copy.setAttribute('height',height);
       return 'data:image/svg+xml;base64,'+btoa(unescape(encodeURIComponent(new XMLSerializer().serializeToString(copy))));
     }catch(e){return 'cx-capture:svg';}
   }
   var raw=element.getAttribute('data-original') || element.getAttribute('data-src') || element.getAttribute('origin-src') || element.currentSrc || element.getAttribute('src') || '';
   var source='';try{source=new URL(raw,doc.baseURI).href;}catch(e){}
   if(element.complete && element.naturalWidth>0 && source===element.currentSrc) {
     try {
       var scale=Math.min(1,2048/Math.max(element.naturalWidth,element.naturalHeight)), canvas=doc.createElement('canvas');
       canvas.width=Math.max(1,Math.round(element.naturalWidth*scale));canvas.height=Math.max(1,Math.round(element.naturalHeight*scale));
       var context=canvas.getContext('2d');context.fillStyle='white';context.fillRect(0,0,canvas.width,canvas.height);context.drawImage(element,0,0,canvas.width,canvas.height);
       var encoded=canvas.toDataURL('image/png');canvas.width=canvas.height=0;
       return encoded.length>7000000?'cx-capture:oversize':encoded;
     }catch(e){}
   }
   return source;
 }
"""
