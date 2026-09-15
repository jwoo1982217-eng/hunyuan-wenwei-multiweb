# -*- coding: utf-8 -*-
# patch31：图生视频参考图注入（文心）
# 1) 在 generateVideo 的 LongCat 判断前（6905 getLoginUrl 处）插入：
#    invoke-static {v6, v20}, WenxinAttachmentUploader->stashVideoRefs(config, refs)
#    （v6=config, v20=refImages，两寄存器都活跃，零新增）
# 2) 在 :cond_4 入口（7092）插入：
#    invoke-static {}, WenxinAttachmentUploader->injectVideoRefs()
#    （无参调用，零寄存器）
import io, sys, os, shutil
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

p = r'D:\reasonix\hunyuan multiweb\start-apk-work\decoded\smali_classes4\com\wenwei\deepseekproxy\proxy\CustomWebVideoAutomation.smali'
data = open(p, encoding='utf-8', errors='replace').read()

# 锚点1：6905 行的 getLoginUrl 前（用唯一上下文）
anchor1 = '''    .line 2311
    invoke-virtual {v6}, Lcom/wenwei/deepseekproxy/proxy/CustomWebProviderConfig;->getLoginUrl()Ljava/lang/String;'''
assert data.count(anchor1) == 1, 'anchor1 count=%d' % data.count(anchor1)

# 锚点2：:cond_4 入口
anchor2 = '''    :cond_4
    :try_start_d
    sget-object v1, Lcom/wenwei/deepseekproxy/proxy/CustomWebVideoAutomation;->INSTANCE:Lcom/wenwei/deepseekproxy/proxy/CustomWebVideoAutomation;'''
assert data.count(anchor2) == 1, 'anchor2 count=%d' % data.count(anchor2)

if 'patch31_wenxin' in data:
    print('patch31 already applied')
    sys.exit(0)

shutil.copy2(p, p + '.bak-patch31')

new1 = '''    .line 2311
    # ---- patch31_wenxin: stash refs for wenxin video i2v ----
    move-object/from16 v1, v20
    invoke-static {v6, v1}, Lcom/wenwei/deepseekproxy/proxy/WenxinAttachmentUploader;->stashVideoRefs(Lcom/wenwei/deepseekproxy/proxy/CustomWebProviderConfig;Ljava/util/List;)V
    # ---- end patch31_wenxin ----
    invoke-virtual {v6}, Lcom/wenwei/deepseekproxy/proxy/CustomWebProviderConfig;->getLoginUrl()Ljava/lang/String;'''

new2 = '''    :cond_4
    # ---- patch31_wenxin: wenxin video ref inject (touch + bridge + js fallback) ----
    invoke-static {}, Lcom/wenwei/deepseekproxy/proxy/WenxinAttachmentUploader;->injectVideoRefs()V
    # ---- end patch31_wenxin ----
    :try_start_d
    sget-object v1, Lcom/wenwei/deepseekproxy/proxy/CustomWebVideoAutomation;->INSTANCE:Lcom/wenwei/deepseekproxy/proxy/CustomWebVideoAutomation;'''

data = data.replace(anchor1, new1, 1)
data = data.replace(anchor2, new2, 1)
open(p, 'w', encoding='utf-8', newline='\n').write(data)
print('patch31 applied OK')