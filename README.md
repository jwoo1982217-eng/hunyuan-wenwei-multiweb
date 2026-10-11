# 混元太极 MultiWeb

Android 本地 AI 网关：在手机上管理多个网页端和 API 供应商，通过 OpenAI 兼容接口提供聊天、生图和视频请求。

本仓库承接原 CNB `hunyuan-wenwei-multiweb` 项目。当前源码版本为 **1.0.44-picai生图**（versionCode 44），应用包名为 `com.wenwei.deepseekproxy.multiweb`。**最新版位于默认分支 `workbuddy`**。

## 功能与源码

- 多供应商聊天及网页会话中转，包含 ChatGPT、Gemini、DeepSeek、豆包、千问、智谱和 WorkBuddy 等适配。
- 可配置 OpenAI 兼容 API 供应商，以及已有的生图、视频适配。
- 网页聊天队列、发送确认、回答归属、SSE 心跳和请求阶段诊断。
- Android 前台服务、网关状态及快捷开关。
- `tools/picai-worker/` 为可选 Cloudflare Worker 中转源码。

各供应商的可用模型、登录要求及额度取决于其服务状态和你自己的账号配置。适配代码存在不代表上游当前一定可用。

主要应用代码在 `app-proxy/src/main/java/com/wenwei/deepseekproxy/`。同仓保留的 `com/github/jing332/` 目录为旧版源码，维护时请先确认实际调用链。

## 构建与验证

安装 Android SDK（compileSdk 36、targetSdk 35）及 JDK 17 或更高版本。配置 `ANDROID_HOME`，或在本地 `local.properties` 中设置 `sdk.dir`。Gradle Wrapper 固定为 9.3.1。

```bash
./gradlew :app-proxy:testDebugUnitTest :app-proxy:assembleDebug
npm ci --prefix tools/test
npm test --prefix tools/test
```

APK 输出：`app-proxy/build/outputs/apk/debug/app-proxy-debug.apk`。当前配置仅包含 `arm64-v8a`，Debug 和 Release 沿用工程既有的 Debug 签名配置。

## 使用文档

- [网页聊天中转](docs/web-chat-relay.md)
- [AppBMS 生图](AppBMS生图接入说明.md)
- [NanoAI 视频](NanoAI视频接入说明.md)
- [CNB DeepSeek 适配记录](CNB-DeepSeek4.1接入说明.md)
- [PicAI 生图与可选 Worker](PicAI生图接入说明.md)
- [本地文本模型调用](本地文本模型调用操作说明.md)
- [上游基线与原始版本](BASELINE.md)

## 分支与迁移记录

本次迁移保留本地可取得的 71 个既有提交和 11 个标签，源版本提交为 `294621acaaa4814f4a3bda4fece297966269094c`。

- `workbuddy`：最新版，默认分支。
- `main`：原工程本地 main 分支。
- `archive/cnb-main`：本地最后保存的 CNB main 远端版本。
- `patch/handoff-doc`：本地保存的交接文档分支。

由于 CNB 已不可用，本仓库以本地留存内容为迁移来源；仅存在于 CNB、未保存在本地的内容不在本次迁移范围。

本地 stash 草稿保留在原工程，不作为公开分支发布。访问密钥、Cookie、登录凭据、`local.properties`、构建缓存、分析副本及 APK 不随源码发布。Worker 的 `RELAY_KEY` 请通过 `wrangler secret put RELAY_KEY` 设置自己的值。

本仓库保留原始源码署名和基线记录；第三方代码的授权以其原始许可为准。
