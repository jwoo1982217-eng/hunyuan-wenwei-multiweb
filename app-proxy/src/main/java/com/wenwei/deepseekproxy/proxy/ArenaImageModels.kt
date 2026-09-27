package com.wenwei.deepseekproxy.proxy

/**
 * 竞技场（arena.ai）生图模型目录。
 *
 * 模型清单来自 arena.ai/image/direct 页面内嵌的官方注册表（实时抓取，45 个
 * userSelectable 纯生图模型），剔除了 3 个必须上传参考图的编辑模型
 * （qwen-image-edit-2511 / seededit-3.0 / wan2.5-i2i-preview，当前自动化不支持图生图）。
 * 注意：gpt-image-2 不在官方清单内（泄露后已下架），不要凭传闻添加。
 * 对外模型 id 统一 `arena-<publicName>`。
 */
object ArenaImageModels {

    data class Entry(val publicId: String, val displayName: String, val upstreamId: String)

    private const val PREFIX = "arena-"

    val all: List<Entry> = listOf(
        // 页面默认（Max 路由器，boss-bandit）：不切换选择器
        Entry("arena-image-web", "竞技场 Max 智能路由（默认）", ""),
        // ── 官方注册表纯生图模型（按厂商归类）──
        Entry(PREFIX + "gpt-image-1", "竞技场 GPT Image 1", "gpt-image-1"),
        Entry(PREFIX + "krea-2-large", "竞技场 Krea 2 Large", "krea-2-large"),
        Entry(PREFIX + "krea-2-medium", "竞技场 Krea 2 Medium", "krea-2-medium"),
        Entry(PREFIX + "krea-2-turbo", "竞技场 Krea 2 Turbo", "krea-2-turbo"),
        Entry(PREFIX + "nano-banana", "竞技场 Nano Banana", "gemini-2.5-flash-image-preview"),
        Entry(PREFIX + "nano-banana-2-lite", "竞技场 Nano Banana 2 Lite", "gemini-3.1-flash-lite-image"),
        Entry(PREFIX + "flux-2-pro", "竞技场 FLUX 2 Pro", "flux-2-pro"),
        Entry(PREFIX + "flux-2-dev", "竞技场 FLUX 2 Dev", "flux-2-dev"),
        Entry(PREFIX + "flux-1-kontext-pro", "竞技场 FLUX 1 Kontext Pro", "flux-1-kontext-pro"),
        Entry(PREFIX + "flux-1-kontext-dev", "竞技场 FLUX 1 Kontext Dev", "flux-1-kontext-dev"),
        Entry(PREFIX + "seedream-5.0-pro", "竞技场 Seedream 5.0 Pro", "seedream-5.0-pro"),
        Entry(PREFIX + "seedream-5.0-lite", "竞技场 Seedream 5.0 Lite", "seedream-5.0-lite"),
        Entry(PREFIX + "seedream-4.5", "竞技场 Seedream 4.5", "seedream-4.5"),
        Entry(PREFIX + "seedream-3", "竞技场 Seedream 3", "seedream-3"),
        Entry(PREFIX + "qwen-image-2.0-pro", "竞技场 Qwen Image 2.0 Pro", "qwen-image-2.0-pro"),
        Entry(PREFIX + "qwen-image-2.0", "竞技场 Qwen Image 2.0", "qwen-image-2.0"),
        Entry(PREFIX + "qwen-image-2512", "竞技场 Qwen Image 2512", "qwen-image-2512"),
        Entry(PREFIX + "hunyuan-image-2.1", "竞技场 混元 Image 2.1", "hunyuan-image-2.1"),
        Entry(PREFIX + "grok-imagine-image-quality", "竞技场 Grok Imagine 高质", "grok-imagine-image-quality"),
        Entry(PREFIX + "grok-imagine-image", "竞技场 Grok Imagine", "grok-imagine-image"),
        Entry(PREFIX + "recraft-v4", "竞技场 Recraft V4", "recraft-v4"),
        Entry(PREFIX + "recraft-v3", "竞技场 Recraft V3", "recraft-v3"),
        Entry(PREFIX + "ideogram-v3-quality", "竞技场 Ideogram V3 高质量", "ideogram-v3-quality"),
        Entry(PREFIX + "wan2.7-image-pro", "竞技场 万相 2.7 Image Pro", "wan2.7-image-pro"),
        Entry(PREFIX + "wan2.7-image", "竞技场 万相 2.7 Image", "wan2.7-image"),
        Entry(PREFIX + "wan2.6-image", "竞技场 万相 2.6 Image", "wan2.6-image"),
        Entry(PREFIX + "wan2.6-t2i", "竞技场 万相 2.6 文生图", "wan2.6-t2i"),
        Entry(PREFIX + "wan2.5-t2i-preview", "竞技场 万相 2.5 文生图", "wan2.5-t2i-preview"),
        Entry(PREFIX + "uni-1.1-max", "竞技场 Uni 1.1 Max", "uni-1.1-max"),
        Entry(PREFIX + "uni-1.1", "竞技场 Uni 1.1", "uni-1.1"),
        Entry(PREFIX + "photon", "竞技场 Photon", "photon"),
        Entry(PREFIX + "lucid-origin", "竞技场 Lucid Origin", "lucid-origin"),
        Entry(PREFIX + "hidream-o1-image", "竞技场 HiDream O1", "hidream-o1-image"),
        Entry(PREFIX + "cosmos3-super", "竞技场 Cosmos 3 Super", "cosmos3-super"),
        Entry(PREFIX + "cosmos3-super-agentic", "竞技场 Cosmos 3 Super Agentic", "cosmos3-super-agentic"),
        Entry(PREFIX + "eren-v2", "竞技场 Eren V2", "eren-v2"),
        Entry(PREFIX + "lo-bah-png", "竞技场 Lo Bah PNG", "lo-bah-png"),
        Entry(PREFIX + "sungod", "竞技场 Sungod（腾讯）", "sungod"),
        Entry(PREFIX + "auto-bear-v2", "竞技场 Auto Bear V2（阿里）", "auto-bear-v2"),
        Entry(PREFIX + "zen-bear-v14r", "竞技场 Zen Bear V14R（阿里）", "zen-bear-v14r"),
        Entry(PREFIX + "zen-bear-v4", "竞技场 Zen Bear V4（阿里）", "zen-bear-v4"),
    )

    fun providerModels(): List<ProviderModel> = all.map { entry ->
        ProviderModel(
            publicModelId = entry.publicId,
            displayName = entry.displayName,
            providerType = WebProviderType.ARENA_GUEST,
            capabilities = setOf(ProviderCapability.IMAGE_GENERATION),
            upstreamModelId = entry.upstreamId,
        )
    }
}
