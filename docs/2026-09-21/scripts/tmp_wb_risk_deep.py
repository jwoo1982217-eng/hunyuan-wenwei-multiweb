# -*- coding: utf-8 -*-
"""WorkBuddy chat 风控深挖：真实出口 IP + T1 完整契约 + 频次变量"""
import io, json, re, subprocess, sys, time, urllib.request, urllib.error, base64, uuid
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
ADB = r'D:\android-sdk\platform-tools\adb.exe'
SER = '127.0.0.1:5555'
PKG = 'com.wenwei.deepseekproxy.multiweb'
BASE = 'https://www.workbuddy.cn'

def sh(cmd):
    return subprocess.run([ADB, '-s', SER, 'shell', cmd], capture_output=True, text=True, errors='replace').stdout

s = sh(f'run-as {PKG} cat shared_prefs/workbuddy_tokens.xml')
tok = re.search(r'name="www\.workbuddy\.cn_token"[^>]*>([^<]*)<', s).group(1)
p = tok.split('.')[1] + '=' * (-len(tok.split('.')[1]) % 4)
uid = json.loads(base64.urlsafe_b64decode(p)).get('sub')

# 真实出口 IP（多源）
pub_ip = ''
for url in ('https://ifconfig.me/ip', 'https://ipinfo.io/ip', 'https://api.ipify.org'):
    try:
        pub_ip = urllib.request.urlopen(url, timeout=8).read().decode().strip()
        if pub_ip and re.match(r'^\d+\.\d+\.\d+\.\d+$', pub_ip):
            break
    except Exception:
        pass
print('出口 IP:', pub_ip or '(拿不到)')

conv_id = uuid.uuid4().hex
req_id = uuid.uuid4().hex
trace_id = uuid.uuid4().hex

FULL = {
    'Content-Type': 'application/json',
    'Accept': 'application/json, text/event-stream',
    'User-Agent': 'WorkBuddy/5.5.4 WorkBuddy/5.5.4 CLI/2.137.1',
    'Authorization': 'Bearer ' + tok,
    'X-Domain': 'www.workbuddy.cn',
    'X-Product': 'SaaS',
    'X-Agent-Purpose': 'craft',
    'X-IDE-Name': 'WorkBuddy',
    'X-IDE-Type': 'WorkBuddy',
    'X-IDE-Version': '5.5.4',
    'X-User-Id': uid,
    'X-Conversation-ID': conv_id,
    'X-Conversation-Request-ID': req_id,
    'X-Conversation-Message-ID': uuid.uuid4().hex,
    'X-Request-ID': req_id,
    'X-Root-Request-ID': uuid.uuid4().hex,
    'X-Trace-ID': trace_id,
    'X-B3-TraceId': trace_id,
    'X-B3-SpanId': uuid.uuid4().hex[:16],
    'X-B3-Sampled': '1',
    'x-codebuddy-request': '1',
}
if pub_ip:
    FULL['X-Forwarded-For'] = pub_ip
    FULL['X-Real-IP'] = pub_ip
    FULL['X-Client-IP'] = pub_ip

BODY_FULL = {
    'model': 'auto',
    'messages': [{'role': 'user', 'content': '你好'}],
    'stream': True,
    'outbound_client_name': 'WorkBuddy',
    'passthrough_ip': pub_ip or '',
}

def call(headers, body, label):
    r = urllib.request.Request(BASE + '/v2/chat/completions',
                               data=json.dumps(body).encode(), headers=headers, method='POST')
    try:
        resp = urllib.request.urlopen(r, timeout=60)
        raw = resp.read().decode('utf-8', 'replace')
        bits = []
        for ln in raw.splitlines():
            if ln.startswith('data:'):
                pp = ln[5:].strip()
                if pp and pp != '[DONE]':
                    try:
                        c = json.loads(pp)['choices'][0]['delta'].get('content')
                        if c:
                            bits.append(c)
                    except Exception:
                        pass
        print(f'[{label}] 🎉 200 正文: {"".join(bits)[:100]!r}')
        return True
    except urllib.error.HTTPError as e:
        b = e.read().decode('utf-8', 'replace')
        try:
            j = json.loads(b)
            print(f'[{label}] HTTP {e.code} code={j.get("code")} msg={j.get("msg")}')
        except Exception:
            print(f'[{label}] HTTP {e.code} {b[:120]}')
        return False

# 1) T1 完整契约 + 真实 IP
call(FULL, BODY_FULL, 'T1完整契约+真实IP')
time.sleep(2)
# 2) 无 IP 透port 头（排除 IP 头本身触发）
h2 = {k: v for k, v in FULL.items() if k not in ('X-Forwarded-For', 'X-Real-IP', 'X-Client-IP')}
b2 = {k: v for k, v in BODY_FULL.items() if k != 'passthrough_ip'}
call(h2, b2, 'T1契约无IP头')
time.sleep(2)
# 3) 极简（排除累积）
call({'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tok},
     {'model': 'auto', 'messages': [{'role': 'user', 'content': 'hi'}], 'stream': True},
     '极简Bearer-only')