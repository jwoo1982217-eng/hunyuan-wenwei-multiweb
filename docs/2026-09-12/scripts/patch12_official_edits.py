# -*- coding: utf-8 -*-
"""
patch12: 按 official docs 修正商汤图生图协议。
官方（platform.sensenova.cn/docs，用户提供截图）：
  - 图片编辑接口 = POST /v1/images/edits（JSON，同步），必须传输入图片
  - images 元素是对象：{"image_url": "公网URL 或 data:image/png;base64,xxx"}（裸字符串 400）
  - 支持 watermark / n / size(含 1344x3136 竖版) / response_format:"b64_json" / prompt_extend
实测（curl+视觉）：edits+对象数组 → 箭头变金完美保留版式、无水印；t2i 仍走 generations。
改动（senseNovaImage helper）：
  1) images 元素包成 {"image_url": dataUrl} 对象
  2) 有参考图时端点 images/edits，否则 images/generations（v11 动态路径）
  3) edits 分支加 response_format:"b64_json"
"""
import io
import sys

sys.stdout.reconfigure(encoding='utf-8')
CLI = r'D:\reasonix\hunyuan multiweb\start-apk-work\decoded\smali_classes4\com\wenwei\deepseekproxy\proxy\OpenAiCompatibleClient.smali'
EOL = '\r\n'

EDITS = [
    ('A1 路径变量初始化',
     EOL.join([
         '    new-instance v1, Lorg/json/JSONArray;',
         '',
         '    invoke-direct {v1}, Lorg/json/JSONArray;-><init>()V',
         '',
         '    const-string v10, "image_data"',
     ]),
     EOL.join([
         '    new-instance v1, Lorg/json/JSONArray;',
         '',
         '    invoke-direct {v1}, Lorg/json/JSONArray;-><init>()V',
         '',
         '    const-string v11, "images/generations"',
         '',
         '    const-string v10, "image_data"',
     ])),
    ('A2 images 元素包对象',
     EOL.join([
         '    :put_img',
         '    invoke-virtual {v1, v8}, Lorg/json/JSONArray;->put(Ljava/lang/Object;)Lorg/json/JSONArray;',
     ]),
     EOL.join([
         '    :put_img',
         '    new-instance v11, Lorg/json/JSONObject;',
         '',
         '    invoke-direct {v11}, Lorg/json/JSONObject;-><init>()V',
         '',
         '    const-string v10, "image_url"',
         '',
         '    invoke-virtual {v11, v10, v8}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;',
         '',
         '    invoke-virtual {v1, v11}, Lorg/json/JSONArray;->put(Ljava/lang/Object;)Lorg/json/JSONArray;',
     ])),
    ('A3 edits 端点切换',
     EOL.join([
         '    :do_images',
         '    const-string v10, "ImageRoute"',
     ]),
     EOL.join([
         '    :do_images',
         '    const-string v11, "images/edits"',
         '',
         '    const-string v10, "ImageRoute"',
     ])),
    ('A4 response_format',
     EOL.join([
         '    const-string v10, "image_url"',
         '',
         '    invoke-virtual {v0, v10}, Lorg/json/JSONObject;->remove(Ljava/lang/String;)Ljava/lang/Object;',
         '',
         '    :has_no_refs',
     ]),
     EOL.join([
         '    const-string v10, "image_url"',
         '',
         '    invoke-virtual {v0, v10}, Lorg/json/JSONObject;->remove(Ljava/lang/String;)Ljava/lang/Object;',
         '',
         '    const-string v10, "response_format"',
         '',
         '    const-string v9, "b64_json"',
         '',
         '    invoke-virtual {v0, v10, v9}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;',
         '',
         '    :has_no_refs',
     ])),
    ('A5 endpoint 用动态路径',
     EOL.join([
         '    const-string v3, "images/generations"',
         '',
         '    invoke-virtual {v12, v13, v3}, Lcom/wenwei/deepseekproxy/proxy/OpenAiCompatibleClient;->endpoint$app_proxy(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Ljava/lang/String;)Ljava/lang/String;',
     ]),
     EOL.join([
         '    invoke-virtual {v12, v13, v11}, Lcom/wenwei/deepseekproxy/proxy/OpenAiCompatibleClient;->endpoint$app_proxy(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Ljava/lang/String;)Ljava/lang/String;',
     ])),
]

t = io.open(CLI, encoding='utf-8', newline='').read()
i = t.find('.method public final senseNovaImage(')
j = t.find('.end method', i)
body = t[i:j]

for tag, old, new in EDITS:
    n = body.count(old)
    if n != 1:
        print(f'[FAIL] {tag}: anchor count = {n}')
        sys.exit(1)
    body = body.replace(old, new)
    print(f'[OK] {tag}')

t = t[:i] + body + t[j:]
io.open(CLI, 'w', encoding='utf-8', newline='').write(t)

chk = io.open(CLI, encoding='utf-8', newline='').read()
seg = chk[chk.find('.method public final senseNovaImage('):]
seg = seg[:seg.find('.end method')]
checks = {
    'images/generations 默认路径': 'const-string v11, "images/generations"' in seg,
    'images/edits 切换': 'const-string v11, "images/edits"' in seg,
    'image_url 对象包装': '"image_url"' in seg,
    'response_format b64_json': '"response_format"' in seg and '"b64_json"' in seg,
    'endpoint 用 v11': 'invoke-virtual {v12, v13, v11}, ' in seg,
}
for k, ok in checks.items():
    print('[自检]', 'PASS' if ok else 'FAIL', k)
sys.exit(0 if all(checks.values()) else 1)
