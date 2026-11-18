package com.hebronmimam.mifi.bridge

import android.os.Handler
import android.os.Looper
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import android.webkit.WebView
import com.hebronmimam.mifi.BuildConfig
import com.hebronmimam.mifi.MiFiApp
import com.hebronmimam.mifi.device.Mifi
import com.hebronmimam.mifi.device.NotSignedIn
import com.hebronmimam.mifi.device.Router
import com.hebronmimam.mifi.service.MonitorService
import com.hebronmimam.mifi.service.Notifier
import com.hebronmimam.mifi.update.WebBundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What the bridge needs from the screen that hosts it. */
interface Host {
    fun exportUsage()
    fun openOriginal()
    fun share(text: String)
    fun haptic(kind: String)
    fun requestNotifications()
    fun requestBatteryExemption()
    fun batteryExempt(): Boolean
    fun notificationsAllowed(): Boolean
    fun openInstallSettings()
    fun openWifiSettings()
    fun openUrl(url: String)
    fun exit()
}

/**
 * The only way the web screens reach the phone and the box. Messages arrive as
 * JSON {id, m, a}; replies go back as {id, ok, r | e}; events as {ev, d}.
 * Only pages served from the app's own asset origin can talk to it.
 */
class Bridge(private val app: MiFiApp, private val web: WebView, private val host: Host) {
    private val main = Handler(Looper.getMainLooper())
    private var proxy: JavaScriptReplyProxy? = null
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mifi-live").apply { isDaemon = true } }
    private var liveTask: ScheduledFuture<*>? = null
    private var watchTask: ScheduledFuture<*>? = null
    private val recording = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var pendingRoute: String? = null
    @Volatile var insets: JSONObject = JSONObject()

    private val sink: (String, JSONObject) -> Unit = { type, data -> event(type, data) }

    fun attach() {
        WebViewCompat.addWebMessageListener(web, "MiFiNative", setOf(ORIGIN)) { _, message, origin, isMainFrame, reply ->
            if (!isMainFrame || origin.toString().trimEnd('/') != ORIGIN) return@addWebMessageListener
            proxy = reply
            handle(message)
        }
        app.addSink(sink)
        app.monitor.onSms = { list -> event("sms", JSONObject().put("messages", JSONArray(list.map { it.toJson() }))) }
    }

    fun detach() {
        app.removeSink(sink)
        live(false)
        ticker.shutdownNow()
    }

    fun route(r: String) {
        pendingRoute = r
        event("route", JSONObject().put("route", r))
    }

    fun event(type: String, data: JSONObject) {
        val msg = JSONObject().put("ev", type).put("d", data).toString()
        main.post { runCatching { proxy?.postMessage(msg) } }
    }

    private fun reply(id: Any?, ok: Boolean, value: Any?) {
        val msg = JSONObject().put("id", id).put("ok", ok).put(if (ok) "r" else "e", value ?: JSONObject.NULL).toString()
        main.post { runCatching { proxy?.postMessage(msg) } }
    }

    private fun handle(message: WebMessageCompat) {
        val req = runCatching { JSONObject(message.data ?: return) }.getOrNull() ?: return
        val id = req.opt("id")
        val m = req.optString("m")
        val a = req.optJSONObject("a") ?: JSONObject()
        // A few calls must run on the UI thread; everything else off it.
        if (m in UI_CALLS) {
            main.post { runCatching { reply(id, true, ui(m, a)) }.onFailure { reply(id, false, err(it)) } }
            return
        }
        app.calls.execute {
            try {
                reply(id, true, call(m, a))
            } catch (t: Throwable) {
                reply(id, false, err(t))
            }
        }
    }

    private fun err(t: Throwable): JSONObject = when (t) {
        is NotSignedIn -> JSONObject().put("code", "signin").put("message", "Sign in to do that.")
        is java.io.IOException -> JSONObject().put("code", "offline").put("message", t.message ?: "Can't reach the box.")
        is IllegalArgumentException -> JSONObject().put("code", "bad").put("message", t.message ?: "Bad request")
        else -> JSONObject().put("code", "error").put("message", t.message ?: t.javaClass.simpleName)
    }

    private fun ui(m: String, a: JSONObject): Any? {
        when (m) {
            "openOriginal" -> host.openOriginal()
            "share" -> host.share(a.optString("text"))
            "haptic" -> host.haptic(a.optString("kind", "tick"))
            "notif.request" -> host.requestNotifications()
            "battery.exempt" -> host.requestBatteryExemption()
            "usage.export" -> host.exportUsage()
            "update.allow" -> host.openInstallSettings()
            "wifi.settings" -> host.openWifiSettings()
            "openUrl" -> {
                val u = a.optString("url")
                require(u.startsWith("https://github.com/") || u.startsWith("https://api.github.com/")) { "Only GitHub links open from here" }
                host.openUrl(u)
            }
            "exit" -> host.exit()
        }
        return true
    }

    private fun dev(a: JSONObject): String =
        a.optString("dev").ifEmpty { null } ?: app.devices.id ?: app.prefs.selected ?: throw IllegalArgumentException("No device yet")

    private fun call(m: String, a: JSONObject): Any? {
        val d = app.devices
        return when (m) {
            "hello" -> JSONObject()
                .put("app", BuildConfig.VERSION_NAME)
                .put("web", app.web.activeVersion())
                .put("native", WebBundle.NATIVE_API)
                .put("prefs", prefs())
                .put("ui", app.prefs.ui())
                .put("insets", insets)
                .put("route", pendingRoute.also { pendingRoute = null } ?: JSONObject.NULL)
                .put("state", d.state())
            "state" -> d.state()
            "refresh" -> { d.refresh(a.optBoolean("clients", true)); d.state() }
            "find" -> { d.find(force = true); d.state() }
            "live" -> { live(a.optBoolean("on")); true }
            "delay" -> app.lan.delay() ?: JSONObject.NULL

            "signIn" -> {
                val pw = a.optString("password")
                require(pw.isNotEmpty()) { "Enter the password" }
                d.signIn(pw, a.optBoolean("remember", app.prefs.stayIn)).toJson().put("state", d.state())
            }
            "signOut" -> { d.signOut(); d.state() }

            "mifi.get" -> d.session { b ->
                val mifi = b as? Mifi ?: throw IllegalArgumentException("Not a MiFi")
                mifi.get(a.getString("cmd"), a.optBoolean("multi", true), strings(a.optJSONObject("extra")))
            }
            "mifi.set" -> d.session { b ->
                val mifi = b as? Mifi ?: throw IllegalArgumentException("Not a MiFi")
                mifi.set(a.getString("goformId"), strings(a.optJSONObject("params")), a.optBoolean("ad", true))
            }
            "router.call" -> d.session { b ->
                val r = b as? Router ?: throw IllegalArgumentException("Not a router")
                r.call(a.getJSONObject("body"), a.optInt("timeout", 17000))
            }

            // The box lists by its storage number, which isn't the order texts arrived in: newest first by date.
            "sms.list" -> {
                val kept = keptUnread()
                JSONArray(d.session { it.messages(a.optInt("limit", 60)) }.sortedByDescending { it.time }.map {
                    (if (!it.unread && !it.outgoing && it.id in kept) it.copy(unread = true) else it).toJson()
                })
            }
            "sms.read" -> {
                val ids = ids(a)
                keepUnread(keptUnread() - ids.toSet())
                val ok = d.session { it.markRead(ids) }
                Notifier.clearSms(app, ids)
                ok
            }
            // Neither box can mark a text unread again, so the app keeps that mark on this phone.
            "sms.unread" -> { keepUnread(keptUnread() + ids(a)); true }
            "sms.delete" -> {
                val ids = ids(a)
                val ok = d.session { it.delete(ids) }
                keepUnread(keptUnread() - ids.toSet())
                Notifier.clearSms(app, ids)
                ok
            }
            "sms.send" -> d.session { it.send(a.getString("number"), a.getString("text")) }

            "usage.hours" -> app.usage.hours(dev(a), a.getLong("from"), a.getLong("to"))
            "usage.months" -> app.usage.months(dev(a))
            "usage.devices" -> app.usage.devices(dev(a), a.getLong("from"), a.getLong("to"))
            "usage.client" -> app.usage.clientHours(dev(a), a.getString("mac"), a.getLong("from"), a.getLong("to"))
            "usage.temps" -> app.usage.temps(dev(a), a.getLong("from"), a.getLong("to"))
            "usage.setMine" -> { app.usage.setMine(dev(a), a.getString("mac"), a.optBoolean("mine")); true }
            "usage.rename" -> { app.usage.rename(dev(a), a.getString("mac"), a.optString("name")); true }
            "usage.me" -> app.usage.meta("me:${dev(a)}") ?: JSONObject.NULL
            "usage.info" -> app.usage.info()
            "usage.clear" -> { app.usage.clear(); true }

            "prefs.get" -> prefs()
            "prefs.set" -> { setPrefs(a); prefs() }
            "ui.set" -> { app.prefs.saveUi(a); true }

            "device.list" -> d.list()
            "device.select" -> { d.select(a.getString("id")); d.state() }
            "device.rename" -> { d.rename(a.getString("id"), a.getString("name")); d.state() }
            "device.scheme" -> d.schemeInfo() ?: JSONObject.NULL

            "update.info" -> app.updater.info()
            "update.check" -> app.updater.check().also { app.updater.announce(it) }
            "update.web" -> app.updater.installWeb() ?: JSONObject.NULL
            "update.canInstall" -> app.updater.canInstallApk()
            "update.install" -> {
                if (!app.updater.canInstallApk()) JSONObject().put("needsPermission", true)
                else {
                    app.updater.progress = { done, total -> event("update", JSONObject().put("state", "downloading").put("done", done).put("total", total)) }
                    app.updater.installApk()
                    JSONObject().put("state", "confirm")
                }
            }
            "battery.isExempt" -> host.batteryExempt()
            else -> throw IllegalArgumentException("Unknown call $m")
        }
    }

    /** Texts the owner marked unread, per box, as message ids. */
    private fun keptUnread(): Set<String> =
        app.devices.id?.let { app.prefs.getString("unread:$it") }?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun keepUnread(ids: Set<String>) {
        val id = app.devices.id ?: return
        app.prefs.putString("unread:$id", ids.joinToString("\n"))
    }

    private fun prefs(): JSONObject {
        val p = app.prefs
        return JSONObject()
            .put("checkMinutes", p.checkMinutes)
            .put("stayIn", p.stayIn)
            .put("smsNotify", p.smsNotify)
            .put("statusBar", p.statusBar)
            .put("lowAlert", p.lowAlert)
            .put("recordUsage", p.recordUsage)
            .put("hotAlert", p.hotAlert)
            .put("fastWhenLow", p.fastWhenLow)
            .put("slowInSaver", p.slowInSaver)
            .put("autoUpdate", p.autoUpdate)
            .put("batteryExempt", host.batteryExempt())
            .put("notifications", host.notificationsAllowed())
    }

    private fun setPrefs(a: JSONObject) {
        val p = app.prefs
        if (a.has("checkMinutes")) p.checkMinutes = a.getInt("checkMinutes").coerceIn(1, 60)
        if (a.has("stayIn")) {
            p.stayIn = a.getBoolean("stayIn")
            if (!p.stayIn) app.devices.forgetStored()
        }
        if (a.has("smsNotify")) p.smsNotify = a.getBoolean("smsNotify")
        if (a.has("statusBar")) p.statusBar = a.getBoolean("statusBar")
        if (a.has("lowAlert")) p.lowAlert = a.getBoolean("lowAlert")
        if (a.has("recordUsage")) p.recordUsage = a.getBoolean("recordUsage")
        if (a.has("hotAlert")) p.hotAlert = a.getBoolean("hotAlert")
        if (a.has("fastWhenLow")) p.fastWhenLow = a.getBoolean("fastWhenLow")
        if (a.has("slowInSaver")) p.slowInSaver = a.getBoolean("slowInSaver")
        if (a.has("autoUpdate")) p.autoUpdate = a.getBoolean("autoUpdate")
        MonitorService.start(app)
    }

    /** 1 Hz readings for the dial while the Now screen is open. */
    @Synchronized
    fun live(on: Boolean) {
        liveTask?.cancel(false)
        liveTask = null
        if (!on || ticker.isShutdown) return
        liveTask = ticker.scheduleWithFixedDelay({
            app.devices.live()?.let { event("live", it) }
        }, 0, 1, TimeUnit.SECONDS)
    }

    /**
     * While the app is on screen, a reading every 30 s goes into the usage record, so "Today used"
     * and the Usage tab move as data is used, not only on the background check's minutes.
     */
    @Synchronized
    fun watch(on: Boolean) {
        watchTask?.cancel(false)
        watchTask = null
        if (!on || ticker.isShutdown) return
        watchTask = ticker.scheduleWithFixedDelay({
            if (recording.compareAndSet(false, true)) app.calls.execute {
                try { recordNow() } finally { recording.set(false) }
            }
        }, 0, 30, TimeUnit.SECONDS)
    }

    private fun recordNow() {
        val record = app.prefs.recordUsage
        val s = runCatching { app.devices.refresh(withClients = true) }.getOrNull() ?: return
        val id = app.devices.id ?: return
        if (!record) return
        if (runCatching { app.recorder.record(id, s, app.lan.myIp()) }.isSuccess) event("usage", JSONObject().put("dev", id))
    }

    private fun ids(a: JSONObject): List<String> {
        val arr = a.optJSONArray("ids") ?: return listOfNotNull(a.optString("id").ifEmpty { null })
        return (0 until arr.length()).map { arr.get(it).toString() }
    }

    private fun strings(o: JSONObject?): Map<String, String> {
        o ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { k -> out[k] = o.get(k).toString() }
        return out
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        private val UI_CALLS = setOf(
            "openOriginal", "share", "haptic", "notif.request", "battery.exempt", "usage.export",
            "update.allow", "wifi.settings", "openUrl", "exit",
        )
    }
}
