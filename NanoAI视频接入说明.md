# NanoAI 视频接入说明（给其他 AI / 维护者）

> 本文记录 2026-09-29 在 `workbuddy` 分支上为混元太极-MultiWeb 接入 NanoAI 视频后端（可灵 Kling + v_fuse 双引擎）的完整实现与注意事项。改动未提交，如需提交请只暂存本文列出的文件。

## 一句话概括

App 内网关中新增「NanoAI 视频」供应商：手机端直连 `nano-banana.leansoft-ai.com`（Nano AI 薄壳 App 的后端），向 `/v1/videos/generations` 调用方提供 2 个视频模型（`nanoai-kling` / `nanoai-seedance-t2v`），支持文生视频与图生视频。

## 改动清单

| 文件 | 动作 | 内容 |
|---|---|---|
| `app-proxy/src/main/java/com/wenwei/deepseekproxy/proxy/NanoAiVideoClient.kt` | 新增 | NanoAI 协议完整客户端（见下文协议小节） |
| `app-proxy/src/main/java/com/wenwei/deepseekproxy/proxy/ProviderCatalog.kt` | 修改 | 枚举新增 `NANOAI_VIDEO("NanoAI 视频", null, emptyList())`；`models` 末尾追加 `NanoAiVideoClient.MODELS.map { ... }`（publicModelId = `nanoai-kling`/`nanoai-seedance-t2v`，upstreamModelId = `k_grok`/`v_fuse`） |
| `app-proxy/src/main/java/com/wenwei/deepseekproxy/proxy/ProxyServer.kt` | 修改 | `handleVideoGen` 中新增 `NANOAI_VIDEO` 分支（位于 DOUBAO_WEB 分支之后），解析参考图与宽高比后调 `NanoAiVideoClient.generateVideo(...)`，返回 `ImageGenResponse` |
| `app-proxy/src/main/java/com/wenwei/deepseekproxy/MainActivity.kt` | 修改 | **视频 Tab 下拉的供应商白名单**（约 1253 行）加入 `NANOAI_VIDEO`——这个 filter 不加的话，App 内视频页下拉只有豆包，新模型看不见（1.0.13 踩过）；ProxyScreen 新增 `videoTemplate` 状态与设置菜单「模板」项（仅 NanoAI 模型显示，二级列表按类别分组 27 个模板）；生成按钮把 template 传入 ViewModel，模板模式无照片时 Toast 提示 |
| `app-proxy/src/main/java/com/wenwei/deepseekproxy/ProxyViewModel.kt` | 修改 | `generateVideo` 新增 `template: String?` 参数，请求体带 `template` 字段（值为模板名 style_name） |
| `app-proxy/build.gradle` | 修改 | versionCode 15 / versionName `1.0.15-NanoAI视频模板` |

注意：工程里存在 `com.github.jing332.deepseekproxy`（旧版）与 `com.wenwei.deepseekproxy`（现行版）两套包名，**实际构建只走 wenwei 包**，改代码别改错包。

## 图生视频（App 内链路）

App 内视频页支持图生视频，链路已全通：

1. **入口**：视频 Tab 与聊天/生图共用附件按钮，从相册/文件选一张**真实照片**（合成图、纯渐变图会被上游内容检测拒，state=fail）。
2. **传参**：`ProxyViewModel.generateVideo(attachmentUris=...)` 把图片读成 base64，放进请求 body 的 `image_data[0].data`（与豆包分支同格式）；比例放 `ratio_str`（9:16/3:4/1:1/16:9/4:3/2:5/5:2 或 Auto）。
3. **后端**：NANOAI_VIDEO 分支解出图片字节（按 JPEG/PNG 魔数定扩展名），`normalizeRatio` 把 `ratio_str` 折算成上游支持的 9:16/16:9/1:1，然后 createTask 带 `file` 字段上传。
4. **结果**：轮询 success 后返回成片直链，App 内气泡原位替换显示。

## 协议（逆向自 Nano AI 1.8.3 / versionCode 83，Python SDK 真机实测核对）

上游 `https://nano-banana.leansoft-ai.com`，UA 固定 `okhttp/4.12.0`，App 标识 `com.nano.art.ai.generator`，版本头 `1.8.3`/`83`。

1. **认证**：请求头 `X-Client-Auth` = `base64( AES-256-CBC( "{ts}||{包名}||{ts+60}" ) )`。明文三段由 APK 内 JNI 拼接（libnativelib.so），60 秒有效期，**每次请求都要重新生成**（轮询期间会过期）。AES key/iv（32+16 字节）不在 APK 内，由云端 Remote Config 下发，抓包 observed 值已硬编码在 `NanoAiVideoClient.kt`。
2. **固定 8 头**（APK `NetworkModule.addHeaderRequest`，没有 fcm-token 头）：`X-Client-Auth`、`device-id`、`app-user-id`（空）、`app-version`、`version-code`、`language-code`（en）、`app-id`、`os-type`（android）。
3. **提交**：`POST /video/createTask`，multipart/form-data：`prompt`、`aspectRatio`（9:16/16:9/1:1）、`image_prompt`（空）、`model_type`（k_grok 或 v_fuse）、`is_regenerate`（false）、`fcm_token`（可空）、`language_code`（en）。图生视频额外一个 `file` 字段（Content-Type 固定 image 通配类型）；**纯文生视频不发 file 字段**（APK 纯文字模式 file 为 null，伪造文件会被误判）。成功返回 `data.taskId`。
4. **轮询**：`POST /video/checkTask`，JSON body `{taskId, secretKey:"", accessKey:"", model_type, is_regenerate:false}`。`data.state`：`waiting` → `success`/`fail`。success 后从 `data.resultJson` 解析 `resultUrls[0]` 即成片直链。

## 限额与风控（实测结论，别再踩）

- **额度按 device-id 记账**：5 条/天、单设备并发 1。`device-id` 客户端可控（APK 用 ANDROID_ID）→ 本客户端**每次任务随机生成 16 位 hex**，额度自动刷新。
- **高频 createTask 触发上游 Cloudflare 1010 封 IP** → 提交前固定 `REQUEST_DELAY_MS=2000`。
- **上游在 Cloudflare 防护后面**：机房 IP（Cloudflare Workers 等）大概率被拒，与 AppBMS 同款限制 → 只能跑住宅/手机流量 IP，客户端强制 `Proxy.NO_PROXY`（防系统代理劫持出口 IP）。这也是做成 App 内置而非云端的原因。
- 403 时对用户的提示语义是「切换网络（Wi-Fi ↔ 流量）后重试」。
- **成片直链在 tempfile.aiquickdraw.com，TTL 未知**，调用方应尽快下载，不要长期外链。
- Kotlin 坑：块注释可嵌套，KDoc 里不要出现 `image/*` 字样（会开嵌套注释导致整文件解析失败）。

## 模型清单与命名规则

| 对外 id | 显示名 | 上游 model_type | 实测（2026-09-29，macOS 住宅网络） |
|---|---|---|---|
| `nanoai-kling` | 可灵视频 | `k_grok`（= 可灵 Kling，CDN 模板文件名实证） | 文生 ✅ ~36s；图生 ✅ ~36s（真实照片） |
| `nanoai-seedance-t2v` | Seedance 视频 | `v_fuse`（疑似字节 Seedance，无确证） | 文生 ✅ ~18s；图生 ✅ ~24s（真实照片） |

两个引擎的图生/文生是同一条 createTask 接口：**带真实照片即图生，不带即文生**。对外 id 里的 `t2v` 只是为了命中下游视频分类词元，不代表只能文生。

- **对外 id 必须含视频词元**（`kling` / `t2v`）：下游客户端（墨听等）按词元正则把模型归类到视频桶，`k_grok`/`v_fuse` 这种原名会被分进聊天桶。`owned_by` 等字段不参与判定。
- **图生视频要用真实照片**：合成渐变图（纯 Python 生成的 PNG）会被上游内容检测拒（state=fail，resultJson 为空），可灵与 v_fuse 两条线都一样；换真实照片立即成功。App 用户传的照片是真实图，不受影响。
- **别把对外 id 命名为 `seedream` 开头**——`seedream` 命中下游的生图正则，会被分进生图桶。

## 玩法模板（1.0.15 新增）

上游云端模板库共 27 个模板，已**内置**在 `NanoAiVideoClient.TEMPLATES`（来源：CDN JSON `cdn.leansoft-ai.com/nano-ai/video-thumbs/data_20260126_2143.json` 的 2026-09-26 快照，含真实 thumb_url）。分类：Trending 9 个 / Hot 5 个 / Vintage 5 个 / Arrest 4 个 / Viral Dance 4 个（Baby Dance 1-4 为可灵引擎，其余为 v_fuse）。

**字段语义（2026-09-29 三种组合全部真机实测通过，成片已逐帧目检）：**

| createTask 字段 | 填什么 | 实测 |
|---|---|---|
| `prompt` | 模板的 `video_prompt`（服务端基础键：`video_motion` 或专属键如 `video_elsa`） | ✅ motion 键让照片自然动起来 |
| `image_prompt` | 模板的 `image_prompt`（官方效果子键，如 `nano_rain`） | ✅ Rain 模板生成"雕塑人脸杯淋雨"效果 |
| `image_prompt`（同字段） | **自由描述文本** | ✅ Elsa 模板 + "make it rain heavily"，画面真实下雨 |
| `image_prompt`（同字段） | **子键 + 描述拼接** `"nano_rain, neon city lights at night"` | ✅ 正常出片 |

**分发规则（ProxyServer NANOAI 分支）**：请求体带 `template`（style_name）时——引擎以模板自带 `model_type` 为准（选了 Seedance 也能用可灵模板）；`prompt` 填模板键；`image_prompt` = 官方子键（+用户描述拼接，`"子键, 描述"`）。用户在输入框打的字在模板模式下是**附加描述**，不是主提示词。无 `template` 字段走普通模式。未知模板名返回 400。

**UI 用法**：视频 Tab 选 NanoAI 模型 → 设置 → 模板 → 挑一个（或"不使用模板"）→ 输入框写附加描述（可空）→ 生成。模板效果以照片为画面来源，无照片时 App 会 Toast 提醒（不拦截）。

## 网关侧请求/响应

- 提交：`POST /v1/videos/generations`，body `{"model":"nanoai-kling","prompt":"...","ratio":"9:16"}`（ratio / ratio_str / aspect_ratio / size 均收，`720x1280` 自动折算）；模板模式加 `"template":"<模板名>"`。
- 图生视频参考图：`image_data[0].data`（base64，与豆包分支同格式）或顶层 `image`/`image_url`（data URI 或 http 直链）。
- 网关内部完成 createTask + 轮询（5s 间隔，最多 36 次 ≈180s），同步返回 `{"created":...,"data":[{"url":"<成片直链>"}]}`。调用方超时建议 ≥180s。

## 构建

```bash
cd <工程根目录>
# 使用 Android Studio 自带的 JBR（路径按本机实际调整）
./gradlew :app-proxy:assembleDebug    # debug 包
./gradlew :app-proxy:assembleRelease  # release 包（工程惯例 debug 签名）
```

产物：`app-proxy/build/outputs/apk/{debug,release}/app-proxy-*.apk`。release 用 debug 签名是工程刻意为之（与测试机已装版本覆盖更新兼容），不要"修正"成正式签名。

## 验收方法

1. 手机安装后启动 App 网关，客户端拉 `/v1/models` 应见 `nanoai-kling`、`nanoai-seedance-t2v` 两个模型（归类在视频桶）。
2. 手机**切到流量**（或家用 Wi-Fi），调 `nanoai-kling` 文生视频一条（约 40–90s 出片）。
3. 若报 403 / 1010：换网络再试；若任务长期 waiting 后 fail，多为提示词或图片触发上游内容检测，换内容重试。
4. 图生视频传真实照片验证 file 上传链路。

## 关联背景

- 协议依据：文蔚「无限视频（NanoAI）」技能包——含对照 APK 反编译的核对报告（修复版 Python SDK）、部署技能书、真实样片。原包不随本仓库分发。
- 引擎归属：`k_grok`=可灵（快手）确证；`v_fuse` 疑似 Seedance（抓包 CDN 路径带 /seedance/，但该路径已证实只是存储路由命名，不构成引擎证据）。
- 同类先例：AppBMS 生图（`AppBMS生图接入说明.md`），同样封机房 IP、走 App 内置直连。
