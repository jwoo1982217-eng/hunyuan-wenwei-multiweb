# -*- coding: utf-8 -*-
"""
patch10: 模型自动发现修复
  A) parseModelList 识别 OpenRouter 风格 output_modalities 字段（商汤 token.sensenova.cn
     的生图模型 sensenova-u1.5-lite / sensenova-u1-fast 靠它声明能力，模型名无 image 关键词）。
     实现：metadata 中把 'output_modalities":["image' 改写为已有关键词 'image_generation'，
     video 同理 —— 零分支改动，仅插入 4 条指令 x2。
  B) 新增静态方法 injectBuiltinModels：baseUrl 含 bigmodel.cn 时并入智谱内置
     生图/视频清单（智谱 /models 实测只返回 GLM 聊天模型，2026-09-12）。
  C) test$2 调用点用 injectBuiltinModels 包住 parseModelList 结果。
运行环境：文件为 CRLF；铁律——不用 PowerShell 碰 smali。
"""
import io
import os
import shutil
import sys

sys.stdout.reconfigure(encoding='utf-8')
D = r'D:\reasonix\hunyuan multiweb\start-apk-work\decoded\smali_classes4\com\wenwei\deepseekproxy\proxy'
CLI = os.path.join(D, 'OpenAiCompatibleClient.smali')
T2 = os.path.join(D, 'OpenAiCompatibleClient$test$2.smali')
EOL = '\r\n'

IMG_ANCHOR = EOL.join([
    '    invoke-static {v11, v12}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullExpressionValue(Ljava/lang/Object;Ljava/lang/String;)V',
    '',
    '    .line 99',
    '    .local v11, "metadata":Ljava/lang/String;',
    '    const-string v12, "text_to_image"',
])
IMG_INSERT = EOL.join([
    '    invoke-static {v11, v12}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullExpressionValue(Ljava/lang/Object;Ljava/lang/String;)V',
    '',
    '    # patch10: output_modalities:["image.."] -> image_generation 标记（商汤/OpenRouter 风格声明）',
    '    const-string v12, "output_modalities\\":[\\"image"',
    '    const-string v13, "image_generation"',
    '    invoke-virtual {v11, v12, v13}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;',
    '    move-result-object v11',
    '',
    '    .line 99',
    '    .local v11, "metadata":Ljava/lang/String;',
    '    const-string v12, "text_to_image"',
])

VID_ANCHOR = EOL.join([
    '    .local v2, "explicitImage":Z',
    '    const-string v4, "text_to_video"',
])
VID_INSERT = EOL.join([
    '    .local v2, "explicitImage":Z',
    '    # patch10: output_modalities:["video.."] -> video_generation 标记',
    '    const-string v12, "output_modalities\\":[\\"video"',
    '    const-string v13, "video_generation"',
    '    invoke-virtual {v11, v12, v13}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;',
    '    move-result-object v11',
    '    const-string v4, "text_to_video"',
])

T2_ANCHOR = EOL.join([
    '    move-result-object v6',
    '',
    '    invoke-virtual {v5, v0, v6}, Lcom/wenwei/deepseekproxy/proxy/OpenAiCompatibleClient;->normalizeProviderModels$app_proxy(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;)Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;',
])
T2_INSERT = EOL.join([
    '    move-result-object v6',
    '',
    '    # patch10: 智谱 /models 不含 cogview/cogvideox —— 并入内置清单',
    '    invoke-static {v0, v6}, Lcom/wenwei/deepseekproxy/proxy/OpenAiCompatibleClient;->injectBuiltinModels(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;)Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;',
    '',
    '    move-result-object v6',
    '',
    '    invoke-virtual {v5, v0, v6}, Lcom/wenwei/deepseekproxy/proxy/OpenAiCompatibleClient;->normalizeProviderModels$app_proxy(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;)Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;',
])

HELPER = EOL.join([
    '',
    '# patch10: 智谱（bigmodel.cn）/models 只返回 GLM 聊天模型，CogView/CogVideoX 从不进列表；',
    '# 并入内置清单（只增不删，与手填模型合并去重由同步侧 distinct 兜底）。',
    '.method public static injectBuiltinModels(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;)Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;',
    '    .locals 12',
    '',
    '    invoke-virtual {v12}, Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;->getBaseUrl()Ljava/lang/String;',
    '',
    '    move-result-object v0',
    '',
    '    if-eqz v0, :cond_ret',
    '',
    '    sget-object v1, Ljava/util/Locale;->ROOT:Ljava/util/Locale;',
    '',
    '    invoke-virtual {v0, v1}, Ljava/lang/String;->toLowerCase(Ljava/util/Locale;)Ljava/lang/String;',
    '',
    '    move-result-object v0',
    '',
    '    const-string v1, "bigmodel.cn"',
    '',
    '    invoke-virtual {v0, v1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z',
    '',
    '    move-result v0',
    '',
    '    if-eqz v0, :cond_ret',
    '',
    '    invoke-virtual {v13}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;->getChatModels()Ljava/util/List;',
    '',
    '    move-result-object v4',
    '',
    '    invoke-virtual {v13}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;->getImageModels()Ljava/util/List;',
    '',
    '    move-result-object v5',
    '',
    '    invoke-virtual {v13}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;->getVideoModels()Ljava/util/List;',
    '',
    '    move-result-object v6',
    '',
    '    const-string v9, "cogview-3-flash"',
    '',
    '    invoke-interface {v5, v9}, Ljava/util/List;->contains(Ljava/lang/Object;)Z',
    '',
    '    move-result v10',
    '',
    '    if-nez v10, :skip_img',
    '',
    '    invoke-static {v5, v9}, Lkotlin/collections/CollectionsKt;->plus(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/util/List;',
    '',
    '    move-result-object v5',
    '',
    '    :skip_img',
    '    const-string v9, "cogvideox-flash"',
    '',
    '    invoke-interface {v6, v9}, Ljava/util/List;->contains(Ljava/lang/Object;)Z',
    '',
    '    move-result v10',
    '',
    '    if-nez v10, :skip_v1',
    '',
    '    invoke-static {v6, v9}, Lkotlin/collections/CollectionsKt;->plus(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/util/List;',
    '',
    '    move-result-object v6',
    '',
    '    :skip_v1',
    '    const-string v9, "cogvideox-3"',
    '',
    '    invoke-interface {v6, v9}, Ljava/util/List;->contains(Ljava/lang/Object;)Z',
    '',
    '    move-result v10',
    '',
    '    if-nez v10, :skip_v2',
    '',
    '    invoke-static {v6, v9}, Lkotlin/collections/CollectionsKt;->plus(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/util/List;',
    '',
    '    move-result-object v6',
    '',
    '    :skip_v2',
    '    invoke-static {v5}, Lkotlin/collections/CollectionsKt;->distinct(Ljava/lang/Iterable;)Ljava/util/List;',
    '',
    '    move-result-object v5',
    '',
    '    invoke-static {v6}, Lkotlin/collections/CollectionsKt;->distinct(Ljava/lang/Iterable;)Ljava/util/List;',
    '',
    '    move-result-object v6',
    '',
    '    invoke-interface {v4}, Ljava/util/List;->size()I',
    '',
    '    move-result v9',
    '',
    '    invoke-interface {v5}, Ljava/util/List;->size()I',
    '',
    '    move-result v10',
    '',
    '    invoke-interface {v6}, Ljava/util/List;->size()I',
    '',
    '    move-result v11',
    '',
    '    add-int v0, v9, v10',
    '',
    '    add-int v0, v0, v11',
    '',
    '    new-instance v1, Ljava/lang/StringBuilder;',
    '',
    '    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V',
    '',
    '    const-string v2, "\\u8fde\\u63a5\\u6210\\u529f\\uff0c\\u53d1\\u73b0 "',
    '',
    '    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;',
    '',
    '    const-string v2, " \\u4e2a\\u6a21\\u578b\\uff08\\u804a\\u5929 "',
    '',
    '    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v1, v9}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;',
    '',
    '    const-string v2, "\\uff0c\\u751f\\u56fe "',
    '',
    '    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v1, v10}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;',
    '',
    '    const-string v2, "\\uff0c\\u89c6\\u9891 "',
    '',
    '    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v1, v11}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;',
    '',
    '    const-string v2, "\\uff09"',
    '',
    '    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;',
    '',
    '    move-result-object v3',
    '',
    '    invoke-virtual {v13}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;->getSuccess()Z',
    '',
    '    move-result v2',
    '',
    '    invoke-virtual {v13}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;->getLatencyMs()J',
    '',
    '    move-result-wide v7',
    '',
    '    new-instance v1, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;',
    '',
    '    invoke-direct/range {v1 .. v8}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;-><init>(ZLjava/lang/String;Ljava/util/List;Ljava/util/List;Ljava/util/List;J)V',
    '',
    '    move-result-object v0',
    '',
    '    return-object v0',
    '',
    '    :cond_ret',
    '    return-object v13',
    '.end method',
    '',
])


def apply(path, anchor, insert, tag):
    t = io.open(path, encoding='utf-8', newline='').read()
    if anchor not in t:
        print(f'[FAIL] {tag}: anchor 未找到（可能已打过补丁或版本漂移）')
        sys.exit(1)
    if t.count(anchor) != 1:
        print(f'[FAIL] {tag}: anchor 出现 {t.count(anchor)} 次，需唯一')
        sys.exit(1)
    t = t.replace(anchor, insert)
    io.open(path, 'w', encoding='utf-8', newline='').write(t)
    print(f'[OK] {tag}')


def main():
    for path in (CLI, T2):
        bak = path + '.bak-step10'
        if not os.path.exists(bak):
            shutil.copy2(path, bak)
            print(f'[备份] {os.path.basename(bak)}')

    cli = io.open(CLI, encoding='utf-8', newline='').read()
    if 'injectBuiltinModels' in cli or 'output_modalities' in cli:
        print('[中止] patch10 似乎已应用过（文件中已有标记）')
        sys.exit(1)

    apply(CLI, IMG_ANCHOR, IMG_INSERT, 'A1 parseModelList 生图 modalities')
    apply(CLI, VID_ANCHOR, VID_INSERT, 'A2 parseModelList 视频 modalities')

    # 追加 helper 到文件末尾（保持 CRLF，若文件末尾无换行则补一个）
    cli = io.open(CLI, encoding='utf-8', newline='').read()
    if not cli.endswith(EOL):
        cli += EOL
    cli += HELPER
    io.open(CLI, 'w', encoding='utf-8', newline='').write(cli)
    print('[OK] A3 追加 injectBuiltinModels 方法')

    apply(T2, T2_ANCHOR, T2_INSERT, 'C  test$2 注入调用')

    # 自检
    cli = io.open(CLI, encoding='utf-8', newline='').read()
    t2 = io.open(T2, encoding='utf-8', newline='').read()
    checks = {
        'client: output_modalities image 模式': 'output_modalities\\":[\\"image' in cli,
        'client: output_modalities video 模式': 'output_modalities\\":[\\"video' in cli,
        'client: injectBuiltinModels 定义': '.method public static injectBuiltinModels(' in cli,
        'test$2: injectBuiltinModels 调用': 'injectBuiltinModels(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;)' in t2,
    }
    for k, ok in checks.items():
        print(f'[自检] {"PASS" if ok else "FAIL"} {k}')
    if not all(checks.values()):
        sys.exit(1)
    print('patch10 全部完成')


if __name__ == '__main__':
    main()
