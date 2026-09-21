package com.wenwei.deepseekproxy.workbuddy

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WorkBuddy 插件授权登录页。
 *
 * 官方授权流由客户端驱动：先向服务器申请 state，用户在返回的 authUrl 里完成网页登录，
 * 应用后台轮询 token 接口，取到 token 后自动保存（[WorkBuddyCredentialStore]）并关闭页面。
 * 之后本地网关即可用该 token 直连 /v2/chat/completions，实现「网页登录 → API 调用」。
 */
class WorkBuddyLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private var pendingRegion: WorkBuddyClient.Region? = null
    private var pendingState: String = ""
    @Volatile private var polling = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkBuddyCredentialStore.attach(this)
        val region = runCatching {
            WorkBuddyClient.Region.valueOf(intent.getStringExtra(EXTRA_REGION).orEmpty())
        }.getOrNull() ?: WorkBuddyClient.Region.DOMESTIC

        title = "WorkBuddy 登录（${region.displayName}）"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 247, 242))
        }
        root.addView(toolbar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        statusView = TextView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            textSize = 14f
            setTextColor(Color.rgb(62, 87, 75))
            text = "正在获取授权链接…"
        }
        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = true
            }
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                    setStatus("WorkBuddy 页面证书校验失败，请检查系统时间和网络。")
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    setStatus("系统 WebView 进程退出，请返回后重新打开登录页。")
                    return true
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        openGrant(region)
    }

    private fun openGrant(region: WorkBuddyClient.Region) {
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) {
                runCatching { WorkBuddyClient.requestLoginSession(region) }
            }
            session.fold(
                onSuccess = {
                    pendingRegion = region
                    pendingState = it.state
                    setStatus("请在页面里登录 WorkBuddy，完成后会自动取回凭证。")
                    webView.loadUrl(it.authUrl)
                    startPolling(region, it.state)
                },
                onFailure = {
                    setStatus("获取授权链接失败：${it.message}")
                    Toast.makeText(this@WorkBuddyLoginActivity, "获取授权链接失败", Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun startPolling(region: WorkBuddyClient.Region, state: String) {
        if (polling) return
        polling = true
        lifecycleScope.launch {
            repeat(POLL_TIMES) { index ->
                if (index > 0) delay(POLL_INTERVAL_MS)
                if (isFinishing || isDestroyed) return@launch
                val credential = withContext(Dispatchers.IO) {
                    runCatching { WorkBuddyClient.pollGrant(region, state) }.getOrNull()
                } ?: return@repeat
                WorkBuddyCredentialStore.save(region, credential)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WorkBuddyLoginActivity, "WorkBuddy 登录成功", Toast.LENGTH_SHORT).show()
                }
                setResult(RESULT_OK)
                finish()
                return@launch
            }
            polling = false
            setStatus("仍未检测到登录完成，可在页面里确认后点右上角「检查」。")
        }
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
        super.onDestroy()
    }

    private fun toolbar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        addView(actionLabel("返回") {
            finish()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(TextView(context).apply {
            text = "WorkBuddy 登录"
            textSize = 20f
            setTextColor(Color.rgb(25, 27, 25))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(actionLabel("刷新") {
            webView.reload()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(actionLabel("检查") {
            val region = pendingRegion ?: return@actionLabel
            val state = pendingState
            if (state.isNotBlank()) {
                polling = false
                setStatus("正在检查登录状态…")
                startPolling(region, state)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun actionLabel(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(62, 87, 75))
        setPadding(dp(14), 0, dp(14), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_REGION = "workbuddy_region"
        private const val POLL_TIMES = 200
        private const val POLL_INTERVAL_MS = 3_000L

        fun intent(context: Context, region: WorkBuddyClient.Region) =
            Intent(context, WorkBuddyLoginActivity::class.java)
                .putExtra(EXTRA_REGION, region.name)
    }
}
