package com.wenwei.deepseekproxy

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.wenwei.deepseekproxy.proxy.LogStore

/**
 * User-enabled process keep-alive hook. Accessibility events are intentionally
 * discarded; this service never reads node/window content or performs gestures.
 */
class KeepAliveAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        LogStore.w("KeepAlive", "无障碍保活服务被系统中断")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogStore.i("KeepAlive", "无障碍服务已连接；本地网关仍只能由用户在主界面显式启动")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        LogStore.w("KeepAlive", "无障碍保活服务已断开")
        return super.onUnbind(intent)
    }

}
