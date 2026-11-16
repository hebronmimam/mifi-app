package com.hebronmimam.mifi.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Settings and small state. Backed up with the usage history; the device
 * password is NOT here (see [Secrets]).
 */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Minutes between background checks: 2, 5 or 15. "When it matters" drops to 1. */
    var checkMinutes: Int
        get() = sp.getInt("checkMinutes", 5)
        set(v) = sp.edit().putInt("checkMinutes", v).apply()

    var stayIn: Boolean
        get() = sp.getBoolean("stayIn", true)
        set(v) = sp.edit().putBoolean("stayIn", v).apply()

    var smsNotify: Boolean
        get() = sp.getBoolean("smsNotify", true)
        set(v) = sp.edit().putBoolean("smsNotify", v).apply()

    var statusBar: Boolean
        get() = sp.getBoolean("statusBar", true)
        set(v) = sp.edit().putBoolean("statusBar", v).apply()

    var lowAlert: Boolean
        get() = sp.getBoolean("lowAlert", true)
        set(v) = sp.edit().putBoolean("lowAlert", v).apply()

    var recordUsage: Boolean
        get() = sp.getBoolean("recordUsage", true)
        set(v) = sp.edit().putBoolean("recordUsage", v).apply()

    /** Router only: alert above 70 °C. */
    var hotAlert: Boolean
        get() = sp.getBoolean("hotAlert", true)
        set(v) = sp.edit().putBoolean("hotAlert", v).apply()

    /** Check every minute while the battery is low or the internet is down. */
    var fastWhenLow: Boolean
        get() = sp.getBoolean("fastWhenLow", true)
        set(v) = sp.edit().putBoolean("fastWhenLow", v).apply()

    /** Every 15 minutes while the phone is in battery saver. */
    var slowInSaver: Boolean
        get() = sp.getBoolean("slowInSaver", true)
        set(v) = sp.edit().putBoolean("slowInSaver", v).apply()

    /** Look for app updates on GitHub once a day. */
    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit().putBoolean("autoUpdate", v).apply()

    /** The device the app shows: "kind:model". */
    var selected: String?
        get() = sp.getString("selected", null)
        set(v) = sp.edit().putString("selected", v).apply()

    /** Known devices: id -> {kind, model, name, host, lastSeen}. */
    fun devices(): JSONObject = runCatching { JSONObject(sp.getString("devices", "{}")!!) }.getOrDefault(JSONObject())
    fun saveDevices(o: JSONObject) = sp.edit().putString("devices", o.toString()).apply()

    /** Free-form UI preferences the web screens keep (tab, period, …). */
    fun ui(): JSONObject = runCatching { JSONObject(sp.getString("ui", "{}")!!) }.getOrDefault(JSONObject())
    fun saveUi(o: JSONObject) = sp.edit().putString("ui", o.toString()).apply()

    fun getString(k: String): String? = sp.getString(k, null)
    fun putString(k: String, v: String?) = sp.edit().putString(k, v).apply()
    fun getLong(k: String, d: Long = 0) = sp.getLong(k, d)
    fun putLong(k: String, v: Long) = sp.edit().putLong(k, v).apply()
}
