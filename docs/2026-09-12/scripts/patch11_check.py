# -*- coding: utf-8 -*-
"""修正 patch11 自检：只扫 senseNovaImage 方法体。"""
import io
import sys

sys.stdout.reconfigure(encoding='utf-8')
CLI = r'D:\reasonix\hunyuan multiweb\start-apk-work\decoded\smali_classes4\com\wenwei\deepseekproxy\proxy\OpenAiCompatibleClient.smali'
t = io.open(CLI, encoding='utf-8', newline='').read()
i = t.find('.method public final senseNovaImage(')
if i < 0:
    print('[FAIL] helper 未找到')
    sys.exit(1)
j = t.find('.end method', i)
body = t[i:j]
checks = {
    'helper 定义': i >= 0,
    'watermark put(String,Z)': 'put(Ljava/lang/String;Z)' in body,
    'images 字段写入': '"images"' in body,
    'image_data 移除': '"image_data"' in body,
    '无 init 后 move-result（invoke-direct/range 不存在）': 'invoke-direct/range' not in body,
    '循环与标签齐': all(x in body for x in (':goto_loop', ':do_images', ':next_iter', ':use_raw', ':put_img', ':mime_ok', ':has_no_refs')),
    '分支调用在 executeImage': 'senseNovaImage(Lcom/wenwei' in t.split('.method public final senseNovaImage')[0],
}
for k, ok in checks.items():
    print('[自检]', 'PASS' if ok else 'FAIL', k)
sys.exit(0 if all(checks.values()) else 1)
