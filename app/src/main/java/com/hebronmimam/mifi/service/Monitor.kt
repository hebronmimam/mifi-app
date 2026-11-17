package com.hebronmimam.mifi.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import com.hebronmimam.mifi.MiFiApp
import com.hebronmimam.mifi.device.Message
import com.hebronmimam.mifi.device.Mifi
import com.hebronmimam.mifi.device.Status
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date

/** One background check: battery, new messages, usage. Returns the delay until the next. */
class Monitor(private val app: MiFiApp) {
    @Volatile var onSms: ((List<Message>) -> Unit)? = null

    fun check(): Long {
        val prefs = app.prefs
        val devices = app.devices
        val s: Status? = runCatching { devices.refresh(withClients = prefs.recordUsage) }.getOrNull()
        val id = devices.id

        if (s != null && id != null) {
            s.battery?.let { prefs.putString("lastBattery:$id", "$it|${System.currentTimeMillis()}") }
            lowBattery(s)
            hot(s)
            if (prefs.smsNotify && s.signedIn) runCatching { messages(id, s) }
            if (prefs.recordUsage) runCatching { app.recorder.record(id, s, app.lan.myIp()) }
        }
        showStatus(s)
        if (prefs.autoUpdate) runCatching { app.updater.dailyCheck() }

        val pm = app.getSystemService(PowerManager::class.java)
        val matters = s != null && (s.connected == false || (s.battery != null && !s.charging && s.battery <= 20))
        val minutes = when {
            prefs.slowInSaver && pm.isPowerSaveMode -> 15
            prefs.fastWhenLow && matters -> 1
            else -> prefs.checkMinutes.coerceIn(1, 60)
        }
        return minutes * 60_000L
    }

    fun showStatus(s: Status? = app.devices.last) {
        if (!MonitorService.running) return
        shown = key(s)
        app.getSystemService(NotificationManager::class.java).notify(Notifier.ID_STATUS, statusNotification(app, s))
    }

    @Volatile private var shown: String? = null
    private fun key(s: Status?) = s?.let { "${app.devices.id}|${it.battery}|${it.charging}|${it.connected}|${it.network}|${it.clients?.joinToString(",") { c -> c.mac + c.name }}" }

    /** Any fresh reading (the app open, a sign-in) also updates the status bar, not only the timed check. */
    fun readingChanged() {
        val s = app.devices.last ?: return
        if (key(s) != shown) showStatus(s)
    }

    private fun lowBattery(s: Status) {
        val b = s.battery ?: return
        val prefs = app.prefs
        val armed = prefs.sp.getBoolean("lowArmed", true)
        if (s.charging || b >= 25) {
            if (!armed) prefs.sp.edit().putBoolean("lowArmed", true).apply()
            Notifier.cancelLow(app)
            return
        }
        if (b <= 20 && armed && prefs.lowAlert) {
            Notifier.low(app, b)
            prefs.sp.edit().putBoolean("lowArmed", false).apply()
        }
    }

    /** Router only: one alert above 70 °C, re-armed below 65 °C. */
    private fun hot(s: Status) {
        val t = s.tempC ?: return
        val prefs = app.prefs
        val armed = prefs.sp.getBoolean("hotArmed", true)
        if (t < 65) {
            if (!armed) prefs.sp.edit().putBoolean("hotArmed", true).apply()
            return
        }
        if (t > 70 && armed && prefs.hotAlert) {
            Notifier.hot(app, t.toInt())
            prefs.sp.edit().putBoolean("hotArmed", false).apply()
        }
    }

    /** Notify each unread message once. The first run only learns what is already there. */
    private fun messages(id: String, s: Status) {
        if (s.smsUnread == 0) return
        val list = app.devices.session { it.messages(30) }
        val unread = list.filter { it.unread && !it.outgoing }
        val key = "smsSeen:$id"
        val stored = app.prefs.getString(key)
        val seen = stored?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() } ?: mutableSetOf()
        fun k(m: Message) = "${m.id}|${m.time}"
        val fresh = if (stored == null) emptyList() else unread.filter { k(it) !in seen }
        val name = app.prefs.devices().optJSONObject(id)?.optString("name") ?: "MiFi"
        fresh.forEach { Notifier.sms(app, name, it) }
        seen.addAll(unread.map { k(it) })
        app.prefs.putString(key, JSONArray(seen.toList().takeLast(300)).toString())
        if (fresh.isNotEmpty()) onSms?.invoke(fresh)
    }

    companion object {
        fun statusNotification(ctx: Context, s: Status?): Notification {
            val app = ctx.applicationContext as MiFiApp
            val id = app.devices.id
            val name = id?.let { app.prefs.devices().optJSONObject(it)?.optString("name") } ?: "MiFi"
            if (s == null) {
                val text = when (app.devices.problem) {
                    "wifi" -> "Not on Wi-Fi"
                    "unknown" -> "This Wi-Fi isn't a MiFi or router the app knows"
                    else -> "Can't reach it right now"
                }
                val sel = app.prefs.selected
                val last = sel?.let { app.prefs.getString("lastBattery:$it") }?.split('|')
                val title = if (last != null && last.size == 2) {
                    val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last[1].toLong()))
                    "MiFi · ${last[0]}% at $at"
                } else "MiFi"
                return Notifier.status(ctx, null, title, text)
            }
            val parts = mutableListOf<String>()
            if (s.connected == false) parts += "No internet" else s.network?.let { parts += it }
            s.provider?.let { parts += it }
            s.clients?.let { parts += if (it.size == 1) "1 device" else "${it.size} devices" }
            if (!s.signedIn) parts += "Sign in to see more"
            val title = if (s.battery != null) "$name · ${s.battery}%" + (if (s.charging) " · charging" else "") else name
            // The number is the icon only while "Battery in the status bar" is on; otherwise the app's own icon.
            val percent = if (app.prefs.statusBar && (s.kind == Mifi.KIND || s.battery != null)) s.battery else null
            return Notifier.status(ctx, percent, title, parts.joinToString(" · ").ifEmpty { "Connected" }, deviceLines(app, id, s))
        }

        /** Who is on the box now, for the expanded notification: the owner's names first, this phone marked. */
        private fun deviceLines(app: MiFiApp, id: String?, s: Status): List<String> {
            val list = s.clients ?: return emptyList()
            val names = id?.let { app.usage.aliases(it) } ?: emptyMap()
            val me = app.lan.myIp()
            // Names only: addresses stay out of the shade, which shows on the lock screen too. This phone is "You".
            val (mine, others) = list.partition { me != null && it.ip == me }
            return mine.map { "You" } + others.map { c ->
                val n = names[c.mac.uppercase()] ?: c.name?.takeIf { it.isNotBlank() && it != "*" } ?: "Unnamed device"
                if (c.wifi) n else "$n · cable"
            }
        }
    }
}
