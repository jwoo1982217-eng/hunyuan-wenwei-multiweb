# 会话交接文档 — 混元 Multiweb Android App 中转端适配

> 生成时间：2026-08-22 14:00
> 原始会话：sess_b19baffa-f166-4aa8-98d0-76b728181b96
> 工作目录：`D:\reasonix\hunyuan multiweb`

---

## 📌 项目概述

**混元 Multiweb** 是一个 Android 中转端 App（包名 `com.wenwei.deepseekproxy`），将 AI 模型 API 调用拦截并代理到本地 WebView。本次工作聚焦于**三个功能模块的 smali 逆向修复**：

| 模块 | 目标 | 状态 |
|------|------|------|
| 智谱 cogview-3-flash 生图 | 修复字段读取错误、尺寸映射、apiRoot 路由 | ✅ 完成 |
| Agnes / 智谱图生视频 | 修复 image_data 字段转换 | ✅ 完成 |
| LongCat（美团）视频生成 | 替换 JS IIFE 激活技能 | ✅ 完成 |
| 视频播放控件 | 叠加 media3 compose 播放控件 | ✅ 完成 |

---

## ✅ 已完成：10 处 smali 修复

### 修复清单

| # | 修改位置 | 问题根因 | 修复方案 |
|---|---------|---------|---------|
| 1 | `OpenAiCompatibleClient.executeImage(~L1513)` | `optString("cogview")` 读错字段（应为 `model`） | 改为 `optString("model").contains("cogview")` |
| 2 | `adaptToZhipuImage(~L4173)` | 条件反转，导致非比例字符串被错误处理 | 改为仅含 `":"` 时才做 WxH 解析转换 |
| 3 | `zhipuSizeFor(~L4199)` | 尺寸映射用的是 glm-image 而非 cogview 官方尺寸 | 更新为 cogview 官方尺寸映射表 |
| 4 | `apiRoot(~L540)` | 只识别 `/v1` 结尾，其余强加 `/v1`，导致智谱聊天 404 | 改为 `/v1`～`/v6` 结尾均保留原路径 |
| 5 | `executeVideo(~L2053) Agnes 分支` | App 发 `image_data[{data,b64,mime}]`，Agnes API 只认 `image`(string) | 在 Agnes 分支中将 image_data[0] 转为 `image="data:<mime>;base64,<b64>"` 并删除 image_data |
| 6 | `MainActivityKt.UrlVideo(L98646后)` + 新类 `VideoControlsKt.smali` | App 无播放控件 | 保留原 Player，在其后叠加 media3 compose PlayPauseButton+ProgressSlider |
| 7 | `executeVideo` Agnes 块后 | App 发 `image_data`，智谱只认 `image_url` | 追加智谱分支：image_data[0] → image_url(data URI) |
| 8 | `CustomWebVideoAutomation` v9(L2344)/v10(L2437) | JS IIFE 模式检测不触发，需 pointer event tap 序列 | base64 编码替换两处 IIFE，注入 tap 序列（.van-popover__wrapper） |
| 9 | `adaptToZhipuImage` 方法头(~L4377) | 智谱生图默认加"AI生成"显式+隐式水印 | 方法开头注入 `watermark_enabled=false`（置于条件分支前，所有路径生效）；需账号已签免责声明（个人中心-安全管理-去水印管理） |
| 10 | `executeVideo` bigmodel.cn 分支(~L2043) | 智谱视频同样默认加水印 | with_audio 注入后追加 `watermark_enabled=false`；官方 VideoCommonRequest 支持同参数 |

### 关键尺寸映射（cogview 官方）

| 比例 | WxH |
|------|-----|
| 1:1 | 1024×1024 |
| 9:16 | 720×1440 |
| 16:9 | 1440×720 |
| 3:4 / 2:3 | 864×1152 |
| 4:3 / 3:2 | 1152×864 |
| 默认 | 1024×1024 |

---

## 📁 关键路径

```
工作目录:          D:\reasonix\hunyuan multiweb\
smali 工程目录:    D:\Temp\orig-apk-decoded\
核心 smali:        smali_classes4\com\wenwei\deepseekproxy\proxy\OpenAiCompatibleClient.smali
LongCat smali:     smali_classes4\...\proxy\CustomWebVideoAutomation.smali
新增类:            smali_classes3\com\wenwei\deepseekproxy\VideoControlsKt.smali
修复脚本:          .reasonix\edit-work\
                   fix-zhipu-final.ps1   (修复#1-3)
                   fix-apiroot-v4.ps1    (修复#4)
                   fix-agnes-i2v.ps1     (修复#5)
                   add-video-controls.ps1 (修复#6)
                   fix-zhipu-i2v.ps1     (修复#7)
                   fix-longcat-skill.ps1 (修复#8)
最新 APK(10处修复): D:\Temp\patched-aligned-debugSigned.apk   (已安装到 emulator-5554)
旧 APK(8处):       D:\Temp\patched-longcat-aligned-debugSigned.apk
备份目录:          .reasonix\backups\
apktool:           D:\reasonix_archive\tools\apktool.jar  (v2.9.3)
JDK:               D:\reasonix_archive\tools\jdk\jdk-17.0.20+8
签名工具:          uber-apk-signer.jar
adb:               D:\reasonix_archive\tools\android-sdk\platform-tools
模拟器:            雷电模拟器 emulator-5554（无 su/monkey，root 不可用但 run-as 可用）
```

---

## 🔧 构建命令

```bat
:: 1. 重建 APK
D:\reasonix_archive\tools\jdk\jdk-17.0.20+8\bin\java.exe -jar D:\reasonix_archive\tools\apktool.jar b -f D:\Temp\orig-apk-decoded -o D:\Temp\patched.apk

:: 2. 签名
D:\reasonix_archive\tools\jdk\jdk-17.0.20+8\bin\java.exe -jar D:\reasonix_archive\tools\uber-apk-signer.jar -a D:\Temp\patched.apk

:: 3. 安装到模拟器
set PATH=D:\reasonix_archive\tools\android-sdk\platform-tools;%PATH%
adb -s emulator-5554 install -r D:\Temp\patched-aligned-debugSigned.apk
```

---

## 🔬 验证方法论（重要教训）

1. **勿信外部图像分析工具**（本会话曾因污染输出报假成功，导致用户质疑）
2. **三方交叉验证**：
   - `uiautomator dump` 提取 UI 文本（搜索"生图错误:"等字样）
   - 节点结构分析（图片节点 bounds）
   - logcat 过滤：`ImageRoute` / `ImageGen` / `VideoApi` / `VideoRoute` / `SendButton`
3. 入口日志 `ImageRoute 完整body前200` 永远显示转换**前** body，判断转换要看实际生成结果
4. UI 侧日志（LogStore tag="App"，如"视频请求...参考图=N"）只进 App 内"日志"tab，不进 logcat
5. `adb input text` 不支持空格和中文；中文用输入法，空格用 `%s`
6. App 状态有记忆：重启后保留供应商/模型/比例选择

### PowerShell + smali patch 常见坑

- heredoc `` @'...'@ `` 会把 JS 内容当 PS 解析 → 改用 base64 编码
- smali 字符串结尾多出 `)` 会报 `mismatched input` → 逐字符对比原始文件

---

## 📋 未完成 / 待决定事项

1. ~~**LongCat 视频生成实测**~~ ✅ 2026-08-22 已实测：文生视频、图生视频均正常（logcat tag=LongCatVideo / LongCatGen）

### 🧪 2026-08-22 补充实测记录

| 功能 | 结果 | 备注 |
|------|------|------|
| 智谱 cogview-3-flash 生图（无水印） | ✅ | `watermark_enabled=false` 生效，画面无"AI生成"字样 |
| 智谱 cogvideox-flash 视频（无水印） | ✅ | 同参数生效；封面 URL 中 `_watermark.png` 只是资源桶命名 |
| 美团(自定义web) 图生图 | ✅ | 参考图注入成功（日志："已注入 1 张参考图"），输出为参考图的风格化结果 |
| 美团(自定义web) 文生视频 | ✅ | 正常 |

**已知非缺陷边缘情况（美团图生图）**：
- 冷启动后立即发请求可能报 `image_mode_not_found`（页面未加载完、"图片生成"技能入口尚未渲染）→ 重试即可恢复
- 技能未激活时提交，LongCat 以纯文本回复（非图片）→ 轮询无新图，约 6 分钟后报 `image_timeout`
- 均为页面状态/时序问题，非代码缺陷；如需加固可给模式检测加重试循环（参照 CustomWebVideoAutomation v9/v10 的做法）

2. **豆包视频额度恢复后**
   - 补抓新会话首条 completion body
   - 决定走直调（DoubaoClient 重写）还是 web 自动化（CustomWebVideoAutomation 模式）

3. **智谱生图 9:16 比例实测**
   - 代码路径与 1:1 相同，风险低，未单独验证

4. **Agnes 视频 duration/尺寸参数透传**
   - UI 不发这些字段，用的默认 1152×768 → 自动映射，暂无问题

---

## ☁️ CNB 仓库

- 仓库地址：https://cnb.cool/hunyuan-2026/hunyuan-wenwei-multiweb
- 交接文档所在分支：`patch/handoff-doc`
- 直接访问：https://cnb.cool/hunyuan-2026/hunyuan-wenwei-multiweb/-/tree/patch/handoff-doc/.reasonix/SESSION-HANDOFF-AUTO.md
- Token（有 repo-code:rw scope）：`5Yfqp2113d7H3GF48R8VDUVByBH`（存于各 .js 文件中）
- 上传脚本：`.reasonix/cnb-push/upload-handoff.js`（isomorphic-git 推送）

---

## 🧪 快速回归测试

### 智谱生图
> 生图 tab → 智谱 → cogview-3-flash → 任意比例 → 发英文提示词 → UI 无"生图错误"

### Agnes 图生视频
> 视频 tab → Agnes → agnes-video-v2.0 → ＋选图(Pictures/MultiWeb) → 发提示词
> → VideoApi code=200 → 轮询 completed → App 内播放

### LongCat 视频
> 视频 tab → LongCat(美团) → 发提示词
> → logcat `tag=LongCatVideo` → 确认 JS 执行结果（active/missed/more-tapped）

---

## 📎 附：smali 修复详细位置参考

### OpenAiCompatibleClient.smali（核心修复 #1-5,7）

| 方法 | 行号 | 说明 |
|------|------|------|
| `executeImage` | ~L1513 | #1：字段名从 "cogview" 改为读取 "model" 再 contains |
| `adaptToZhipuImage` | ~L4173 | #2：条件反转修复 |
| `ziphuSizeFor` | ~L4199 | #3：尺寸映射更新 |
| `apiRoot` | ~L540 | #4：支持 /v1~v6 结尾 |
| `executeVideo` | ~L2053 | #5/#7：image_data → image / image_url 转换 |
| `executeVideo` | ~L2043 | #10：bigmodel.cn 分支注入 watermark_enabled=false |
| `adaptToZhipuImage` | ~L4377 | #9：方法头注入 watermark_enabled=false |

### CustomWebVideoAutomation.smali（修复 #8）

| 位置 | 说明 |
|------|------|
| L2344 (v9) | 初始模式检测 JS IIFE 替换 |
| L2437 (v10) | 320ms 后重检 JS IIFE 替换 |

### VideoControlsKt.smali（新增文件 #6）

- 路径：`smali_classes3\com\wenwei\deepseekproxy\VideoControlsKt.smali`
- 包含：Compose VideoPlayer + PlayPauseButton + ProgressSlider

---

*文档由会话自动生成交接，后续工作可从此处无缝接续。*
