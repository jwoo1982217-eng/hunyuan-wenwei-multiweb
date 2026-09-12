# -*- coding: utf-8 -*-
"""
patch10b: 修复 injectBuiltinModels 的 VerifyError。
原因（铁律4）：原 helper 中 v0/v1/v2 等寄存器跨类型复用（String/int/Object），且
:cond_ret 出口有两个前驱分支，寄存器类型在合并点冲突（copyRes1 ... type=Conflict）。
修复：从 .bak-step10 恢复后重新应用 patch10（A1/A2/A3/C），helper 全部重写为：
  - 每个寄存器只承载一种类型（String/int/List/J 专存专用，零跨类型复用）
  - 唯一出口 :ret_p1 单前驱；:skip_x 合并点两侧寄存器类型完全一致
运行环境：CRLF；用 Python 处理 smali。
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

# 寄存器分工（locals 16，p0=v16 config，p1=v17 result）：
#   v0 String(baseUrl→后作模型名常量)  v1 Locale→int(总数)  v2 String(小写)→int(聊天数)
#   v3 String("bigmodel.cn")→int(生图数)  v4 int(判定)  v5/v6/v7 List(chat/img/vid)
#   v8 UninitThis  v9 int(success)  v10 String(message)  v11/v12/v13 List  v14/v15 J(latency)
# 附加说明：v2/v3 的 String→int 均为直线重赋值（无合并点），verifier 允许；
# 合并点仅 :skip_img/:skip_v1/:skip_v2（两侧类型一致）与单前驱 :ret_p1。
HELPER = EOL.join([
    '',
    '# patch10: 智谱（bigmodel.cn）/models 只返回 GLM 聊天模型，CogView/CogVideoX 从不进列表；',
    '# 并入内置清单（只增不删，与手填模型合并去重由同步侧 distinct 兜底）。',
    '.method public static injectBuiltinModels(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;)Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;',
    '    .locals 16',
    '',
    '    invoke-virtual/range {v16 .. v16}, Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;->getBaseUrl()Ljava/lang/String;',
    '',
    '    move-result-object v0',
    '',
    '    sget-object v1, Ljava/util/Locale;->ROOT:Ljava/util/Locale;',
    '',
    '    invoke-virtual {v0, v1}, Ljava/lang/String;->toLowerCase(Ljava/util/Locale;)Ljava/lang/String;',
    '',
    '    move-result-object v2',
    '',
    '    const-string v3, "bigmodel.cn"',
    '',
    '    invoke-virtual {v2, v3}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z',
    '',
    '    move-result v4',
    '',
    '    if-eqz v4, :ret_p1',
    '',
    '    invoke-virtual/range {v17 .. v17}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;->getChatModels()Ljava/util/List;',
    '',
    '    move-result-object v5',
    '',
    '    invoke-virtual/range {v17 .. v17}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;->getImageModels()Ljava/util/List;',
    '',
    '    move-result-object v6',
    '',
    '    invoke-virtual/range {v17 .. v17}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;->getVideoModels()Ljava/util/List;',
    '',
    '    move-result-object v7',
    '',
    '    const-string v0, "cogview-3-flash"',
    '',
    '    invoke-interface {v6, v0}, Ljava/util/List;->contains(Ljava/lang/Object;)Z',
    '',
    '    move-result v4',
    '',
    '    if-nez v4, :skip_img',
    '',
    '    invoke-static {v6, v0}, Lkotlin/collections/CollectionsKt;->plus(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/util/List;',
    '',
    '    move-result-object v6',
    '',
    '    :skip_img',
    '    const-string v0, "cogvideox-flash"',
    '',
    '    invoke-interface {v7, v0}, Ljava/util/List;->contains(Ljava/lang/Object;)Z',
    '',
    '    move-result v4',
    '',
    '    if-nez v4, :skip_v1',
    '',
    '    invoke-static {v7, v0}, Lkotlin/collections/CollectionsKt;->plus(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/util/List;',
    '',
    '    move-result-object v7',
    '',
    '    :skip_v1',
    '    const-string v0, "cogvideox-3"',
    '',
    '    invoke-interface {v7, v0}, Ljava/util/List;->contains(Ljava/lang/Object;)Z',
    '',
    '    move-result v4',
    '',
    '    if-nez v4, :skip_v2',
    '',
    '    invoke-static {v7, v0}, Lkotlin/collections/CollectionsKt;->plus(Ljava/lang/Iterable;Ljava/lang/Object;)Ljava/util/List;',
    '',
    '    move-result-object v7',
    '',
    '    :skip_v2',
    '    invoke-static {v6}, Lkotlin/collections/CollectionsKt;->distinct(Ljava/lang/Iterable;)Ljava/util/List;',
    '',
    '    move-result-object v6',
    '',
    '    invoke-static {v7}, Lkotlin/collections/CollectionsKt;->distinct(Ljava/lang/Iterable;)Ljava/util/List;',
    '',
    '    move-result-object v7',
    '',
    '    invoke-interface {v5}, Ljava/util/List;->size()I',
    '',
    '    move-result v2',
    '',
    '    invoke-interface {v6}, Ljava/util/List;->size()I',
    '',
    '    move-result v3',
    '',
    '    invoke-interface {v7}, Ljava/util/List;->size()I',
    '',
    '    move-result v4',
    '',
    '    add-int v1, v2, v3',
    '',
    '    add-int v1, v1, v4',
    '',
    '    new-instance v13, Ljava/lang/StringBuilder;',
    '',
    '    invoke-direct {v13}, Ljava/lang/StringBuilder;-><init>()V',
    '',
    '    const-string v0, "\\u8fde\\u63a5\\u6210\\u529f\\uff0c\\u53d1\\u73b0 "',
    '',
    '    invoke-virtual {v13, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v13, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;',
    '',
    '    const-string v0, " \\u4e2a\\u6a21\\u578b\\uff08\\u804a\\u5929 "',
    '',
    '    invoke-virtual {v13, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v13, v2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;',
    '',
    '    const-string v0, "\\uff0c\\u751f\\u56fe "',
    '',
    '    invoke-virtual {v13, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v13, v3}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;',
    '',
    '    const-string v0, "\\uff0c\\u89c6\\u9891 "',
    '',
    '    invoke-virtual {v13, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v13, v4}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;',
    '',
    '    const-string v0, "\\uff09"',
    '',
    '    invoke-virtual {v13, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;',
    '',
    '    invoke-virtual {v13}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;',
    '',
    '    move-result-object v13',
    '',
    '    invoke-virtual/range {v17 .. v17}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;->getLatencyMs()J',
    '',
    '    move-result-wide v14',
    '',
    '    invoke-virtual/range {v17 .. v17}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;->getSuccess()Z',
    '',
    '    move-result v9',
    '',
    '    move-object v10, v13',
    '',
    '    move-object v11, v5',
    '',
    '    move-object v12, v6',
    '',
    '    move-object v13, v7',
    '',
    '    new-instance v8, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;',
    '',
    '    invoke-direct/range {v8 .. v15}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;-><init>(ZLjava/lang/String;Ljava/util/List;Ljava/util/List;Ljava/util/List;J)V',
    '',
    '    move-result-object v0',
    '',
    '    return-object v0',
    '',
    '    :ret_p1',
    '    return-object v17',
    '.end method',
    '',
])


def apply(path, anchor, insert, tag):
    t = io.open(path, encoding='utf-8', newline='').read()
    if anchor not in t or t.count(anchor) != 1:
        print(f'[FAIL] {tag}: anchor 非唯一或未找到')
        sys.exit(1)
    io.open(path, 'w', encoding='utf-8', newline='').write(t.replace(anchor, insert))
    print(f'[OK] {tag}')


def main():
    # 1) 从 .bak-step10 恢复到补丁前状态（幂等）
    for path in (CLI, T2):
        bak = path + '.bak-step10'
        if not os.path.exists(bak):
            print(f'[FAIL] 缺少备份 {bak}')
            sys.exit(1)
        shutil.copy2(bak, path)
    print('[OK] 已从 .bak-step10 恢复两个 smali')

    # 2) 重新应用 A1/A2/A3（helper 换成 10b 版）/C
    apply(CLI, IMG_ANCHOR, IMG_INSERT, 'A1 生图 modalities')
    apply(CLI, VID_ANCHOR, VID_INSERT, 'A2 视频 modalities')

    cli = io.open(CLI, encoding='utf-8', newline='').read()
    if not cli.endswith(EOL):
        cli += EOL
    cli += HELPER
    io.open(CLI, 'w', encoding='utf-8', newline='').write(cli)
    print('[OK] A3 追加 10b 版 injectBuiltinModels')

    apply(T2, T2_ANCHOR, T2_INSERT, 'C test$2 注入调用')

    cli = io.open(CLI, encoding='utf-8', newline='').read()
    t2 = io.open(T2, encoding='utf-8', newline='').read()
    checks = {
        'A1 模式在文件': 'output_modalities\\":[\\"image' in cli,
        'A2 模式在文件': 'output_modalities\\":[\\"video' in cli,
        'helper 定义': '.method public static injectBuiltinModels(' in cli,
        'helper 旧版残留(.locals 12)': 'injectBuiltinModels' in cli and '.locals 12' in cli.split('injectBuiltinModels')[1][:200],
        '调用点在 test$2': 'injectBuiltinModels(Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;' in t2,
    }
    for k, ok in checks.items():
        print(f'[自检] {"PASS" if ok else "FAIL"} {k}')
    if not all([checks[k] for k in checks if k != 'helper 旧版残留(.locals 12)']):
        sys.exit(1)
    if checks['helper 旧版残留(.locals 12)']:
        print('[FAIL] 旧版 helper 残留')
        sys.exit(1)
    print('patch10b 完成')


if __name__ == '__main__':
    main()
