# -*- coding: utf-8 -*-
"""重登后复测：拉设备最新 token -> 解码 -> 直连 chat（多模型）"""
import io, json, re, subprocess, sys, time, urllib.request, urllib.error, base64
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ADB = r'D:\android-sdk\platform-tools\adb.exe'
PKG = 'com.wenwei.deepseekproxy.multiweb'
LOCAL = r'D:\reasonix\hunyuan multiweb'

# 1) 拉最新 tokens
subprocess.run([ADB, 'shell', 'run-as', PKG, 'cat', 'shared_prefs/workbuddy_tokens.xml'],
               stdout=open(LOCAL + '/tmp_wb_tokens.xml', 'w', encoding='utf-8', errors='replace'),
               stderr=subprocess.DEVNULL, timeout=30)
s = io.open(LOCAL + '/tmp_wb_tokens.xml', encoding='utf-8', errors='replace').read()
m = re.search(r'name="www\.workbuddy\.cn_token"[^>]*>([^<]*)<', s)
if not m:
    sys.exit('设备上没有 token！')
tok = m.group(1)
p = tok.split('.')[1] + '=' * (-len(tok.split('.')[1]) % 4)
payload = json.loads(base64.urlsafe_b64decode(p))
import datetime
iat = payload.get('iat')
print(f'新 token: len={len(tok)} sub={payload.get("sub")} iat={datetime.datetime.fromtimestamp(iat)} exp={datetime.datetime.fromtimestamp(payload.get("exp"))}')
uid = payload.get('sub')

# 2) 直连 chat
def call(model):
    body = json.dumps({'model': model,
                       'messages': [{'role': 'user', 'content': '用一句话介绍你自己'}],
                       'stream': True}).encode()
    h = {
        'Content-Type': 'application/json',
        'Accept': 'application/json, text/event-stream',
        'User-Agent': 'WorkBuddy/5.5.4 WorkBuddy/5.5.4 CLI/2.137.1',
        'Authorization': 'Bearer ' + tok,
        'X-Domain': 'www.workbuddy.cn',
        'X-Product': 'SaaS',
        'X-User-Id': uid,
        'X-Conversation-ID': 'conv-' + str(int(time.time() * 1000)),
        'X-Request-ID': str(time.time_ns()),
        'x-codebuddy-request': '1',
    }
    r = urllib.request.Request('https://www.workbuddy.cn/v2/chat/completions',
                               data=body, headers=h, method='POST')
    try:
        resp = urllib.request.urlopen(r, timeout=60)
        raw = resp.read().decode('utf-8', 'replace')
        bits = []
        for ln in raw.splitlines():
            if ln.startswith('data:'):
                pp = ln[5:].strip()
                if pp and pp != '[DONE]':
                    try:
                        bits.append(json.loads(pp)['choices'][0]['delta'].get('content', '') or '')
                    except Exception:
                        pass
        joined = ''.join(bits)
        print(f'  {model}: HTTP 200 SSE行={len(raw.splitlines())} 正文({len(joined)}字): {joined[:120]!r}')
        return True
    except urllib.error.HTTPError as e:
        b = e.read().decode('utf-8', 'replace')
        try:
            j = json.loads(b)
            print(f'  {model}: HTTP {e.code} code={j.get("code")} msg={j.get("msg")}')
        except Exception:
            print(f'  {model}: HTTP {e.code} {b[:120]}')
        return False

for m_ in ['auto', 'glm-5.3', 'hy4-preview']:
    call(m_)
    time.sleep(1)