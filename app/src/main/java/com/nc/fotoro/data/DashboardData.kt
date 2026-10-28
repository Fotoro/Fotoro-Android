package com.nc.fotoro.data

import org.json.JSONObject

data class DashboardMetrics(
    val total: Int, val processed: Int, val failed: Int, val mediaBytes: Long,
    val diskTotalBytes: Long, val diskFreeBytes: Long, val cacheBytes: Long, val stagingBytes: Long,
    val devicesCount: Int, val queuePending: Int, val queueFailed: Int, val searchVectors: Int,
    val photosWithFaces: Int, val backupVerified: Int, val backupPending: Int,
    val backupAvailable: Boolean, val primaryAvailable: Boolean, val configuredModel: String,
    val collectedAt: String, val stale: Boolean,
)

fun JSONObject.toDashboardMetrics() = DashboardMetrics(
    optInt("total"), optInt("processed"), optInt("failed"), optLong("media_bytes"),
    optLong("disk_total_bytes"), optLong("disk_free_bytes"), optLong("cache_bytes"), optLong("staging_bytes"),
    optInt("devices_count"), optInt("queue_pending"), optInt("queue_failed"), optInt("search_vectors"),
    optInt("photos_with_faces"), optInt("backup_verified"), optInt("backup_pending"),
    optBoolean("backup_available"), optBoolean("primary_available"), optString("configured_model"),
    optString("collected_at"), optBoolean("stale"),
)

data class LinkedDevice(val id: String, val name: String, val platform: String, val status: String,
                        val lastSeen: String, val items: String)

fun JSONObject.toLinkedDevice() = LinkedDevice(optString("id"), optString("name"),
    optString("platform"), optString("status"), optString("last_seen"), optString("items"))

data class BackupStatus(val configured: Boolean, val available: Boolean, val error: String,
                        val unverifiedPrimary: Int, val missingPrimary: Int, val corruptPrimary: Int,
                        val pending: Int, val verified: Int, val failed: Int)

fun JSONObject.toBackupStatus() = BackupStatus(optBoolean("configured"), optBoolean("available"), optString("error"),
    optInt("unverified_primary"), optInt("missing_primary"), optInt("corrupt_primary"),
    optInt("pending"), optInt("verified"), optInt("failed"))

data class ServerDiagnostics(val databaseCheck: String, val schemaVersion: Int, val primaryAvailable: Boolean,
                             val backupAvailable: Boolean, val tailscaleRunning: Boolean,
                             val funnelEnabled: Boolean, val searchModel: String, val searchVectors: Int)

fun JSONObject.toServerDiagnostics(): ServerDiagnostics {
    val db = optJSONObject("database") ?: JSONObject()
    val primary = optJSONObject("primary") ?: JSONObject()
    val backup = optJSONObject("backup") ?: JSONObject()
    val tailscale = optJSONObject("tailscale") ?: JSONObject()
    val search = optJSONObject("search") ?: JSONObject()
    return ServerDiagnostics(db.optString("quick_check"), db.optInt("schema_version"),
        primary.optBoolean("available"), backup.optBoolean("available"), tailscale.optBoolean("running"),
        tailscale.optBoolean("funnel_enabled"), search.optString("model"), search.optInt("vectors"))
}

data class ProcessingSchedule(val enabled: Boolean, val processTime: String, val maxConcurrent: Int,
                              val maxPerBatch: Int, val photos: Boolean, val videos: Boolean,
                              val documents: Boolean, val screenshots: Boolean)

fun JSONObject.toProcessingSchedule() = ProcessingSchedule(optBoolean("enabled"), optString("process_time"),
    optInt("max_concurrent"), optInt("max_per_batch"), optBoolean("process_photos"),
    optBoolean("process_videos"), optBoolean("process_documents"), optBoolean("process_screenshots"))

data class SearchHit(val hash: String, val caption: String, val category: String, val score: Double,
                     val thumbnailUrl: String)
