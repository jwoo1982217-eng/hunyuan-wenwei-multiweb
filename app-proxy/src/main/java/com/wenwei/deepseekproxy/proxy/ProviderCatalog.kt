package com.wenwei.deepseekproxy.proxy

import com.wenwei.deepseekproxy.workbuddy.WorkBuddyModelCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class WebProviderType(
    val displayName: String,
    val loginUrl: String?,
    val cookieOrigins: List<String>
) {
    CNB("混元 / CNB", null, emptyList()),
    DOUBAO_WEB(
        "豆包",
        "https://www.doubao.com/chat/",
        listOf("https://www.doubao.com", "https://doubao.com")
    ),
    CHATGPT_WEB(
        "ChatGPT Web",
        "https://chatgpt.com/",
        listOf(
            "https://chatgpt.com",
            "https://auth.openai.com",
            "https://openai.com"
        )
    ),
    GEMINI_WEB(
        "Gemini Web",
        "https://gemini.google.com/app",
        listOf(
            "https://gemini.google.com",
            "https://accounts.google.com",
            "https://www.google.com",
            "https://google.com",
        )
    ),
    CUSTOM_WEB("自定义 Web", null, emptyList()),
    KIMI_WEB(
        "Kimi Web",
        "https://www.kimi.com/",
        listOf("https://www.kimi.com", "https://kimi.com")
    ),
    DEEPSEEK_WEB(
        "DeepSeek",
        "https://chat.deepseek.com/",
        listOf("https://chat.deepseek.com", "https://deepseek.com")
    ),
    QWEN_WEB(
        "千问 Web",
        "https://www.qianwen.com/",
        listOf("https://www.qianwen.com", "https://qianwen.com", "https://chat2.qianwen.com")
    ),
    QWEN_AI_WEB(
        "千问国际版 Web",
        "https://chat.qwen.ai/",
        listOf("https://chat.qwen.ai")
    ),
    MINIMAX_WEB(
        "MiniMax Web",
        "https://agent.minimaxi.com/",
        listOf("https://agent.minimaxi.com")
    ),
    MIMO_WEB(
        "小米 MiMo Web",
        "https://aistudio.xiaomimimo.com/",
        listOf("https://aistudio.xiaomimimo.com")
    ),
    PERPLEXITY_WEB(
        "Perplexity Web",
        "https://www.perplexity.ai/",
        listOf("https://www.perplexity.ai", "https://perplexity.ai")
    ),
    ZAI_WEB(
        "Z.ai Web",
        "https://chat.z.ai/",
        listOf("https://chat.z.ai")
    ),
    ARENA_GUEST(
        "Arena.ai 免登录",
        "https://arena.ai/text/direct",
        listOf("https://arena.ai")
    ),
    ZHIPU_GUEST("智谱免登录", null, emptyList()),
    // WorkBuddy 走插件 token 授权流（动态 authUrl），不进 Cookie 浏览器登录列表，
    // 登录入口在「浏览器」页的 WorkBuddyStatusCard。只用国内版（对齐精排小说调用链路）。
    WORKBUDDY_DOMESTIC("WorkBuddy 国内版", null, emptyList()),
    RAPHAEL_WEB(
        "Raphael Web",
        "https://raphael.app/zh",
        listOf("https://raphael.app")
    ),
    OPENAI_COMPATIBLE("API 接口", null, emptyList()),
    APPBMS("AppBMS 生图", null, emptyList()),
    NANOAI_VIDEO("NanoAI 视频", null, emptyList()),
    BAILIAN("百炼", null, emptyList()),
    ZHIPU_WEB(
        "智谱清言(登录)",
        "https://chatglm.cn/",
        listOf("https://chatglm.cn", "https://www.chatglm.cn")
    );

    val supportsWebLogin: Boolean get() = loginUrl != null
}

enum class ProviderCapability { CHAT, STREAMING, IMAGE_GENERATION, VISION, VIDEO_GENERATION }

data class ProviderModel(
    val publicModelId: String,
    val displayName: String,
    val providerType: WebProviderType,
    val capabilities: Set<ProviderCapability>,
    val enabled: Boolean = true,
    val upstreamModelId: String = publicModelId,
    val accountId: String? = null
) {
    val groupedLabel: String get() = "${providerType.displayName} · $displayName"
    val supportsImageInput: Boolean get() {
        if (providerType == WebProviderType.CHATGPT_WEB || providerType == WebProviderType.GEMINI_WEB) return true
        // 混元3 (CNB) 走 OpenAI 兼容透传：content 数组（含 image_url）原样转发到
        // /ai/chat/completions，HY3 是 VL 模型，支持图片输入。
        if (providerType == WebProviderType.CNB) return true
        // 豆包 API 客户端：图片经 upload_image 上传图床后以 attachment_block 发送。
        if (providerType == WebProviderType.DOUBAO_WEB) return true
        // DeepSeek 网页端支持识图：图片经 /api/v0/file/upload_file 上传 → ref_file_ids 传入 completion。
        if (providerType == WebProviderType.DEEPSEEK_WEB) return true
        // WebView 浏览器中继类（千问国际/MiniMax/MiMo/Perplexity/Z.ai/Arena/Longcat
        // 等内置中继 + 自定义 Web）统一走通用 file input 注入，任意账号可用。
        if (providerType == WebProviderType.CUSTOM_WEB || isBuiltInBrowserRelay(providerType)) return true
        if (providerType != WebProviderType.OPENAI_COMPATIBLE) return false
        val id = "$upstreamModelId $displayName".lowercase()
        return Regex("(^|[-_./ ])(vision|vlm?|llava|pixtral)([-_./ ]|$)").containsMatchIn(id) ||
            Regex("gpt-4[o.]|gemini|claude-3|claude-sonnet|claude-opus|qwen[-_.]?vl").containsMatchIn(id)
    }
    /** 上报给 /v1/models 的视觉能力：仅当该模型真实支持图片输入时包含 VISION。 */
    val reportedCapabilities: Set<ProviderCapability> get() =
        if (supportsImageInput) capabilities + ProviderCapability.VISION else capabilities
}

data class WebAccountProfile(
    val id: String,
    val providerType: WebProviderType,
    val displayName: String,
    val enabled: Boolean = true
)

/**
 * Single model authority for the relay UI and /v1/models.
 * ChatGPT and Gemini use signed-in WebViews; Raphael Basic uses its public no-login WebView.
 */
object ProviderCatalog {
    @Volatile private var runtimeModels: List<ProviderModel> = emptyList()
    val models = listOf(
        // CNB 混元3：上游已关闭匿名通道（2026-10-02 实测 401 NOT_LOGIN，
        // 新旧会话全灭），暂时禁用；若做登录版（cnb.cool 账号 Cookie）再启用
        ProviderModel(
            CnbClient.DEFAULT_MODEL,
            "混元3",
            WebProviderType.CNB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            enabled = false
        ),
        ProviderModel(
            "doubao",
            "豆包聊天",
            WebProviderType.DOUBAO_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING)
        ),
        ProviderModel(
            "doubao-pro",
            "豆包 Pro",
            WebProviderType.DOUBAO_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING)
        ),
        ProviderModel(
            "doubao-image-web",
            "豆包生图",
            WebProviderType.DOUBAO_WEB,
            setOf(ProviderCapability.IMAGE_GENERATION)
        ),
        ProviderModel(
            "doubao-video-web",
            "豆包视频",
            WebProviderType.DOUBAO_WEB,
            setOf(ProviderCapability.VIDEO_GENERATION)
        ),
        ProviderModel(
            "chatgpt-web",
            "GPT 网页当前模型",
            WebProviderType.CHATGPT_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            enabled = true,
            upstreamModelId = "gpt-web"
        ),
        ProviderModel(
            "gpt-image-2",
            "GPT Image 2（网页）",
            WebProviderType.CHATGPT_WEB,
            setOf(ProviderCapability.IMAGE_GENERATION),
            enabled = true,
            upstreamModelId = "gpt-image-2"
        ),
        ProviderModel(
            "gemini-web",
            "Gemini",
            WebProviderType.GEMINI_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            enabled = true
        ),
        ProviderModel(
            "gemini-image-web",
            "Gemini 生图",
            WebProviderType.GEMINI_WEB,
            setOf(ProviderCapability.IMAGE_GENERATION),
            enabled = true
        ),
        ProviderModel(
            "kimi-web",
            "Kimi",
            WebProviderType.KIMI_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
        ),
        ProviderModel(
            "deepseek-web",
            "DeepSeek",
            WebProviderType.DEEPSEEK_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
        ),
        ProviderModel(
            "deepseek-reasoner-web",
            "DeepSeek 深度思考",
            WebProviderType.DEEPSEEK_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            upstreamModelId = "reasoner",
        ),
        ProviderModel(
            "qwen-web",
            "千问",
            WebProviderType.QWEN_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            upstreamModelId = QwenClient.DEFAULT_MODEL,
        ),
        ProviderModel(
            "qwen-think-web",
            "千问 深度思考",
            WebProviderType.QWEN_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            upstreamModelId = QwenClient.DEFAULT_MODEL,
        ),
        ProviderModel(
            "zhipu-web",
            "智谱免登录",
            WebProviderType.ZHIPU_GUEST,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
        ),
        ProviderModel(
            "zhipu-plus-web",
            "智谱增强模式",
            WebProviderType.ZHIPU_GUEST,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
        ),
        ProviderModel(
            "zhipu-image-web",
            "智谱免登录生图",
            WebProviderType.ZHIPU_GUEST,
            setOf(ProviderCapability.IMAGE_GENERATION),
        ),
        ProviderModel(
            "qwen-ai-web",
            "千问国际版 网页当前模型",
            WebProviderType.QWEN_AI_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
        ),
        ProviderModel(
            "minimax-web",
            "MiniMax 网页当前模型",
            WebProviderType.MINIMAX_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
        ),
        ProviderModel(
            "mimo-web",
            "小米 MiMo 网页当前模型",
            WebProviderType.MIMO_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
        ),
        ProviderModel(
            "perplexity-web",
            "Perplexity Auto",
            WebProviderType.PERPLEXITY_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
        ),
        ProviderModel(
            "zai-web",
            "Z.ai 网页当前模型",
            WebProviderType.ZAI_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
        ),
        ProviderModel(
            "arena-guest-web",
            "Arena Max 免登录",
            WebProviderType.ARENA_GUEST,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
        ),
        // 竞技场比赛生图模型（45 个）统一在 ArenaImageModels.kt 维护，
        // 对齐 WebAI2API lmarena 适配器清单（仅纯文生图模型）
        ProviderModel(
            "raphael-image-web",
            "Raphael 免费生图",
            WebProviderType.RAPHAEL_WEB,
            setOf(ProviderCapability.IMAGE_GENERATION),
            enabled = true,
            upstreamModelId = "raphael-basic"
        ),
        ProviderModel(
            "zhipu-glm-web",
            "清言GLM 登录·快速",
            WebProviderType.ZHIPU_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            upstreamModelId = "",
        ),
        ProviderModel(
            "zhipu-glm-think-web",
            "清言GLM 登录·深度思考",
            WebProviderType.ZHIPU_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            upstreamModelId = "zero",
        ),
        ProviderModel(
            "zhipu-glm-research-web",
            "清言GLM 登录·深度研究",
            WebProviderType.ZHIPU_WEB,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            upstreamModelId = "deep_research",
        ),
        // WorkBuddy 国内/国际版各 16 个模型由 WorkBuddyModelCatalog 展开（对照精排小说调用契约）
    ) + WorkBuddyModelCatalog.providerModels() + ArenaImageModels.providerModels() + AppBmsImageClient.MODELS.map { m ->
        // AppBMS 46 个生图模型：手机流量/住宅宽带直连上游（上游封机房 IP），无需登录
        ProviderModel(
            "bms-$m",
            m,
            WebProviderType.APPBMS,
            setOf(ProviderCapability.IMAGE_GENERATION),
            enabled = true,
            upstreamModelId = m,
        )
    } + NanoAiVideoClient.MODELS.map { m ->
        // NanoAI 视频（k_grok=可灵 / v_fuse）：手机流量直连上游（上游经 CF 防护，
        // 机房 IP 大概率 1010），无需登录。publicId 含 kling/t2v 词元供下游归类
        ProviderModel(
            m.publicId,
            m.displayName,
            WebProviderType.NANOAI_VIDEO,
            setOf(ProviderCapability.VIDEO_GENERATION),
            enabled = true,
            upstreamModelId = m.upstreamModelType,
        )
    } + listOf(
        // 百炼模型体验页匿名通道（额度绑浏览器 Cookie，「清空」后重新捕获即刷新）
        ProviderModel(
            "qwen3.8-max",
            "百炼千问3.8-Max",
            WebProviderType.BAILIAN,
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            enabled = true,
            upstreamModelId = "qwen3.8-max",
        )
    )

    val enabledModels: List<ProviderModel> get() =
        (models.filter { it.enabled } + runtimeModels.filter { it.enabled })
            .distinctBy { it.publicModelId.lowercase() }
    val chatModels: List<ProviderModel> get() = enabledModels.filter {
        ProviderCapability.CHAT in it.capabilities
    }
    val imageModels: List<ProviderModel> get() = enabledModels.filter {
        ProviderCapability.IMAGE_GENERATION in it.capabilities
    }
    val videoModels: List<ProviderModel> get() = enabledModels.filter {
        ProviderCapability.VIDEO_GENERATION in it.capabilities
    }

    fun find(publicModelId: String): ProviderModel? = enabledModels.firstOrNull {
        it.publicModelId.equals(publicModelId, ignoreCase = true)
    }

    fun setRuntimeModels(models: List<ProviderModel>) {
        runtimeModels = models.distinctBy { it.publicModelId }
    }
}

object WebCookieSnapshot {
    fun merge(headers: List<String>): String = headers
        .flatMap { it.split(';') }
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains('=') }
        .distinct()
        .joinToString("; ")

    fun chatGptAccessToken(sessionJson: String): String = runCatching {
        Json.parseToJsonElement(sessionJson).jsonObject["accessToken"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("")

    /** Visitor cookies are present before login, so they must not be accepted as a Doubao account. */
    fun hasDoubaoLoginCookie(header: String): Boolean {
        val names = header.split(';').mapNotNull { part ->
            part.substringBefore('=', "").trim().lowercase().takeIf(String::isNotBlank)
        }.toSet()
        return names.any { it == "sessionid" || it == "sessionid_ss" || it == "sid_guard" }
    }
}
