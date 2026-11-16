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
        0 -> android.util.Base64.encodeToString(password.toByteArray(), android.util.Base64.NO_WRAP)
        1 -> hash(password)
        else -> hash(hash(password) + ld)
    }

    fun ad(waInner: String, crVersion: String, rd: String): String = adHash(adHash(waInner + crVersion) + rd)

    fun toJson(): JSONObject = JSONObject()
        .put("login", login).put("upper", upper).put("ad", ad)
        .put("loginAd", loginAd).put("detected", detected)

    companion object {
        val DEFAULT = MifiScheme(login = 2, upper = true, ad = "md5", loginAd = false, detected = false)

        fun fromJson(o: JSONObject?): MifiScheme? = o?.let {
            MifiScheme(
                it.optInt("login", 2), it.optBoolean("upper", true), it.optString("ad", "md5"),
                it.optBoolean("loginAd", false), it.optBoolean("detected", false),
            )
        }

        private val SHA_FLAG = Regex("""WEB_ATTR_IF_SUPPORT_SHA256\s*[:=]\s*["']?(\d)""")
        private val UPPER_AFTER_HASH = Regex(
            """(?:SHA256|sha256|hex_sha256|paswordAlgorithmsCookie)\s*\([^;]{0,200}?\)\s*\)?\s*\.toUpperCase\(\)"""
        )

        /** Read the scheme out of the concatenated scripts of the box's web page. */
        fun analyze(js: String): MifiScheme {
            if (js.isBlank()) return DEFAULT
            val flag = SHA_FLAG.find(js)?.groupValues?.get(1)?.toIntOrNull()
            val usesLd = js.contains("\"LD\"") || js.contains("'LD'") || js.contains("cmd=LD") || js.contains("getLD")
            val login = when {
                flag != null && flag in 0..2 -> if (flag == 0 && usesLd) 2 else flag
                usesLd -> 2
                else -> 0
            }

            // The LOGIN request and the code just before it, where the password is hashed.
            val loginAt = indexOfToken(js, "LOGIN")
            val loginWin = if (loginAt >= 0) js.substring((loginAt - 2500).coerceAtLeast(0), (loginAt + 600).coerceAtMost(js.length)) else ""
            val upper = if (loginWin.isNotEmpty()) UPPER_AFTER_HASH.containsMatchIn(loginWin) else UPPER_AFTER_HASH.containsMatchIn(js)

            // The AD token: hash(hash(wa_inner_version + cr_version) + RD).
            val adAt = js.indexOf("cr_version", js.indexOf("wa_inner_version").coerceAtLeast(0))
            val adWin = if (adAt >= 0) js.substring((adAt - 600).coerceAtLeast(0), (adAt + 600).coerceAtMost(js.length)) else ""
            val ad = when {
                adWin.contains("hex_md5") || adWin.contains("md5(") -> "md5"
                adWin.contains("SHA256") || adWin.contains("sha256") -> "sha256"
                else -> "md5"
            }
            val loginAd = loginWin.contains("AD:") || loginWin.contains("\"AD\"") || loginWin.contains(".AD=")
            return MifiScheme(login, upper, ad, loginAd, detected = flag != null || loginWin.isNotEmpty())
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
