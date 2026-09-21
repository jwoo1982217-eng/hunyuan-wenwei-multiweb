package com.wenwei.deepseekproxy.proxy

import com.wenwei.deepseekproxy.workbuddy.WorkBuddyClient
import com.wenwei.deepseekproxy.workbuddy.WorkBuddyCredentialStore
import com.wenwei.deepseekproxy.workbuddy.WorkBuddyUnauthorizedException
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkBuddy（网页登录授权 → 插件 token）聊天路由。
 *
 * 凭证由 WorkBuddyLoginActivity 网页登录后写入 WorkBuddyCredentialStore；
 * 这里只做读取、临期续期与调用，遇到 401/403 强制刷新一次后重试，
 * 刷新仍失败则提示重新登录。消息文本经 [ChatMessage.textContent] 提取，
 * 图片附件暂不支持（WorkBuddy 插件协议未验证多模态入参）。
 */
internal suspend fun RoutingContext.handleWorkBuddyChat(
    model: ProviderModel,
    req: ChatCompletionRequest,
    completionId: String,
    created: Int,
) {
    val region = when (model.providerType) {
        WebProviderType.WORKBUDDY_INTL -> WorkBuddyClient.Region.INTERNATIONAL
        else -> WorkBuddyClient.Region.DOMESTIC
    }
    if (!WorkBuddyCredentialStore.isLoggedIn(region)) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            mapOf("error" to "请先在「浏览器」页完成 WorkBuddy（${region.displayName}）网页登录授权")
        )
        return
    }
    ProxyServer.run {
        respondProviderText(req, completionId, created) { emit ->
            withContext(Dispatchers.IO) {
                val messages = req.messages.map { it.role to it.textContent() }
                val temperature = req.temperature ?: 0.6
                val credential = WorkBuddyCredentialStore.freshCredential(region)
                    ?: throw WorkBuddyUnauthorizedException("WorkBuddy 登录已失效，请重新登录")
                try {
                    WorkBuddyClient.chatStream(
                        region, credential, model.upstreamModelId, messages, temperature, emit
                    )
                } catch (e: WorkBuddyUnauthorizedException) {
                    LogStore.w("WorkBuddy", "对话鉴权失效，尝试刷新 token 后重试：${e.message}")
                    val rotated = WorkBuddyCredentialStore.refreshCredential(region) ?: throw e
                    WorkBuddyClient.chatStream(
                        region, rotated, model.upstreamModelId, messages, temperature, emit
                    )
                }
            }
        }
    }
}
