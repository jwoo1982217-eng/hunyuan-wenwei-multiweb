# -*- coding: utf-8 -*-
"""
patch14: 中转 tab 豆包视频接通网页端新协议（seedance V2）。
- 新增 DoubaoVideoV2.smali（Java 编译 javac→d8→baksmali，见 docs/2026-09-12 报告）：
  POST /chat/completion（chat_ability.ability_type=17 + seedance_v2.0_mini，两次发送）
  → 轮询 /im/chain/single 拿 vid → /creativity/resource/get_without_watermark 换无水印直链。
- ProxyServer.smali 调用点：无参考图时走 V2，有参考图保留旧路径。
"""
import io
import shutil
import sys

sys.stdout.reconfigure(encoding='utf-8')
D = r'D:\reasonix\hunyuan multiweb\start-apk-work\decoded\smali_classes4\com\wenwei\deepseekproxy\proxy'
V2_SRC = r'D:\reasonix\hunyuan multiweb\tmp_javaclass\smali_out\smali\com\wenwei\deepseekproxy\proxy\DoubaoVideoV2.smali'
PS = D + r'\ProxyServer.smali'
EOL = '\r\n'

OLD = EOL.join([
    '    sget-object v0, Lcom/wenwei/deepseekproxy/proxy/DoubaoClient;->INSTANCE:Lcom/wenwei/deepseekproxy/proxy/DoubaoClient;',
    '',
    '    const/4 v5, 0x0',
    '',
    '    invoke-virtual {v0, v7, v8, v5, v15}, Lcom/wenwei/deepseekproxy/proxy/DoubaoClient;->generateVideo(Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Ljava/util/List;)Lcom/wenwei/deepseekproxy/proxy/DoubaoClient$DoubaoVideoResult;',
    '',
    '    move-result-object v0',
    '',
    '    move-object v3, v0',
])
NEW = EOL.join([
    '    invoke-interface {v15}, Ljava/util/List;->isEmpty()Z',
    '',
    '    move-result v0',
    '',
    '    if-eqz v0, :cond_dv_old',
    '',
    '    invoke-static {v8, v7}, Lcom/wenwei/deepseekproxy/proxy/DoubaoVideoV2;->generate(Ljava/util/Map;Ljava/lang/String;)Ljava/lang/Object;',
    '',
    '    move-result-object v0',
    '',
    '    check-cast v0, Lcom/wenwei/deepseekproxy/proxy/DoubaoClient$DoubaoVideoResult;',
    '',
    '    move-object v3, v0',
    '',
    '    goto :goto_dv_done',
    '',
    '    :cond_dv_old',
    '    sget-object v0, Lcom/wenwei/deepseekproxy/proxy/DoubaoClient;->INSTANCE:Lcom/wenwei/deepseekproxy/proxy/DoubaoClient;',
    '',
    '    const/4 v5, 0x0',
    '',
    '    invoke-virtual {v0, v7, v8, v5, v15}, Lcom/wenwei/deepseekproxy/proxy/DoubaoClient;->generateVideo(Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Ljava/util/List;)Lcom/wenwei/deepseekproxy/proxy/DoubaoClient$DoubaoVideoResult;',
    '',
    '    move-result-object v0',
    '',
    '    move-object v3, v0',
    '',
    '    :goto_dv_done',
])


def main():
    bak = PS + '.bak-step14'
    if not os.path.exists(bak):
        shutil.copy2(PS, bak)
        print('[备份] ProxyServer.smali.bak-step14')

    t = io.open(PS, encoding='utf-8', newline='').read()
    if 'DoubaoVideoV2' in t:
        print('[中止] patch14 已应用')
        sys.exit(1)
    if OLD not in t or t.count(OLD) != 1:
        print('[FAIL] 调用点锚点 count =', t.count(OLD))
        sys.exit(1)
    t = t.replace(OLD, NEW)
    io.open(PS, 'w', encoding='utf-8', newline='').write(t)
    print('[OK] 调用点已替换')

    shutil.copy2(V2_SRC, D + r'\DoubaoVideoV2.smali')
    print('[OK] DoubaoVideoV2.smali 已复制')

    chk = io.open(PS, encoding='utf-8', newline='').read()
    v2 = io.open(D + r'\DoubaoVideoV2.smali', encoding='utf-8', newline='').read()
    checks = {
        '调用点分支': ':cond_dv_old' in chk and ':goto_dv_done' in chk and 'DoubaoVideoV2;->generate(' in chk,
        'V2 类定义': '.class public final Lcom/wenwei/deepseekproxy/proxy/DoubaoVideoV2;' in v2,
        'V2 generate 方法': '.method public static generate(Ljava/util/Map;Ljava/lang/String;)Ljava/lang/Object;' in v2,
        '旧路径保留': chk.count('generateVideo(Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Ljava/util/List;)') == 1,
    }
    for k, ok in checks.items():
        print('[自检]', 'PASS' if ok else 'FAIL', k)
    sys.exit(0 if all(checks.values()) else 1)


import os
main()
