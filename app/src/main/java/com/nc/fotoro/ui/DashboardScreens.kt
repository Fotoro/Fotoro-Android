package com.nc.fotoro.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.nc.fotoro.AppUiState
import com.nc.fotoro.AppViewModel
import com.nc.fotoro.ui.theme.Coral
import com.nc.fotoro.ui.theme.Cream
import com.nc.fotoro.ui.theme.Line
import com.nc.fotoro.ui.theme.Mint
import com.nc.fotoro.ui.theme.Muted
import com.nc.fotoro.ui.theme.Panel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun readableBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val mb = bytes / (1024.0 * 1024)
    return when {
        mb >= 1024 -> "%.1f GB".format(mb / 1024)
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}

@Composable
private fun Page(title: String, subtitle: String, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(22.dp)) {
        Text("fotoro.", color = Cream, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(30.dp))
        Text(title, color = Cream, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        body()
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun DataRow(title: String, value: String, detail: String = "") {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(Panel).border(1.dp, Line).padding(17.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Cream, fontWeight = FontWeight.Medium)
            if (detail.isNotBlank()) Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(value, color = Cream, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Action(label: String, busy: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Coral, strokeWidth = 2.dp)
        else Text(label)
    }
}

@Composable
internal fun DevicesScreen(state: AppUiState, model: AppViewModel) {
    LaunchedEffect(state.pairingExpiresAt) {
        val expiry = runCatching { Instant.parse(state.pairingExpiresAt).toEpochMilli() }.getOrNull()
        if (expiry != null) {
            delay((expiry - System.currentTimeMillis()).coerceAtLeast(0))
            model.clearPairingInvitation()
        }
    }
    Page("Devices", "Your server and paired devices") {
        Text("${state.devices.size} linked devices", color = Cream, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        state.devices.forEach { device ->
            DataRow(device.name, device.status, "${device.platform} · ${device.items}" +
                if (device.lastSeen.isNotBlank()) " · ${device.lastSeen.take(10)}" else "")
        }
        if (state.devices.isEmpty()) Text("No device data yet. Connect to your server and refresh.", color = Muted)
        Action("Refresh devices", state.dashboardLoading, model::refreshDashboard)
        Spacer(Modifier.height(30.dp))
        Text("Pair a new device", color = Muted, style = MaterialTheme.typography.labelMedium)
        Text("This invitation expires shortly. Only share it with a device you control.", color = Muted)
        Action("Create pairing QR", onClick = model::createPairingInvitation)
        if (state.pairingPayload.isNotBlank()) {
            PairingQr(state.pairingPayload)
            Text("Expires ${state.pairingExpiresAt}", color = Muted, fontSize = 12.sp)
            Text("Hide code", color = Coral,
                modifier = Modifier.clickable { model.clearPairingInvitation() }.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun PairingQr(payload: String) {
    var bitmap by remember(payload) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(payload) {
        bitmap = withContext(Dispatchers.Default) {
            val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 384, 384,
                mapOf(EncodeHintType.MARGIN to 2))
            Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(384 * 384) { index ->
                    if (matrix[index % 384, index / 384]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                setPixels(pixels, 0, 384, 0, 0, 384, 384)
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "Pairing QR code", Modifier.size(260.dp)) }
            ?: CircularProgressIndicator(color = Coral)
    }
}

@Composable
internal fun StorageScreen(state: AppUiState, model: AppViewModel) {
    val metrics = state.metrics
    val storage = state.storage
    Page("Storage", "Measured on your Fotoro computer") {
        if (metrics != null) {
            if (state.metricsCached || metrics.stale) Text("Last saved reading · refresh when connected", color = Coral)
            DataRow("Media originals", readableBytes(metrics.mediaBytes), "Space consumed by your media")
            DataRow("Disk free", readableBytes(metrics.diskFreeBytes), "Space available for more uploads")
            DataRow("Disk capacity", readableBytes(metrics.diskTotalBytes))
            DataRow("Preview cache", readableBytes(metrics.cacheBytes))
            DataRow("Upload staging", readableBytes(metrics.stagingBytes))
        } else Text("Connect to the server to measure storage.", color = Muted)
        Spacer(Modifier.height(22.dp))
        Text("Backup health", color = Muted, style = MaterialTheme.typography.labelMedium)
        if (storage != null) {
            DataRow("Second disk", if (storage.available) "Available" else "Unavailable")
            DataRow("Verified copies", storage.verified.toString())
            DataRow("Waiting for copy", storage.pending.toString())
            DataRow("Primary not verified", storage.unverifiedPrimary.toString())
            DataRow("Missing or corrupt", "${storage.missingPrimary + storage.corruptPrimary}")
            DataRow("Failed backup", storage.failed.toString())
            if (storage.error.isNotBlank()) Text(storage.error, color = Coral)
        }
        Action("Run backup check", state.busy, model::syncServerStorage)
        Action("Verify stored copies", state.busy, model::verifyServerStorage)
        Action("Refresh readings", state.dashboardLoading, model::refreshDashboard)
    }
}

@Composable
internal fun ProcessingScreen(state: AppUiState, model: AppViewModel) {
    val metrics = state.metrics
    val schedule = state.schedule
    Page("AI models", "Processing status from your own server") {
        if (metrics != null) {
            DataRow("Selected model", metrics.configuredModel.ifBlank { "None" })
            DataRow("Processed media", "${metrics.processed} / ${metrics.total}")
            DataRow("Search vectors", metrics.searchVectors.toString())
            DataRow("Photos with faces", metrics.photosWithFaces.toString())
            DataRow("Waiting in queue", metrics.queuePending.toString())
            DataRow("Failed analysis", metrics.queueFailed.toString())
        }
        Spacer(Modifier.height(20.dp))
        Text("Processing schedule", color = Muted, style = MaterialTheme.typography.labelMedium)
        if (schedule != null) {
            Row(Modifier.fillMaxWidth().background(Panel).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Automatic processing", color = Cream, modifier = Modifier.weight(1f))
                Switch(checked = schedule.enabled, onCheckedChange = model::setProcessingEnabled)
            }
            DataRow("Scheduled time", schedule.processTime.ifBlank { "Not set" })
            DataRow("Parallel jobs", schedule.maxConcurrent.toString())
            DataRow("Batch limit", schedule.maxPerBatch.toString())
            DataRow("Photos / videos", "${if (schedule.photos) "On" else "Off"} / ${if (schedule.videos) "On" else "Off"}")
            DataRow("Documents / screenshots", "${if (schedule.documents) "On" else "Off"} / ${if (schedule.screenshots) "On" else "Off"}")
        }
        Action("Refresh processing", state.dashboardLoading, model::refreshDashboard)
    }
}

@Composable
internal fun SettingsScreen(state: AppUiState, model: AppViewModel) {
    val diagnostics = state.diagnostics
    Page("Settings", "Account and server diagnostics") {
        DataRow("Signed in", state.account?.email.orEmpty())
        DataRow("Private server", state.server?.url?.removePrefix("https://").orEmpty())
        if (diagnostics != null) {
            Spacer(Modifier.height(18.dp))
            Text("System diagnostics", color = Muted, style = MaterialTheme.typography.labelMedium)
            DataRow("Database", diagnostics.databaseCheck.ifBlank { "Unknown" }, "Schema ${diagnostics.schemaVersion}")
            DataRow("Primary disk", if (diagnostics.primaryAvailable) "Ready" else "Unavailable")
            DataRow("Second disk", if (diagnostics.backupAvailable) "Ready" else "Unavailable")
            DataRow("Tailscale", if (diagnostics.tailscaleRunning) "Connected" else "Offline")
            DataRow("Search index", "${diagnostics.searchVectors} vectors", diagnostics.searchModel)
        }
        Action("Refresh diagnostics", state.dashboardLoading, model::refreshDashboard)
        Action("Reconnect this phone", state.busy, model::pair)
        Spacer(Modifier.height(24.dp))
        Text("Sign out", color = Coral, modifier = Modifier.clickable { model.signOut() }.padding(14.dp))
    }
}
