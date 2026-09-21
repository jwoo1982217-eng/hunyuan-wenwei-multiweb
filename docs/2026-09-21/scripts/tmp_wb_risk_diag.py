# -*- coding: utf-8 -*-
"""WorkBuddy 风控原因调查：
1) models API 对照（同 token）
2) auth/state 对照（账号体系）
3) wb_decoded smali 搜 11140 语义
4) 请求频次时间线（设备日志）"""
import io, json, re, subprocess, sys, time, urllib.request, urllib.error, base64
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ADB = r'D:\android-sdk\platform-tools\adb.exe'
SER = '127.0.0.1:5555'
PKG = 'com.wenwei.deepseekproxy.multiweb'

def sh(cmd, wait=0):
    if wait:
        time.sleep(wait)
    return subprocess.run([ADB, '-s', SER, 'shell', cmd], capture_output=True, text=True, errors='replace').stdout

# 设备最新 token
s = sh(f'run-as {PKG} cat shared_prefs/workbuddy_tokens.xml')
m = re.search(r'name="www\.workbuddy\.cn_token"[^>]*>([^<]*)<', s)
tok = m.group(1)
p = tok.split('.')[1] + '=' * (-len(tok.split('.')[1]) % 4)
payload = json.loads(base64.urlsafe_b64decode(p))
uid = payload.get('sub')
print('token sub:', uid)

BASE = 'https://www.workbuddy.cn'

def get(url, label, extra=None):
    h = {'User-Agent': 'WorkBuddy/5.5.4 WorkBuddy/5.5.4 CLI/2.137.1',
         'Authorization': 'Bearer ' + tok,
         'X-Domain': 'www.workbuddy.cn', 'X-Product': 'SaaS',
         'X-User-Id': uid, 'x-codebuddy-request': '1'}
    if extra:
        h.update(extra)
    r = urllib.request.Request(url, headers=h, method='GET')
    try:
        resp = urllib.request.urlopen(r, timeout=30)
        body = resp.read().decode('utf-8', 'replace')
        print(f'[{label}] HTTP {resp.status} len={len(body)} 头部: {body[:120]}')
        return body
    except urllib.error.HTTPError as e:
        b = e.read().decode('utf-8', 'replace')
        print(f'[{label}] HTTP {e.code}: {b[:150]}')
    except Exception as e:
        print(f'[{label}] {type(e).__name__}: {e}')
    return None

# 1) 模型列表（09-15 验证过 Bearer 可拉）
get(BASE + '/v2/enterprises/personal/models', 'models API')
time.sleep(1)
# 2) auth/state（账号体系）
get(BASE + '/v2/plugin/auth/state?platform=CLI', 'auth/state')
time.sleep(1)
# 3) chat 再确认
get(BASE + '/v2/chat/completions', 'chat(GET 探测)')