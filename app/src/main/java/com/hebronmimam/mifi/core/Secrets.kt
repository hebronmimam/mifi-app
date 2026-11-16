package com.hebronmimam.mifi.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device passwords, encrypted with a key that never leaves the Android Keystore.
 * The file is excluded from backup; values are never logged.
 */
class Secrets(ctx: Context) {
    private val sp = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun has(id: String) = sp.contains("pw:$id")

    fun put(id: String, password: String) {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(password.toByteArray(Charsets.UTF_8))
        val blob = c.iv + ct
        sp.edit().putString("pw:$id", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(id: String): String? {
        val s = sp.getString("pw:$id", null) ?: return null
        return runCatching {
            val blob = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(id: String) = sp.edit().remove("pw:$id").apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "mifi-device-passwords"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
