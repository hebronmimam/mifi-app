package com.hebronmimam.mifi.update

import android.content.Context
import android.util.Base64
import com.hebronmimam.mifi.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipInputStream

/**
 * The web screens. The APK carries a copy in assets/web; newer copies come from
 * the GitHub releases as a zip signed with the project's web key, and are only
 * used when the signature checks out and the native side is new enough.
 */
class WebBundle(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "web")

    fun bundledVersion(): Int = runCatching {
        ctx.assets.open("web/version.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)).optInt("web", 0) }
    }.getOrDefault(0)

    /** The downloaded version, if it is newer than the bundled one and this APK can run it. */
    fun installedVersion(): Int {
        val v = runCatching { JSONObject(File(dir, "version.json").readText()) }.getOrNull() ?: return 0
        if (v.optInt("minNative", 1) > NATIVE_API) return 0
        return v.optInt("web", 0)
    }

    private fun useInstalled() = installedVersion() > bundledVersion()

    fun activeVersion(): Int = maxOf(bundledVersion(), installedVersion().takeIf { useInstalled() } ?: 0)

    fun open(path: String): InputStream? {
        val clean = path.trimStart('/').ifEmpty { "index.html" }
        if (clean.contains("..")) return null
        if (useInstalled()) {
            val f = File(dir, clean)
            if (f.isFile && f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return f.inputStream()
        }
        return runCatching { ctx.assets.open("web/$clean") }.getOrNull()
    }

    /** Verify and unpack a downloaded bundle. Returns its version, or null if refused. */
    fun install(zip: File, signature: ByteArray): Int? {
        if (!verify(zip, signature)) return null
        val next = File(ctx.filesDir, "web-next").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var total = 0L
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(next, e.name)
                if (!out.canonicalPath.startsWith(next.canonicalPath + File.separator)) return null
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = z.read(buf)
                        if (r < 0) break
                        total += r
                        if (total > 40L shl 20) return null
                        o.write(buf, 0, r)
                    }
                }
            }
        }
        val v = runCatching { JSONObject(File(next, "version.json").readText()) }.getOrNull() ?: return null
        if (v.optInt("minNative", 1) > NATIVE_API || !File(next, "index.html").isFile) return null
        val old = File(ctx.filesDir, "web-old").apply { deleteRecursively() }
        if (dir.exists() && !dir.renameTo(old)) return null
        if (!next.renameTo(dir)) { old.renameTo(dir); return null }
        old.deleteRecursively()
        return v.optInt("web", 0)
    }

    companion object {
        /** Bumped when the bridge gains calls that new web screens depend on. */
        const val NATIVE_API = 1

        fun verify(file: File, signature: ByteArray): Boolean {
            val key = BuildConfig.WEB_BUNDLE_KEY.trim()
            if (key.isEmpty()) return false
            return runCatching {
                val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)))
                val s = Signature.getInstance("SHA256withECDSA")
                s.initVerify(pub)
                file.inputStream().use { inp ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        s.update(buf, 0, r)
                    }
                }
                s.verify(signature)
            }.getOrDefault(false)
        }
    }
}
