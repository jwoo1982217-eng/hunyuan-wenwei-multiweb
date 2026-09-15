# -*- coding: utf-8 -*-
# v11：修复签名不匹配（stashVideoRefs 参数改为 CustomWebProviderConfig）
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

# 1) javac（带 stub 目录）
out = os.path.join(ROOT, 'tmp_javaclass', 'out_wx11')
if os.path.exists(out): shutil.rmtree(out)
os.makedirs(out)
r = run([JAVAC, '-source', '8', '-target', '8', '-encoding', 'UTF-8',
         '-classpath', ANDROID_JAR + ';' + os.path.join(ROOT, 'tmp_javaclass', 'stubs'),
         '-d', out,
         os.path.join(ROOT, 'tmp_javaclass', 'WenxinAttachmentUploader.java')])
print('[1] javac rc=', r.returncode)
if r.returncode != 0:
    print((r.stdout or '')[-3000:]); print((r.stderr or '')[-2000:]); sys.exit(1)

pkg = os.path.join(out, 'com', 'wenwei', 'deepseekproxy', 'proxy')
# 只取 WenxinAttachmentUploader*.class（stub 的 class 不要进 dex）
classes = [os.path.join(pkg, f) for f in os.listdir(pkg)
           if f.startswith('WenxinAttachmentUploader') and f.endswith('.class')]
print('    classes:', sorted(os.path.basename(c) for c in classes))

# 2) d8
dex_out = os.path.join(ROOT, 'tmp_javaclass', 'dex_wx12')
if os.path.exists(dex_out): shutil.rmtree(dex_out)
os.makedirs(dex_out)
r = run([JAVA, '-cp', D8, 'com.android.tools.r8.D8', '--min-api', '26', '--output', dex_out, '--lib', ANDROID_JAR] + classes)
print('[2] d8 rc=', r.returncode)
if r.returncode != 0:
    print((r.stdout or '')[-2000:]); sys.exit(1)

# 3) wrapper + decode
wrapper = os.path.join(ROOT, 'tmp_javaclass', 'dex', 'wrapper_wx12.apk')
with zipfile.ZipFile(wrapper, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('classes.dex', open(os.path.join(dex_out, 'classes.dex'), 'rb').read())
smali_out = os.path.join(ROOT, 'tmp_javaclass', 'smali_wx12')
if os.path.exists(smali_out): shutil.rmtree(smali_out)
r = run([JAVA, '-jar', APKTOOL, 'd', '-f', wrapper, '-o', smali_out])
print('[3] apktool d rc=', r.returncode)
smali_dir = os.path.join(smali_out, 'smali', 'com', 'wenwei', 'deepseekproxy', 'proxy')
files = sorted(os.listdir(smali_dir))

# 4) 覆盖 decoded
dst_dir = os.path.join(ROOT, 'start-apk-work', 'decoded', 'smali_classes4', 'com', 'wenwei', 'deepseekproxy', 'proxy')
for f in os.listdir(dst_dir):
    if f.startswith('WenxinAttachmentUploader') and f.endswith('.smali'):
        os.remove(os.path.join(dst_dir, f))
for name in files:
    src = os.path.join(smali_dir, name)
    dst = os.path.join(dst_dir, name)
    data = open(src, encoding='utf-8').read()
    assert not data.startswith('\ufeff') and '\r\n' not in data, name
    open(dst, 'w', encoding='utf-8', newline='\n').write(data)
print('[4] installed', len(files), 'smali files')

# 5) 验证签名
up = os.path.join(dst_dir, 'WenxinAttachmentUploader.smali')
d = open(up, encoding='utf-8', errors='replace').read()
import re
for m in re.finditer(r'\.method public static (stashVideoRefs|injectVideoRefs|injectRefsAuto)[^\n]*', d):
    print('[5] sig:', m.group(0).strip())

# 6) build + sign + install
out_apk = os.path.join(ROOT, 'start-apk-work', 'out-patch32.apk')
r = run([JAVA, '-jar', APKTOOL, 'b', os.path.join(ROOT, 'start-apk-work', 'decoded'), '-o', out_apk])
print('[6] apktool b rc=', r.returncode)
if r.returncode != 0:
    errs = [l for l in (r.stdout or '').splitlines() if 'Invalid' in l or 'Could not' in l or 'error' in l.lower()]
    print('\n'.join(errs[-10:])); sys.exit(1)
aligned = os.path.join(ROOT, 'start-apk-work', 'out-patch32-aligned.apk')
r = run([ZIPALIGN, '-f', '4', out_apk, aligned]); print('[7] zipalign rc=', r.returncode)
assert r.returncode == 0
r = run([APKSIGNER, 'sign', '--ks', KEYSTORE, '--ks-pass', 'pass:android', aligned])
print('[8] sign rc=', r.returncode, (r.stderr or '')[-120:])
assert r.returncode == 0
r = run([ADB, '-s', 'emulator-5554', 'install', '-r', aligned])
print('[9] install:', (r.stdout or '').strip())
assert 'Success' in (r.stdout or '')
r = run([ADB, '-s', 'emulator-5554', 'shell', 'am', 'start', '-n',
         'com.wenwei.deepseekproxy.multiweb/com.wenwei.deepseekproxy.MainActivity'])
print('[10] launch:', (r.stdout or '').strip()[:120])
print('ALL DONE')