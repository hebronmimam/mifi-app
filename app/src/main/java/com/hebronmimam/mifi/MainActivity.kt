package com.hebronmimam.mifi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.hebronmimam.mifi.bridge.Bridge
import com.hebronmimam.mifi.bridge.Host
import com.hebronmimam.mifi.service.MonitorService
import org.json.JSONObject
import java.io.OutputStreamWriter

/** Hosts the web screens. All device and phone access goes through [Bridge]. */
class MainActivity : Activity(), Host {
    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private val app get() = application as MiFiApp

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        web = WebView(this)
        web.setBackgroundColor(0xFF121212.toInt())
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/") { path ->
                val stream = app.web.open(path) ?: return@addPathHandler null
                WebResourceResponse(mime(path), if (mime(path).startsWith("text") || path.endsWith(".js") || path.endsWith(".json")) "utf-8" else null, stream)
            }
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                if (u.scheme == "https") openUrl(u.toString())
                return true
            }
        }

        bridge = Bridge(app, web, this)
        bridge.attach()

        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // The status bar is padded natively; the bottom (gesture bar, keyboard) goes to CSS.
            v.setPadding(sys.left, sys.top, sys.right, 0)
            val d = resources.displayMetrics.density
            val o = JSONObject()
                .put("bottom", (sys.bottom / d).toInt())
                .put("ime", (ime.bottom / d).toInt())
            bridge.insets = o
            bridge.event("insets", o)
            WindowInsetsCompat.CONSUMED
        }

        setContentView(web)
        intent?.getStringExtra(EXTRA_ROUTE)?.let { bridge.route(it) }
        web.loadUrl("https://appassets.androidplatform.net/index.html")

        MonitorService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { bridge.route(it) }
    }

    override fun onResume() {
        super.onResume()
        bridge.event("visible", JSONObject().put("on", true))
        app.calls.execute { runCatching { app.devices.refresh() } }
    }

    override fun onPause() {
        bridge.live(false)
        bridge.event("visible", JSONObject().put("on", false))
        super.onPause()
    }

    override fun onDestroy() {
        bridge.detach()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Back is handed to the screens first")
    override fun onBackPressed() {
        bridge.event("back", JSONObject())
    }

    // ---- Host ----

    override fun exit() = moveTaskToBack(true).let { }

    override fun exportUsage() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "mifi-usage.csv")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT)
    }

    @Deprecated("Activity result API needs androidx.activity; this app stays dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            bridge.event("export", JSONObject().put("ok", false))
            return
        }
        app.calls.execute {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)!!.use { os -> OutputStreamWriter(os, Charsets.UTF_8).use { app.usage.export(it) } }
            }.isSuccess
            bridge.event("export", JSONObject().put("ok", ok))
        }
    }

    override fun openOriginal() {
        startActivity(Intent(this, OriginalPageActivity::class.java))
    }

    override fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(i, null))
    }

    override fun haptic(kind: String) {
        val c = when (kind) {
            "confirm" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
            "reject" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        web.performHapticFeedback(c)
    }

    override fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else {
            bridge.event("permission", JSONObject().put("notifications", true))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            bridge.event("permission", JSONObject().put("notifications", ok))
            MonitorService.start(this)
        }
    }

    override fun notificationsAllowed(): Boolean =
        getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()

    override fun batteryExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    override fun requestBatteryExemption() {
        if (batteryExempt()) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    override fun openInstallSettings() {
        if (Build.VERSION.SDK_INT >= 26) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
    }

    override fun openWifiSettings() {
        val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
        runCatching { startActivity(i) }
    }

    override fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        private const val REQ_EXPORT = 7
        private const val REQ_NOTIF = 8

        fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
    }
}
