package com.nc.fotoro.data

import android.os.Build
import android.net.Uri
import android.util.Base64
import android.content.Context
import com.nc.fotoro.BuildConfig
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ApiFailure(val status: Int, message: String) : IOException(message)

class FotoroApi(private val store: SecureStore) {
    companion object { private val refreshGate = Mutex() }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private val website = "https://fotoro.vercel.app"

    private suspend fun request(method: String, url: String, body: JSONObject? = null, bearer: String? = null,
                                extraHeaders: Map<String, String> = emptyMap(), readTimeoutSeconds: Long = 60): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        extraHeaders.forEach { (key, value) -> builder.header(key, value) }
        val requestBody = body?.toString()?.toRequestBody(jsonType)
        builder.method(method, requestBody)
        val callClient = if (readTimeoutSeconds == 60L) client
            else client.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
        callClient.newCall(builder.build()).execute().use { response ->
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

    suspend fun startBrowserSignIn(): Pair<PendingBrowserSignIn, String> {
        val response = request("POST", "$website/api/auth/mobile-handoff", JSONObject())
        val state = response.getString("state")
        val loginUrl = response.getString("login_url")
        require(state.matches(Regex("[a-f0-9]{64}")) &&
            Uri.parse(loginUrl).scheme == "https" && Uri.parse(loginUrl).host == "fotoro.vercel.app") {
            "Browser sign-in returned an invalid URL"
        }
        val pending = PendingBrowserSignIn(state, System.currentTimeMillis() + 15 * 60_000)
        store.pendingBrowserSignIn = pending
        return pending to loginUrl
    }

    suspend fun pollBrowserSignIn(state: String): AccountSession? {
        val response = request("GET", "$website/api/auth/mobile-handoff?state=${Uri.encode(state)}")
        if (response.optString("status") != "complete") return null
        val access = response.getString("access_token")
        val payload = String(Base64.decode(access.split('.')[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val expires = JSONObject(payload).optLong("exp", System.currentTimeMillis() / 1000 + 3600)
        val account = AccountSession(access, response.getString("refresh_token"), expires,
            response.getString("user_id"), response.optString("email"), response.optString("name"))
        store.account = account
        store.pendingBrowserSignIn = null
        return account
    }

    suspend fun validAccountToken(forceRefresh: Boolean = false, rejectedToken: String? = null): String = refreshGate.withLock {
        // Refresh tokens rotate. Workers and screens must re-read the stored session
        // under one process-wide lock before exchanging it.
        val account = store.account ?: throw ApiFailure(401, "Sign in to continue")
        if (account.expiresAt > System.currentTimeMillis() / 1000 + 90 &&
            (!forceRefresh || (rejectedToken != null && account.accessToken != rejectedToken))) {
            return@withLock account.accessToken
        }
        val url = BuildConfig.FOTORO_SUPABASE_URL.trimEnd('/')
        val key = BuildConfig.FOTORO_SUPABASE_ANON_KEY
        if (url.isBlank() || key.isBlank()) throw ApiFailure(401, "Supabase refresh configuration is missing")
        val data = try {
            request("POST", "$url/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", account.refreshToken), extraHeaders = mapOf("apikey" to key))
        } catch (error: ApiFailure) {
            if (error.status == 400 || error.status == 401) throw ApiFailure(401, "Account session expired. Sign in again")
            throw error
        }
        val updated = account.copy(accessToken = data.getString("access_token"),
            refreshToken = data.getString("refresh_token"),
            expiresAt = System.currentTimeMillis() / 1000 + data.optLong("expires_in", 3600))
        store.account = updated
        updated.accessToken
    }

    suspend fun pair(): PairedServer {
        val session = authorizedRequest("POST", "$website/api/pairing", JSONObject())
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
        val upload = request("GET", "${server.url}/api/v1/uploads/$id", bearer = server.deviceToken)
        if (upload.optString("status") !in listOf("uploading", "completed")) {
            throw ApiFailure(410, "Upload session expired")
        }
        return upload.getLong("received_size")
    }

    suspend fun appendChunk(id: String, offset: Long, bytes: ByteArray, length: Int) = withContext(Dispatchers.IO) {
        val server = store.server ?: throw ApiFailure(401, "Pair this phone first")
        val request = Request.Builder().url("${server.url}/api/v1/uploads/$id")
            .header("Authorization", "Bearer ${server.deviceToken}")
            .header("Upload-Offset", offset.toString())
            .patch(bytes.toRequestBody("application/octet-stream".toMediaType(), 0, length)).build()
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

    private suspend fun authorizedRequest(method: String, url: String, body: JSONObject? = null,
                                          readTimeoutSeconds: Long = 60): JSONObject {
        val token = validAccountToken()
        return try { request(method, url, body, token, readTimeoutSeconds = readTimeoutSeconds) }
        catch (error: ApiFailure) {
            if (error.status != 401) throw error
            request(method, url, body, validAccountToken(forceRefresh = true, rejectedToken = token),
                readTimeoutSeconds = readTimeoutSeconds)
        }
    }

    private suspend fun ownerGet(path: String): JSONObject {
        val server = store.server ?: throw ApiFailure(401, "Pair this phone first")
        return authorizedRequest("GET", server.url + path)
    }

    private suspend fun ownerPost(path: String, body: JSONObject = JSONObject(), timeoutSeconds: Long = 60): JSONObject {
        val server = store.server ?: throw ApiFailure(401, "Pair this phone first")
        return authorizedRequest("POST", server.url + path, body, timeoutSeconds)
    }

    suspend fun dashboardMetrics() = ownerGet("/api/stats")
    suspend fun linkedDevices() = ownerGet("/devices")
    suspend fun storageStatus() = ownerGet("/api/v1/storage")
    suspend fun syncStorage() = ownerPost("/api/v1/storage/sync", timeoutSeconds = 150)
    suspend fun verifyStorage() = ownerPost("/api/v1/storage/verify", timeoutSeconds = 150)
    suspend fun diagnostics() = ownerGet("/api/v1/diagnostics")
    suspend fun processingSchedule() = ownerGet("/api/scheduler/config")
    suspend fun updateProcessingSchedule(enabled: Boolean) =
        ownerPost("/api/scheduler/config", JSONObject().put("enabled", enabled))
    suspend fun search(query: String) = ownerGet("/api/search?q=${Uri.encode(query)}")
    suspend fun mediaItem(id: String): JSONObject {
        val server = store.server ?: throw ApiFailure(401, "Pair this phone first")
        return request("GET", "${server.url}/api/v1/media/${Uri.encode(id)}", bearer = server.deviceToken)
    }

    suspend fun pairingInvitation(): JSONObject =
        authorizedRequest("POST", "$website/api/pairing", JSONObject())

    private fun mediaRequest(asset: ServerAsset): Request {
        val server = store.server ?: throw ApiFailure(401, "Pair this phone first")
        return Request.Builder().url(asset.contentUrl).apply {
            if (asset.needsAuth) header("Authorization", "Bearer ${server.deviceToken}")
        }.build()
    }

    /** Explicit full-original verification; streams bytes without holding them in memory. */
    suspend fun verifyOriginal(asset: ServerAsset): Boolean = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(mediaRequest(asset)).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code, "Could not download original (${response.code})")
            val stream = response.body?.byteStream() ?: throw IOException("Original is empty")
            stream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.equals(asset.id, ignoreCase = true)
    }

    /** Download a selected document to private cache for Android's native PDF reader or an external app. */
    suspend fun downloadDocument(asset: ServerAsset, context: Context): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "fotoro_documents").apply { mkdirs() }
        val extension = asset.name.substringAfterLast('.', "bin").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        val destination = File(folder, "${asset.id.filter { it.isLetterOrDigit() }}.$extension")
        if (destination.exists() && destination.length() == asset.fileSize) return@withContext destination
        val temporary = File(folder, "${destination.name}.part")
        try {
            client.newCall(mediaRequest(asset)).execute().use { response ->
                if (!response.isSuccessful) throw ApiFailure(response.code, "Could not open document (${response.code})")
                val input = response.body?.byteStream() ?: throw IOException("Document is empty")
                input.use { source -> temporary.outputStream().use { target -> source.copyTo(target) } }
            }
            if (asset.fileSize > 0 && temporary.length() != asset.fileSize) throw IOException("Document download was incomplete")
            if (!temporary.renameTo(destination)) throw IOException("Could not save document preview")
            destination
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    suspend fun testServer(): Boolean = try {
        val server = store.server ?: return false
        request("GET", "${server.url}/api/v1/me", bearer = server.deviceToken)
        true
    } catch (_: Exception) { false }
}
