package com.hebronmimam.mifi

import android.app.Application
import com.hebronmimam.mifi.core.Prefs
import com.hebronmimam.mifi.core.Secrets
import com.hebronmimam.mifi.device.Devices
import com.hebronmimam.mifi.net.Lan
import com.hebronmimam.mifi.service.Monitor
import com.hebronmimam.mifi.service.Notifier
import com.hebronmimam.mifi.update.Updater
import com.hebronmimam.mifi.update.WebBundle
import com.hebronmimam.mifi.usage.Recorder
import com.hebronmimam.mifi.usage.UsageDb
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MiFiApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var secrets: Secrets; private set
    lateinit var lan: Lan; private set
    lateinit var devices: Devices; private set
    lateinit var usage: UsageDb; private set
    lateinit var recorder: Recorder; private set
    lateinit var monitor: Monitor; private set
    lateinit var web: WebBundle; private set
    lateinit var updater: Updater; private set

    /** Background checks, one at a time. */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "mifi-io").apply { isDaemon = true } }
    /** Calls from the screens. Device access is serialised inside [Devices]. */
    val calls: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "mifi-call").apply { isDaemon = true } }

    private val sinks = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()
    fun addSink(s: (String, JSONObject) -> Unit) = sinks.add(s)
    fun removeSink(s: (String, JSONObject) -> Unit) = sinks.remove(s)

    /** Send an event to the open screens, if any. */
    fun emit(type: String, data: JSONObject) = sinks.forEach { runCatching { it(type, data) } }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        secrets = Secrets(this)
        lan = Lan(this)
        devices = Devices(lan, prefs, secrets)
        usage = UsageDb(this)
        recorder = Recorder(usage)
        monitor = Monitor(this)
        web = WebBundle(this)
        updater = Updater(this)
        Notifier.channels(this)
        devices.listen { emit("state", devices.state()) }
    }
}
