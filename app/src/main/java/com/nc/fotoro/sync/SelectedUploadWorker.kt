package com.nc.fotoro.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nc.fotoro.R
import com.nc.fotoro.data.ApiFailure
import com.nc.fotoro.data.FotoroApi
import com.nc.fotoro.data.LocalAsset
import com.nc.fotoro.data.MediaCatalog
import com.nc.fotoro.data.SecureStore
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

/** Explicit file offload is serialized by SyncScheduler's work chain and survives process death. */
class SelectedUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private class UnreadableSelection(message: String) : IOException(message)
    private val api = FotoroApi(SecureStore(context))
    private val catalog = MediaCatalog(context)
    private val settings = context.getSharedPreferences("fotoro_sync", Context.MODE_PRIVATE)
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channel = "fotoro_file_upload"
    private val notificationId = 9500

    override suspend fun doWork(): Result {
        if (SyncScheduler.isPaused(applicationContext)) return Result.success()
        val uri = inputData.getString("uri")?.let(Uri::parse) ?: return Result.failure()
        return try {
            notifications.createNotificationChannel(NotificationChannel(channel, "Selected file uploads", NotificationManager.IMPORTANCE_LOW))
            setForeground(note("Preparing selected file", 0))
            val (name, size) = metadata(uri)
            if (size <= 0) throw UnreadableSelection("Selected file has no readable size")
            val sha = catalog.sha256(LocalAsset(0, uri, "", name, "", size, 0, false))
            val mime = applicationContext.contentResolver.getType(uri) ?: "application/octet-stream"
            val asset = LocalAsset(0, uri, "picked:$sha", name, mime, size, 0, mime.startsWith("video/"))
            val key = "selected_upload_$sha"
            var uploadId = settings.getString(key, null)
            var offset = uploadId?.let { id ->
                try { api.uploadOffset(id) }
                catch (error: ApiFailure) { if (error.status in listOf(404, 410)) null else throw error }
            }
            if (offset == null) {
                uploadId = api.createUpload(asset, sha)
                settings.edit().putString(key, uploadId).apply()
                offset = 0L
            }
            val id = requireNotNull(uploadId)
            var lastPercent = -1
            catalog.uploadChunks(asset, offset) { chunkOffset, bytes, length ->
                currentCoroutineContext().ensureActive()
                if (SyncScheduler.isPaused(applicationContext)) throw CancellationException("Transfers stopped")
                api.appendChunk(id, chunkOffset, bytes, length)
                val percent = (((chunkOffset + length) * 100) / size).toInt().coerceIn(0, 100)
                if (percent >= lastPercent + 2 || percent == 100) {
                    lastPercent = percent
                    setProgress(workDataOf("done" to percent, "total" to 100, "name" to name))
                    setForeground(note("Offloading $name", percent))
                }
            }
            api.completeUpload(id)
            settings.edit().remove(key).apply()
            notifications.notify(notificationId, NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Fotoro · file saved")
                .setContentText(name).setAutoCancel(true).build())
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            setProgress(workDataOf("error" to (error.message ?: "Selected file upload paused")))
            if (error is SecurityException || error is java.io.FileNotFoundException || error is UnreadableSelection ||
                error is ApiFailure && error.status in 400..499 && error.status !in listOf(408, 429)) Result.failure()
            else Result.retry()
        }
    }

    private fun metadata(uri: Uri): Pair<String, Long> {
        applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0) ?: "selected-file"
                val size = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                if (size > 0) return name to size
            }
        }
        val length = applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        return (uri.lastPathSegment ?: "selected-file") to length
    }

    private fun note(title: String, percent: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Fotoro · $title")
            .setContentText(if (percent == 0) "Preparing original" else "$percent% transferred")
            .setProgress(100, percent, percent == 0).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop transfers", SyncScheduler.stopAction(applicationContext))
            .build()
        return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
