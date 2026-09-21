# -*- coding: utf-8 -*-
# patch32b：在 /v1/chat/completions 路由注册前插入 WorkBuddy token 自愈（零寄存器 invoke-static {}）
import io, sys, os, shutil, subprocess
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

ROOT = r'D:\reasonix\hunyuan multiweb'
PS = os.path.join(ROOT, 'start-apk-work', 'decoded',
                  r'smali_classes4\com\wenwei\deepseekproxy\proxy\ProxyServer.smali')
SMALI_SRC = os.path.join(ROOT, r'tmp_javaclass\smali_wb\smali\com\wenwei\deepseekproxy\proxy')

# ---------- 1) 装 WorkBuddyClient*.smali 进 decoded ----------
dst_dir = os.path.dirname(PS)
for f in os.listdir(dst_dir):
    if f.startswith('WorkBuddyClient') and f.endswith('.smali'):
        os.remove(os.path.join(dst_dir, f))
n = 0
for f in os.listdir(SMALI_SRC):
    if f.startswith('WorkBuddyClient') and f.endswith('.smali'):
        shutil.copy2(os.path.join(SMALI_SRC, f), os.path.join(dst_dir, f))
        n += 1
print('[1] WorkBuddyClient smali installed:', n)

# ---------- 2) 路由注入点 ----------
data = io.open(PS, encoding='utf-8', errors='replace').read()

anchor = '''    const-string v6, "/v1/chat/completions"

    invoke-static {v0, v6, v1}, Lio/ktor/server/routing/RoutingBuilderKt;->post(Lio/ktor/server/routing/Route;Ljava/lang/String;Lkotlin/jvm/functions/Function2;)Lio/ktor/server/routing/Route;'''

if 'patch32b_workbuddy' in data:
    print('[2] patch32b already applied')
    sys.exit(0)

cnt = data.count(anchor)
print('[2] anchor count =', cnt)
assert cnt == 1, 'anchor not unique'

new = '''    # ---- patch32b_workbuddy: token 自愈（每次请求前确保 WorkBuddy token 有效；零寄存器非阻塞）----
    invoke-static {}, Lcom/wenwei/deepseekproxy/proxy/WorkBuddyClient;->ensureTokenAsync()V
    # ---- end patch32b_workbuddy ----

    const-string v6, "/v1/chat/completions"

    invoke-static {v0, v6, v1}, Lio/ktor/server/routing/RoutingBuilderKt;->post(Lio/ktor/server/routing/Route;Ljava/lang/String;Lkotlin/jvm/functions/Function2;)Lio/ktor/server/routing/Route;'''

data = data.replace(anchor, new, 1)
shutil.copy2(PS, PS + '.bak-patch32b')
io.open(PS, 'w', encoding='utf-8', newline='\n').write(data)
print('[2] patch32b applied, backup written')
