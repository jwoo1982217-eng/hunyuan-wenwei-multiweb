package com.wenwei.deepseekproxy.proxy

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun webAttachmentUploadScript(attachments: List<ChatAttachment>): String {
    val encoded = buildJsonArray {
        attachments.forEach { item ->
            add(buildJsonObject {
                put("name", item.name)
                put("mime", item.mime)
                put("data", item.dataUrl)
            })
        }
    }.toString()
    return """
        (()=>{try{
          const input=document.querySelector('input[type="file"]');
          if(!input)return JSON.stringify({ok:false,error:'file_input_not_found'});
          const dt=new DataTransfer();
          for(const item of $encoded){
            const comma=item.data.indexOf(',');
            const bytes=atob(item.data.slice(comma+1));
            const data=new Uint8Array(bytes.length);
            for(let i=0;i<bytes.length;i++)data[i]=bytes.charCodeAt(i);
            dt.items.add(new File([data],item.name,{type:item.mime}));
          }
          input.files=dt.files;
          input.dispatchEvent(new Event('input',{bubbles:true}));
          input.dispatchEvent(new Event('change',{bubbles:true}));
          return JSON.stringify({ok:true,count:dt.files.length});
        }catch(e){return JSON.stringify({ok:false,error:String(e)})}})()
    """.trimIndent()
}

/**
 * 武装脚本：把待上传附件预置到页面变量，并 hook HTMLInputElement.prototype.click，
 * 拦截「按钮点击 → 动态创建 input[type=file] → .click()」这类站点（豆包网页版等）的
 * 文件选择器，直接把 DataTransfer 注入 files 并触发 change，避免弹系统文件选择器。
 * 若页面已有静态 input[type=file] 则直接注入（mode=direct）。
 * 返回 JSON: {ok, mode: 'direct'|'armed', error?}
 */
internal fun webAttachmentArmScript(attachments: List<ChatAttachment>): String {
    val encoded = buildJsonArray {
        attachments.forEach { item ->
            add(buildJsonObject {
                put("name", item.name)
                put("mime", item.mime)
                put("data", item.dataUrl)
            })
        }
    }.toString()
    return """
        (()=>{try{
          const inject = (input, items) => {
            const dt = new DataTransfer();
            for (const item of items) {
              const comma = item.data.indexOf(',');
              const bytes = atob(item.data.slice(comma + 1));
              const data = new Uint8Array(bytes.length);
              for (let i = 0; i < bytes.length; i++) data[i] = bytes.charCodeAt(i);
              dt.items.add(new File([data], item.name, { type: item.mime }));
            }
            input.files = dt.files;
            input.dispatchEvent(new Event('input', { bubbles: true }));
            input.dispatchEvent(new Event('change', { bubbles: true }));
          };
          window.__multiwebPendingFiles = $encoded;
          window.__multiwebUploadResult = 'pending';
          // 1) 已有可见的静态 input[type=file] → 直接注入（display:none 的 input 注入无效）
          const existing = [...document.querySelectorAll('input[type="file"]')].find(i=>
            i.offsetParent!==null || (i.getBoundingClientRect().width>0 && i.getBoundingClientRect().height>0)
          );
          if (existing) {
            inject(existing, window.__multiwebPendingFiles);
            window.__multiwebPendingFiles = [];
            window.__multiwebUploadResult = 'injected';
            return JSON.stringify({ok:true, mode:'direct'});
          }
          // 2) hook 动态创建的 file input 的 .click()
          if (!window.__multiwebFileClickHooked) {
            const origClick = HTMLInputElement.prototype.click;
            HTMLInputElement.prototype.click = function() {
              if (this.type === 'file' && window.__multiwebPendingFiles && window.__multiwebPendingFiles.length) {
                try {
                  inject(this, window.__multiwebPendingFiles);
                  window.__multiwebPendingFiles = [];
                  window.__multiwebUploadResult = 'injected';
                  return;
                } catch (e) {
                  window.__multiwebUploadResult = 'error:' + String(e);
                }
              }
              return origClick.apply(this, arguments);
            };
            window.__multiwebFileClickHooked = true;
          }
          return JSON.stringify({ok:true, mode:'armed'});
        }catch(e){return JSON.stringify({ok:false,error:String(e)})}})()
    """.trimIndent()
}

/** 读取武装结果：injected 表示文件已注入；pending 表示还在等按钮点击。 */
internal const val WEB_ATTACHMENT_RESULT_SCRIPT = """
    (()=>{try{return JSON.stringify({result:window.__multiwebUploadResult||'',pending:(window.__multiwebPendingFiles||[]).length});}catch(e){return JSON.stringify({result:'error:'+String(e),pending:0})}})()
"""

/** 更激进的上传/附件按钮点击：兼容纯图标按钮（class/aria-label/title/内嵌 svg 描述）。 */
internal const val WEB_ATTACHMENT_BUTTON_SCRIPT = """
    (()=>{
      const text = e => (e.getAttribute('aria-label')||e.title||e.getAttribute('data-testid')||e.innerText||'').trim();
      const hit = e => /upload|attach|add files|image|photo|picture|图片|照片|上传|添加文件|添加图片|加号|附件/i.test(text(e)||'') ||
        (e.className&&typeof e.className==='string'&&/upload|attach/i.test(e.className));
      const els=[...document.querySelectorAll('button,[role="button"],[class*="upload" i],[class*="file" i]')]
        .filter(x=>hit(x) && x.offsetParent!==null);
      // 优先点最内层的具体按钮/图标（如 longcat 的 .upload-file 会触发 input.click()，
      // 而外层 .upload-button-content 容器点击无效）。按后代数升序取最内层。
      const button=els.sort((a,b)=>(a.querySelectorAll('*').length-b.querySelectorAll('*').length))[0];
      if(button){button.click();return true;}
      return false;
    })()
"""
