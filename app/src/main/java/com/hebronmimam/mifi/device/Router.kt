package com.hebronmimam.mifi.device

import android.util.Base64
import com.hebronmimam.mifi.core.Hash
import com.hebronmimam.mifi.core.long
import com.hebronmimam.mifi.core.merge
import com.hebronmimam.mifi.core.num
import com.hebronmimam.mifi.core.objects
import com.hebronmimam.mifi.core.str
import com.hebronmimam.mifi.net.Lan
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Tozed ZLT home router (MTN-branded). Every call is a JSON POST to
 * /cgi-bin/http.cgi. See docs/PROTOCOL.md.
 */
class Router(private val lan: Lan, override val host: String) : Box {
    override val kind = KIND
    override var model: String? = null
        private set

    @Volatile private var sessionId = ""
    @Volatile private var token = ""

    /** One call. GETs need only the session; POSTs also carry the write token. */
    fun call(body: JSONObject, timeoutMs: Int = 8000): JSONObject {
        val b = JSONObject(body.toString())
        if (!b.has("sessionId")) b.put("sessionId", sessionId)
        val write = b.optString("method") == "POST"
        if (write && !b.has("token")) {
            if (token.isEmpty()) refreshToken()
            b.put("token", token)
        }
        val r = lan.request(
            "http://$host/cgi-bin/http.cgi", "POST",
            mapOf(
                "Content-Type" to "application/json;charset=UTF-8",
                "Accept" to "application/json, text/plain, */*",
                "Referer" to "http://$host/",
            ),
            b.toString().toByteArray(Charsets.UTF_8), timeoutMs,
        )
        if (!r.ok) throw java.io.IOException("HTTP ${r.code}")
        val o = runCatching { JSONObject(r.body.trim().ifEmpty { "{}" }) }.getOrElse { JSONObject() }
        val msg = o.str("message")
        if (msg == "NO_AUTH" || msg == "LOGIN_TIMEOUT") {
            sessionId = ""
            throw NotSignedIn()
        }
        if (write && b.optInt("cmd") != 100) runCatching { refreshToken() }
        return o
    }

    private fun get(cmd: Int, extra: Map<String, Any> = emptyMap(), timeoutMs: Int = 8000): JSONObject {
        val b = JSONObject().put("cmd", cmd).put("method", "GET")
        extra.forEach { (k, v) -> b.put(k, v) }
        return call(b, timeoutMs)
    }

    private fun post(cmd: Int, extra: Map<String, Any>): JSONObject {
        val b = JSONObject().put("cmd", cmd).put("method", "POST")
        extra.forEach { (k, v) -> b.put(k, v) }
        return call(b, 17000)
    }

    private fun refreshToken() {
        token = get(233).str("token") ?: token
    }

    override fun probe(): Boolean {
        val o = runCatching {
            call(JSONObject().put("cmd", 80).put("method", "GET").put("sessionId", ""), 4000)
        }.getOrNull() ?: return false
        if (o.length() == 0) return false
        model = o.str("model", "modelName", "device_name", "devicename", "product_name", "board_type") ?: model ?: "Home router"
        return true
    }

    override val hasSession get() = sessionId.isNotEmpty()

    override fun signedIn(): Boolean {
        if (sessionId.isEmpty()) return false
        return runCatching { get(113); true }.getOrDefault(false)
    }

    override fun signIn(password: String): SignIn {
        runCatching { call(JSONObject().put("cmd", 104).put("method", "GET").put("sessionId", ""), 4000) }
        val t = call(JSONObject().put("cmd", 232).put("method", "GET").put("sessionId", "")).str("token")
            ?: return SignIn(false, "The router did not answer", rejected = false)
        val r = call(
            JSONObject()
                .put("cmd", 100).put("method", "POST").put("sessionId", "")
                .put("username", "admin")
                .put("passwd", Hash.sha256(t + password))
                .put("isAutoUpgrade", "1").put("isCheckPasswd", "1")
                .put("token", t)
        )
        val sid = r.str("sessionId")
        if (sid != null && r.str("login_fail") == null && r.str("login_fail2") == null) {
            sessionId = sid
            runCatching { refreshToken() }
            return SignIn(true)
        }
        val times = r.num("login_times")?.toInt()
        val wait = r.num("login_time")?.toInt() ?: 0
        val left = times?.let { (3 - it).coerceAtLeast(0) }
        return if (left == 0 || wait > 0)
            SignIn(false, "Too many tries. The router is locked for a few minutes.", retryAfterSec = if (wait > 0) wait else 180, attemptsLeft = 0)
        else SignIn(false, "That password didn't work.", attemptsLeft = left)
    }

    override fun signOut() {
        runCatching { post(101, emptyMap()) }
        sessionId = ""
        token = ""
    }

    override fun live(): JSONObject {
        // Rates live in cmd 402 (the router's own home page reads them there), in kbit/s.
        val o = get(402, timeoutMs = 3000)
        return JSONObject()
            .put("rxRate", rate(sum(o, "dl_flow_rate", "dl_flow_rate_4g") ?: o.num("realtime_rx_thrpt")))
            .put("txRate", rate(sum(o, "ul_flow_rate", "ul_flow_rate_4g") ?: o.num("realtime_tx_thrpt")))
            .put("t", System.currentTimeMillis())
    }

    override fun status(withClients: Boolean): Status {
        val sys = get(113)
        val all = JSONObject().merge(sys)
        // 402 rates, 205 radio + session counters, 207 temperature, 337/1005 month flow and unread texts.
        for (cmd in intArrayOf(402, 205, 207, 337, 1005)) runCatching { all.merge(get(cmd)) }
        val clients = if (withClients) runCatching { clients() }.getOrNull() else null
        val wanRx = all.long("wan_rx_bytes")
        val wanTx = all.long("wan_tx_bytes")
        val used = flowBytes(all.num("sys_used_flow", "usedFlow"), all.str("usedFlowUnit"))
        // flow_dl/flow_ul: MB since the line came up (resets on reconnect; the recorder handles that).
        val session = sum(all, "flow_dl", "flow_ul")?.let { (it * MB).toLong() }
        val month = all.num("mon_total_flow", "mon_download_flow")?.let { (it * MB).toLong() }
        return Status(
            kind = KIND, model = model, signedIn = true,
            battery = all.num("battery_capacity")?.toInt()?.takeIf { it in 0..100 },
            charging = all.str("battery_charge_status") == "1",
            connected = all.str("data_switch", "dialMode")?.let { it == "1" },
            network = all.str("network_type_str", "network_type"),
            provider = all.str("network_operator", "operator", "spn", "plmn_name", "ISP", "isp"),
            bars = all.num("signal_lvl")?.toInt(),
            rsrp = all.num("RSRP", "rsrp", "RSRP_5G"),
            snr = all.num("SINR", "sinr", "SINR_5G"),
            rxRate = rate(sum(all, "dl_flow_rate", "dl_flow_rate_4g")),
            txRate = rate(sum(all, "ul_flow_rate", "ul_flow_rate_4g")),
            counter = if (wanRx != null || wanTx != null) (wanRx ?: 0) + (wanTx ?: 0) else session ?: used,
            monthBytes = used ?: month,
            sessionSec = dhms(all.str("onlineDuration", "online_duration")),
            uptimeSec = all.long("runTime", "uptime", "run_time"),
            tempC = all.num("device_temperature", "temperature", "temp", "chip_temp"),
            smsUnread = all.num("sms_unread")?.toInt(),
            ssid = all.str("ssid24", "ssid58"),
            clients = clients,
            firmware = all.str("fake_version", "software_version", "fwversion"),
            raw = all,
        )
    }

    fun clients(): List<Client> {
        val o = get(223)
        val list = o.optJSONArray("dhcp_list_info")?.objects().orEmpty()
        return list.mapNotNull { c ->
            val mac = c.str("mac", "MAC", "macAddr")?.uppercase() ?: return@mapNotNull null
            Client(
                mac, c.str("name", "hostname", "host_name"), c.str("ip", "ipaddr", "ip_addr", "IP"),
                wifi = c.str("interface")?.contains("wlan") ?: true,
            )
        }
    }

    override fun messages(limit: Int): List<Message> {
        val out = ArrayList<Message>()
        var page = 1
        while (out.size < limit && page <= 8) {
            val o = get(12, mapOf("page_num" to page, "subcmd" to 0))
            val items = o.str("sms_list")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            if (items.isEmpty()) break
            items.mapNotNullTo(out) { parseSms(it) }
            val total = o.num("sms_total")?.toInt() ?: 0
            if (out.size >= total) break
            page++
        }
        return out.sortedByDescending { it.time }.take(limit)
    }

    override fun markRead(ids: List<String>): Boolean = ids.all { ok(post(12, mapOf("index" to it))) }

    override fun delete(ids: List<String>): Boolean = ok(post(14, mapOf("index" to ids.joinToString(","), "subcmd" to 0)))

    override fun send(number: String, text: String): Boolean =
        ok(post(13, mapOf("phoneNo" to number, "content" to b64(text))))

    private fun ok(o: JSONObject) = o.str("message") == "0" || o.optBoolean("success", false)

    companion object {
        const val KIND = "router"

        /** The router reports rates in kbit/s on most builds; turned into bytes per second. */
        fun rate(v: Double?): Long? = v?.let { (it * 1000 / 8).toLong() }

        private const val MB = 1024.0 * 1024.0

        /** The router splits some readings across fields (5G + 4G); null only when none is there. */
        fun sum(o: JSONObject, vararg keys: String): Double? {
            val v = keys.mapNotNull { o.num(it) }
            return if (v.isEmpty()) null else v.sum()
        }

        /** "1-7-42-14" (days-hours-minutes-seconds) as seconds. */
        fun dhms(s: String?): Long? {
            val p = s?.split('-')?.map { it.trim().toLongOrNull() ?: return null } ?: return null
            if (p.size != 4) return null
            return ((p[0] * 24 + p[1]) * 60 + p[2]) * 60 + p[3]
        }

        fun flowBytes(v: Double?, unit: String?): Long? {
            v ?: return null
            val mb = when (unit?.uppercase()) {
                "TB" -> v * 1024 * 1024
                "GB" -> v * 1024
                "KB" -> v / 1024
                else -> v
            }
            return (mb * 1024 * 1024).toLong()
        }

        fun b64(s: String): String = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        /** One sms_list item: base64 of "index flag number date time text…", with spaces in the number as "$". */
        fun parseSms(item: String): Message? {
            val line = runCatching { String(Base64.decode(item.trim(), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return null
            return parseSmsLine(line)
        }

        fun parseSmsLine(line: String): Message? {
            val p = line.split(' ')
            if (p.size < 5) return null
            return Message(
                id = p[0],
                number = p[2].replace('$', ' '),
                text = p.drop(5).joinToString(" "),
                time = parseTime("${p[3]} ${p[4]}"),
                unread = p[1] == "0",
            )
        }

        private fun parseTime(s: String): Long {
            for (f in listOf("yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yy/MM/dd HH:mm:ss", "yy-MM-dd HH:mm:ss", "dd/MM/yyyy HH:mm:ss")) {
                val d = runCatching { SimpleDateFormat(f, Locale.US).apply { isLenient = false }.parse(s) }.getOrNull()
                if (d != null) return d.time
            }
            return 0
        }
    }
}
