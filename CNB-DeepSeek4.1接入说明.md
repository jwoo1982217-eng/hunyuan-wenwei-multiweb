# CNB · DeepSeek V4.1 Flash 接入说明（1.0.42）

移植自墨🐳 v1.0.13 分支：通过 cnb.cool 网页登录会话直接调用 `deepseek-v4.1-flash`，
无需 API Key，与「混元3」共用同一 CNB 通道（多账号自动轮换）。

## 使用步骤

1. 打开 App「浏览器」页，供应商下拉选择 **混元 / CNB**；
   现在会直接打开 `https://cnb.cool/explore` AI 会话界面（不再停在首页）。
2. 登录 cnb.cool（手机号/微信均可），出现对话输入框后点「保存会话」；
   可登录多个账号，代理会自动轮换。
3. 本地网关模型列表新增 `cnb-deepseek-v4.1-flash`（显示名
   `DeepSeek V4.1 Flash（CNB）`），在任意 OpenAI 兼容客户端直接选用即可。

```
POST http://127.0.0.1:8080/v1/chat/completions
{ "model": "cnb-deepseek-v4.1-flash", "messages": [...] }
```

## 代码变更

| 文件 | 变更 |
| --- | --- |
| `ProviderCatalog.kt` | 新增模型 `cnb-deepseek-v4.1-flash`（upstream `deepseek-v4.1-flash`）；CNB loginUrl 改为 `https://cnb.cool/explore` 直达会话界面 |
| `ProxyServer.kt` | `mapModel` 白名单对齐墨🐳 `CNB_KNOWN_MODELS`（12 个：hy3/hy4-preview、DSCoder-max、deepseek-v4.1-flash[-high/-low]、deepseek-v4-pro/-flash、glm-5.3[-flash]、glm-5.2、kimi-k3） |
| `CnbClient.kt` | csrfkey 正则由 `([a-f0-9]{32})` 放宽为 `([A-Za-z0-9_-]+)`，兼容 CNB 新格式 key |

## 备注

- 上游接口 `POST https://cnb.cool/ai/chat/completions`，OpenAI 兼容透传，
  认证 = 浏览器保存的登录 Cookie + explore 页 csrftoken/csrfkey。
- 匿名通道已关（401 NOT_LOGIN），必须先在浏览器页登录并保存会话。
- 「混元3」（hy3-preview）条目保留不变；CNB 其余白名单模型可经
  `ApiProviderStore`/runtime 目录按需暴露。
