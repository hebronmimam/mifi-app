package com.hebronmimam.mifi

import android.annotation.SuppressLint
import android.app.Activity
import android.net.ConnectivityManager
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The box's own web page, for anything this app doesn't cover. No bridge, no
 * file access; the app's process is bound to the Wi-Fi while it is open so the
 * page loads even when Android prefers mobile data.
 */
class OriginalPageActivity : Activity() {
    private lateinit var web: WebView
    private val cm get() = getSystemService(ConnectivityManager::class.java)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MiFiApp
        val wifi = app.lan.wifi()
        if (wifi != null) cm.bindProcessToNetwork(wifi)
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.webViewClient = WebViewClient()
        setContentView(web)
        val gw = app.lan.gateway(wifi) ?: app.devices.box?.host
        if (gw == null) {
            web.loadData("<body style='background:#121316;color:#F1F2F5;font:16px sans-serif;padding:24px'>Join the box's Wi-Fi first.</body>", "text/html", "utf-8")
        } else {
            web.loadUrl("http://$gw/")
        }
    }

    @Deprecated("Plain Activity back handling")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        cm.bindProcessToNetwork(null)
        super.onDestroy()
    }
}
