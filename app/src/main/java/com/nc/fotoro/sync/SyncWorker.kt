package com.nc.fotoro.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nc.fotoro.R
import com.nc.fotoro.data.ApiFailure
import com.nc.fotoro.data.FotoroApi
import com.nc.fotoro.data.LocalAsset
import com.nc.fotoro.data.MediaCatalog
import com.nc.fotoro.data.SecureStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

object SyncScheduler {
    const val MANUAL = "fotoro_manual_sync"
    const val AUTO = "fotoro_auto_sync"
    const val SELECTED = "fotoro_selected_uploads"
    const val SELECTED_TAG = "fotoro_selected_upload"
    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true).build()
    fun scheduleAuto(context: Context, enabled: Boolean, wifiOnly: Boolean = true) {
        val work = WorkManager.getInstance(context)
        if (enabled) work.enqueueUniquePeriodicWork(AUTO, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(wifiOnly)).build())
        else work.cancelUniqueWork(AUTO)
    }
    fun syncNow(context: Context, wifiOnly: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(wifiOnly)).build())
    }
    fun uploadSelected(context: Context, uri: Uri) {
        val request = OneTimeWorkRequestBuilder<SelectedUploadWorker>()
            .setInputData(workDataOf("uri" to uri.toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(SELECTED_TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SELECTED, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

private class SyncPause : Exception()

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { private val syncGate = Mutex() }
    private val store = SecureStore(context)
    private val api = FotoroApi(store)
    private val catalog = MediaCatalog(context)
    private val settings = context.getSharedPreferences("fotoro_sync", Context.MODE_PRIVATE)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channel = "fotoro_backup"
    private val notificationId = 9021

    override suspend fun doWork(): Result = syncGate.withLock { runSync() }

    private suspend fun runSync(): Result {
        if (store.server == null || !catalog.hasAccess()) return Result.success()
        try {
            val generation = catalog.generation()
            val fullCheckAt = settings.getLong("last_full_check", 0)
            val unchanged = generation != null &&
                generation.first == settings.getString("media_version", null) &&
                generation.second == settings.getLong("media_generation", -1) &&
                System.currentTimeMillis() - fullCheckAt < 24 * 60 * 60_000L
            if (unchanged) return checkPendingCopies()
            notificationManager.createNotificationChannel(NotificationChannel(channel, "Photo backup", NotificationManager.IMPORTANCE_LOW))
            setForeground(notification("Finding new photos", 0, 0))
            val all = catalog.list()
            if (all.isEmpty()) {
                saveSummary(0, 0, 0, 0)
                return Result.success()
            }
            var backedUp = 0
            var pending = 0
            var completed = 0
            var unavailable = 0
            val started = System.currentTimeMillis()
            val missing = ArrayList<LocalAsset>()
            val awaitingSecondCopy = JSONArray()
            for (batch in all.chunked(200)) {
                currentCoroutineContext().ensureActive()
                val query = JSONArray()
                batch.forEach { query.put(JSONObject().put("device_asset_id", it.stableId).put("size", it.size)) }
                val statuses = api.deviceStatus(query)
                for (i in 0 until statuses.length()) {
                    val state = statuses.getJSONObject(i).optString("state")
                    when (state) {
                        "backed_up" -> backedUp++
                        "primary_verified" -> {
                            pending++
                            awaitingSecondCopy.put(JSONObject().put("device_asset_id", batch[i].stableId).put("size", batch[i].size))
                        }
                        else -> missing += batch[i]
                    }
                }
            }
            for (asset in missing) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() - started > 8 * 60_000) {
                    saveSummary(all.size, backedUp, pending, completed)
                    return Result.retry()
                }
                setForeground(notification("Backing up ${asset.name}", completed, missing.size))
                try {
                    upload(asset, started + 8 * 60_000)
                } catch (_: SecurityException) {
                    unavailable++
                    continue
                } catch (_: java.io.FileNotFoundException) {
                    unavailable++
                    continue
                } catch (_: IllegalStateException) {
                    unavailable++
                    continue
                }
                completed++
                pending++ // server verifies primary immediately; a second disk copy may follow later
                awaitingSecondCopy.put(JSONObject().put("device_asset_id", asset.stableId).put("size", asset.size))
                setProgress(workDataOf("done" to completed, "total" to missing.size, "name" to asset.name))
                saveSummary(all.size, backedUp, pending, completed)
            }
            saveSummary(all.size, backedUp, pending, completed)
            settings.edit().putInt("unavailable", unavailable)
                .putString("pending_assets", awaitingSecondCopy.toString())
                .putString("media_version", generation?.first)
                .putLong("media_generation", generation?.second ?: -1)
                .putLong("last_full_check", System.currentTimeMillis()).apply()
            notificationManager.notify(notificationId, NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Fotoro backup up to date")
                .setContentText("$completed uploaded · $backedUp safely backed up${if (unavailable > 0) " · $unavailable unavailable" else ""}")
                .setAutoCancel(true).build())
            return Result.success()
        } catch (_: SyncPause) {
            return Result.retry()
        } catch (error: Exception) {
            setProgress(workDataOf("error" to (error.message ?: "Sync paused")))
            return if (error is ApiFailure && error.status in 400..499 && error.status != 408 && error.status != 429) Result.failure()
            else Result.retry()
        }
    }

    private suspend fun checkPendingCopies(): Result {
        val raw = settings.getString("pending_assets", "[]") ?: "[]"
        val previous = try { JSONArray(raw) } catch (_: Exception) {
            settings.edit().putLong("last_full_check", 0).apply()
            return Result.retry()
        }
        if (previous.length() == 0) {
            settings.edit().putLong("last_sync", System.currentTimeMillis()).apply()
            return Result.success()
        }
        val cursor = settings.getInt("pending_cursor", 0) % previous.length()
        val checkedPositions = (0 until minOf(previous.length(), 1000)).map { (cursor + it) % previous.length() }
        val backedPositions = HashSet<Int>()
        var newlyBacked = 0
        for (positions in checkedPositions.chunked(200)) {
            val batch = JSONArray()
            positions.forEach { batch.put(previous.getJSONObject(it)) }
            val statuses = api.deviceStatus(batch)
            for (i in 0 until statuses.length()) {
                when (statuses.getJSONObject(i).optString("state")) {
                    "backed_up" -> { newlyBacked++; backedPositions += positions[i] }
                    "primary_verified" -> Unit
                    else -> {
                        settings.edit().putLong("last_full_check", 0).apply()
                        return Result.retry()
                    }
                }
            }
        }
        val remaining = JSONArray()
        for (i in 0 until previous.length()) if (i !in backedPositions) remaining.put(previous.getJSONObject(i))
        settings.edit().putString("pending_assets", remaining.toString())
            .putInt("pending_cursor", if (remaining.length() == 0) 0 else (cursor + checkedPositions.size) % remaining.length())
            .putInt("primary_only", remaining.length())
            .putInt("backed_up", settings.getInt("backed_up", 0) + newlyBacked)
            .putLong("last_sync", System.currentTimeMillis()).apply()
        return Result.success()
    }

    private suspend fun upload(asset: LocalAsset, deadline: Long) {
        val sha = catalog.sha256(asset)
        val key = "upload_${asset.stableId}"
        var uploadId = settings.getString(key, null)
        var offset = uploadId?.let {
            try { api.uploadOffset(it) }
            catch (error: ApiFailure) { if (error.status == 404 || error.status == 410) null else throw error }
        }
        if (offset == null) {
            uploadId = api.createUpload(asset, sha)
            settings.edit().putString(key, uploadId).apply()
            offset = 0L
        }
        val id = requireNotNull(uploadId)
        catalog.uploadChunks(asset, offset) { chunkOffset, bytes, length ->
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() > deadline) throw SyncPause()
            api.appendChunk(id, chunkOffset, bytes, length)
            setProgress(workDataOf("bytes" to (chunkOffset + length), "size" to asset.size, "name" to asset.name))
        }
        api.completeUpload(id)
        settings.edit().remove(key).apply()
    }

    private fun saveSummary(total: Int, backedUp: Int, pending: Int, uploaded: Int) {
        settings.edit().putInt("total", total).putInt("backed_up", backedUp)
            .putInt("primary_only", pending).putInt("uploaded_last", uploaded)
            .putLong("last_sync", System.currentTimeMillis()).apply()
    }

    private fun notification(title: String, done: Int, total: Int): ForegroundInfo {
        val note = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Fotoro · $title")
            .setContentText(if (total == 0) "Checking your library" else "$done of $total items transferred")
            .setProgress(total, done, total == 0).setOngoing(true).setOnlyAlertOnce(true).build()
        return ForegroundInfo(notificationId, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
