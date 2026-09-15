# -*- coding: utf-8 -*-
# patch29：文心附件走原生路径
# 在 chat() 的 uploadAttachments 调用点前插入分支：
#   若 config.loginUrl 含 "wenxin" → WenxinAttachmentUploader.upload(view, attachments)
#   成功即跳过 JS 注入；失败继续原有 uploadAttachments。
#
# 插入位置：16302-16332 之间。上下文：
#   if-nez v10, :cond_f        # attachments.isEmpty() 检查
#   .line 332
#   :try_start_37
#   sget-object v10, CustomWebChatAutomation->INSTANCE
#   ... 保存寄存器到 continuation ...
#   invoke-direct {v10, v12, v6, v2, v3} uploadAttachments
#
# 我们在 `:try_start_37` 后、`sget-object v10` 前插入：
#   invoke-virtual {v2}, CustomWebProviderConfig->getLoginUrl()Ljava/lang/String;
#   move-result-object v10
#   const-string v11, "wenxin"
#   invoke-virtual {v10, v11}, String->contains(CharSequence)Z
#   move-result v10
#   if-eqz v10, :skip_wenxin_<N>
#   invoke-static {v12, v6}, WenxinAttachmentUploader->upload(WebView, List)Z
#   move-result v10
#   if-eqz v10, :skip_wenxin_<N>
#   goto :goto_after_upload_<N>   # 跳过 JS 注入直接到上传完成后的代码
#   :skip_wenxin_<N>
#
# 问题：goto 目标 :cond_e / :goto_10 是上传完成后的汇合点，寄存器状态复杂。
# 更简单：文心上传成功与否都继续原有 uploadAttachments（JS 注入对文心是空操作，
# 不会破坏页面状态——已验证：注入后 files=1 但页面无反应）。
# 即：只需在调用 uploadAttachments 前先跑一遍 WenxinAttachmentUploader.upload，
# 之后无论成败都继续原流程。
#
# 由于 try_start_37 区域对寄存器有严格约束（catchall 恢复），插入代码可能破坏
# catchall 的寄存器假设。安全做法：在 try 块外、if-nez v10, :cond_f 之前插入。
#
# 最终方案：在 16300 行 `if-nez v10, :cond_f` 之前插入完整分支代码。
# 该区域在 :try_end_36 .. catchall_97 之后（catchall 已结束），寄存器 v0-v16 活跃。
# 使用 v10/v11（临时）、v2=view、v6=attachments、v12=? 需确认。
# 从 16304 可见 uploadAttachments 的 view 参数用的是 v12。而 v2 也是 config
# （16248 注释 .local v2, "config"）。v12 从哪来？看 16290-16300：
#   move-object v10, v6 / check-cast Collection / isEmpty
# 需要确认 v12 = view。往下游代码看 16332: invoke-direct {v10, v12, v6, v2, v3}
#   → v12=view, v6=attachments, v2=config, v3=continuation。
# 所以插入代码用：
#   invoke-virtual {v2}, getLoginUrl  → v10
#   const-string v11, "wenxin"
#   contains → if-eqz v10, skip
#   invoke-static {v12, v6}, upload → if-eqz v10, skip
#   :skip  (继续原流程)
import io, sys, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

p = r'D:\reasonix\hunyuan multiweb\start-apk-work\decoded\smali_classes4\com\wenwei\deepseekproxy\proxy\CustomWebChatAutomation.smali'
data = open(p, encoding='utf-8', errors='replace').read()

anchor = '''    if-nez v10, :cond_f

    .line 332
    :try_start_37
    sget-object v10, Lcom/wenwei/deepseekproxy/proxy/CustomWebChatAutomation;->INSTANCE:Lcom/wenwei/deepseekproxy/proxy/CustomWebChatAutomation;'''

assert data.count(anchor) == 1, 'anchor count = %d' % data.count(anchor)

# 插入的分支代码。label 名用唯一后缀避免冲突。
branch = '''    if-nez v10, :cond_f

    # ---- patch29: wenxin native upload path (gesture + NativeWebFileChooserBridge) ----
    :try_start_wx
    invoke-virtual {v2}, Lcom/wenwei/deepseekproxy/proxy/CustomWebProviderConfig;->getLoginUrl()Ljava/lang/String;

    move-result-object v10

    const-string v11, "wenxin"

    invoke-virtual {v10, v11}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z

    move-result v10

    if-eqz v10, :wx_skip
    :try_end_wx
    .catchall {:try_start_wx .. :try_end_wx} :catchall_wx

    :try_start_wx2
    invoke-static {v12, v6}, Lcom/wenwei/deepseekproxy/proxy/WenxinAttachmentUploader;->upload(Landroid/webkit/WebView;Ljava/util/List;)Z

    move-result v10

    invoke-static {v10}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object v11

    const-string v10, "CustomWeb"

    const-string v13, "wenxin native upload done"

    invoke-static {v10, v13}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    :try_end_wx2
    .catchall {:try_start_wx2 .. :try_end_wx2} :catchall_wx2

    :wx_skip
    # ---- end patch29 ----

    .line 332
    :try_start_37
    sget-object v10, Lcom/wenwei/deepseekproxy/proxy/CustomWebChatAutomation;->INSTANCE:Lcom/wenwei/deepseekproxy/proxy/CustomWebChatAutomation;'''

data = data.replace(anchor, branch, 1)
open(p, 'w', encoding='utf-8', newline='\n').write(data)
print('patched OK, new len', len(data))