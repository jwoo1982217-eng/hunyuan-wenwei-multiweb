# -*- coding: utf-8 -*-
"""清空两台设备 WorkBuddy 条目的 apiKey（去掉我的 token），保留 baseUrl+模型清单模板"""
import io, json, re, subprocess, sys, time
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ADB = r'D:\android-sdk\platform-tools\adb.exe'
PKG = 'com.wenwei.deepseekproxy.multiweb'
LOCAL = r'D:\reasonix\hunyuan multiweb'

def sh(serial, cmd, wait=0):
    if wait:
        time.sleep(wait)
    return subprocess.run([ADB, '-s', serial, 'shell', cmd], capture_output=True, text=True, errors='replace').stdout

for serial in ('127.0.0.1:5555', 'emulator-5554'):
    print(f'===== {serial}')
    sh(serial, f'am force-stop {PKG}', 2)
    xml = sh(serial, f'run-as {PKG} cat shared_prefs/api_provider_configs.xml')
    m = re.search(r'<string name="providers_v1">(.*?)</string>', xml, re.S)
    if not m:
        print('  providers_v1 未找到')
        continue
    raw = (m.group(1).replace('&quot;', '"').replace('&apos;', "'")
           .replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&'))
    arr = json.loads(raw)
    changed = False
    for p in arr:
        if (p.get('name') or '').startswith('WorkBuddy') and (p.get('apiKey') or ''):
            print(f"  清空 {p['name']} 的 apiKey（原 len={len(p['apiKey'])}）")
            p['apiKey'] = ''
            changed = True
    if not changed:
        print('  apiKey 均为空，无需处理')
        continue
    # 设备侧备份
    sh(serial, f'run-as {PKG} cp shared_prefs/api_provider_configs.xml '
               f'shared_prefs/api_provider_configs.xml.bak-clearkey')
    js = json.dumps(arr, ensure_ascii=False, separators=(', ', ': '))
    js_xml = (js.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
              .replace('"', '&quot;').replace("'", '&apos;'))
    xml2 = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            '    <string name="providers_v1">' + js_xml + '</string>\n</map>\n')
    io.open(LOCAL + '\\tmp_providers_clearkey.xml', 'w', encoding='utf-8', newline='\n').write(xml2)
    subprocess.run([ADB, '-s', serial, 'push', LOCAL + '\\tmp_providers_clearkey.xml',
                    '/data/local/tmp/prov_ck.xml'], capture_output=True, timeout=60)
    sh(serial, f'run-as {PKG} cp /data/local/tmp/prov_ck.xml shared_prefs/api_provider_configs.xml')
    # 验证
    v = sh(serial, f'run-as {PKG} cat shared_prefs/api_provider_configs.xml')
    arr2 = json.loads((re.search(r'<string name="providers_v1">(.*?)</string>', v, re.S).group(1)
                       .replace('&quot;', '"').replace('&apos;', "'")
                       .replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&')))
    for p in arr2:
        if (p.get('name') or '').startswith('WorkBuddy'):
            print(f"  {p['name']}: apiKeyLen={len(p.get('apiKey') or '')} models={len(p.get('chatModels') or [])} baseUrl={p.get('baseUrl')}")
    sh(serial, f'am start -n {PKG}/com.wenwei.deepseekproxy.MainActivity', 5)
    print('  App 已重启')