package com.hebronmimam.mifi.core

import java.security.MessageDigest

object Hash {
    fun sha256(s: String): String = hex(digest("SHA-256", s.toByteArray(Charsets.UTF_8)))
    fun md5(s: String): String = hex(digest("MD5", s.toByteArray(Charsets.UTF_8)))
    fun sha256(b: ByteArray): ByteArray = digest("SHA-256", b)

    private fun digest(alg: String, b: ByteArray) = MessageDigest.getInstance(alg).digest(b)

    fun hex(b: ByteArray): String {
        val out = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
