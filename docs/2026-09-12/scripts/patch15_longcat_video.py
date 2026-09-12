# -*- coding: utf-8 -*-
"""
patch15: 中转 tab 美团（LongCat）视频接通 HTTP 新协议。
- 新增 LongCatVideoV2.smali（Java 编译 javac→d8→baksmali）：
  Cookie 现取自 App 全局 CookieManager（浏览器 tab 登录 longcat.chat 即可，无需保存会话）；
  POST /api/v1/chat-completion-V2（agentId=genVideo）拿 taskId → 轮询
  /api/v1/task-check → SUCCESS 取 extra[].content[].url（s3plus 公开 CDN mp4）。
- CustomWebVideoAutomation.generateVideo 入口：config.loginUrl 含 longcat.chat 时
  直接调 LongCatVideoV2.generate(prompt) 返回，不再进 WebView 页面自动化。
"""
import io
import os
import shutil
import sys

sys.stdout.reconfigure(encoding='utf-8')
D = r'D:\reasonix\hunyuan multiweb\start-apk-work\decoded\smali_classes4\com\wenwei\deepseekproxy\proxy'
CWVA = D + r'\CustomWebVideoAutomation.smali'
V2_SRC = r'D:\reasonix\hunyuan multiweb\tmp_javaclass\smali_out\smali\com\wenwei\deepseekproxy\proxy\LongCatVideoV2.smali'
EOL = '\r\n'

OLD = EOL.join([
    '    .end annotation',
    '',
    '    move-object/from16 v1, p4',
])
NEW = EOL.join([
    '    .end annotation',
    '',
    '    # patch15: 美团（LongCat）走 HTTP 新协议，Cookie 现取 CookieManager',
    '    invoke-virtual {p1}, Lcom/wenwei/deepseekproxy/proxy/CustomWebProviderConfig;->getLoginUrl()Ljava/lang/String;',
    '',
    '    move-result-object v0',
    '',
    '    const-string v5, "longcat.chat"',
    '',
    '    invoke-virtual {v0, v5}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z',
    '',
    '    move-result v0',
    '',
    '    if-eqz v0, :cond_lc_http_skip',
    '',
    '    invoke-static {p2}, Lcom/wenwei/deepseekproxy/proxy/LongCatVideoV2;->generate(Ljava/lang/String;)Ljava/util/List;',
    '',
    '    move-result-object v0',
    '',
    '    return-object v0',
    '',
    '    :cond_lc_http_skip',
    '    move-object/from16 v1, p4',
])


def main():
    bak = CWVA + '.bak-step15'
    if not os.path.exists(bak):
        shutil.copy2(CWVA, bak)
        print('[备份] CustomWebVideoAutomation.smali.bak-step15')

    t = io.open(CWVA, encoding='utf-8', newline='').read()
    if 'LongCatVideoV2' in t:
        print('[中止] patch15 已应用')
        sys.exit(1)
    if OLD not in t or t.count(OLD) != 1:
        print('[FAIL] 锚点 count =', t.count(OLD))
        sys.exit(1)
    t = t.replace(OLD, NEW)
    io.open(CWVA, 'w', encoding='utf-8', newline='').write(t)
    print('[OK] generateVideo 入口 LongCat 分支')

    shutil.copy2(V2_SRC, D + r'\LongCatVideoV2.smali')
    print('[OK] LongCatVideoV2.smali 已复制')

    chk = io.open(CWVA, encoding='utf-8', newline='').read()
    v2 = io.open(D + r'\LongCatVideoV2.smali', encoding='utf-8', newline='').read()
    checks = {
        '入口分支': ':cond_lc_http_skip' in chk and 'LongCatVideoV2;->generate(Ljava/lang/String;)Ljava/util/List;' in chk,
        'V2 类定义': '.class public final Lcom/wenwei/deepseekproxy/proxy/LongCatVideoV2;' in v2,
        'V2 generate 方法': '.method public static generate(Ljava/lang/String;)Ljava/util/List;' in v2,
        'getLoginUrl 调用': 'CustomWebProviderConfig;->getLoginUrl()Ljava/lang/String;' in chk,
    }
    for k, ok in checks.items():
        print('[自检]', 'PASS' if ok else 'FAIL', k)
    sys.exit(0 if all(checks.values()) else 1)


main()
