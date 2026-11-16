package com.hebronmimam.mifi.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Proxy
import java.net.URL

class HttpResult(val code: Int, val body: String, val headers: Map<String, List<String>>) {
    val ok get() = code in 200..299
    fun header(name: String): List<String> =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()
}

/**
 * The phone's Wi-Fi link to the box. Every device request is bound to the Wi-Fi
 * network, so it still reaches the box when Android prefers mobile data (a Wi-Fi
 * without internet, or a MiFi whose SIM is out of data).
 */
class Lan(ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)

    fun wifi(): Network? {
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /** The Wi-Fi's gateway, which is where both supported boxes answer. */
    fun gateway(n: Network? = wifi()): String? {
        n ?: return null
        val lp = cm.getLinkProperties(n) ?: return null
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
            ?: lp.routes.firstOrNull { it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }?.gateway
        return gw?.hostAddress
    }

    /** This phone's own address on that Wi-Fi, used to find it in the box's device list. */
    fun myIp(n: Network? = wifi()): String? {
        n ?: return null
        val lp = cm.getLinkProperties(n) ?: return null
        return lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
    }

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 8000,
        network: Network? = wifi(),
    ): HttpResult {
        val n = network ?: throw IOException("Not on Wi-Fi")
        return open(URL(url), n, method, headers, body, timeoutMs)
    }

    companion object {
        /** Plain request on the default network (GitHub). */
        fun internet(
            url: String,
            headers: Map<String, String> = emptyMap(),
            timeoutMs: Int = 15000,
            maxBytes: Int = 64 shl 20,
        ): HttpResult = open(URL(url), null, "GET", headers, null, timeoutMs, maxBytes)

        fun stream(url: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            return c
        }

        private fun open(
            url: URL, n: Network?, method: String, headers: Map<String, String>,
            body: ByteArray?, timeoutMs: Int, maxBytes: Int = 4 shl 20,
        ): HttpResult {
            val c = (if (n != null) n.openConnection(url, Proxy.NO_PROXY) else url.openConnection()) as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = timeoutMs.coerceAtMost(6000)
                c.readTimeout = timeoutMs
                c.useCaches = false
                c.instanceFollowRedirects = n == null
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val text = stream?.use { readCapped(it, maxBytes) } ?: ""
                return HttpResult(code, text, c.headerFields.filterKeys { it != null })
            } finally {
                c.disconnect()
            }
        }

        private fun readCapped(s: InputStream, max: Int): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = s.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                if (out.size() > max) throw IOException("Response too large")
            }
            return out.toString("UTF-8")
        }
    }
}
