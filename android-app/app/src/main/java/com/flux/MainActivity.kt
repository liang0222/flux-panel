package com.flux

import androidx.core.content.edit
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.URI
import android.content.res.Configuration
import org.json.JSONArray
import org.json.JSONObject


class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        setupWebView()

        setupSystemBars()
        setupRootBackground()
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // 隐藏滚动条但不影响滚动
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        webView.overScrollMode = View.OVER_SCROLL_NEVER


        setupWebViewBackground()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != APP_HOST) return null
                if (url.scheme == "https") assetLoader.shouldInterceptRequest(url)?.let { return it }
                // The reserved app origin must never fall through to a network request.
                return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (request.isForMainFrame && isBundlePage(url)) return false
                if (request.isForMainFrame && request.hasGesture() && url.scheme == "https" && url.host != APP_HOST) {
                    val intent = Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)
                    try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { }
                }
                return true
            }
        }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            webView.loadData("<p>请更新 Android System WebView 或 Chrome 后重新打开应用。</p>", "text/html; charset=UTF-8", null)
            return
        }
        WebViewCompat.addWebMessageListener(webView, "FluxPanel", setOf(APP_ORIGIN)) { view, message, sourceOrigin, isMainFrame, reply ->
            if (!isMainFrame || sourceOrigin.scheme != "https" || sourceOrigin.encodedAuthority != APP_HOST ||
                view.url?.let { isBundlePage(Uri.parse(it)) } != true) return@addWebMessageListener
            val body = message.data ?: return@addWebMessageListener
            if (body.length > 16384) return@addWebMessageListener
            val request = runCatching { JSONObject(body) }.getOrNull() ?: return@addWebMessageListener
            val id = request.opt("id") as? Number ?: return@addWebMessageListener
            val result = handlePanelRequest(request)
            reply.postMessage(result.put("id", id).toString())
        }
        webView.loadUrl(APP_URL)
    }

    private fun isBundlePage(url: Uri) = url.scheme == "https" && url.encodedAuthority == APP_HOST &&
        url.encodedPath == "/assets/index.html" && url.encodedQuery == null

    private fun isPanelAddress(value: String): Boolean {
        if (value.length > 2048 || value.any { it.isWhitespace() || it == '\\' }) return false
        val url = runCatching { URI(value).parseServerAuthority() }.getOrNull() ?: return false
        return url.scheme == "https" && url.host != null && !url.host.equals(APP_HOST, ignoreCase = true) &&
            url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null &&
            (url.port == -1 || url.port in 1..65535)
    }

    private fun handlePanelRequest(request: JSONObject): JSONObject {
        val prefs = getSharedPreferences("panel_config", MODE_PRIVATE)
        val stored = runCatching { JSONArray(prefs.getString("panel_addresses", "[]") ?: "[]") }.getOrDefault(JSONArray())
        val addresses = (0 until stored.length()).mapNotNull { stored.optJSONObject(it) }
            .filter { it.opt("name") is String && it.opt("address") is String }.toMutableList()
        val denied = JSONObject().put("ok", false)
        val action = request.opt("action") as? String ?: return denied
        if (action != "list") {
            val name = request.opt("name") as? String ?: return denied
            if (name.isBlank()) return denied
            val index = addresses.indexOfFirst { it.optString("name") == name }
            when (action) {
                "save" -> {
                    val address = request.opt("address") as? String ?: return denied
                    if (name.length > 100 || !isPanelAddress(address) || index >= 0) return denied
                    addresses.add(JSONObject().put("name", name).put("address", address)
                        .put("inx", addresses.none { it.optBoolean("inx") && isPanelAddress(it.optString("address")) }))
                    if (addresses.last().optBoolean("inx")) addresses.dropLast(1).forEach { it.put("inx", false) }
                }
                "select" -> {
                    if (index < 0 || !isPanelAddress(addresses[index].optString("address"))) return denied
                    addresses.forEachIndexed { i, entry -> entry.put("inx", i == index) }
                }
                "delete" -> {
                    if (index < 0) return denied
                    addresses.removeAll { it.optString("name") == name }
                }
                else -> return denied
            }
            prefs.edit { putString("panel_addresses", JSONArray(addresses).toString()) }
        }
        return JSONObject().put("ok", true).put("addresses", JSONArray(addresses))
    }

    private companion object {
        const val APP_HOST = WebViewAssetLoader.DEFAULT_DOMAIN
        const val APP_ORIGIN = "https://$APP_HOST"
        const val APP_URL = "$APP_ORIGIN/assets/index.html"
    }

    private fun setupSystemBars() {
        val isDarkTheme = resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

        if (isDarkTheme) {
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility =
                        window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
            }
        } else {
            window.statusBarColor = Color.WHITE
            window.navigationBarColor = Color.WHITE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility =
                        window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupSystemBars()
        setupWebViewBackground()
        setupRootBackground()
    }

    private fun setupWebViewBackground() {
        val isDarkTheme = resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        webView.setBackgroundColor(if (isDarkTheme) Color.BLACK else Color.WHITE)
    }

    private fun setupRootBackground() {
        val isDarkTheme = resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val rootView = findViewById<View>(android.R.id.content)
        rootView.setBackgroundColor(if (isDarkTheme) Color.BLACK else Color.WHITE)
    }
}
