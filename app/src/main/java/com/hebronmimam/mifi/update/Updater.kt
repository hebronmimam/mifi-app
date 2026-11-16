package com.hebronmimam.mifi.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Base64
import com.hebronmimam.mifi.BuildConfig
import com.hebronmimam.mifi.MiFiApp
import com.hebronmimam.mifi.net.Lan
import com.hebronmimam.mifi.service.Notifier
import org.json.JSONObject
import java.io.File

/**
 * New versions come from this project's GitHub releases:
 *  - an APK asset (`mifi-<version>.apk`) on a `v<version>` release, installed
 *    with one tap through Android's own installer;
 *  - a web-screens asset (`web-<n>.zip` + `web-<n>.zip.sig`), installed quietly
 *    once its signature checks out.
 */
class Updater(private val app: MiFiApp) {
    private val api = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}"
    @Volatile var latest: JSONObject? = null; private set
    @Volatile var progress: ((Long, Long) -> Unit)? = null

    fun info(): JSONObject = JSONObject()
        .put("app", BuildConfig.VERSION_NAME)
        .put("web", app.web.activeVersion())
        .put("repo", BuildConfig.UPDATE_REPO)
        .put("checkedAt", app.prefs.getLong("update:checkedAt"))
        .put("latest", latest ?: JSONObject.NULL)

    fun check(): JSONObject {
        val r = Lan.internet("$api/releases?per_page=15", HEADERS)
        if (!r.ok) throw java.io.IOException("GitHub answered ${r.code}")
        val releases = org.json.JSONArray(r.body)
        var apk: JSONObject? = null
        var web: JSONObject? = null
        for (i in 0 until releases.length()) {
            val rel = releases.getJSONObject(i)
            if (rel.optBoolean("draft")) continue
            val assets = rel.optJSONArray("assets") ?: continue
            val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.optString("name") }
            val tag = rel.optString("tag_name")
            if (apk == null && tag.startsWith("v")) {
                byName.values.firstOrNull { it.optString("name").endsWith(".apk") }?.let { a ->
                    apk = JSONObject()
                        .put("version", tag.removePrefix("v"))
                        .put("url", a.optString("browser_download_url"))
                        .put("size", a.optLong("size"))
                        .put("notes", rel.optString("body").take(2000))
                        .put("page", rel.optString("html_url"))
                }
            }
            if (web == null) {
                for ((name, a) in byName) {
                    val m = WEB_ASSET.matchEntire(name) ?: continue
                    val sig = byName["$name.sig"] ?: continue
                    web = JSONObject()
                        .put("version", m.groupValues[1].toInt())
                        .put("url", a.optString("browser_download_url"))
                        .put("sig", sig.optString("browser_download_url"))
                    break
                }
            }
            if (apk != null && web != null) break
        }
        val out = JSONObject()
            .put("app", apk ?: JSONObject.NULL)
            .put("web", web ?: JSONObject.NULL)
            .put("appNewer", apk?.let { newer(it.optString("version"), BuildConfig.VERSION_NAME) } ?: false)
            .put("webNewer", web?.let { it.optInt("version") > app.web.activeVersion() } ?: false)
        latest = out
        app.prefs.putLong("update:checkedAt", System.currentTimeMillis())
        return out
    }

    /** Once a day from the background check: take new screens, announce a new APK once. */
    fun dailyCheck() {
        if (System.currentTimeMillis() - app.prefs.getLong("update:checkedAt") < 24 * 3_600_000L) return
        val l = check()
        if (l.optBoolean("webNewer")) runCatching { installWeb() }
        if (l.optBoolean("appNewer")) {
            val v = l.getJSONObject("app").optString("version")
            if (app.prefs.getString("update:told") != v) {
                Notifier.update(app, v)
                app.prefs.putString("update:told", v)
            }
        }
    }

    /** Download and apply the newest web screens. Returns the new version, or null. */
    fun installWeb(): Int? {
        val w = (latest ?: check()).optJSONObject("web") ?: return null
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val zip = File(dir, "web.zip")
        download(w.getString("url"), zip)
        val sigText = Lan.internet(w.getString("sig"), HEADERS).body.trim()
        val sig = runCatching { Base64.decode(sigText, Base64.DEFAULT) }.getOrNull() ?: return null
        val v = app.web.install(zip, sig)
        zip.delete()
        return v
    }

    fun canInstallApk(): Boolean = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

    /** Download the APK and hand it to Android's installer, which asks the owner to tap Install. */
    fun installApk() {
        val a = (latest ?: check()).optJSONObject("app") ?: throw IllegalStateException("No app update")
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "mifi.apk")
        download(a.getString("url"), apk)
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val sid = pi.createSession(params)
        pi.openSession(sid).use { s ->
            s.openWrite("mifi.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val cb = PendingIntent.getBroadcast(app, sid, Intent(app, InstallReceiver::class.java), flags)
            s.commit(cb.intentSender)
        }
    }

    private fun download(url: String, to: File) {
        val c = Lan.stream(url, HEADERS)
        try {
            val total = c.contentLengthLong
            c.inputStream.use { inp ->
                to.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (done > 80L shl 20) throw java.io.IOException("Download too large")
                        progress?.invoke(done, total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private val HEADERS = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "mifi-app")
        private val WEB_ASSET = Regex("""web-(\d+)\.zip""")

        /** Dotted version compare: is [a] newer than [b]? */
        fun newer(a: String, b: String): Boolean {
            val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}

/** Android's installer reports back here; it may need the owner to confirm. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val app = context.applicationContext as MiFiApp
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> app.emit("update", JSONObject().put("state", "installed"))
            else -> app.emit(
                "update",
                JSONObject().put("state", "failed").put("message", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"),
            )
        }
    }
}
