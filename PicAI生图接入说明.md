# PicAI 生图接入说明（给其他 AI / 维护者）

> 本文记录 2026-10-08 在 `workbuddy` 分支上为混元太极-MultiWeb 接入 PicAI 生图后端（picai.com，GPT Image 2）的完整实现与注意事项。提交 tag：`picai-gpt-image-2-20261008`（初版 v1.0.43）、`picai-cookie-session-20261008`（会话修复 v1.0.44）。

## 一句话概括

App 内网关中新增「PicAI 生图」供应商：手机直连 `picai.com` 的 `/api/fal/*` 匿名生图通道，**每次生图一套全新身份（新随机 XFF + 独立 Cookie 会话）**实现额度无限刷新，向 `/v1/images/generations` 调用方提供 `picai-gpt-image-2`（GPT Image 2 文生图）。

## 改动清单

| 文件 | 动作 | 内容 |
|---|---|---|
| `app-proxy/src/main/java/com/wenwei/deepseekproxy/proxy/PicaiImageClient.kt` | 新增 | PicAI 协议完整客户端（提交 → 3s 轮询 → 取 outputs url，含身份轮换重试） |
| `app-proxy/src/main/java/com/wenwei/deepseekproxy/proxy/ProviderCatalog.kt` | 修改 | 枚举新增 `PICAI("PicAI 生图", null, emptyList())`；models 链追加 `PicaiImageClient.MODELS.map`（publicModelId = `picai-<原名>`，**前缀避免与 `bms-gpt-image-2` 撞名**） |
| `app-proxy/src/main/java/com/wenwei/deepseekproxy/proxy/ProxyServer.kt` | 修改 | `handleImageGen` 中新增 `PICAI` 分支（位于 APPBMS 分支之后），调 `PicaiImageClient.generateImage(prompt, ratio, registered.upstreamModelId)` |
| `app-proxy/build.gradle` | 修改 | versionCode 43 / versionName `1.0.43-picai生图` |
| `app-proxy/src/test/.../ProviderCatalogTest.kt` | 修改 | 新增 2 个测试：picai 模型暴露形态、mintIp 保留段排除 + 比例映射契约 |
| `tools/test/probe_picai_quota.py` | 新增 | 上游额度机制对照实验脚本（复现"额度键是 XFF 不是 Cookie"的证据） |

## 上游协议（逆向自 picai-relay，Python 参考实现）

上游 `https://picai.com`，UA 伪装 Chrome 131。请求头三件套：`User-Agent`、`Accept: application/json`、`X-Forwarded-For` + `X-Real-IP`（伪造身份 IP）。

1. **提交**：`POST /api/fal/run`，body：
   ```json
   {"modelId": "gpt-image-2",
    "input": {"prompt": "...", "image_size": "square_hd", "aspect_ratio": "1:1",
              "num_images": 1, "width": 1024, "height": 1024, "num_inference_steps": 28},
    "libraryMeta": {"prompt": "..."}}
   ```
   → 200 返回 `{jobId, credits, dailyRemaining, dailyLimit}`。
2. **轮询**：`GET /api/fal/jobs/{jobId}`（3s 一次，上限 60 次≈3 分钟）→
   `status == "succeeded"` 后取 `outputs[]` 中 `kind == "image"` 的 `url`；
   `failed` 抛错。
3. **比例映射**（App `resolveRatio` 产出串 → picai 三元组，一一对应）：
   `1:1 → (square_hd, 1024x1024)`；`9:16 → (portrait_16_9, 576x1024)`；
   `16:9 → (landscape_16_9, 1024x576)`；`4:3 → (landscape_4_3, 1152x864)`；
   `3:4 → (portrait_4_3, 864x1152)`。未知比例回落 1:1。

## "无限"机制（2026-10-08 对照实验实测，4 次真实请求）

| 组 | 身份 | 结果 |
|---|---|---|
| A1 | 新 Cookie + 新伪造 XFF | ✅ 28s 出图（fal.media PNG 直链） |
| A2 | 同会话第 2 次 | ❌ `402 insufficient_credits` |
| C1 | **新 Cookie + 复用 A 的 XFF** | ❌ 立刻 `402` |
| B1 | 全新身份 | ✅ 又成功 |

结论（两层钥匙，缺一不可）：

- **额度钥匙 = `X-Forwarded-For` 头里的 IP**：每个新 IP 首次生图免费放行（响应 credits=2 < cost=3 仍放行），同 IP 第二次起 `402 insufficient_credits`。Cookie 与额度无关。
- **轮询门票 = 会话 Cookie（picai_sid）**：提交响应 `set-cookie picai_sid=…`，`GET /api/fal/jobs/{jobId}` 校验任务归属，**不带这个 Cookie 直接 `{"error":"forbidden"}` 403**——App 内 curl `-v` 抓包实锤：同一会话提交 200 → 无 Cookie 轮询 403。
- 因此"无限" = **每个生图任务一套全新身份（新 XFF + 新 CookieJar）**：提交拿到新会话 Cookie，轮询复用它；下个任务再换全新身份（IPv4 随机空间约 40 亿）。
- `402/429/403/5xx/连接故障` → 换新身份重试（上限 4 次）；其余 4xx 属参数错误，直接抛给调用方。
- 注意"refresh 新 cookie 就能无限"只对一半：**新 cookie 开新会话刷额度（配合新 XFF），但轮询又必须复用本会话的 cookie**——无状态客户端会死在轮询这一步。

### 排障血泪史（别再踩：测试要覆盖全链路每一跳）

初版移植只丢了 Cookie 一件事，却引发一连串假象：手动 curl 测的全是**提交**这一跳（轮询从未测过），空包测试更是被参数校验挡在门外——"链路没问题"的假象把排查引向 TLS 指纹/尺寸/出口 IP/UID 一连串冤案。最终靠 App 内 curl `-v` 抓包（提交 200 + 无 Cookie 轮询 403）一锤定音。**教训：接入类任务必须端到端验证每一跳（提交→轮询→取结果），优先对比同一请求在可用/故障通道的完整交换过程，而不是逐个猜变量。**

## 关键限制（踩过的坑，别再踩）

- **传输栈**：主传输 exec 系统自带 `/system/bin/curl`（真机实测可过；ProcessBuilder 直 exec 不经 shell，body 逐参数传入），每身份一个独立 CookieJar 文件（`-c/-b`），用完即删；curl 不可用时回落 OkHttp（v1.0.44 起兜底栈也带会话 CookieJar）。机房/代理出口在实测中未被封（东京/新加坡出口均通过），但仍建议直连。
- 只接了**文生图**：App 生图路由只收 prompt/size。picai 的图生图（`/api/fal/upload` dataUrl 上传 + `gpt-image-2-edit`）暂未接入，需要时再扩展。
- 结果 URL 为上游转存地址（fal.media），**有回收周期**，客户端拿到后及时转存。
- 上游若改为按真实 TCP 源 IP 计数或收紧游客策略，"无限"失效——届时表现为持续 402/429/403，属上游策略变更，非本服务故障（把模型下架或挂提示即可）。
- 单文件网关参考实现 picai-relay（含 deploy.bat/start.vbs 等 Windows 部署件）在分析目录 `.tmp_wxgpt2/extracted/` 与 `picai_deploy(2).zip`，不随本仓库分发。

## 构建

```bash
cd <工程根目录>
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app-proxy:assembleDebug :app-proxy:testDebugUnitTest
```

产物：`app-proxy/build/outputs/apk/debug/app-proxy-debug.apk`（工程惯例 debug 签名直装，勿改正式签名）。本批交付命名：`混元太极-MultiWeb-1.0.44-picai生图-arm64-debug.apk`。

## 验收方法

1. 手机安装后启动 App 网关，拉 `/v1/models` 应见 `picai-gpt-image-2`。
2. OpenAI 兼容客户端调用：
   ```json
   POST /v1/images/generations
   {"model": "picai-gpt-image-2", "prompt": "...", "size": "9:16"}
   ```
3. 实测参考（2026-10-08，macOS 住宅网络直连上游验证链路）：28s 出图；连续换身份请求均成功。
4. 连续生多张验证"无限"：第 2、3 张不应出现额度类 502；若出现，看网关日志 `PicaiImage` 标签的重试记录。

## 单测与证据

- `ProviderCatalogTest`：34 项全绿（含 picai 模型暴露形态 + mintIp 排除表 + 比例映射契约）。
- 额度机制对照实验可复现：`python3 tools/test/probe_picai_quota.py`（注意：A1/B1 两个实验组会真实消耗上游两次生图）。
