# -*- coding: utf-8 -*-
"""
patch10c: 构造器 invoke-direct 后面不能跟 move-result（<init> 返回 void，
result 寄存器未被赋值 → verifier 报 copyRes1 v0<- result0 type=Conflict）。
正确写法参照同文件 parseModelList 尾部：invoke-direct/range 后直接 return-object v8。
"""
import io
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')
CLI = r'D:\reasonix\hunyuan multiweb\start-apk-work\decoded\smali_classes4\com\wenwei\deepseekproxy\proxy\OpenAiCompatibleClient.smali'
EOL = '\r\n'

OLD = EOL.join([
    '    invoke-direct/range {v8 .. v15}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;-><init>(ZLjava/lang/String;Ljava/util/List;Ljava/util/List;Ljava/util/List;J)V',
    '',
    '    move-result-object v0',
    '',
    '    return-object v0',
])
NEW = EOL.join([
    '    invoke-direct/range {v8 .. v15}, Lcom/wenwei/deepseekproxy/proxy/ApiTestResult;-><init>(ZLjava/lang/String;Ljava/util/List;Ljava/util/List;Ljava/util/List;J)V',
    '',
    '    return-object v8',
])

t = io.open(CLI, encoding='utf-8', newline='').read()
if OLD in t and t.count(OLD) == 1:
    io.open(CLI, 'w', encoding='utf-8', newline='').write(t.replace(OLD, NEW))
    print('[OK] 已移除 <init> 后多余的 move-result-object')
else:
    print('[FAIL] 锚点未找到或非唯一，count =', t.count(OLD))
    sys.exit(1)

chk = io.open(CLI, encoding='utf-8', newline='').read()
ok = 'return-object v8' in chk.split('injectBuiltinModels')[1]
print('[自检]', 'PASS' if ok else 'FAIL', 'helper 尾部为 return-object v8')
sys.exit(0 if ok else 1)
