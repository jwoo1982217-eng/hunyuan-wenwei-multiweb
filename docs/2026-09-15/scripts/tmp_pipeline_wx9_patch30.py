# -*- coding: utf-8 -*-
# v9 流水线：回退 patch30（VerifyError 版）→ 新 patch30（零寄存器：仅 invoke-static {v2}）
import io, sys, os, shutil, zipfile, subprocess
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

ROOT = r'D:\reasonix\hunyuan multiweb'
JAVA = r'D:\jdk17\bin\java.exe'
JAVAC = r'D:\jdk17\bin\javac.exe'
APKTOOL = r'D:\reasonix_archive\tools\apktool.jar'
ZIPALIGN = r'D:\android-sdk\build-tools\36.0.0\zipalign.exe'
APKSIGNER = r'D:\android-sdk\build-tools\36.0.0\apksigner.bat'
KEYSTORE = r'C:\Users\Administrator\.android\debug.keystore'
ADB = r'D:\android-sdk\platform-tools\adb.exe'
D8 = r'D:\android-sdk\build-tools\36.0.0\lib\d8.jar'
ANDROID_JAR = r'D:\android-sdk\platforms\android-36\android.jar'

def run(args, **kw):
    env = dict(os.environ); env['JAVA_HOME'] = r'D:\jdk17'
    return subprocess.run(args, capture_output=True, text=True, errors='replace', env=env, **kw)

dst_dir = os.path.join(ROOT, 'start-apk-work', 'decoded', 'smali_classes4', 'com', 'wenwei', 'deepseekproxy', 'proxy')

# 0) 回退 patch30
img = os.path.join(dst_dir, 'CustomWebImageAutomation.smali')
bak = img + '.bak-patch30'
if os.path.exists(bak):
    shutil.copy2(bak, img)
    print('[0] reverted patch30 from backup')

# 1) javac
out = os.path.join(ROOT, 'tmp_javaclass', 'out_wx9')
if os.path.exists(out): shutil.rmtree(out)
os.makedirs(out)
r = run([JAVAC, '-source', '8', '-target', '8', '-encoding', 'UTF-8',
         '-classpath', ANDROID_JAR, '-d', out, os.path.join(ROOT, 'tmp_javaclass', 'WenxinAttachmentUploader.java')])
print('[1] javac rc=', r.returncode)
if r.returncode != 0:
    print((r.stdout or '')[-3000:]); print((r.stderr or '')[-2000:]); sys.exit(1)

pkg = os.path.join(out, 'com', 'wenwei', 'deepseekproxy', 'proxy')
classes = [os.path.join(pkg, f) for f in os.listdir(pkg) if f.endswith('.class')]

# 2) d8
dex_out = os.path.join(ROOT, 'tmp_javaclass', 'dex_wx10')
if os.path.exists(dex_out): shutil.rmtree(dex_out)
os.makedirs(dex_out)
r = run([JAVA, '-cp', D8, 'com.android.tools.r8.D8', '--min-api', '26', '--output', dex_out, '--lib', ANDROID_JAR] + classes)
print('[2] d8 rc=', r.returncode)
if r.returncode != 0:
    print((r.stdout or '')[-2000:]); sys.exit(1)

# 3) wrapper + decode
wrapper = os.path.join(ROOT, 'tmp_javaclass', 'dex', 'wrapper_wx10.apk')
with zipfile.ZipFile(wrapper, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('classes.dex', open(os.path.join(dex_out, 'classes.dex'), 'rb').read())
smali_out = os.path.join(ROOT, 'tmp_javaclass', 'smali_wx10')
if os.path.exists(smali_out): shutil.rmtree(smali_out)
r = run([JAVA, '-jar', APKTOOL, 'd', '-f', wrapper, '-o', smali_out])
print('[3] apktool d rc=', r.returncode)
smali_dir = os.path.join(smali_out, 'smali', 'com', 'wenwei', 'deepseekproxy', 'proxy')
files = sorted(os.listdir(smali_dir))

# 4) 覆盖 decoded
for f in os.listdir(dst_dir):
    if f.startswith('WenxinAttachmentUploader') and f.endswith('.smali'):
        os.remove(os.path.join(dst_dir, f))
for name in files:
    src = os.path.join(smali_dir, name)
    dst = os.path.join(dst_dir, name)
    data = open(src, encoding='utf-8').read()
    assert not data.startswith('\ufeff') and '\r\n' not in data, name
    open(dst, 'w', encoding='utf-8', newline='\n').write(data)
print('[4] installed', len(files), 'wenxin uploader smali files')

# 5) patch30 v2：零寄存器方案
img = os.path.join(dst_dir, 'CustomWebImageAutomation.smali')
cdata = open(img, encoding='utf-8', errors='replace').read()

anchor = '''    if-nez v0, :cond_8

    .line 1559
    :try_start_5
    sget-object v0, Lkotlin/Result;->Companion:Lkotlin/Result$Companion;'''

if 'patch30v2_wenxin' in cdata:
    print('[5] patch30v2 already applied')
elif cdata.count(anchor) == 1:
    branch = '''    if-nez v0, :cond_8

    # ---- patch30v2_wenxin: image-gen ref inject (zero new registers, v2=refImages) ----
    invoke-static {v2}, Lcom/wenwei/deepseekproxy/proxy/WenxinAttachmentUploader;->injectRefsAuto(Ljava/util/List;)V
    # ---- end patch30v2_wenxin ----

    .line 1559
    :try_start_5
    sget-object v0, Lkotlin/Result;->Companion:Lkotlin/Result$Companion;'''
    if not os.path.exists(img + '.bak-patch30'):
        shutil.copy2(img, img + '.bak-patch30')
    cdata = cdata.replace(anchor, branch, 1)
    open(img, 'w', encoding='utf-8', newline='\n').write(cdata)
    print('[5] patch30v2 applied')
else:
    print('[5] ANCHOR NOT FOUND! count=', cdata.count(anchor))
    sys.exit(1)

# 6) build + sign + install
out_apk = os.path.join(ROOT, 'start-apk-work', 'out-patch30.apk')
r = run([JAVA, '-jar', APKTOOL, 'b', os.path.join(ROOT, 'start-apk-work', 'decoded'), '-o', out_apk])
print('[6] apktool b rc=', r.returncode)
if r.returncode != 0:
    print((r.stdout or '')[-3000:]); sys.exit(1)
aligned = os.path.join(ROOT, 'start-apk-work', 'out-patch30-aligned.apk')
r = run([ZIPALIGN, '-f', '4', out_apk, aligned]); print('[7] zipalign rc=', r.returncode)
assert r.returncode == 0
r = run([APKSIGNER, 'sign', '--ks', KEYSTORE, '--ks-pass', 'pass:android', aligned])
print('[8] sign rc=', r.returncode, (r.stderr or '')[-150:])
assert r.returncode == 0
r = run([ADB, '-s', 'emulator-5554', 'install', '-r', aligned])
print('[9] install:', (r.stdout or '').strip())
assert 'Success' in (r.stdout or '')
r = run([ADB, '-s', 'emulator-5554', 'shell', 'am', 'start', '-n',
         'com.wenwei.deepseekproxy.multiweb/com.wenwei.deepseekproxy.MainActivity'])
print('[10] launch:', (r.stdout or '').strip()[:120])
print('ALL DONE')