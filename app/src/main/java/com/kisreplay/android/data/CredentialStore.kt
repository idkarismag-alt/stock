package com.kisreplay.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("kis_credentials", Context.MODE_PRIVATE)
    private val alias = "kis_replay_android_api"

    fun save(value: ApiCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val plain = JSONObject().put("appKey", value.appKey).put("secretKey", value.secretKey)
            .toString().toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(plain)
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): ApiCredentials? = runCatching {
        val iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)
        val payload = Base64.decode(prefs.getString("payload", ""), Base64.NO_WRAP)
        if (iv.isEmpty() || payload.isEmpty()) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(payload), Charsets.UTF_8))
        ApiCredentials(json.getString("appKey"), json.getString("secretKey"))
    }.getOrNull()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
