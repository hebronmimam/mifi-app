package com.hebronmimam.mifi.core

import org.json.JSONArray
import org.json.JSONObject

/** Small helpers for the loosely-typed JSON both devices return. */
fun JSONObject.str(vararg keys: String): String? {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val v = opt(k)?.toString()?.trim() ?: continue
        if (v.isNotEmpty()) return v
    }
    return null
}

fun JSONObject.num(vararg keys: String): Double? {
    for (k in keys) {
        val v = str(k) ?: continue
        val d = v.replace(",", "").toDoubleOrNull()
        if (d != null && !d.isNaN()) return d
    }
    return null
}

fun JSONObject.long(vararg keys: String): Long? = num(*keys)?.toLong()

fun JSONObject.merge(other: JSONObject?): JSONObject {
    if (other == null) return this
    val it = other.keys()
    while (it.hasNext()) {
        val k = it.next()
        if (!has(k) || str(k) == null) put(k, other.opt(k))
    }
    return this
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
    return o
}
