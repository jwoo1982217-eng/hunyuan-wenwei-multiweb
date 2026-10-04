# ChatGPT / Gemini 网页聊天中转

本次改动基于 `workbuddy` 分支 `7bae6c5`，应用包名
`com.wenwei.deepseekproxy.multiweb`。已有本地网关、登录态、浏览器和模型入口继续使用。

## 使用

在混元 MultiWeb 启动中转，打开 ChatGPT 或 Gemini 浏览器页并完成登录。
墨听 D 的 MultiWeb 接口继续连接 `http://127.0.0.1:8800/v1`，使用网关设置中的
API Key。聊天模型 ID 仍为 `chatgpt-web` / `gemini-web`。

`stream: true` 会在网页出现正文后逐步输出，并在等待期间每 10 秒发送 SSE 注释
保活。`stream: false` 等网页确认完成后返回完整 JSON。保活注释不是模型回答，
也不能保证覆盖客户端自身的总超时。

普通请求兼容原有参数。需要核对网页模型或允许较长推理时，可追加：

```json
{
  "model": "chatgpt-web",
  "messages": [{"role": "user", "content": "请分析这个方案"}],
  "stream": true,
  "web_model": "Pro",
  "web_timeout_seconds": 3600
}
```

`web_model` 在发送前和回复完成时核对网页显示的模型名称，不切换模型。`Pro` 匹配名称中的
独立 Pro 单词；其他值忽略大小写后与网页显示名称完整匹配。如果选择器无法识别或
模型不符，发送前返回 `web_model_unverified` / `web_model_mismatch`。
没有该参数时，沿用网页当前选择，网关模型 ID 不表示已经选中 Pro。

`web_timeout_seconds` 只作用于本次 ChatGPT/Gemini 等待完整回复的阶段，范围
30–3600 秒，默认 180 秒。排队、加载网页、填入输入框和确认发送另有上限。
调用方也需设置足够的读取/总超时。

## 诊断

运行日志顶部显示最近网页调用的阶段、排队时间、首次输出时间、总耗时和识别到的
网页模型。上述时间均从收到网页任务开始计算。登录后热调用复用已有 WebView；
网页本身的推理耗时和网络耗时仍会存在。

带原有 Authorization 访问 `/v1/status`，新增字段：

- `browserHostState`：`closed` / `loading` / `mounted`。它只描述浏览器生命周期。
- `browserCalls`：最近 8 次网页任务的阶段、状态、耗时、模型名称、校验状态和错误码。
  不记录提示词、正文、Cookie 或凭据。

原有模型 readiness 判断继续依赖原有登录、附着与调用证据。新诊断字段不改变模型
可用性的判断。

## 请求完整性

所有使用单个浏览器的聊天、图片、视频 DOM 任务共用队列；API 直连提供商仍可并行。
排队超过 30 秒返回 `browser_busy`。

点击发送只标记一次派发。必须从新的用户消息、新的回答或生成状态取得接收证据，
15 秒未确认返回 `submission_unconfirmed`，不会自动再次点击或重新发送。

回答按本次消息的位置识别，连续两次相同文字不会被当成旧回答过滤。
完成需要接收证据、网页完成信号、生成状态结束和最终内容稳定，文字短暂停顿本身
不会结束调用。JavaScript MutationObserver 将变化推送给 Android，500 毫秒一次的
有界读取用于遗漏事件和完成确认。

SSE 已输出的文字只能追加。若网页改写已经输出的正文，本次流返回
`web_response_rewritten` 错误，不重复拼接全文；非流式返回最终完整内容。
错误流没有成功的 `finish_reason: stop`。网页关闭、导航、内核退出或客户端取消
都会结束本次监听并释放队列。取消或超时不会自动停止网页已经开始的生成；下一次
调用若仍发现生成中，会拒绝发送。

## 验证

JVM 测试覆盖完成判断、模型校验、流式正文改写、错误事件、超时、排队和取消。
`tools/test/web-chat-observer.test.cjs` 使用真实 DOM 解析器模拟两个网站，覆盖
重复回答、旧消息重建、隐藏/禁用发送按钮、单次派发、接收回执、生成状态、图片和
DOM 事件推送。运行方式：

```sh
npm install --prefix /tmp/multiweb-dom-tests --no-save --ignore-scripts --no-audit --no-fund jsdom@26
NODE_PATH=/tmp/multiweb-dom-tests/node_modules node --test tools/test/web-chat-observer.test.cjs
./gradlew :app-proxy:testDebugUnitTest
```

编译和模拟测试不代表已登录真实网站验证，网页选择器随网站变化可能需要调整。
后台运行依赖 Android 保留本应用及浏览器，现有前台服务不会阻止系统的所有回收。
