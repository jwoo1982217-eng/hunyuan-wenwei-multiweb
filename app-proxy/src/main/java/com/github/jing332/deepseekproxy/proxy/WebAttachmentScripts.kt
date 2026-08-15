package com.github.jing332.deepseekproxy.proxy

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

internal const val WEB_ATTACHMENT_BUTTON_SCRIPT = """
    (()=>{
      const button=[...document.querySelectorAll('button')].find(x=>
        /upload|attach|add files|上传|添加文件|添加图片/i.test(
          (x.getAttribute('aria-label')||x.title||x.innerText||'').trim()
        )
      );
      if(button)button.click();
      return !!button;
    })()
"""
