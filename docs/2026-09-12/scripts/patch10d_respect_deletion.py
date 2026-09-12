# -*- coding: utf-8 -*-
"""
patch10d: 修复"删掉的内置模型被同步加回"回归。
规则：仅当该供应商当前 生图/视频 列表为空（=全新条目）时才注入内置清单；
列表非空视为用户自管，完全尊重增删（配置侧非空列表本来就由同步的
config.videoModels + ... 合并保留，无需 helper 回灌）。
边界：把某类列表删到空再保存，会被重新填充（与"新建条目"不可区分，无 tombstone）。
实现：helper 里给生图注入包 isEmpty(config.imageModels) guard，视频同理。
寄存器：v11 临时 List（窗口填充前空闲），v4 临时 int。
"""
import io
import sys

sys.stdout.reconfigure(encoding='utf-8')
CLI = r'D:\reasonix\hunyuan multiweb\start-apk-work\decoded\smali_classes4\com\wenwei\deepseekproxy\proxy\OpenAiCompatibleClient.smali'
EOL = '\r\n'

OLD = EOL.join([
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
])

NEW = EOL.join([
    '    # patch10d: 仅当该供应商当前生图列表为空（新条目）时注入；非空=用户自管，不回灌',
    '    invoke-virtual/range {v16 .. v16}, Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;->getImageModels()Ljava/util/List;',
    '',
    '    move-result-object v11',
    '',
    '    invoke-interface {v11}, Ljava/util/List;->isEmpty()Z',
    '',
    '    move-result v4',
    '',
    '    if-eqz v4, :skip_img_all',
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
    '    :skip_img_all',
    '    invoke-virtual/range {v16 .. v16}, Lcom/wenwei/deepseekproxy/proxy/ApiProviderConfig;->getVideoModels()Ljava/util/List;',
    '',
    '    move-result-object v11',
    '',
    '    invoke-interface {v11}, Ljava/util/List;->isEmpty()Z',
    '',
    '    move-result v4',
    '',
    '    if-eqz v4, :skip_vid_all',
    '',
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
    '    :skip_vid_all',
])

t = io.open(CLI, encoding='utf-8', newline='').read()
if OLD not in t or t.count(OLD) != 1:
    print('[FAIL] 锚点未找到或非唯一，count =', t.count(OLD))
    sys.exit(1)
io.open(CLI, 'w', encoding='utf-8', newline='').write(t.replace(OLD, NEW))
print('[OK] patch10d 已应用')

chk = io.open(CLI, encoding='utf-8', newline='').read()
seg = chk.split('injectBuiltinModels', 1)[1][:6000]
checks = {
    '生图 guard': 'getImageModels()Ljava/util/List;' in seg and 'isEmpty()Z' in seg,
    '视频 guard': seg.count('getVideoModels()Ljava/util/List;') >= 1 and seg.count('isEmpty()Z') >= 2,
    'guard 标签': ':skip_img_all' in seg and ':skip_vid_all' in seg,
}
for k, ok in checks.items():
    print('[自检]', 'PASS' if ok else 'FAIL', k)
sys.exit(0 if all(checks.values()) else 1)
