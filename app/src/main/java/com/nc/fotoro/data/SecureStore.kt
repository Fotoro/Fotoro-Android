package com.nc.fotoro.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class AccountSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String,
    val name: String,
)

data class PairedServer(val url: String, val deviceId: String, val deviceToken: String)
data class PendingBrowserSignIn(val state: String, val expiresAt: Long)

/** Credentials stay off Android backup and are encrypted with a non-exportable Keystore key. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("fotoro_secure", Context.MODE_PRIVATE)
    private val keyAlias = "fotoro_credentials_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun put(name: String, value: JSONObject?) {
        if (value == null) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun get(name: String): JSONObject? {
        val value = prefs.getString(name, null) ?: return null
        return try {
            val packed = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            }
            JSONObject(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
        } catch (_: Exception) { null }
    }

    var account: AccountSession?
        get() = get("account")?.let {
            AccountSession(it.getString("access"), it.getString("refresh"), it.getLong("expires"),
                it.getString("id"), it.optString("email"), it.optString("name"))
        }
        set(value) = put("account", value?.let {
            JSONObject().put("access", it.accessToken).put("refresh", it.refreshToken)
                .put("expires", it.expiresAt).put("id", it.userId).put("email", it.email).put("name", it.name)
        })

    var server: PairedServer?
        get() = get("server")?.let { PairedServer(it.getString("url"), it.getString("id"), it.getString("token")) }
        set(value) = put("server", value?.let {
            JSONObject().put("url", it.url).put("id", it.deviceId).put("token", it.deviceToken)
        })

    var endpointRecoveryKey: String?
        get() = get("endpoint_recovery_key")?.optString("value")?.takeIf { it.isNotBlank() }
        set(value) = put("endpoint_recovery_key",
            value?.takeIf { it.isNotBlank() }?.let { JSONObject().put("value", it.trim()) })

    var pendingBrowserSignIn: PendingBrowserSignIn?
        get() = get("browser_signin")?.let {
            PendingBrowserSignIn(it.getString("state"), it.getLong("expires"))
        }
        set(value) = put("browser_signin", value?.let {
            JSONObject().put("state", it.state).put("expires", it.expiresAt)
        })

    fun clear() { prefs.edit().clear().apply() }
}
