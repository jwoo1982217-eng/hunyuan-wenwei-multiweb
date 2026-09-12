# -*- coding: utf-8 -*-
"""
patch11: 商汤生图特判（文生图 + 图生图统一处理，全部无水印）
实测结论（2026-09-12，curl + 视觉比对）：
  - 端点：POST /v1/images/generations（/images/edits 400）
  - 图生图：顶层 "images": [dataURL...]（1-5 项）；App 原 grok 风格 image:{url} 会报
    "invalid images, should contain between 1 and 5 items"
  - 水印：默认右下角"日日新 sensenova"角标；"watermark": false 是唯一有效去水印写法
    （watermark_enabled / water_mark 均无效，API 对未知字段宽松不报错）
实现：
  A) executeImage 入口（参数校验后、Cloudflare 分支前）加 sensenova baseUrl 分支，
     提前 return senseNovaImage(config, model, rawRequest)
  B) 新增实例方法 senseNovaImage：body = raw + model + watermark:false；
     有 image_data 时转顶层 images 数组（dataURL）并移除 image_data/image/image_url；
     POST images/generations。
寄存器纪律：各寄存器类型专存；合并点两侧类型一致；<init> 后不跟 move-result。
"""
import io
import sys

sys.stdout.reconfigure(encoding='utf-8')
CLI = r'D:\reasonix\hunyuan multiweb\start-apk-work\decoded\smali_classes4\com\wenwei\deepseekproxy\proxy\OpenAiCompatibleClient.smali'
EOL = '\r\n'
C = 'Lcom/wenwei/deepseekproxy/proxy/OpenAiCompatibleClient;'

# ---------- A) executeImage 入口分支 ----------
ANCHOR_A = EOL.join([
    '    invoke-static {v4, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V',
    '',
    '    .line 275',
    '    sget-object v0, Lcom/wenwei/deepseekproxy/proxy/CloudflareWorkersAiClient;->INSTANCE:Lcom/wenwei/deepseekproxy/proxy/CloudflareWorkersAiClient;',
])
INSERT_A = EOL.join([
    '    invoke-static {v4, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V',
    '',
    '    # patch11: 商汤生图特判（文/图生图统一 generations + images 数组 + watermark:false）',
    '    invoke-virtual {v2}, Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;->getBaseUrl()Ljava/lang/String;',
    '',
    '    move-result-object v0',
    '',
    '    sget-object v5, Ljava/util/Locale;->ROOT:Ljava/util/Locale;',
    '',
    '    invoke-virtual {v0, v5}, Ljava/lang/String;->toLowerCase(Ljava/util/Locale;)Ljava/lang/String;',
    '',
    '    move-result-object v0',
    '',
    '    const-string v5, "sensenova"',
    '',
    '    invoke-virtual {v0, v5}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z',
    '',
    '    move-result v0',
    '',
    '    if-eqz v0, :cond_sense_skip',
    '',
    '    invoke-virtual/range {v1 .. v4}, ' + C + ';->senseNovaImage(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Ljava/lang/String;Ljava/lang/String;)Lokhttp3/Response;',
    '',
    '    move-result-object v0',
    '',
    '    return-object v0',
    '',
    '    :cond_sense_skip',
    '    .line 275',
    '    sget-object v0, Lcom/wenwei/deepseekproxy/proxy/CloudflareWorkersAiClient;->INSTANCE:Lcom/wenwei/deepseekproxy/proxy/CloudflareWorkersAiClient;',
])

# ---------- B) senseNovaImage 方法体 ----------
HELPER = EOL.join([
    '',
    '# patch11: 商汤生图（文生图+图生图）：统一 generations 端点、watermark:false、',
    '# 参考图转顶层 images 数组（dataURL，1-5 项）。',
    '# 寄存器：v0 body / v1 images / v2 refs / v3 i / v4 len|flag / v5 item / v6 data /',
    '#         v7 mime / v8 dataUrl / v9 int临时 / v10 String临时；p0=v12 this, p1=v13 config, p2=v14 model, p3=v15 raw',
    '.method public final senseNovaImage(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Ljava/lang/String;Ljava/lang/String;)Lokhttp3/Response;',
    '    .locals 12',
    '',
    '    new-instance v0, Lorg/json/JSONObject;',
    '',
    '    invoke-direct {v0, v15}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V',
    '',
    '    const-string v10, "watermark"',
    '',
    '    const/4 v9, 0x0',
    '',
    '    invoke-virtual {v0, v10, v9}, Lorg/json/JSONObject;->put(Ljava/lang/String;Z)Lorg/json/JSONObject;',
    '',
    '    const-string v10, "model"',
    '',
    '    invoke-virtual {v0, v10, v14}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;',
    '',
    '    new-instance v1, Lorg/json/JSONArray;',
    '',
    '    invoke-direct {v1}, Lorg/json/JSONArray;-><init>()V',
    '',
    '    const-string v10, "image_data"',
    '',
    '    invoke-virtual {v0, v10}, Lorg/json/JSONObject;->optJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;',
    '',
    '    move-result-object v2',
    '',
    '    if-eqz v2, :has_no_refs',
    '',
    '    invoke-virtual {v2}, Lorg/json/JSONArray;->length()I',
    '',
    '    move-result v4',
    '',
    '    if-lez v4, :has_no_refs',
    '',
    '    const/4 v3, 0x0',
    '',
    '    :goto_loop',
    '    invoke-virtual {v2}, Lorg/json/JSONArray;->length()I',
    '',
    '    move-result v4',
    '',
    '    if-ge v3, v4, :do_images',
    '',
    '    invoke-virtual {v2, v3}, Lorg/json/JSONArray;->optJSONObject(I)Lorg/json/JSONObject;',
    '',
    '    move-result-object v5',
    '',
    '    if-eqz v5, :next_iter',
    '',
    '    const-string v10, "data"',
    '',
    '    invoke-virtual {v5, v10}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;',
    '',
    '    move-result-object v6',
    '',
    '    invoke-virtual {v6}, Ljava/lang/String;->length()I',
    '',
    '    move-result v9',
    '',
    '    if-lez v9, :next_iter',
    '',
    '    const-string v10, "data:"',
    '',
    '    invoke-virtual {v6, v10}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z',
    '',
    '    move-result v9',
    '',
    '    if-nez v9, :use_raw',
    '',
    '    const-string v10, "mime"',
    '',
    '    const-string v7, "image/jpeg"',
    '',
    '    invoke-virtual {v5, v10, v7}, Lorg/json/JSONObject;->optString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;',
    '',
    '    move-result-object v7',
    '',
    '    invoke-virtual {v7}, Ljava/lang/String;->length()I',
    '',
    '    move-result v9',
    '',
    '    if-nez v9, :mime_ok',
    '',
    '    const-string v7, "image/jpeg"',
    '',
    '    :mime_ok',
    '    new-instance v8, Ljava/lang/StringBuilder;',
    '',
    '    invoke-direct {v8}, Ljava/lang/StringBuilder;-><init>()V',
    '',
    '    const-string v10, "data:"',
    '',
    '    invoke-virtual {v8, v10}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v8, v7}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    const-string v10, ";base64,"',
    '',
    '    invoke-virtual {v8, v10}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v8, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v8}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;',
    '',
    '    move-result-object v8',
    '',
    '    goto :put_img',
    '',
    '    :use_raw',
    '    move-object v8, v6',
    '',
    '    :put_img',
    '    invoke-virtual {v1, v8}, Lorg/json/JSONArray;->put(Ljava/lang/Object;)Lorg/json/JSONArray;',
    '',
    '    :next_iter',
    '    add-int/lit8 v3, v3, 0x1',
    '',
    '    goto :goto_loop',
    '',
    '    :do_images',
    '    const-string v10, "images"',
    '',
    '    invoke-virtual {v0, v10, v1}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;',
    '',
    '    const-string v10, "image_data"',
    '',
    '    invoke-virtual {v0, v10}, Lorg/json/JSONObject;->remove(Ljava/lang/String;)Ljava/lang/Object;',
    '',
    '    const-string v10, "image"',
    '',
    '    invoke-virtual {v0, v10}, Lorg/json/JSONObject;->remove(Ljava/lang/String;)Ljava/lang/Object;',
    '',
    '    const-string v10, "image_url"',
    '',
    '    invoke-virtual {v0, v10}, Lorg/json/JSONObject;->remove(Ljava/lang/String;)Ljava/lang/Object;',
    '',
    '    :has_no_refs',
    '    invoke-virtual {v0}, Lorg/json/JSONObject;->toString()Ljava/lang/String;',
    '',
    '    move-result-object v0',
    '',
    '    sget-object v1, Lcom/wenwei/deepseekproxy/proxy/OpenAiCompatibleClient;->client:Lokhttp3/OkHttpClient;',
    '',
    '    new-instance v2, Lokhttp3/Request$Builder;',
    '',
    '    invoke-direct {v2}, Lokhttp3/Request$Builder;-><init>()V',
    '',
    '    const-string v3, "images/generations"',
    '',
    '    invoke-virtual {v12, v13, v3}, Lcom/wenwei/deepseekproxy/proxy/OpenAiCompatibleClient;->endpoint$app_proxy(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Ljava/lang/String;)Ljava/lang/String;',
    '',
    '    move-result-object v3',
    '',
    '    invoke-virtual {v2, v3}, Lokhttp3/Request$Builder;->url(Ljava/lang/String;)Lokhttp3/Request$Builder;',
    '',
    '    move-result-object v2',
    '',
    '    invoke-direct {v12, v2, v13}, Lcom/wenwei/deepseekproxy/proxy/OpenAiCompatibleClient;->authorize(Lokhttp3/Request$Builder;Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;)Lokhttp3/Request$Builder;',
    '',
    '    move-result-object v2',
    '',
    '    const-string v3, "Content-Type"',
    '',
    '    const-string v4, "application/json"',
    '',
    '    invoke-virtual {v2, v3, v4}, Lokhttp3/Request$Builder;->header(Ljava/lang/String;Ljava/lang/String;)Lokhttp3/Request$Builder;',
    '',
    '    move-result-object v2',
    '',
    '    sget-object v5, Lokhttp3/RequestBody;->Companion:Lokhttp3/RequestBody$Companion;',
    '',
    '    sget-object v6, Lokhttp3/MediaType;->Companion:Lokhttp3/MediaType$Companion;',
    '',
    '    invoke-virtual {v6, v4}, Lokhttp3/MediaType$Companion;->get(Ljava/lang/String;)Lokhttp3/MediaType;',
    '',
    '    move-result-object v4',
    '',
    '    invoke-virtual {v5, v0, v4}, Lokhttp3/RequestBody$Companion;->create(Ljava/lang/String;Lokhttp3/MediaType;)Lokhttp3/RequestBody;',
    '',
    '    move-result-object v3',
    '',
    '    invoke-virtual {v2, v3}, Lokhttp3/Request$Builder;->post(Lokhttp3/RequestBody;)Lokhttp3/Request$Builder;',
    '',
    '    move-result-object v2',
    '',
    '    invoke-virtual {v2}, Lokhttp3/Request$Builder;->build()Lokhttp3/Request;',
    '',
    '    move-result-object v2',
    '',
    '    invoke-virtual {v1, v2}, Lokhttp3/OkHttpClient;->newCall(Lokhttp3/Request;)Lokhttp3/Call;',
    '',
    '    move-result-object v1',
    '',
    '    invoke-interface {v1}, Lokhttp3/Call;->execute()Lokhttp3/Response;',
    '',
    '    move-result-object v1',
    '',
    '    return-object v1',
    '.end method',
    '',
])


def main():
    t = io.open(CLI, encoding='utf-8', newline='').read()
    bak = CLI + '.bak-step11'
    shutil_copy(bak, t)

    if 'senseNovaImage' in t:
        print('[中止] patch11 已应用过')
        sys.exit(1)

    if ANCHOR_A not in t or t.count(ANCHOR_A) != 1:
        print('[FAIL] A 锚点未找到或非唯一，count =', t.count(ANCHOR_A))
        sys.exit(1)
    t = t.replace(ANCHOR_A, INSERT_A)
    print('[OK] A executeImage 入口商汤分支')

    if not t.endswith(EOL):
        t += EOL
    t += HELPER
    print('[OK] B 追加 senseNovaImage 方法')

    io.open(CLI, 'w', encoding='utf-8', newline='').write(t)

    chk = io.open(CLI, encoding='utf-8', newline='').read()
    seg = chk.split('senseNovaImage', 1)[1]
    checks = {
        '分支调用点': chk.count('senseNovaImage(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Ljava/lang/String;Ljava/lang/String;)Lokhttp3/Response;') >= 2,
        'helper 定义': '.method public final senseNovaImage(' in chk,
        'watermark put': seg.find('put(Ljava/lang/String;Z)') > 0,
        'images 转换': seg.find('"images"') > 0,
        '无 init 后 move-result': 'invoke-direct/range' not in seg,
    }
    for k, ok in checks.items():
        print('[自检]', 'PASS' if ok else 'FAIL', k)
    if not all(checks.values()):
        sys.exit(1)
    print('patch11 完成')


def shutil_copy(bak, content):
    import shutil
    import os
    if not os.path.exists(bak):
        shutil.copy2(CLI, bak)
        print('[备份]', bak.rsplit('\\', 1)[-1])


if __name__ == '__main__':
    main()
