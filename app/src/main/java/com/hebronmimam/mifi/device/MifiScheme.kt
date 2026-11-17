package com.hebronmimam.mifi.device

import com.hebronmimam.mifi.core.Hash
import org.json.JSONObject

/**
 * How a ZTE firmware hashes its sign-in password and its write token (AD).
 * The variants differ between firmwares, and a wrong sign-in hash counts as a
 * failed attempt, so the app reads the scheme from the box's own scripts
 * before the first sign-in instead of guessing.
 */
data class MifiScheme(
    /** 0 = base64(password), 1 = sha256(password), 2 = sha256(sha256(password) + LD). */
    val login: Int,
    /** Hex digests in upper case (the common ZTE web code calls toUpperCase). */
    val upper: Boolean,
    /** "md5" or "sha256" for the AD write token. */
    val ad: String,
    /** The LOGIN post carries an AD too. */
    val loginAd: Boolean,
    /** True when found in the box's scripts, false when defaults were used. */
    val detected: Boolean,
) {
    fun hash(s: String): String = Hash.sha256(s).let { if (upper) it.uppercase() else it }

    fun adHash(s: String): String =
        (if (ad == "md5") Hash.md5(s) else Hash.sha256(s)).let { if (upper && ad != "md5") it.uppercase() else it }

    fun loginPassword(password: String, ld: String): String = when (login) {
        0 -> b64(password)
        1 -> hash(b64(password))
        else -> hash(hash(password) + ld)
    }

    fun ad(waInner: String, crVersion: String, rd: String): String = adHash(adHash(waInner + crVersion) + rd)

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION).put("login", login).put("upper", upper).put("ad", ad)
        .put("loginAd", loginAd).put("detected", detected)

    companion object {
        /** Bumped when [analyze] changes, so a scheme read by older code is read again. */
        private const val VERSION = 2

        val DEFAULT = MifiScheme(login = 2, upper = true, ad = "md5", loginAd = false, detected = false)

        private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

        fun fromJson(o: JSONObject?): MifiScheme? = o?.takeIf { it.optInt("v") == VERSION }?.let {
            MifiScheme(
                it.optInt("login", 2), it.optBoolean("upper", true), it.optString("ad", "md5"),
                it.optBoolean("loginAd", false), it.optBoolean("detected", false),
            )
        }

        private val SHA_FLAG = Regex("""WEB_ATTR_IF_SUPPORT_SHA256\s*[:=]\s*["']?(\d)""")
        private val UPPER_AFTER_HASH = Regex(
            """(?:SHA256|sha256|hex_sha256|paswordAlgorithmsCookie)\s*\([^;]{0,200}?\)\s*\)?\s*\.toUpperCase\(\)"""
        )
        /** The bundled SHA256 picks its hex alphabet from a flag: `for(var i=g?"0123456789ABCDEF":…`. */
        private val HEX_CASE_FLAG = Regex("""([A-Za-z_$][\w$]*)\s*\?\s*["']0123456789ABCDEF["']""")
        private val LOGIN_REQUEST = Regex("""goformId\s*[:=]\s*["']LOGIN["']""")

        /** Read the scheme out of the concatenated scripts of the box's web page. */
        fun analyze(js: String): MifiScheme {
            if (js.isBlank()) return DEFAULT
            val flag = SHA_FLAG.find(js)?.groupValues?.get(1)?.toIntOrNull()
            val usesLd = js.contains("\"LD\"") || js.contains("'LD'") || js.contains("cmd=LD") || js.contains("getLD") || js.contains("nv:\"LD\"")
            val login = when {
                flag != null && flag in 0..2 -> if (flag == 0 && usesLd) 2 else flag
                usesLd -> 2
                else -> 0
            }

            // What paswordAlgorithmsCookie() is on this firmware (SHA256 on most, MD5 on some).
            val pac = js.indexOf("function paswordAlgorithmsCookie").takeIf { it >= 0 }
                ?.let { js.substring(it, (it + 160).coerceAtMost(js.length)) }.orEmpty()
            val pacAlgo = if (pac.contains("md5", ignoreCase = true)) "md5" else "sha256"

            // Upper case either from an explicit .toUpperCase(), or from the bundled SHA256's hex flag.
            val upper = UPPER_AFTER_HASH.containsMatchIn(js) || sha256HexIsUpper(js)

            // The LOGIN request object itself: does it carry an AD? (Many firmwares skip AD for LOGIN.)
            val loginAt = LOGIN_REQUEST.find(js)?.range?.first ?: indexOfToken(js, "LOGIN")
            val loginObj = if (loginAt >= 0) js.substring(loginAt, js.indexOf('}', loginAt).let { if (it < 0) js.length else it }.coerceAtMost(loginAt + 600)) else ""
            val loginAd = Regex("""[{,\s]AD\s*:|["']AD["']\s*:""").containsMatchIn(loginObj)

            // The AD token: hash(hash(wa_inner_version + cr_version) + RD), with whichever hash builds it.
            val adAt = Regex("""\.AD\s*=|[{,]\s*AD\s*:""").find(js)?.range?.first ?: -1
            val crAt = js.indexOf("cr_version", js.indexOf("wa_inner_version").coerceAtLeast(0))
            val adWin = when {
                adAt >= 0 -> js.substring((adAt - 400).coerceAtLeast(0), adAt)
                crAt >= 0 -> js.substring((crAt - 600).coerceAtLeast(0), (crAt + 600).coerceAtMost(js.length))
                else -> ""
            }
            val ad = when {
                adWin.contains("hex_md5") || adWin.contains("md5(") -> "md5"
                adWin.contains("paswordAlgorithmsCookie") -> pacAlgo
                adWin.contains("SHA256") || adWin.contains("sha256") -> "sha256"
                else -> "md5"
            }
            return MifiScheme(login, upper, ad, loginAd, detected = flag != null || loginObj.isNotEmpty())
        }

        private fun sha256HexIsUpper(js: String): Boolean {
            val at = js.indexOf("function SHA256").takeIf { it >= 0 } ?: return false
            val body = js.substring(at, (at + 6000).coerceAtMost(js.length))
            val v = HEX_CASE_FLAG.find(body)?.groupValues?.get(1) ?: return false
            return Regex("""[,;{(\s]${Regex.escape(v)}\s*=\s*(?:1|!0|true)\b""").containsMatchIn(body)
        }

        /** Index of "LOGIN" as a whole string literal (not LOGIN_MULTI_USER, not LOGOUT). */
        private fun indexOfToken(js: String, token: String): Int {
            for (q in listOf("\"$token\"", "'$token'")) {
                val i = js.indexOf(q)
                if (i >= 0) return i
            }
            return -1
        }
    }
}
