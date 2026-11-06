package com.nc.fotoro.data

import android.util.Base64
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

data class NodeRoutes(val preferred: List<String>)

/** Decrypts the host-produced AES-256-GCM route envelope. The recovery key is never sent to Supabase. */
object NodeEndpointVault {
    private val aad = "fotoro-node-endpoint-v1".toByteArray(Charsets.UTF_8)
    private val base64Url = Regex("^[A-Za-z0-9_-]+$")

    fun decrypt(recoveryKey: String, rawEnvelope: String): NodeRoutes {
        if (rawEnvelope.length > 6_000) {
            throw IllegalArgumentException("The encrypted server endpoint is too large.")
        }
        val keyText = recoveryKey.trim()
        require(keyText.length == 43 && base64Url.matches(keyText)) {
            "Enter the 43-character recovery key printed by ./fotoro endpoint-key."
        }
        val keyBytes = decode(keyText)
        if (keyBytes.size != 32) {
            keyBytes.fill(0)
            throw IllegalArgumentException("The recovery key must decode to 32 bytes.")
        }

        try {
            val envelope = try {
                JSONObject(rawEnvelope)
            } catch (_: Exception) {
                throw IllegalArgumentException("The encrypted server endpoint is malformed.")
            }
            if (envelope.optInt("version") != 1 || envelope.optString("algorithm") != "A256GCM") {
                throw IllegalArgumentException("This server endpoint format is not supported.")
            }
            val nonce: ByteArray
            val ciphertext: ByteArray
            try {
                nonce = decode(envelope.getString("nonce"))
                ciphertext = decode(envelope.getString("ciphertext"))
            } catch (_: Exception) {
                throw IllegalArgumentException("The encrypted server endpoint is malformed.")
            }
            if (nonce.size != 12 || ciphertext.size !in 16..4096) {
                throw IllegalArgumentException("The encrypted server endpoint is malformed.")
            }

            val clear = try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(aad)
                    doFinal(ciphertext)
                }
            } catch (_: Exception) {
                throw IllegalArgumentException("That recovery key could not unlock this server endpoint.")
            }

            val details = try {
                JSONObject(String(clear, Charsets.UTF_8))
            } catch (_: Exception) {
                throw IllegalArgumentException("The decrypted server endpoint is malformed.")
            }
            if (details.optString("tailscale_ip").isBlank()) {
                throw IllegalArgumentException("The decrypted server endpoint is incomplete.")
            }
            val routes = listOf(details.optString("tailnet_url"), details.optString("public_url"))
                .mapNotNull(::validatedTailscaleOrigin)
                .distinct()
            if (routes.isEmpty()) {
                throw IllegalArgumentException("No usable Tailscale HTTPS route is registered. Check the host's Tailscale setup.")
            }
            return NodeRoutes(routes)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun decode(value: String): ByteArray {
        if (!base64Url.matches(value)) throw IllegalArgumentException("Encrypted endpoint data is malformed.")
        return try {
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Encrypted endpoint data is malformed.")
        }
    }

    private fun validatedTailscaleOrigin(value: String): String? {
        if (value.isBlank()) return null
        val parsed = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val host = parsed.host?.lowercase() ?: return null
        if (parsed.scheme != "https" || !host.endsWith(".ts.net") ||
            parsed.userInfo != null || parsed.port != -1 ||
            (parsed.path != null && parsed.path.isNotEmpty() && parsed.path != "/") ||
            parsed.query != null || parsed.fragment != null
        ) return null
        return "https://$host"
    }
}
