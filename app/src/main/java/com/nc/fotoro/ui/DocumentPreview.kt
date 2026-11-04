package com.nc.fotoro.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nc.fotoro.AppUiState
import com.nc.fotoro.AppViewModel
import com.nc.fotoro.data.ServerAsset
import com.nc.fotoro.ui.theme.CobaltLight
import com.nc.fotoro.ui.theme.Cream
import com.nc.fotoro.ui.theme.Ink
import com.nc.fotoro.ui.theme.Muted
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DocumentPreview(asset: ServerAsset, state: AppUiState, model: AppViewModel) {
    val context = LocalContext.current
    LaunchedEffect(asset.id) { model.loadDocument(asset) }
    val file = state.documentFile.takeIf { state.documentAssetId == asset.id }
    var page by remember(asset.id) { mutableIntStateOf(0) }
    var pageCount by remember(asset.id) { mutableIntStateOf(0) }
    var bitmap by remember(asset.id) { mutableStateOf<Bitmap?>(null) }
    var previewError by remember(asset.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(file, page) {
        if (file == null || asset.mimeType != "application/pdf") return@LaunchedEffect
        bitmap = null
        runCatching { withContext(Dispatchers.IO) { renderPage(file, page) } }
            .onSuccess { (image, count) -> bitmap = image; pageCount = count; previewError = null }
            .onFailure { previewError = it.message ?: "Could not render PDF" }
    }
    Column(Modifier.fillMaxSize().background(Ink).padding(top = 80.dp, bottom = 76.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.documentLoading || (file != null && asset.mimeType == "application/pdf" && bitmap == null && previewError == null)) {
            CircularProgressIndicator(color = CobaltLight)
        } else if (file != null && asset.mimeType == "application/pdf" && bitmap != null) {
            Image(bitmap!!.asImageBitmap(), "Page ${page + 1} of $pageCount",
                Modifier.weight(1f).fillMaxWidth(), contentScale = ContentScale.Fit)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { page-- }, enabled = page > 0) { Text("‹") }
                Text("${page + 1} / $pageCount", color = Cream)
                Button(onClick = { page++ }, enabled = page + 1 < pageCount) { Text("›") }
            }
        } else {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(previewError ?: if (file != null) "Open this document in another app" else "Document unavailable",
                        color = Muted, modifier = Modifier.padding(24.dp))
                    if (file == null && !state.documentLoading) {
                        Button(onClick = { model.loadDocument(asset) }) { Text("Retry download") }
                    }
                }
            }
        }
        if (file != null) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                runCatching {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, asset.mimeType.ifBlank { "application/octet-stream" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }.onFailure { model.reportError(it.message ?: "No app can open this document") }
            }) { Text("Open with another app") }
        }
    }
}

private fun renderPage(file: File, index: Int): Pair<Bitmap, Int> {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val count = renderer.pageCount
            require(index in 0 until count) { "Page is unavailable" }
            renderer.openPage(index).use { page ->
                val scale = minOf(1600f / page.width, 2200f / page.height, 2f)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                image.eraseColor(android.graphics.Color.WHITE)
                page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return image to count
            }
        }
    }
}
