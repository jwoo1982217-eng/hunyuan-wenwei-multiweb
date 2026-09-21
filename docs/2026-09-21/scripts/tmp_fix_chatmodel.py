# -*- coding: utf-8 -*-
"""chat_model 从 hy3-preview（混元3/CNB）切到 workbuddy-cn（WorkBuddy国内 Web）"""
import io, re, subprocess, sys, time
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ADB = r'D:\android-sdk\platform-tools\adb.exe'
PKG = 'com.wenwei.deepseekproxy.multiweb'
LOCAL = r'D:\reasonix\hunyuan multiweb'
NEW_MODEL = 'workbuddy-cn'

for serial in ('127.0.0.1:5555', 'emulator-5554'):
    print(f'===== {serial}')
    r = subprocess.run([ADB, '-s', serial, 'shell', 'run-as', PKG, 'cat',
                        'shared_prefs/ds_proxy.xml'],
                       capture_output=True, text=True, errors='replace')
    xml = r.stdout
    if 'name="chat_model"' not in xml:
        print('  chat_model 键不存在，跳过')
        continue
    xml2 = re.sub(r'(name="chat_model"[^>]*>)[^<]*(</string>)',
                  lambda m: m.group(1) + NEW_MODEL + m.group(2), xml)
    assert xml2 != xml
    io.open(LOCAL + '\\tmp_dsproxy_model.xml', 'w', encoding='utf-8', newline='\n').write(xml2)
    subprocess.run([ADB, '-s', serial, 'shell', 'am', 'force-stop', PKG],
                   capture_output=True, timeout=30)
    time.sleep(1)
    subprocess.run([ADB, '-s', serial, 'push', LOCAL + '\\tmp_dsproxy_model.xml',
                    '/data/local/tmp/dsproxy_model.xml'], capture_output=True, timeout=60)
    subprocess.run([ADB, '-s', serial, 'shell', 'run-as', PKG, 'cp',
                    '/data/local/tmp/dsproxy_model.xml', 'shared_prefs/ds_proxy.xml'],
                   capture_output=True, timeout=30)
    v = subprocess.run([ADB, '-s', serial, 'shell', 'run-as', PKG, 'cat',
                        'shared_prefs/ds_proxy.xml'],
                       capture_output=True, text=True, errors='replace').stdout
    m = re.search(r'name="chat_model"[^>]*>([^<]*)<', v)
    print(f'  chat_model 现为: {m.group(1) if m else "?"}')
    subprocess.run([ADB, '-s', serial, 'shell', 'am', 'start', '-n',
                    PKG + '/com.wenwei.deepseekproxy.MainActivity'],
                   capture_output=True, timeout=30)
    print('  App 已重启')