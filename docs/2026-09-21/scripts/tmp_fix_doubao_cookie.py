# -*- coding: utf-8 -*-
"""修复豆包：CDP 拿 WebView 当前 doubao cookie -> 写设备 doubao_cookies -> 重启 App"""
import io, json, re, subprocess, sys, time, urllib.request
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ADB = r'D:\android-sdk\platform-tools\adb.exe'
SER = '127.0.0.1:5555'
PKG = 'com.wenwei.deepseekproxy.multiweb'
LOCAL = r'D:\reasonix\hunyuan multiweb'

def sh(cmd, wait=0):
    if wait:
        time.sleep(wait)
    return subprocess.run([ADB, '-s', SER, 'shell', cmd], capture_output=True, text=True, errors='replace').stdout

# 1) CDP 拿当前 cookie
pid = sh(f'pidof {PKG}').strip()
subprocess.run([ADB, '-s', SER, 'forward', 'tcp:9222', f'localabstract:webview_devtools_remote_{pid}'],
               check=True, capture_output=True)
pages = None
for _ in range(4):
    try:
        raw = urllib.request.urlopen('http://127.0.0.1:9222/json', timeout=15).read().decode()
        pages = json.loads(raw)
        if pages:
            break
    except Exception:
        pass
    time.sleep(1)
page = next((p for p in pages if 'doubao' in (p.get('url') or '')), None)
if not page:
    sys.exit('没有 doubao 页面，先打开浏览器 tab 的豆包')

import websocket
ws = websocket.create_connection(page['webSocketDebuggerUrl'], suppress_origin=True)
ws.settimeout(30)
mid = 0

def cdp(method, params=None):
    global mid
    mid += 1
    ws.send(json.dumps({'id': mid, 'method': method, 'params': params or {}}))
    while True:
        msg = json.loads(ws.recv())
        if msg.get('id') == mid:
            return msg.get('result', {})

cdp('Network.enable', {})
cookies = [c for c in cdp('Network.getAllCookies', {}).get('cookies', [])
           if 'doubao' in c.get('domain', '')]
ws.close()
print('doubao cookies:', len(cookies))
# 只取登录相关 + csrf + 必要键，排除明显无关的大键（保持精简但完整）
keep = [c for c in cookies if c['name'] in
        ('sessionid', 'sessionid_ss', 'sid_guard', 'passport_csrf_token',
         'passport_csrf_token_default', 'ttwid', 'sdk_ss_label', ' _tea_utm_cache',
         'gf_partition', 'novel_gf', 'odin_tt', 'csrf_token')]
header = '; '.join(f"{c['name']}={c['value']}" for c in keep)
print('保留键数:', len(keep), 'header len:', len(header))
print('登录 cookie 在:', any(c['name'] in ('sessionid', 'sessionid_ss') for c in keep))

# 2) 读设备 ds_proxy.xml，替换 doubao_cookies
xml = sh(f'run-as {PKG} cat shared_prefs/ds_proxy.xml')
io.open(LOCAL + r'\tmp_dsproxy_bak.xml', 'w', encoding='utf-8').write(xml)
print('设备 ds_proxy.xml 已备份到本地，大小:', len(xml))

new_arr = [header]
new_json = json.dumps(new_arr, ensure_ascii=False)
new_json_xml = (new_json.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
                .replace('"', '&quot;').replace("'", '&apos;'))
xml2 = re.sub(r'(name="doubao_cookies"[^>]*>).*?(</string>)',
              lambda m: m.group(1) + new_json_xml + m.group(2), xml, flags=re.S)
assert xml2 != xml, 'doubao_cookies 替换失败'
io.open(LOCAL + r'\tmp_dsproxy_new.xml', 'w', encoding='utf-8', newline='\n').write(xml2)
sh('push ' + LOCAL.replace('\\', '/') + '/tmp_dsproxy_new.xml /data/local/tmp/dsproxy_new.xml')
sh(f'run-as {PKG} cp /data/local/tmp/dsproxy_new.xml shared_prefs/ds_proxy.xml')
# 备份到设备侧
sh(f'run-as {PKG} cp shared_prefs/ds_proxy.xml shared_prefs/ds_proxy.xml.bak-doubao')
v = sh(f'run-as {PKG} cat shared_prefs/ds_proxy.xml')
m = re.search(r'name="doubao_cookies"[^>]*>(.*?)</string>', v, re.S)
ok = m and 'sessionid=' in (m.group(1).replace('&quot;', '"'))
print('设备 doubao_cookies 已更新:', bool(ok))

# 3) 重启 App
sh(f'am force-stop {PKG}', 1)
sh(f'am start -n {PKG}/com.wenwei.deepseekproxy.MainActivity', 6)
print('App 已重启')