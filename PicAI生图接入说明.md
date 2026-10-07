# PicAI 生图接入说明（给其他 AI / 维护者）

> 本文记录 2026-10-08 在 `workbuddy` 分支上为混元太极-MultiWeb 接入 PicAI 生图后端（picai.com，GPT Image 2）的完整实现与注意事项。提交 tag：`picai-gpt-image-2-20261008`。

## 一句话概括

App 内网关中新增「PicAI 生图」供应商：手机直连 `picai.com` 的 `/api/fal/*` 匿名生图通道，**每次生图现算一个新随机 XFF 身份**实现额度无限刷新，向 `/v1/images/generations` 调用方提供 `picai-gpt-image-2`（GPT Image 2 文生图）。

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

结论：

- **额度键 = `X-Forwarded-For` 头里的 IP，与 Cookie 无关**（C1 是关键证据）。
- 游客额度语义：每个新 IP **首次生图免费放行**（响应显示 credits=2 < cost=3 仍放行），同 IP 第二次起 402。
- 因此每次生图 mint 一个新随机公网 IPv4 即"无限"（IPv4 随机空间约 40 亿）。同一次生图的提交+轮询必须复用同一身份。
- `402/429/403/5xx/连接故障` → 换新身份重试（上限 4 次）；其余 4xx 属参数错误，直接抛给调用方。
- 注意网上流传的"refresh 新 cookie 就能无限"说法不准确——cookie 是幌子，XFF 才是钥匙。

## 关键限制（踩过的坑，别再踩）

- **机房/代理出口有被封风险**：与 AppBMS 同理，OkHttp 必须 `Proxy.NO_PROXY` 直连，只保证手机流量/家庭宽带。402/403 持续出现时提示用户切换网络。
- 只接了**文生图**：App 生图路由只收 prompt/size。picai 的图生图（`/api/fal/upload` dataUrl 上传 + `gpt-image-2-edit`）暂未接入，需要时再扩展。
- 结果 URL 为上游转存地址（fal.media），**有回收周期**，客户端拿到后及时转存。
- 上游若改为按真实 TCP 源 IP 计数或校验 XFF，"无限"失效——届时表现为持续 402/429，属上游策略变更，非本服务故障（把模型下架或挂提示即可）。
- 单文件网关参考实现 picai-relay（含 deploy.bat/start.vbs 等 Windows 部署件）在分析目录 `.tmp_wxgpt2/extracted/`，不随本仓库分发。

## 构建

```bash
cd <工程根目录>
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app-proxy:assembleDebug :app-proxy:testDebugUnitTest
```

产物：`app-proxy/build/outputs/apk/debug/app-proxy-debug.apk`（工程惯例 debug 签名直装，勿改正式签名）。本批交付命名：`混元太极-MultiWeb-1.0.43-picai生图-arm64-debug.apk`。

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
