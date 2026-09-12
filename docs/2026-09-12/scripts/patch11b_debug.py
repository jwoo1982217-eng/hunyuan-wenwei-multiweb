# -*- coding: utf-8 -*-
"""patch11b: 给 patch11 加诊断日志（分支命中 / raw 是否含 image_data / images 数组长度）。"""
import io
import sys

sys.stdout.reconfigure(encoding='utf-8')
CLI = r'D:\reasonix\hunyuan multiweb\start-apk-work\decoded\smali_classes4\com\wenwei\deepseekproxy\proxy\OpenAiCompatibleClient.smali'
EOL = '\r\n'

OLD_BRANCH = EOL.join([
    '    if-eqz v0, :cond_sense_skip',
    '',
    '    invoke-virtual/range {v1 .. v4}, Lcom/wenwei/deepseekproxy/proxy/OpenAiCompatibleClient;->senseNovaImage(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Ljava/lang/String;Ljava/lang/String;)Lokhttp3/Response;',
])
NEW_BRANCH = EOL.join([
    '    if-eqz v0, :cond_sense_skip',
    '',
    '    const-string v0, "ImageRoute"',
    '',
    '    const-string v5, "patch11: sensenova branch hit"',
    '',
    '    invoke-static {v0, v5}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I',
    '',
    '    new-instance v5, Ljava/lang/StringBuilder;',
    '',
    '    invoke-direct {v5}, Ljava/lang/StringBuilder;-><init>()V',
    '',
    '    const-string v6, "patch11: raw has image_data="',
    '',
    '    invoke-virtual {v5, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    const-string v6, "\\"image_data\\""',
    '',
    '    invoke-virtual {v4, v6}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z',
    '',
    '    move-result v7',
    '',
    '    invoke-virtual {v5, v7}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v5}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;',
    '',
    '    move-result-object v5',
    '',
    '    const-string v6, "ImageRoute"',
    '',
    '    invoke-static {v6, v5}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I',
    '',
    '    invoke-virtual/range {v1 .. v4}, Lcom/wenwei/deepseekproxy/proxy/OpenAiCompatibleClient;->senseNovaImage(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Ljava/lang/String;Ljava/lang/String;)Lokhttp3/Response;',
])

OLD_LOOP_END = EOL.join([
    '    :do_images',
    '    const-string v10, "images"',
    '',
    '    invoke-virtual {v0, v10, v1}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;',
])
NEW_LOOP_END = EOL.join([
    '    :do_images',
    '    const-string v10, "ImageRoute"',
    '',
    '    new-instance v8, Ljava/lang/StringBuilder;',
    '',
    '    invoke-direct {v8}, Ljava/lang/StringBuilder;-><init>()V',
    '',
    '    const-string v5, "patch11: senseNovaImage images len="',
    '',
    '    invoke-virtual {v8, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v1}, Lorg/json/JSONArray;->length()I',
    '',
    '    move-result v9',
    '',
    '    invoke-virtual {v8, v9}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v8}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;',
    '',
    '    move-result-object v8',
    '',
    '    invoke-static {v10, v8}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I',
    '',
    '    const-string v10, "images"',
    '',
    '    invoke-virtual {v0, v10, v1}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;',
])


def apply(path, old, new, tag):
    t = io.open(path, encoding='utf-8', newline='').read()
    if old not in t or t.count(old) != 1:
        print(f'[FAIL] {tag}: anchor count = {t.count(old)}')
        sys.exit(1)
    io.open(path, 'w', encoding='utf-8', newline='').write(t.replace(old, new))
    print(f'[OK] {tag}')


apply(CLI, OLD_BRANCH, NEW_BRANCH, '分支日志')
apply(CLI, OLD_LOOP_END, NEW_LOOP_END, 'helper images 长度日志')
print('patch11b 完成')
