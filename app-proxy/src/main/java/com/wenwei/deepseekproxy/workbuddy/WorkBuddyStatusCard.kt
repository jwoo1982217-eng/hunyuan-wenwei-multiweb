package com.wenwei.deepseekproxy.workbuddy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * 「浏览器」页里的 WorkBuddy 登录状态卡片（自包含）。
 *
 * WorkBuddy 走的是插件 token 授权流而非 Cookie 抓取，因此不进内置供应商下拉，
 * 由本卡片提供「网页登录授权 / 退出登录」入口；登录完成后本地网关即可通过
 * workbuddy-web / workbuddy-intl-web 模型直连 API。返回本页时自动刷新状态。
 */
@Composable
fun WorkBuddyStatusCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    Column(modifier) {
        HorizontalDivider()
        Text(
            "WorkBuddy 网页登录 → API（插件授权，非 Cookie）",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        // 只用国内版（对齐精排小说调用链路）
        listOf(WorkBuddyClient.Region.DOMESTIC).forEach { region ->
            val loggedIn = remember(tick, region) { WorkBuddyCredentialStore.isLoggedIn(region) }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (loggedIn) "WorkBuddy ${region.displayName}：已授权" else "WorkBuddy ${region.displayName}：未登录",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = {
                    context.startActivity(WorkBuddyLoginActivity.intent(context, region))
                }) { Text(if (loggedIn) "重新登录" else "网页登录") }
                if (loggedIn) {
                    TextButton(onClick = {
                        WorkBuddyCredentialStore.clear(region)
                        tick++
                    }) { Text("退出") }
                }
            }
        }
    }
}
