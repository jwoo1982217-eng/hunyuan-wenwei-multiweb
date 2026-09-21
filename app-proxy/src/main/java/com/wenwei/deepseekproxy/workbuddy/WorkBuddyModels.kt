package com.wenwei.deepseekproxy.workbuddy

import com.wenwei.deepseekproxy.proxy.ProviderCapability
import com.wenwei.deepseekproxy.proxy.ProviderModel
import com.wenwei.deepseekproxy.proxy.WebProviderType

/**
 * WorkBuddy 线路模型清单（对照精排小说 NovelProofreader 的 BuiltInWorkBuddyModel）。
 *
 * upstreamName 为官方 /v2/chat/completions 接受的 model id；
 * 对外模型 id 加区域前缀：国内版 `workbuddy-<模型>`，国际版 `workbuddy-intl-<模型>`。
 */
object WorkBuddyModelCatalog {

    data class Entry(val displayName: String, val upstreamName: String)

    val all: List<Entry> = listOf(
        Entry("Auto", "auto"),
        Entry("混元 4 Preview", "hy4-preview"),
        Entry("混元 3", "hy3"),
        Entry("混元 3-X", "hy3-x"),
        Entry("DeepSeek V4.1 Flash", "deepseek-v4.1-flash"),
        Entry("DeepSeek V4 Pro", "deepseek-v4-pro"),
        Entry("GLM 5.3", "glm-5.3"),
        Entry("GLM 5.3 Flash", "glm-5.3-flash"),
        Entry("GLM 5.2", "glm-5.2"),
        Entry("GLM 5.1", "glm-5.1"),
        Entry("GLM 5V Turbo", "glm-5v-turbo"),
        Entry("Kimi K3.1", "kimi-k3-1"),
        Entry("Kimi K2.8 Preview", "kimi-k2.8-preview"),
        Entry("Kimi K2.7", "kimi-k2.7"),
        Entry("Kimi K2.6", "kimi-k2.6"),
        Entry("MiniMax M3", "minimax-m3"),
    )

    private val chatCaps = setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING)

    /** 展开为网关模型目录：国内版 16 个（只用国内版，对齐精排小说调用链路）。 */
    fun providerModels(): List<ProviderModel> = all.map { entry ->
        ProviderModel(
            publicModelId = "workbuddy-" + entry.upstreamName,
            displayName = "WorkBuddy " + entry.displayName,
            providerType = WebProviderType.WORKBUDDY_DOMESTIC,
            capabilities = chatCaps,
            upstreamModelId = entry.upstreamName,
        )
    }
}
