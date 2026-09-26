# AppBMS 生图接入说明（给其他 AI / 维护者）

> 本文记录 2026-09-26 在 `workbuddy` 分支上为混元太极-MultiWeb 接入 AppBMS 生图后端（46 个模型）的完整实现与注意事项。改动未提交，如需提交请只暂存本文列出的文件。

## 一句话概括

App 内网关中新增「AppBMS 生图」供应商：手机端通过 ECDSA 设备会话 + 请求签名直连 `api.appbms.com`，向 `/v1/images/generations` 调用方提供 46 个免费生图模型（`bms-` 前缀）。

## 改动清单

| 文件 | 动作 | 内容 |
|---|---|---|
| `app-proxy/src/main/java/com/wenwei/deepseekproxy/proxy/AppBmsImageClient.kt` | 新增 | AppBMS 协议完整客户端（见下文协议小节） |
| `app-proxy/src/main/java/com/wenwei/deepseekproxy/proxy/ProviderCatalog.kt` | 修改 | 枚举新增 `APPBMS("AppBMS 生图", null, emptyList())`；`models` 末尾追加 `AppBmsImageClient.MODELS.map { ... }`（publicModelId = `bms-<原名>`，upstreamModelId = 原名） |
| `app-proxy/src/main/java/com/wenwei/deepseekproxy/proxy/ProxyServer.kt` | 修改 | `handleImageGen` 中新增 `APPBMS` 分支（位于 ZHIPU_GUEST 分支之前），调 `AppBmsImageClient.generateImage(prompt, ratio, registered.upstreamModelId)` 并返回 `ImageGenResponse` |
| `app-proxy/build.gradle` | 修改 | versionCode 11 / versionName `1.0.11-appbms生图`；`defaultConfig` 新增 `ndk { abiFilters 'arm64-v8a' }`（应用户要求只打 arm64） |

注意：工程里存在 `com.github.jing332.deepseekproxy`（旧版）与 `com.wenwei.deepseekproxy`（现行版）两套包名，**实际构建只走 wenwei 包**（namespace/applicationId 均为 wenwei），改代码别改错包。

## AppBMS 协议（逆向自 appbms_gateway.py）

上游 `https://api.appbms.com`，UA 固定 `okhttp/4.12.0`，App 标识 `com.photoeditor.remakemefaceswapaigenerator`，版本 `1.3.2`。

1. **挑战**：`POST /v1/device-sessions/challenges`，body `{platform:"android", appIdentifier, packageName, installationId: UUID, appVersion}` → 201 返回 `{challengeId, challenge}`。
2. **建会话**：本地生成 ECDSA P-256(secp256r1) 密钥对，公钥导出 SPKI 的 PEM（64 字符换行）；`POST /v1/device-sessions`，body 带 `challengeId/challenge/publicKeyPem/installationId/appVersion` → 200/201 返回 `{deviceSessionId}`。会话缓存在 `AppBmsImageClient.sessionId`（进程级，401 时清空重建并重试一次）。
3. **签名头**（之后所有请求）：`ts=毫秒时间戳`，`nonce=b64url(16随机字节)`，`bh=b64url(SHA256(body))`，待签名字符串 `"$method\n$path\n$ts\n$nonce\n$sessionId\n$bh"`，用私钥 `SHA256withECDSA` 签名（**DER 格式**，Android `Signature` 默认即 DER，与 Python cryptography 一致），base64url 无 padding。请求头：`X-Device-Session-Id`、`X-Request-Timestamp`、`X-Request-Nonce`、`X-Body-SHA256`、`X-Device-Signature`、`X-Signed-Recovery-Version: 1`。
4. **生图**：`POST /v1/ai/ai-image-gen`，body `{imagePathList: [], prompt, imageRatio: "1:1" 等, model}`（带签名）→ 200/202 返回 `{jobId, statusUrl?}`（statusUrl 可能是绝对 URL，需去掉 BASE 前缀）。
5. **轮询**：`GET statusPath`（GET 签名时空 body），`state` 为 `completed` 时取 `result.imageUrls[]`；`failed/cancelled/expired` 抛错；间隔取 `pollAfterMs`（默认 3500，下限 1000ms），最多 60 次。

## 关键限制（踩过的坑，别再踩）

- **上游封禁机房/代理 IP**：Cloudflare Workers 等云端部署会被 403（`Forbidden: requests to api.appbms.com are not allowed"），已实测。因此只能跑在住宅/手机流量 IP 上——这正是做成 App 内置而非云端的原因。客户端里 OkHttp 必须 `proxy(Proxy.NO_PROXY)`，否则用户开系统代理时会被劫持出口 IP 导致 403。
- 403 时对用户的提示语义是「切换网络（Wi-Fi ↔ 流量）后重试」。
- 会话是进程级缓存；App 重启后自动重建，无需持久化。

## 模型清单（46 个，公开 id 为 `bms-` 前缀）

stable-diffusion, sdxl, sd-turbo, sd3, flux, flux-pro, flux-dev, dall-e-3, midjourney, realistic, realistic-vision, photo, portrait, landscape, anime, cartoon, art, 3d, oil-painting, watercolor, digital-art, cyberpunk, fantasy, abstract, minimalist, pixel-art, vector, sketch, claymation, figurine, pixar, disney, comic, manga, chibi, ghibli, dream-shaper, anything-v5, openjourney, seedream-5-pro, seedream-5.0, seedream-4.5, nano-banana-pro, nano-banana-2, banana-2-lite, gpt-image-2

实测参考（2026-09-26，macOS 住宅网络）：`sdxl` ~35s 出图、`midjourney` 正常、`gpt-image-2` ~36s。**注意 appbms 的 `gpt-image-2`（横杠）与 masyadi 后端的 `gpt_image_2`（下划线，已死）不是一个东西。**

## 构建

```bash
cd <工程根目录>
# 使用 Android Studio 自带的 JBR（路径按本机实际调整）
./gradlew :app-proxy:assembleDebug    # debug 包
./gradlew :app-proxy:assembleRelease  # release 包（工程惯例 debug 签名）
```

产物：`app-proxy/build/outputs/apk/{debug,release}/app-proxy-*.apk`。release 用 debug 签名是工程刻意为之（与测试机已装版本覆盖更新兼容），不要"修正"成正式签名。

## 验收方法

1. 手机安装后启动 App 网关，记下局域网地址（如 `http://192.168.x.x:port/v1`）。
2. 客户端（墨听等）拉 `/v1/models` 应见 46 个 `bms-` 前缀模型。
3. 手机**切到流量**（或家用 Wi-Fi），调 `bms-gpt-image-2` 生一张图，验证上游接受运营商 IP。
4. 若 403：换网络再试；若持续 403，说明上游把该运营商段也封了，需要重新评估。

## 关联背景

- 同源参考实现：`appbms_gateway.py`（Python 原版，位于墨听工程的 `remakeface-skill/` 目录，本地跑在 8802 端口；不随本仓库分发）。
- 另一后端 masyadi（27 个模型）走云端 Cloudflare Worker，与本供应商互不影响。
