# 会话交接文档(AUTO) - 2026-08-22 13:28

> 触发方式:自动(纯文本量 29,241,402 >= 800,000)
> 会话:sess_b19baffa-f166-4aa8-98d0-76b728181b96

---

## 📌 核心任务

**混元 Multiweb Android App 中转端适配**：Agnes 图生视频参考图、智谱 cogview/cogvideox 生图生视频、LongCat(美团)视频生成。

---

## ✅ 已完成工作(全部验证通过)

### 1. 智谱 cogview-3-flash 生图适配
- 端点 `https://open.bigmodel.cn/api/paas/v4/images/generations`,Bearer 鉴权
- size 比例→WxH 映射(cogview 官方尺寸):1:1→1024x1024、9:16→720x1440、16:9→1440x720、3:4/2:3→864x1152、4:3/3:2→1152x864、默认 1024x1024
- 实测:cat/dog 均成功生成,UI 无错误、日志无 Not Found

### 2. Agnes 图生视频参考图修复
- 根因:App 把参考图放在自定义字段 `image_data`[{data(b64),mime,...}],Agnes API 只认 `image`(string)
- 修复:executeVideo 中加 Agnes 分支(baseUrl 含 "agnes-ai" 或 model 含 "agnes-video"),把 image_data[0] 转成 `image`="data:<mime>;base64,<b64>" 并删除 image_data
- 实测:带参考图任务 task_akAgWthYavJ7SdhTbYTs3VhTo798e **completed**(5秒/1088x832/1.49MB),App 内已播放

### 3. 智谱 cogvideox-flash 图生视频
- 根因与 Agnes 相同:App 发 `image_data` 自定义字段,智谱只认 `image_url`
- 修复:executeVideo 中 Agnes 块后追加智谱分支(baseUrl 含 bigmodel.cn)做 image_data[0] → image_url 转换
- 验证:3 个带参考图任务全部 SUCCESS
- 注意:cogvideox-flash 无 duration 参数,App 默认补的 duration=5 被智谱忽略未报错;flash 不支持首尾帧数组

### 4. 视频播放控件升级
- 方案:保留原 Player(画面渲染路径不变),在其后叠加 media3 官方 compose 控件
- 新文件:`smali_classes3\com\wenwei\deepseekproxy\VideoControlsKt.smali`
- UrlVideo 修改:Player 调用后插入 VideoControls 调用
- 坑:参数寄存器 p1/p2 在 .locals 15 时位于 v16/v17,必须用 `move-object/from16`;compose 控件 changed 传 0、default mask PlayPauseButton=0x3F
- 验证:视频消息区出现 Button 节点(播放按钮),用户确认"可以了"

### 5. LongCat(美团)视频生成 smali patch
- 目标:`CustomWebVideoAutomation.smali` 两处 JS IIFE 替换
  - v9(L2344):初始模式检测 → 含 pointer event tap 序列(.van-popover__wrapper)
  - v10(L2437):320ms 后重检(替换旧 `(()=>{const all=...})` 块)
- 脚本:`.reasonix\edit-work\fix-longcat-skill.ps1`(base64 编码 JS 块,StartsWith 匹配)
- 坑:heredoc `@'...'@` 把 JS 当 PS 解析 → 改用 base64;smali 字符串结尾多 `)` 导致 mismatched input
- APK:`D:\Temp\patched-longcat-aligned-debugSigned.apk`(**已安装** emulator-5554)
- 备份链:`CustomWebVideoAutomation.smali.pre-longcat-skill` / `.pre-longcat-skill2` / `.pre-longcat-skill3`

---

## 🔧 全部 smali 修复清单(共 8 处)

| # | 文件位置 | 内容 | 脚本 |
|---|---------|------|------|
| 1 | OpenAiCompatibleClient executeImage(~L1513) | optString("cogview")读错字段;改为optString("model")contains"cogview" | fix-zhipu-final.ps1 |
| 2 | adaptToZhipuImage(~L4173) | 条件反了;改为含":"才转换,WxH保留 | fix-zhipu-final.ps1 |
| 3 | zhipuSizeFor(~L4199) | 原映射是glm-image尺寸;改为cogview官方尺寸 | fix-zhipu-final.ps1 |
| 4 | apiRoot(~L540) | 只认/v1结尾其余强加/v1;改为/v1~/v6结尾均保留(同时救活智谱聊天) | fix-apiroot-v4.ps1 |
| 5 | executeVideo(~L2053) | Agnes图生视频:image_data[0]→image(data URI) | fix-agnes-i2v.ps1 |
| 6 | MainActivityKt UrlVideo(L98646后)+VideoControlsKt.smali | 视频播放控件:PlayPauseButton+ProgressSlider | add-video-controls.ps1 |
| 7 | executeVideo Agnes块后 | 智谱图生视频:image_data[0]→image_url(data URI) | fix-zhipu-i2v.ps1 |
| 8 | CustomWebVideoAutomation(v9 L2344,v10 L2437) | LongCat视频技能激活JS:IIFE替换含pointer event tap序列 | fix-longcat-skill.ps1 |

---

## 📁 关键路径

| 项 | 路径 |
|----|------|
| smali 工程目录 | `D:\Temp\orig-apk-decoded`(备份链:.bak→.pre-final-fix→.pre-apiroot-fix→.pre-agnes-i2v→.pre-zhipu-i2v→.pre-longcat-skill/2/3) |
| 核心 smali | `D:\Temp\orig-apk-decoded\smali_classes4\com\wenwei\deepseekproxy\proxy\OpenAiCompatibleClient.smali` |
| LongCat smali | `D:\Temp\orig-apk-decoded\smali_classes4\com\wenwei\deepseekproxy\proxy\CustomWebVideoAutomation.smali` |
| 新增类 | `smali_classes3\com\wenwei\deepseekproxy\VideoControlsKt.smali` |
| 修复脚本 | `.reasonix\edit-work\`:fix-zhipu-final.ps1 / fix-apiroot-v4.ps1 / fix-agnes-i2v.ps1 / add-video-controls.ps1 / fix-zhipu-i2v.ps1 / fix-longcat-skill.ps1 |
| **最新 APK(8处修复)** | `D:\Temp\patched-longcat-aligned-debugSigned.apk`(**已安装**) |
| 旧 APK(7处) | `D:\Temp\patched-final5-aligned-debugSigned.apk` |
| 备份目录 | `.reasonix\backups\`(final3 APK + all5fixes smali) |
| apktool | `D:\reasonix_archive\tools\apktool.jar`(2.9.3) |
| JDK/签名 | `D:\reasonix_archive\tools\jdk\jdk-17.0.20+8` + `uber-apk-signer.jar` |
| adb | `D:\reasonix_archive\tools\android-sdk\platform-tools`(设备:雷电模拟器 emulator-5554,无su无monkey,root不可用但run-as可用) |

## 🛠️ 构建命令

```
D:\reasonix_archive\tools\jdk\jdk-17.0.20+8\bin\java.exe -jar D:\reasonix_archive\tools\apktool.jar b -f D:\Temp\orig-apk-decoded -o D:\Temp\patched.apk
D:\reasonix_archive\tools\jdk\jdk-17.0.20+8\bin\java.exe -jar D:\reasonix_archive\tools\uber-apk-signer.jar -a D:\Temp\patched.apk
set PATH=D:\reasonix_archive\tools\android-sdk\platform-tools;%PATH% && adb -s emulator-5554 install -r D:\Temp\patched-aligned-debugSigned.apk
```

## 🔍 验证方法论(重要教训)

1. **勿信外部图像分析工具**(本会话曾被污染输出误导,报过假成功)——用户因此质疑
2. 三方交叉验证:`uiautomator dump` 提取文本(找"生图错误:"等字样) + 节点结构分析(图片节点bounds) + logcat(ImageRoute/ImageGen/VideoApi/VideoRoute/SendButton)
3. 入口日志 `ImageRoute 完整body前200` 永远显示转换前 body,判断转换要看实际生成结果
4. UI 侧日志(LogStore tag="App",如"视频请求...参考图=N")只进 App 内"日志"tab,不进 logcat
5. adb input text 不支持空格和中文;中文用输入法,空格用 %s
6. App 状态有记忆:重启后保留供应商/模型/比例选择
7. **PowerShell + JS smali patch 坑**:heredoc `@'...'@` 会把 JS 当 PS 解析;smali 字符串结尾多 `)` 会报 mismatched input

---

## 📋 未完成/待决定事项

1. **LongCat 视频生成实测** — APK 已安装,需在 App 中导航到视频→LongCat→触发生成,观察 logcat tag=LongCatVideo
2. **豆包视频额度恢复后** — 补抓新会话首条 completion body,决定走直调(DoubaoClient重写)还是 web 自动化(CustomWebVideoAutomation 模式)
3. 智谱生图 9:16 比例实测(代码路径与1:1相同,风险低,未单独验证)
4. Agnes 视频 duration/尺寸参数未透传(UI不发这些字段,用的默认1152x768→自动映射)
5. 定时自动化已启用:每20分钟检测会话文本≥800k刷新本文档

---

## ☁️ CNB 仓库上传进展 ✅ 已完成(2026-08-22 14:00)

- 目标:`https://cnb.cool/hunyuan-2026/hunyuan-wenwei-multiweb`
- Token `5Yfqp...` 有 `repo-code:rw` scope（可创建 blob/分支），缺 `repo-notes:rw`（无法用 upload/files）
- 分支 `patch/handoff-doc` 已存在（从 main commit 6963054d）
- **已通过 isomorphic-git 推送**：本地 clone → checkout patch/handoff-doc → commit 本文件 → push
- 提交 SHA: `af7f06c6b581`，消息: `docs: update session handoff document (auto-generated)`
- 访问: `https://cnb.cool/hunyuan-2026/hunyuan-wenwei-multiweb/-/tree/patch/handoff-doc/.reasonix/SESSION-HANDOFF-AUTO.md`
- 脚本: `.reasonix/cnb-push/upload-handoff.js`

---

## 🧪 快速回归测试

- 智谱生图:生图tab→智谱→cogview-3-flash→任意比例→发英文提示词→UI无"生图错误"
- Agnes图生视频:视频tab→Agnes→agnes-video-v2.0→＋选图(Pictures/MultiWeb)→发提示词→VideoApi code=200→轮询completed→App内播放
- LongCat视频:视频tab→LongCat(美团)→发提示词→Logcat tag=LongCatVideo→确认JS执行结果(active/missed/more-tapped)
