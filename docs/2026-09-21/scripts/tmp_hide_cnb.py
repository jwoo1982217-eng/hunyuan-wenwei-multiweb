# -*- coding: utf-8 -*-
"""A 方案：把 CNB 加入 hidden_web_providers.xml（LDPlayer + emulator 双设备）"""
import io, re, subprocess, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ADB = r'D:\android-sdk\platform-tools\adb.exe'
PKG = 'com.wenwei.deepseekproxy.multiweb'
LOCAL = r'D:\reasonix\hunyuan multiweb'
TARGET = 'CNB'

def sh(serial, *args):
    return subprocess.run([ADB, '-s', serial] + list(args), capture_output=True, text=True, errors='replace')

for serial in ('127.0.0.1:5555', 'emulator-5554'):
    print(f'===== {serial}')
    r = sh(serial, 'shell', 'run-as', PKG, 'cat', 'shared_prefs/hidden_web_providers.xml')
    s = r.stdout
    if not s.strip():
        print('  读取失败（可能无该文件），跳过')
        continue
    if f'<string>{TARGET}</string>' in s:
        print('  已包含 CNB，无需修改')
        continue
    # 备份到本地 + 设备侧
    io.open(LOCAL + f'\\tmp_hidden_{serial.replace(":", "_").replace(".", "_")}.xml', 'w', encoding='utf-8').write(s)
    sh(serial, 'shell', 'run-as', PKG, 'cp',
       'shared_prefs/hidden_web_providers.xml',
       f'shared_prefs/hidden_web_providers.xml.bak-hidecnb')
    # 插入 CNB（放在 </set> 前）
    s2 = s.replace('</set>', f'        <string>{TARGET}</string>\n    </set>')
    assert s2 != s, '替换失败'
    local = LOCAL + '\\tmp_hidden_new.xml'
    io.open(local, 'w', encoding='utf-8', newline='\n').write(s2)
    sh(serial, 'push', local, '/data/local/tmp/hidden_new.xml')
    sh(serial, 'shell', 'run-as', PKG, 'cp', '/data/local/tmp/hidden_new.xml',
       'shared_prefs/hidden_web_providers.xml')
    # 验证
    v = sh(serial, 'shell', 'run-as', PKG, 'cat', 'shared_prefs/hidden_web_providers.xml').stdout
    ok = f'<string>{TARGET}</string>' in v
    print('  写入 CNB 成功:', ok)
    if ok:
        sh(serial, 'shell', 'am', 'force-stop', PKG)
        print('  App 已重启（下次打开生效）')