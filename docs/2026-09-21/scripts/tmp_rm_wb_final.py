# -*- coding: utf-8 -*-
"""彻底移除两台设备 providers_v1 的 WorkBuddy国内/国际 条目"""
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
    kept = [p for p in arr if not (p.get('name') or '').startswith('WorkBuddy')]
    removed = [p.get('name') for p in arr if (p.get('name') or '').startswith('WorkBuddy')]
    print('  移除:', removed or '(无)')
    if not removed:
        continue
    sh(serial, f'run-as {PKG} cp shared_prefs/api_provider_configs.xml '
               f'shared_prefs/api_provider_configs.xml.bak-rmwb3')
    js = json.dumps(kept, ensure_ascii=False, separators=(', ', ': '))
    js_xml = (js.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
              .replace('"', '&quot;').replace("'", '&apos;'))
    xml2 = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            '    <string name="providers_v1">' + js_xml + '</string>\n</map>\n')
    io.open(LOCAL + '\\tmp_providers_rm3.xml', 'w', encoding='utf-8', newline='\n').write(xml2)
    subprocess.run([ADB, '-s', serial, 'push', LOCAL + '\\tmp_providers_rm3.xml',
                    '/data/local/tmp/prov_rm3.xml'], capture_output=True, timeout=60)
    sh(serial, f'run-as {PKG} cp /data/local/tmp/prov_rm3.xml shared_prefs/api_provider_configs.xml')
    v = sh(serial, f'run-as {PKG} cat shared_prefs/api_provider_configs.xml')
    arr2 = json.loads((re.search(r'<string name="providers_v1">(.*?)</string>', v, re.S).group(1)
                       .replace('&quot;', '"').replace('&apos;', "'")
                       .replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&')))
    print('  剩余 providers:', [p['name'] for p in arr2])
    # custom_web 入口确认
    c = sh(serial, f'run-as {PKG} cat shared_prefs/custom_web_providers.xml')
    cnames = re.findall(r'&quot;name&quot;: &quot;([^&]+)&quot;', c)
    print('  custom_web 入口（保留）:', [n for n in cnames if 'WorkBuddy' in n])
    sh(serial, f'am start -n {PKG}/com.wenwei.deepseekproxy.MainActivity', 5)
    print('  App 已重启')