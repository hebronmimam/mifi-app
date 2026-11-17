package com.hebronmimam.mifi.device

import com.hebronmimam.mifi.core.Prefs
import com.hebronmimam.mifi.core.Secrets
import com.hebronmimam.mifi.core.jsonOf
import com.hebronmimam.mifi.net.Lan
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Finds the box on the current Wi-Fi, keeps its session, and remembers every box
 * the phone has met. All device traffic goes through here, one call at a time.
 */
class Devices(private val lan: Lan, private val prefs: Prefs, private val secrets: Secrets) {
    private val lock = Any()

    @Volatile var box: Box? = null; private set
    @Volatile var id: String? = null; private set
    @Volatile var last: Status? = null; private set
    @Volatile var lastAt = 0L; private set
    /** The stored password was refused (or there is none): wait for the owner. */
    @Volatile var needsSignIn = false; private set
    @Volatile var lockedUntil = 0L; private set
    /** "wifi" (not on Wi-Fi), "unknown" (a box this app doesn't know), "offline", or null. */
    @Volatile var problem: String? = null; private set

    /** When "Stay signed in" is off, the password lives only here until the app closes. */
    @Volatile private var memPassword: Pair<String, String>? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
    private fun changed() = listeners.forEach { runCatching { it() } }

    /** The Wi-Fi the current box was found on. A MiFi and a router can share 192.168.0.1, so the address alone can't tell them apart. */
    @Volatile private var foundOn: String? = null

    /** The box at the Wi-Fi gateway, probing again when the Wi-Fi or its gateway changed. */
    fun find(force: Boolean = false): Box? = synchronized(lock) {
        val net = lan.wifi()
        val gw = lan.gateway(net)
        if (gw == null) {
            if (box != null || problem != "wifi") { box = null; id = null; foundOn = null; problem = "wifi"; changed() }
            return null
        }
        val on = "$net|$gw"
        box?.let { if (it.host == gw && on == foundOn && !force) return it }
        val found = listOf<Box>(Mifi(lan, gw, ::scheme), Router(lan, gw)).firstOrNull { it.probe() }
        if (found == null) {
            box = null; id = null; foundOn = null
            problem = "unknown"
            changed()
            return null
        }
        val newId = "${found.kind}:${found.model ?: found.kind}"
        // A different box: the last reading belongs to the old one, so it must not show (or name) this one.
        if (newId != id) { needsSignIn = false; lockedUntil = 0; last = null; lastAt = 0 }
        box = found; id = newId; foundOn = on; problem = null
        register(found, newId)
        changed()
        found
    }

    private fun register(b: Box, id: String) {
        val all = prefs.devices()
        val d = all.optJSONObject(id) ?: JSONObject().put("name", if (b.kind == Mifi.KIND) "MiFi" else "Home router")
        d.put("kind", b.kind).put("model", b.model).put("host", b.host).put("lastSeen", System.currentTimeMillis())
        all.put(id, d)
        prefs.saveDevices(all)
        if (prefs.selected == null) prefs.selected = id
    }

    /** The MiFi hashing scheme, kept per device. Called with a value to store it. */
    private fun scheme(s: MifiScheme?): MifiScheme? {
        val key = "scheme:${id ?: return s}"
        if (s != null) { prefs.putString(key, s.toJson().toString()); return s }
        return prefs.getString(key)?.let { runCatching { MifiScheme.fromJson(JSONObject(it)) }.getOrNull() }
    }

    fun schemeInfo(): JSONObject? = id?.let { prefs.getString("scheme:$it") }?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun password(): String? {
        val id = id ?: return null
        memPassword?.let { if (it.first == id) return it.second }
        return secrets.get(id)
    }

    fun hasPassword() = password() != null

    /** Sign in with the stored password. Never retries a password the box refused. */
    private fun ensure(b: Box) {
        if (needsSignIn) throw NotSignedIn()
        if (System.currentTimeMillis() < lockedUntil) throw NotSignedIn()
        val pw = password() ?: run { needsSignIn = true; throw NotSignedIn() }
        val r = b.signIn(pw)
        if (r.ok) return
        if (r.retryAfterSec > 0) lockedUntil = System.currentTimeMillis() + r.retryAfterSec * 1000L
        if (r.rejected) needsSignIn = true
        changed()
        throw NotSignedIn()
    }

    /** The owner signs in. The password goes to the box and, if asked, to the Keystore. */
    fun signIn(password: String, remember: Boolean): SignIn {
        val b = find() ?: return SignIn(false, "Can't reach the box on this Wi-Fi.", rejected = false)
        val id = id ?: return SignIn(false, "Can't reach the box on this Wi-Fi.", rejected = false)
        if (System.currentTimeMillis() < lockedUntil) {
            val s = ((lockedUntil - System.currentTimeMillis()) / 1000).toInt() + 1
            return SignIn(false, "Locked by the box. Try again in ${s}s.", retryAfterSec = s, attemptsLeft = 0)
        }
        val r = synchronized(lock) { b.signIn(password) }
        if (r.ok) {
            needsSignIn = false
            lockedUntil = 0
            if (remember) { secrets.put(id, password); memPassword = null }
            else { secrets.remove(id); memPassword = id to password }
            runCatching { refresh() }
        } else if (r.retryAfterSec > 0) {
            lockedUntil = System.currentTimeMillis() + r.retryAfterSec * 1000L
        }
        changed()
        return r
    }

    fun signOut() {
        val id = id
        synchronized(lock) { box?.signOut() }
        if (id != null) secrets.remove(id)
        memPassword = null
        needsSignIn = true
        changed()
    }

    /** Keep the password from the Keystore only for this run (Stay signed in turned off). */
    fun forgetStored() {
        val id = id ?: return
        secrets.get(id)?.let { memPassword = id to it }
        secrets.remove(id)
    }

    /** Run [block] with a session, signing in once with the stored password if needed. */
    fun <T> session(block: (Box) -> T): T {
        val b = find() ?: throw IOException(problem ?: "offline")
        synchronized(lock) {
            if (!b.hasSession) ensure(b)
            return try {
                block(b)
            } catch (e: NotSignedIn) {
                ensure(b)
                block(b)
            }
        }
    }

    /** A full reading. Works signed out too, with whatever the box shows without a session. */
    fun refresh(withClients: Boolean = true): Status? {
        val b = find() ?: run { last = null; return null }
        val s = synchronized(lock) {
            if (!b.hasSession) runCatching { ensure(b) }
            var s = try {
                b.status(withClients)
            } catch (e: NotSignedIn) {
                runCatching { ensure(b) }
                runCatching { b.status(withClients) }.getOrElse { Status(b.kind, b.model, signedIn = false) }
            } catch (e: IOException) {
                problem = "offline"
                changed()
                throw e
            }
            if (!s.signedIn && b.kind == Mifi.KIND && runCatching { ensure(b) }.isSuccess) s = b.status(withClients)
            s
        }
        last = s
        lastAt = System.currentTimeMillis()
        problem = null
        changed()
        return s
    }

    fun live(): JSONObject? {
        val b = box ?: return null
        // A session that timed out while the app was away signs itself back in here, once.
        val o = runCatching {
            synchronized(lock) {
                try { b.live() } catch (e: NotSignedIn) { ensure(b); b.live() }
            }
        }.getOrNull() ?: return null
        // The live tick carries the battery too: keep the reading (and the status bar) in step with it.
        val l = last
        if (l != null && o.has("battery") && !o.isNull("battery")) {
            val bat = o.optInt("battery")
            val chg = o.optBoolean("charging", l.charging)
            if (bat != l.battery || chg != l.charging) { last = l.copy(battery = bat, charging = chg); changed() }
        }
        return o
    }

    // ---- known boxes ----

    fun list(): JSONArray {
        val all = prefs.devices()
        val out = JSONArray()
        val it = all.keys()
        while (it.hasNext()) {
            val k = it.next()
            val d = all.getJSONObject(k)
            out.put(JSONObject(d.toString()).put("id", k).put("here", k == id).put("selected", k == prefs.selected))
        }
        return out
    }

    fun rename(devId: String, name: String) {
        val all = prefs.devices()
        all.optJSONObject(devId)?.put("name", name.trim().take(40)) ?: return
        prefs.saveDevices(all)
        changed()
    }

    fun select(devId: String) {
        prefs.selected = devId
        changed()
    }

    fun state(): JSONObject {
        val id = id
        val dev = id?.let { prefs.devices().optJSONObject(it) }
        return jsonOf(
            "id" to id,
            "kind" to box?.kind,
            "model" to box?.model,
            "name" to dev?.optString("name"),
            "selected" to prefs.selected,
            "problem" to problem,
            // A box's session ends on its own while the app is away. With a stored password the app signs
            // back in on the next call, so the screens stay as they were instead of flashing signed out.
            "signedIn" to (box?.hasSession == true ||
                (box != null && hasPassword() && !needsSignIn && System.currentTimeMillis() >= lockedUntil)),
            // No password in the app means the form shows, even when the box has a session: a session
            // the browser opened ends on its own, and the app could not sign itself back in.
            "needsSignIn" to (needsSignIn || (box != null && !hasPassword())),
            "borrowed" to (box?.hasSession == true && !hasPassword()),
            "lockedUntil" to lockedUntil,
            "remembered" to (id != null && secrets.has(id)),
            "status" to last?.toJson(),
            "at" to lastAt,
            "devices" to list(),
        )
    }
}
