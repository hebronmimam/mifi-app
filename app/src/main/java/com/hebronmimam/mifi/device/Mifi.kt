package com.hebronmimam.mifi.device

import com.hebronmimam.mifi.core.long
import com.hebronmimam.mifi.core.num
import com.hebronmimam.mifi.core.objects
import com.hebronmimam.mifi.core.str
import com.hebronmimam.mifi.net.Lan
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

/**
 * ZTE MiFi (goform web API), e.g. the MTN U10LS. See docs/PROTOCOL.md.
 * [schemeStore] keeps the hashing scheme read from this box's own scripts.
 */
class Mifi(
    private val lan: Lan,
    override val host: String,
    private val schemeStore: (MifiScheme?) -> MifiScheme?,
) : Box {
    override val kind = KIND
    override var model: String? = null
        private set

    private var waInner = ""
    private var crVersion = ""
    private val cookies = LinkedHashMap<String, String>()
    @Volatile private var loggedIn = false

    private val base get() = "http://$host"

    // ---- raw API, also used by the web screens through the bridge ----

    fun get(cmds: String, multi: Boolean = true, extra: Map<String, String> = emptyMap(), timeoutMs: Int = 8000): JSONObject {
        val q = StringBuilder("isTest=false")
        if (multi) q.append("&multi_data=1")
        q.append("&cmd=").append(enc(cmds))
        extra.forEach { (k, v) -> q.append('&').append(enc(k)).append('=').append(enc(v)) }
        q.append("&_=").append(System.currentTimeMillis())
        val r = lan.request("$base/goform/goform_get_cmd_process?$q", headers = headers(), timeoutMs = timeoutMs)
        keepCookies(r.header("Set-Cookie"))
        if (!r.ok) throw java.io.IOException("HTTP ${r.code}")
        return parse(r.body)
    }

    /** A write. Adds the AD token; if the box refuses it, tries the other AD hash once and keeps the one that works. */
    fun set(goformId: String, params: Map<String, String> = emptyMap(), withAd: Boolean = true): JSONObject {
        val first = post(goformId, params, if (withAd) scheme() else null)
        if (!withAd || !refused(first)) return first
        val s = scheme()
        val alt = s.copy(ad = if (s.ad == "md5") "sha256" else "md5")
        val second = post(goformId, params, alt)
        if (!refused(second)) schemeStore(s.copy(ad = alt.ad))
        return second
    }

    private fun post(goformId: String, params: Map<String, String>, adScheme: MifiScheme?): JSONObject {
        val form = StringBuilder("isTest=false&goformId=").append(enc(goformId))
        params.forEach { (k, v) -> form.append('&').append(enc(k)).append('=').append(enc(v)) }
        if (adScheme != null) {
            ensureVersions()
            val rd = get("RD").str("RD") ?: ""
            form.append("&AD=").append(enc(adScheme.ad(waInner, crVersion, rd)))
        }
        val r = lan.request(
            "$base/goform/goform_set_cmd_process", "POST",
            headers() + ("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
            form.toString().toByteArray(Charsets.UTF_8),
        )
        keepCookies(r.header("Set-Cookie"))
        if (!r.ok) throw java.io.IOException("HTTP ${r.code}")
        return parse(r.body)
    }

    private fun refused(o: JSONObject) = o.str("result").let { it == "failure" || it == "fail" }

    // ---- Box ----

    override fun probe(): Boolean {
        val o = runCatching { get("model_name,wa_inner_version,cr_version,loginfo", timeoutMs = 4000) }.getOrNull() ?: return false
        if (!o.has("model_name") && !o.has("loginfo")) return false
        model = o.str("model_name") ?: model ?: "ZTE MiFi"
        o.str("wa_inner_version")?.let { waInner = it }
        o.str("cr_version")?.let { crVersion = it }
        loggedIn = o.str("loginfo") == "ok"
        return true
    }

    override val hasSession get() = loggedIn

    override fun signedIn(): Boolean {
        loggedIn = runCatching { get("loginfo").str("loginfo") == "ok" }.getOrDefault(false)
        return loggedIn
    }

    override fun signIn(password: String): SignIn {
        val s = scheme()
        val ld = if (s.login == 2) (get("LD").str("LD") ?: return SignIn(false, "The MiFi did not answer", rejected = false)) else ""
        val r = set("LOGIN", mapOf("password" to s.loginPassword(password, ld)), withAd = s.loginAd)
        val result = r.str("result")
        if (result == "0" || result == "4") {
            loggedIn = true
            return SignIn(true)
        }
        loggedIn = false
        val info = runCatching { get("psw_fail_num_str,login_lock_time") }.getOrNull()
        val left = info?.num("psw_fail_num_str")?.toInt()
        val lock = info?.num("login_lock_time")?.toInt() ?: 0
        return when {
            result == "1" || (left != null && left <= 0) ->
                SignIn(false, "Too many tries. The MiFi is locked for a few minutes.", retryAfterSec = if (lock > 0) lock else 300, attemptsLeft = 0)
            result == "3" -> SignIn(false, "That password didn't work.", attemptsLeft = left)
            else -> SignIn(false, "The MiFi refused the sign-in.", attemptsLeft = left)
        }
    }

    override fun signOut() {
        runCatching { set("LOGOUT") }
        loggedIn = false
        cookies.clear()
    }

    override fun live(): JSONObject {
        val o = get("realtime_rx_thrpt,realtime_tx_thrpt,signalbar,network_type,ppp_status,battery_value,battery_vol_percent,battery_charging", timeoutMs = 3000)
        return JSONObject()
            .put("rxRate", o.long("realtime_rx_thrpt") ?: 0)
            .put("txRate", o.long("realtime_tx_thrpt") ?: 0)
            .put("bars", o.num("signalbar")?.toInt())
            .put("battery", battery(o))
            .put("charging", o.str("battery_charging") == "1")
            .put("connected", connected(o))
            .put("t", System.currentTimeMillis())
    }

    override fun status(withClients: Boolean): Status {
        val o = get(STATUS_CMDS)
        if (o.str("loginfo") != "ok") loggedIn = false else loggedIn = true
        o.str("model_name")?.let { model = it }
        val clients = if (withClients && loggedIn) runCatching { clients() }.getOrNull() else null
        val rx = o.long("monthly_rx_bytes")
        val tx = o.long("monthly_tx_bytes")
        return Status(
            kind = KIND, model = model, signedIn = loggedIn,
            battery = battery(o),
            charging = o.str("battery_charging") == "1",
            connected = connected(o),
            network = o.str("network_type"),
            provider = o.str("network_provider", "network_provider_fullname", "spn_name_data"),
            bars = o.num("signalbar")?.toInt(),
            rsrp = o.num("lte_rsrp", "rsrp", "Z5g_rsrp"),
            snr = o.num("lte_snr", "lte_sinr", "sinr", "Z5g_SINR"),
            rxRate = o.long("realtime_rx_thrpt"),
            txRate = o.long("realtime_tx_thrpt"),
            counter = if (rx != null || tx != null) (rx ?: 0) + (tx ?: 0) else null,
            monthBytes = if (rx != null || tx != null) (rx ?: 0) + (tx ?: 0) else null,
            sessionSec = o.long("realtime_time"),
            smsUnread = o.num("sms_unread_num")?.toInt(),
            ssid = o.str("SSID1"),
            clients = clients,
            firmware = o.str("wa_inner_version", "cr_version"),
            raw = o,
        )
    }

    fun clients(): List<Client> {
        val o = get("station_list", multi = false)
        val list = o.optJSONArray("station_list")?.objects().orEmpty()
        return list.mapNotNull { c ->
            val mac = c.str("mac_addr", "mac", "MAC")?.uppercase() ?: return@mapNotNull null
            Client(mac, c.str("hostname", "host_name", "name")?.takeIf { it != "--" }, c.str("ip_addr", "ip", "IP"))
        }
    }

    override fun messages(limit: Int): List<Message> {
        val o = get(
            "sms_data_total", multi = false,
            extra = mapOf(
                "page" to "0", "data_per_page" to limit.toString(), "mem_store" to "1",
                "tags" to "10", "order_by" to "order by id desc",
            ),
        )
        return o.optJSONArray("messages")?.objects().orEmpty().map { m ->
            val tag = m.str("tag") ?: "0"
            Message(
                id = m.str("id") ?: "",
                number = m.str("number") ?: "",
                text = ucs2Decode(m.str("content") ?: ""),
                time = parseDate(m.str("date")),
                unread = tag == "1",
                outgoing = tag == "2" || tag == "3",
            )
        }
    }

    override fun markRead(ids: List<String>): Boolean =
        ok(set("SET_MSG_READ", mapOf("msg_id" to ids.joinToString(";", postfix = ";"), "tag" to "0")))

    override fun delete(ids: List<String>): Boolean =
        ok(set("DELETE_SMS", mapOf("msg_id" to ids.joinToString(";", postfix = ";"), "notCallback" to "true")))

    override fun send(number: String, text: String): Boolean = ok(
        set(
            "SEND_SMS",
            mapOf(
                "Number" to number,
                "sms_time" to smsTime(),
                "MessageBody" to ucs2Encode(text),
                "ID" to "-1",
                "encode_type" to if (isGsm7(text)) "GSM7_default" else "UNICODE",
            ),
        )
    )

    // ---- helpers ----

    /** The hashing scheme, read once from the box's own scripts and kept. */
    fun scheme(): MifiScheme {
        schemeStore(null)?.let { return it }
        val found = runCatching { MifiScheme.analyze(scripts()) }.getOrDefault(MifiScheme.DEFAULT)
        return schemeStore(found) ?: found
    }

    /** The MiFi's own web-page scripts (read-only GETs, same origin only). */
    private fun scripts(): String {
        val page = lan.request("$base/index.html", headers = headers()).body
        val srcs = Regex("""<script[^>]+src=["']([^"'?#]+)""").findAll(page).map { it.groupValues[1] }
            .filter { !it.startsWith("http") && !it.startsWith("//") }
            .toMutableList()
        // The sign-in code often lives in scripts loaded later by a loader.
        listOf("js/service.js", "js/config/config.js", "js/util.js", "js/set.js", "js/login.js", "js/config/menu.js")
            .forEach { if (it !in srcs) srcs.add(it) }
        val sb = StringBuilder()
        for (src in srcs.take(24)) {
            val path = src.removePrefix("./").removePrefix("/")
            val r = runCatching { lan.request("$base/$path", headers = headers(), timeoutMs = 6000) }.getOrNull() ?: continue
            if (r.ok && r.body.length < 3_000_000) sb.append('\n').append(r.body)
        }
        return sb.toString()
    }

    private fun ensureVersions() {
        if (waInner.isNotEmpty() || crVersion.isNotEmpty()) return
        val o = get("wa_inner_version,cr_version")
        waInner = o.str("wa_inner_version") ?: ""
        crVersion = o.str("cr_version") ?: ""
    }

    private fun headers(): Map<String, String> {
        val h = linkedMapOf(
            "Referer" to "$base/index.html",
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )
        if (cookies.isNotEmpty()) h["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return h
    }

    private fun keepCookies(set: List<String>) {
        for (c in set) {
            val kv = c.substringBefore(';')
            val k = kv.substringBefore('=').trim()
            if (k.isNotEmpty()) cookies[k] = kv.substringAfter('=', "").trim()
        }
    }

    private fun ok(o: JSONObject) = o.str("result").let { it == "success" || it == "0" }

    companion object {
        const val KIND = "mifi"

        private const val STATUS_CMDS =
            "model_name,loginfo,battery_value,battery_vol_percent,battery_charging,battery_pers," +
                "network_type,network_provider,network_provider_fullname,ppp_status,signalbar," +
                "lte_rsrp,lte_snr,lte_sinr,realtime_rx_thrpt,realtime_tx_thrpt,realtime_time," +
                "realtime_rx_bytes,realtime_tx_bytes,monthly_rx_bytes,monthly_tx_bytes,monthly_time," +
                "sms_unread_num,sms_received_flag,SSID1,wa_inner_version,cr_version,modem_main_state," +
                "sim_status,roam_setting_option,wifi_coverage,sysIdleTimeToSleep,net_select"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun parse(body: String): JSONObject =
            runCatching { JSONObject(body.trim().ifEmpty { "{}" }) }.getOrElse { JSONObject() }

        fun battery(o: JSONObject): Int? {
            val v = o.num("battery_value", "battery_vol_percent")?.toInt() ?: return null
            return v.takeIf { it in 0..100 }
        }

        fun connected(o: JSONObject): Boolean? = when (o.str("ppp_status")) {
            null -> null
            "ppp_connected", "ipv6_connected", "ipv4_ipv6_connected" -> true
            else -> false
        }

        fun ucs2Decode(hex: String): String {
            val h = hex.trim()
            if (h.isEmpty() || h.length % 4 != 0 || !h.all { it.isLetterOrDigit() }) return h
            val sb = StringBuilder(h.length / 4)
            var i = 0
            while (i + 4 <= h.length) {
                val c = h.substring(i, i + 4).toIntOrNull(16) ?: return h
                sb.append(c.toChar())
                i += 4
            }
            return sb.toString()
        }

        fun ucs2Encode(s: String): String {
            val sb = StringBuilder(s.length * 4)
            for (ch in s) sb.append(String.format("%04X", ch.code))
            return sb.toString()
        }

        private const val GSM7 =
            "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

        fun isGsm7(s: String) = s.all { GSM7.indexOf(it) >= 0 }

        /** "yy,MM,dd,HH,mm,ss,+tz" in the box's clock, read as the phone's time zone. */
        fun parseDate(d: String?): Long {
            val p = d?.split(',', ';')?.map { it.trim() } ?: return 0
            if (p.size < 6) return 0
            val n = p.take(6).map { it.toIntOrNull() ?: return 0 }
            val c = Calendar.getInstance()
            c.set(2000 + n[0] % 100, n[1] - 1, n[2], n[3], n[4], n[5])
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        fun smsTime(now: Calendar = Calendar.getInstance()): String {
            val tzHours = TimeZone.getDefault().getOffset(now.timeInMillis) / 3_600_000
            fun two(v: Int) = v.toString().padStart(2, '0')
            return listOf(
                two(now.get(Calendar.YEAR) % 100), two(now.get(Calendar.MONTH) + 1), two(now.get(Calendar.DAY_OF_MONTH)),
                two(now.get(Calendar.HOUR_OF_DAY)), two(now.get(Calendar.MINUTE)), two(now.get(Calendar.SECOND)),
            ).joinToString(";") + ";" + (if (tzHours >= 0) "+" else "-") + kotlin.math.abs(tzHours)
        }
    }
}
