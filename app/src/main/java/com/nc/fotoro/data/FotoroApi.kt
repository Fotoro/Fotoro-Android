package com.nc.fotoro.data

import android.os.Build
import com.nc.fotoro.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ApiFailure(val status: Int, message: String) : IOException(message)

class FotoroApi(private val store: SecureStore) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private val website = "https://fotoro.vercel.app"

    private suspend fun request(method: String, url: String, body: JSONObject? = null, bearer: String? = null,
                                extraHeaders: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        extraHeaders.forEach { (key, value) -> builder.header(key, value) }
        val requestBody = body?.toString()?.toRequestBody(jsonType)
        builder.method(method, requestBody)
        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val data = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if (!response.isSuccessful) throw ApiFailure(response.code, data.optString("error", "Request failed (${response.code})"))
            data
        }
    }

    suspend fun signIn(googleIdToken: String): AccountSession {
        val data = request("POST", "$website/api/auth/verify", JSONObject().put("credential", googleIdToken))
        val user = data.getJSONObject("user")
        return AccountSession(data.getString("access_token"), data.getString("refresh_token"),
            data.optLong("expires_at", System.currentTimeMillis() / 1000 + 3600), user.getString("id"),
            user.optString("email"), user.optString("name"))
            .also { store.account = it }
    }

    suspend fun validAccountToken(): String {
        val account = store.account ?: throw ApiFailure(401, "Sign in to continue")
        if (account.expiresAt > System.currentTimeMillis() / 1000 + 90) return account.accessToken
        val url = BuildConfig.FOTORO_SUPABASE_URL.trimEnd('/')
        val key = BuildConfig.FOTORO_SUPABASE_ANON_KEY
        if (url.isBlank() || key.isBlank()) throw ApiFailure(401, "Supabase refresh configuration is missing")
        val data = request("POST", "$url/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", account.refreshToken), extraHeaders = mapOf("apikey" to key))
        val updated = account.copy(accessToken = data.getString("access_token"),
            refreshToken = data.getString("refresh_token"),
            expiresAt = System.currentTimeMillis() / 1000 + data.optLong("expires_in", 3600))
        store.account = updated
        return updated.accessToken
    }

    suspend fun pair(): PairedServer {
        val session = request("POST", "$website/api/pairing", JSONObject(), validAccountToken())
        val url = session.getString("server_url").trimEnd('/')
        require(url.startsWith("https://") && url.substringAfter("https://").substringBefore('/').endsWith(".ts.net")) {
            "Pairing returned an invalid private server URL"
        }
        val deviceId = store.server?.deviceId ?: java.util.UUID.randomUUID().toString()
        val result = request("POST", "$url/api/v1/pairing/redeem", JSONObject()
            .put("pairing_token", session.getString("pairing_token"))
            .put("device_id", deviceId).put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".take(128))
            .put("platform", "android"))
        return PairedServer(url, result.getString("device_id"), result.getString("device_token"))
            .also { store.server = it }
    }

    suspend fun deviceStatus(assets: org.json.JSONArray): org.json.JSONArray {
        val server = store.server ?: throw ApiFailure(401, "Pair this phone first")
        return request("POST", "${server.url}/api/v1/device-assets/status",
            JSONObject().put("assets", assets), server.deviceToken).getJSONArray("assets")
    }

    suspend fun createUpload(asset: LocalAsset, sha256: String): String {
        val server = store.server ?: throw ApiFailure(401, "Pair this phone first")
        return request("POST", "${server.url}/api/v1/uploads", JSONObject()
            .put("original_name", asset.name).put("mime_type", asset.mimeType)
            .put("size", asset.size).put("sha256", sha256).put("device_asset_id", asset.stableId)
            .put("device_id", server.deviceId), server.deviceToken).getString("id")
    }

    suspend fun uploadOffset(id: String): Long {
        val server = store.server ?: throw ApiFailure(401, "Pair this phone first")
        return request("GET", "${server.url}/api/v1/uploads/$id", bearer = server.deviceToken).getLong("received_size")
    }

    suspend fun appendChunk(id: String, offset: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val server = store.server ?: throw ApiFailure(401, "Pair this phone first")
        val request = Request.Builder().url("${server.url}/api/v1/uploads/$id")
            .header("Authorization", "Bearer ${server.deviceToken}")
            .header("Upload-Offset", offset.toString())
            .patch(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code, "Chunk upload failed (${response.code})")
        }
    }

    suspend fun completeUpload(id: String) {
        val server = store.server ?: throw ApiFailure(401, "Pair this phone first")
        request("POST", "${server.url}/api/v1/uploads/$id/complete", JSONObject(), server.deviceToken)
    }

    suspend fun listMedia(page: Int): JSONObject {
        val server = store.server ?: throw ApiFailure(401, "Pair this phone first")
        return request("GET", "${server.url}/api/v1/media?page=$page&limit=100", bearer = server.deviceToken)
    }

    suspend fun testServer(): Boolean = try {
        val server = store.server ?: return false
        request("GET", "${server.url}/api/v1/me", bearer = server.deviceToken)
        true
    } catch (_: Exception) { false }
}
