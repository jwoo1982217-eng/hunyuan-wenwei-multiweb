# WorkBuddy 接入与 502 根因排查定稿（2026-09-21）

> 本轮目标：把精排小说（NovelProofreader）的「网页 WorkBuddy 登录 → API 调用」能力
> 移植进混元太极 MultiWeb 网关，并定位用户实测反复出现的 502。

## 一、最终结论（先说结果）

**502 与 WorkBuddy、与网关代码全部无关。**

用户手机设置了**全局 HTTP 代理**（`192.168.31.247:8899`，电脑端 Python 代理）。
App 内聊天访问 `http://127.0.0.1:8800/v1/chat/completions` 时，OkHttp 默认遵循
系统代理，环回请求被送进代理；代理在**电脑本机**上找 `127.0.0.1:8800`，落空后
返回 Python `http.server` 的默认错误页：

```
HTTP 502: <!DOCTYPE HTML><html lang="en"><head><meta charset="utf-8">
<title>Error response</title>...<p>Message: Bad Gateway.</p>
<p>Error code explanation: 502 - Invalid responses from another server/proxy.</p>
```

用户截图中的错误页与 `curl -x http://192.168.31.247:8899 ... 127.0.0.1:8800`
复现的页面**逐字相同**，铁证。

**修复**：`ProxyViewModel` 访问本地网关的 `http` 客户端固定
`Proxy.NO_PROXY`（commit `089dce5`）。环回请求永远直连本机。

## 二、WorkBuddy 移植要点（对齐精排小说调用链路）

| 项 | 契约 |
|---|---|
| 授权流 | `POST /v2/plugin/auth/state?platform=CLI` → WebView 打开 authUrl 网页登录 → 轮询 `GET /v2/plugin/auth/token?state=` 取 token → `POST /v2/plugin/auth/token/refresh` 无感续期 |
| 对话 | `POST /v2/chat/completions`，`agent=cli`，**必须 stream:true**，SSE 增量解析 |
| 模型 | 16 个真实上游 id（精排 `BuiltInWorkBuddyModel`）：`auto`（默认）、`hy4-preview`、`hy3`、`hy3-x`、`deepseek-v4.1-flash`、`deepseek-v4-pro`、`glm-5.3`、`glm-5.3-flash`、`glm-5.2`、`glm-5.1`、`glm-5v-turbo`、`kimi-k3-1`、`kimi-k2.8-preview`、`kimi-k2.7`、`kimi-k2.6`、`minimax-m3` |
| 对外模型名 | `workbuddy-<上游id>`，如 `workbuddy-auto` |
| 区域 | **只用国内版**（copilot.tencent.com），国际版已移除 |
| 凭证 | SharedPreferences + Android Keystore AES-GCM 加密，临期自动续期，401 强制刷新重试一次 |

代码组织：能力全部在独立包 `workbuddy/`（Client/Sse/CredentialStore/LoginActivity/
StatusCard/Models）+ `proxy/WorkBuddyRoute.kt`；既有巨型 kt 只做最小钩入。

## 三、排查过程中排除的弯路（教训）

1. **占位模型名**：首版误用 `default-model` 作上游 model id，上游必然拒绝。
   精排的真实契约是 16 个模型 id，默认 `auto`。（commit `fcfd17a`）
2. **账号风控误判**：docs 里 09-20 的 11140 账号级封禁记录容易让人误判方向；
   本轮实测该链路已恢复，同机同号精排 App 可用即排除风控。
3. **国际版陷阱**：国内/国际版凭证独立，用户选了未登录的国际版模型导致报错；
   按用户要求直接砍掉国际版，消除选择错误的可能。（commit `341bd6b`）
4. **服务假活**：重装/重启后网关 UI 显示「运行中」但 8800 未监听，需在设置页
   点「重启」真正拉起；排查时先 `adb forward` + curl 确认端口真实在听。
5. **真凶在最后**：凡 App 内报 502 而 `adb forward` + curl 直连 200，优先怀疑
   **系统代理劫持环回请求**——看错误页是不是 Python `http.server` 的
   `Error response` 模板即可一秒确认。

## 四、验证记录

- `adb forward tcp:18800 tcp:8800` + curl：`workbuddy-auto` / `workbuddy-hy3` /
  `workbuddy-hy4-preview` / `workbuddy-glm-5.3` / `workbuddy-kimi-k3-1`
  非流式全部 HTTP 200；流式 SSE 逐段出字正常。
- `curl -x http://192.168.31.247:8899` 复现用户 502 页面（逐字相同）。
- 修复后（NO_PROXY）装机，用户实测 App 内聊天出话正常。

## 五、遗留

- **阅读器 App（njread）调本机网关有同款代理问题**：其 localhost:8800 请求同样
  会被系统代理劫走，需在阅读器侧给本地网关客户端加 NO_PROXY，或在手机
  「WLAN → 当前网络 → 代理 → 不使用代理的网址」加 `127.0.0.1,localhost`。
- 阅读器内保存的网关 Bearer token 为旧值，模型名也需换成 `workbuddy-*`。
