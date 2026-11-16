package com.hebronmimam.mifi.device

import com.hebronmimam.mifi.core.jsonOf
import org.json.JSONArray
import org.json.JSONObject

/** Thrown when the box answers but says the session is gone. */
class NotSignedIn : Exception("Not signed in")

data class SignIn(
    val ok: Boolean,
    val error: String? = null,
    /** Seconds until the box will accept another try (its own lock). */
    val retryAfterSec: Int = 0,
    val attemptsLeft: Int? = null,
    /** The box said no to the password (as opposed to not answering). */
    val rejected: Boolean = !ok,
) {
    fun toJson(): JSONObject = jsonOf("ok" to ok, "error" to error, "retryAfter" to retryAfterSec, "attemptsLeft" to attemptsLeft, "rejected" to rejected)
}

data class Client(val mac: String, val name: String?, val ip: String?, val wifi: Boolean = true) {
    fun toJson(): JSONObject = jsonOf("mac" to mac, "name" to name, "ip" to ip, "wifi" to wifi)
}

data class Message(
    val id: String,
    val number: String,
    val text: String,
    /** Epoch millis, or 0 when the box gave no usable date. */
    val time: Long,
    val unread: Boolean,
    val outgoing: Boolean = false,
) {
    fun toJson(): JSONObject = jsonOf(
        "id" to id, "number" to number, "text" to text, "time" to time,
        "unread" to unread, "outgoing" to outgoing,
    )
}

/** One reading of the box, in the app's own terms. Unknown values stay null. */
data class Status(
    val kind: String,
    val model: String?,
    val signedIn: Boolean,
    val battery: Int? = null,
    val charging: Boolean = false,
    val connected: Boolean? = null,
    val network: String? = null,
    val provider: String? = null,
    val bars: Int? = null,
    val rsrp: Double? = null,
    val snr: Double? = null,
    /** Bytes per second. */
    val rxRate: Long? = null,
    val txRate: Long? = null,
    /** A running byte counter for the whole box (resets are handled by the recorder). */
    val counter: Long? = null,
    val monthBytes: Long? = null,
    val sessionSec: Long? = null,
    val uptimeSec: Long? = null,
    val smsUnread: Int? = null,
    val tempC: Double? = null,
    val ssid: String? = null,
    val clients: List<Client>? = null,
    val firmware: String? = null,
    /** Every field the box returned, for screens that read device-specific values. */
    val raw: JSONObject = JSONObject(),
) {
    fun toJson(): JSONObject = jsonOf(
        "kind" to kind, "model" to model, "signedIn" to signedIn,
        "battery" to battery, "charging" to charging, "connected" to connected,
        "network" to network, "provider" to provider, "bars" to bars,
        "rsrp" to rsrp, "snr" to snr, "rxRate" to rxRate, "txRate" to txRate,
        "monthBytes" to monthBytes, "sessionSec" to sessionSec, "uptimeSec" to uptimeSec,
        "smsUnread" to smsUnread, "tempC" to tempC, "ssid" to ssid, "firmware" to firmware,
        "clients" to clients?.let { l -> JSONArray().also { a -> l.forEach { a.put(it.toJson()) } } },
        "raw" to raw,
    )
}

/** A supported box: the ZTE MiFi or the Tozed home router. */
interface Box {
    val kind: String
    val host: String
    val model: String?

    /** True when the box at [host] is this kind. Works before signing in. */
    fun probe(): Boolean

    /** Whether this client holds a session, without asking the box. */
    val hasSession: Boolean
    fun signedIn(): Boolean
    fun signIn(password: String): SignIn
    fun signOut()
    fun status(withClients: Boolean = true): Status

    /** The few fields the live dial needs, cheap enough for once a second. */
    fun live(): JSONObject

    fun messages(limit: Int = 60): List<Message>
    fun markRead(ids: List<String>): Boolean
    fun delete(ids: List<String>): Boolean
    fun send(number: String, text: String): Boolean
}
