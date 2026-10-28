package com.nc.fotoro.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import com.nc.fotoro.AppUiState
import com.nc.fotoro.AppViewModel
import com.nc.fotoro.data.LocalAsset
import com.nc.fotoro.data.ServerAsset
import com.nc.fotoro.ui.theme.Coral
import com.nc.fotoro.ui.theme.Cream
import com.nc.fotoro.ui.theme.Ink
import com.nc.fotoro.ui.theme.Line
import com.nc.fotoro.ui.theme.Mint
import com.nc.fotoro.ui.theme.Muted
import com.nc.fotoro.ui.theme.Panel
import com.nc.fotoro.ui.theme.PanelRaised
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FotoroApp(model: AppViewModel, onGoogleSignIn: () -> Unit, onMediaPermission: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(800); splash = false }
    Box(Modifier.fillMaxSize().background(Ink)) {
        when {
            splash || state.booting -> Splash()
            state.account == null || state.reauthRequired -> AuthScreen(state, onGoogleSignIn)
            state.server == null -> PairScreen(state, model::pair)
            !state.mediaGranted -> PermissionScreen(onMediaPermission)
            else -> Home(state, model)
        }
        state.error?.let { message ->
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)
                .fillMaxWidth().background(PanelRaised).border(1.dp, Coral, MaterialTheme.shapes.small)
                .clickable { model.clearError() }.padding(16.dp)) {
                Text(message, color = Cream, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DotLogo(modifier: Modifier = Modifier, animated: Boolean = false) {
    val progress = remember { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(animated) { if (animated) progress.animateTo(1f, tween(620)) }
    Canvas(modifier) {
        val step = size.minDimension / 3.4f
        val radius = step * .20f
        for (row in 0..2) for (column in 0..2) {
            val index = row * 3 + column
            val phase = ((progress.value * 1.45f) - index * .055f).coerceIn(0f, 1f)
            drawCircle(if (index == 4) Ink else Coral, radius * phase,
                Offset(step * (column + .7f), step * (row + .7f)))
        }
    }
}

@Composable
private fun DotBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val step = 18.dp.toPx()
        var x = 0f
        while (x < size.width) {
            var y = 0f
            while (y < size.height) {
                drawCircle(Coral.copy(alpha = .10f), 1.dp.toPx(), Offset(x, y))
                y += step
            }
            x += step
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DotBackdrop(Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DotLogo(Modifier.size(104.dp), animated = true)
            Spacer(Modifier.height(24.dp))
            Text("fotoro.", style = MaterialTheme.typography.headlineLarge, color = Cream)
            Spacer(Modifier.height(12.dp))
            Label("YOUR MOMENTS, YOUR PLACE")
        }
    }
}

@Composable
private fun Label(text: String, color: Color = Coral) {
    Text(text, color = color, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DotLogo(Modifier.size(37.dp))
        Spacer(Modifier.width(10.dp))
        Text("fotoro.", color = Cream, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun AuthScreen(state: AppUiState, signIn: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(26.dp)) {
        BrandHeader()
        Spacer(Modifier.height(76.dp))
        Box(Modifier.fillMaxWidth().height(190.dp)) {
            DotBackdrop(Modifier.fillMaxSize())
            Text("Every frame.\nBack home.", color = Cream, style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.align(Alignment.CenterStart))
        }
        Spacer(Modifier.height(28.dp))
        Text("Your phone captures it. Your own server keeps it.", color = Muted,
            style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(48.dp))
        PrimaryButton(if (state.reauthRequired) "Reconnect with Google" else "Continue with Google", signIn, state.busy)
        Spacer(Modifier.height(18.dp))
        Text("New here? Your Fotoro account is created when you continue. Use the same Google account as your server.",
            color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(45.dp))
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(18.dp))
        Label("PRIVATE BY DESIGN", Mint)
        Spacer(Modifier.height(8.dp))
        Text("Originals travel directly between this phone and your computer over your Tailscale network.",
            color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PairScreen(state: AppUiState, pair: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(26.dp)) {
        BrandHeader()
        Spacer(Modifier.height(72.dp))
        Label("02 / CONNECT YOUR SERVER")
        Spacer(Modifier.height(18.dp))
        Text("Make the\nconnection.", color = Cream, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text("Your account is ready. Pair this phone to the server linked to ${state.account?.email.orEmpty()}.",
            color = Muted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(34.dp))
        StepCard("01", "Start Fotoro", "Run ./fotoroctl start on your computer.")
        Spacer(Modifier.height(10.dp))
        StepCard("02", "Connect Tailscale", "Use the same tailnet on this phone and your computer.")
        Spacer(Modifier.height(10.dp))
        StepCard("03", "Pair securely", "We create a short-lived pairing code and exchange it for this phone's own key.")
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Pair this phone", pair, state.busy)
        Spacer(Modifier.height(12.dp))
        Text("Get Tailscale for Android ↗", color = Coral, modifier = Modifier.clickable {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tailscale.com/download/android")))
        }.padding(vertical = 10.dp))
    }
}

@Composable
private fun StepCard(number: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().background(Panel).border(1.dp, Line).padding(18.dp)) {
        Label(number)
        Spacer(Modifier.width(18.dp))
        Column {
            Text(title, color = Cream, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PermissionScreen(request: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(26.dp)) {
        BrandHeader()
        Spacer(Modifier.height(75.dp))
        Label("03 / YOUR LIBRARY")
        Spacer(Modifier.height(18.dp))
        Text("Let the good\nstuff in.", color = Cream, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text("Choose the photos and videos Fotoro can see. Full access enables automatic backup of new moments; selected access backs up only the items you choose.",
            color = Muted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(42.dp))
        StepCard("↗", "Only what you allow", "Fotoro reads your media to show it and send originals to your computer.")
        Spacer(Modifier.height(10.dp))
        StepCard("✓", "Your phone stays yours", "Upload never removes files from your device.")
        Spacer(Modifier.height(30.dp))
        PrimaryButton("Choose photo access", request)
    }
}

@Composable
private fun PrimaryButton(text: String, action: () -> Unit, busy: Boolean = false) {
    Button(onClick = action, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink),
        shape = MaterialTheme.shapes.small) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ink)
        else Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

private enum class Tab { Overview, Library, Backup }

@Composable
private fun Home(state: AppUiState, model: AppViewModel) {
    var tab by remember { mutableStateOf(Tab.Overview) }
    var selected by remember { mutableIntStateOf(-1) }
    var selectedRemote by remember { mutableIntStateOf(-1) }
    val shown = state.media.take(state.visibleCount)
    Scaffold(containerColor = Ink, bottomBar = {
        Row(Modifier.fillMaxWidth().background(Panel).border(1.dp, Line).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { item ->
                Column(Modifier.weight(1f).clickable { tab = item }.padding(7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when (item) { Tab.Overview -> "◉"; Tab.Library -> "▦"; Tab.Backup -> "↥" },
                        color = if (tab == item) Coral else Muted, fontSize = 22.sp)
                    Text(item.name, color = if (tab == item) Cream else Muted, fontSize = 11.sp)
                }
            }
        }
    }) { padding ->
        when (tab) {
            Tab.Overview -> OverviewScreen(state, model, Modifier.padding(padding), openLibrary = { tab = Tab.Library })
            Tab.Library -> LibraryScreen(state, shown, model, Modifier.padding(padding),
                onSelect = { selected = it }, onSelectRemote = { selectedRemote = it })
            Tab.Backup -> BackupScreen(state, model, Modifier.padding(padding))
        }
    }
    if (selected >= 0 && selected < shown.size) Viewer(shown, selected) { selected = -1 }
    if (selectedRemote >= 0 && selectedRemote < state.remoteMedia.size) {
        RemoteViewer(state.remoteMedia, state.server?.deviceToken.orEmpty(), selectedRemote) { selectedRemote = -1 }
    }
}

@Composable
private fun OverviewScreen(state: AppUiState, model: AppViewModel, modifier: Modifier, openLibrary: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(12.dp))
        BrandHeader()
        Spacer(Modifier.height(36.dp))
        Label("YOUR PRIVATE ARCHIVE")
        Spacer(Modifier.height(12.dp))
        Text("Made of\nmoments.", color = Cream, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(8.dp))
        Text("Safe on your own terms.", color = Muted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth().background(Panel).border(1.dp, Line).padding(22.dp)) {
            DotBackdrop(Modifier.fillMaxSize())
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(if (state.serverOnline) Mint else Coral))
                    Spacer(Modifier.width(9.dp))
                    Label(if (state.serverOnline) "SERVER CONNECTED" else "SERVER UNREACHABLE",
                        if (state.serverOnline) Mint else Coral)
                }
                Spacer(Modifier.height(18.dp))
                Text(state.server?.url?.removePrefix("https://") ?: "Your server", color = Cream,
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Text(if (state.serverOnline) "Ready for the next backup." else "Start Fotoro and connect Tailscale, then refresh.",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                if (!state.serverOnline) Text("Check connection ↻", color = Coral,
                    modifier = Modifier.clickable { model.checkServer() }.padding(top = 14.dp))
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("${state.localTotal}", "ON PHONE", Modifier.weight(1f))
            StatCard("${state.backedUp}", "TWO COPIES", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("${state.primaryOnly}", "ON SERVER", Modifier.weight(1f))
            StatCard(if (state.autoSync) "ON" else "OFF", "AUTO BACKUP", Modifier.weight(1f))
        }
        Spacer(Modifier.height(28.dp))
        PrimaryButton(if (state.syncState == WorkInfo.State.RUNNING) "Backup in progress…" else "Back up now",
            model::syncNow, state.syncState == WorkInfo.State.RUNNING)
        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Recent frames", color = Cream, style = MaterialTheme.typography.titleLarge)
            Text("See all ↗", color = Coral, modifier = Modifier.clickable(onClick = openLibrary))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            state.media.take(3).forEach { asset ->
                LocalThumbnail(asset, Modifier.weight(1f).aspectRatio(1f).background(PanelRaised))
            }
        }
        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier) {
    Column(modifier.background(Panel).border(1.dp, Line).padding(16.dp)) {
        Text(value, color = Cream, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Label(label, Muted)
    }
}

@Composable
private fun LibraryScreen(state: AppUiState, shown: List<LocalAsset>, model: AppViewModel,
                          modifier: Modifier, onSelect: (Int) -> Unit, onSelectRemote: (Int) -> Unit) {
    var filter by remember { mutableStateOf("All") }
    var source by remember { mutableStateOf("On phone") }
    val visible = when (filter) { "Photos" -> shown.filterNot { it.isVideo }; "Videos" -> shown.filter { it.isVideo }; else -> shown }
    val remote = when (filter) { "Photos" -> state.remoteMedia.filterNot { it.isVideo }; "Videos" -> state.remoteMedia.filter { it.isVideo }; else -> state.remoteMedia }
    LazyVerticalGrid(GridCells.Fixed(3), modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item(span = { GridItemSpan(3) }) {
            Column(Modifier.statusBarsPadding().padding(22.dp)) {
                BrandHeader()
                Spacer(Modifier.height(26.dp))
                Label("YOUR CAMERA ROLL")
                Spacer(Modifier.height(8.dp))
                Text("Library", color = Cream, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(5.dp))
                Text(if (source == "On phone") "${state.localTotal} items on this phone"
                    else "${state.remoteTotal} items on your server", color = Muted)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    listOf("On phone", "On server").forEach { name ->
                        Text(name, color = if (source == name) Ink else Cream,
                            modifier = Modifier.background(if (source == name) Coral else PanelRaised)
                                .clickable { source = name; if (name == "On server") model.loadRemote() }
                                .padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
                Spacer(Modifier.height(17.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    listOf("All", "Photos", "Videos").forEach { name ->
                        Text(name, color = if (filter == name) Coral else Muted,
                            modifier = Modifier.clickable { filter = name }.padding(vertical = 7.dp))
                    }
                }
            }
        }
        if (source == "On phone") {
            items(visible, key = { it.stableId }) { asset ->
                Box(Modifier.aspectRatio(1f).background(PanelRaised).clickable {
                    onSelect(shown.indexOf(asset))
                }) {
                    LocalThumbnail(asset, Modifier.fillMaxSize())
                    if (asset.isVideo) VideoBadge()
                }
            }
            if (shown.size == state.visibleCount && state.media.size >= shown.size) {
                item(span = { GridItemSpan(3) }) {
                    Text("Load more moments ↓", color = Coral, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable { model.loadMore() }.padding(24.dp))
                }
            }
        } else {
            items(remote, key = { it.id }) { asset ->
                Box(Modifier.aspectRatio(1f).background(PanelRaised).clickable {
                    onSelectRemote(state.remoteMedia.indexOf(asset))
                }) {
                    RemoteImage(asset.thumbnailUrl, state.server?.deviceToken.orEmpty(), asset.needsAuth,
                        asset.name, Modifier.fillMaxSize(), ContentScale.Crop)
                    if (asset.isVideo) VideoBadge()
                }
            }
            if (state.remoteLoading) item(span = { GridItemSpan(3) }) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Coral)
                }
            }
            if (!state.remoteLoading && state.remoteMedia.size < state.remoteTotal) {
                item(span = { GridItemSpan(3) }) {
                    Text("Load more from server ↓", color = Coral, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable { model.loadRemote(false) }.padding(24.dp))
                }
            }
        }
    }
}

@Composable
private fun VideoBadge() {
    Text("▶", color = Cream, modifier = Modifier.padding(6.dp).background(Ink.copy(alpha = .70f))
        .padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun LocalThumbnail(asset: LocalAsset, modifier: Modifier) {
    val context = LocalContext.current
    val model = remember(asset.stableId) {
        ImageRequest.Builder(context).data(asset.uri).apply {
            if (asset.isVideo) decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
        }.build()
    }
    AsyncImage(model = model, contentDescription = asset.name, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
private fun RemoteImage(url: String, token: String, needsAuth: Boolean, name: String,
                        modifier: Modifier, scale: ContentScale) {
    val context = LocalContext.current
    val model = remember(url, token, needsAuth) {
        ImageRequest.Builder(context).data(url).apply {
            if (needsAuth) httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer $token").build())
        }.build()
    }
    AsyncImage(model = model, contentDescription = name, contentScale = scale, modifier = modifier)
}

@Composable
private fun BackupScreen(state: AppUiState, model: AppViewModel, modifier: Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(22.dp)) {
        BrandHeader()
        Spacer(Modifier.height(34.dp))
        Label("OFFLOAD / CONTROL ROOM")
        Spacer(Modifier.height(12.dp))
        Text("Backup", color = Cream, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Original quality, direct to your server.", color = Muted)
        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth().background(Panel).border(1.dp, Line).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatic backup", color = Cream, fontWeight = FontWeight.SemiBold)
                Text("Runs when connected and battery is healthy", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = state.autoSync, onCheckedChange = model::setAutoSync)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().background(Panel).border(1.dp, Line).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi only", color = Cream, fontWeight = FontWeight.SemiBold)
                Text("Keep automatic uploads off mobile data", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = state.wifiOnly, onCheckedChange = model::setWifiOnly)
        }
        Spacer(Modifier.height(14.dp))
        StepCard("✓", "${state.backedUp} with two verified copies", "Ready to offload from your phone when you choose.")
        Spacer(Modifier.height(10.dp))
        StepCard("↗", "${state.primaryOnly} on your server", "Waiting for a second verified disk copy.")
        Spacer(Modifier.height(24.dp))
        if (state.syncState == WorkInfo.State.RUNNING) {
            Label("TRANSFER IN PROGRESS", Mint)
            Spacer(Modifier.height(8.dp))
            Text(state.syncName.ifBlank { "Checking your library" }, color = Cream)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (state.syncTotal > 0) state.syncDone.toFloat() / state.syncTotal else 0f },
                modifier = Modifier.fillMaxWidth(), color = Coral)
            Spacer(Modifier.height(8.dp))
            Text("${state.syncDone} of ${state.syncTotal} uploaded in this run", color = Muted)
            Spacer(Modifier.height(22.dp))
        }
        PrimaryButton("Back up now", model::syncNow, state.syncState == WorkInfo.State.RUNNING)
        Spacer(Modifier.height(18.dp))
        Text(if (state.lastSync == 0L) "No backup check yet" else "Last checked ${DateUtils.getRelativeTimeSpanString(state.lastSync)}",
            color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(42.dp))
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(24.dp))
        Label("ACCOUNT & DEVICE")
        Spacer(Modifier.height(12.dp))
        Text(state.account?.email.orEmpty(), color = Cream)
        Text(state.server?.url?.removePrefix("https://").orEmpty(), color = Muted)
        Spacer(Modifier.height(20.dp))
        Text("Sign out", color = Coral, modifier = Modifier.clickable { model.signOut() }.padding(vertical = 12.dp))
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun Viewer(assets: List<LocalAsset>, initial: Int, close: () -> Unit) {
    val pager = rememberPagerState(initialPage = initial, pageCount = { assets.size })
    val scope = rememberCoroutineScope()
    var zoomedPage by remember { mutableIntStateOf(-1) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = pager.currentPage != zoomedPage) { index ->
                val asset = assets[index]
                if (asset.isVideo) {
                    androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                        VideoView(context).apply { setVideoURI(asset.uri); setMediaController(android.widget.MediaController(context)); start() }
                    }, modifier = Modifier.fillMaxSize())
                } else {
                    var zoom by remember(asset.stableId) { mutableFloatStateOf(1f) }
                    var panX by remember(asset.stableId) { mutableFloatStateOf(0f) }
                    var panY by remember(asset.stableId) { mutableFloatStateOf(0f) }
                    Box(Modifier.fillMaxSize().pointerInput(asset.stableId) {
                        detectTransformGestures { _, delta, change, _ ->
                            zoom = (zoom * change).coerceIn(1f, 5f)
                            zoomedPage = if (zoom > 1f) index else -1
                            if (zoom > 1f) { panX += delta.x; panY += delta.y }
                            else { panX = 0f; panY = 0f }
                        }
                    }, contentAlignment = Alignment.Center) {
                        AsyncImage(model = asset.uri, contentDescription = asset.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer(
                                scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding()
                .background(Ink.copy(alpha = .72f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✕", color = Cream, fontSize = 22.sp,
                    modifier = Modifier.clickable(onClick = close).padding(end = 20.dp))
                Column(Modifier.weight(1f)) {
                    Text(assets[pager.currentPage].name, color = Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1} / ${assets.size}", color = Muted, fontSize = 12.sp)
                }
            }
            ViewerControls(pager.currentPage, assets.size,
                previous = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                next = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ViewerControls(page: Int, total: Int, previous: () -> Unit, next: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().background(Ink.copy(alpha = .82f)).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("‹", color = if (page > 0) Cream else Muted, fontSize = 32.sp,
            modifier = Modifier.clickable(enabled = page > 0, onClick = previous).padding(horizontal = 14.dp))
        Text("PINCH TO ZOOM", color = Muted, style = MaterialTheme.typography.labelMedium)
        Text("›", color = if (page < total - 1) Cream else Muted, fontSize = 32.sp,
            modifier = Modifier.clickable(enabled = page < total - 1, onClick = next).padding(horizontal = 14.dp))
    }
}

@Composable
private fun RemoteViewer(assets: List<ServerAsset>, deviceToken: String, initial: Int, close: () -> Unit) {
    val pager = rememberPagerState(initialPage = initial, pageCount = { assets.size })
    val scope = rememberCoroutineScope()
    var zoomedPage by remember { mutableIntStateOf(-1) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = pager.currentPage != zoomedPage) { index ->
                val asset = assets[index]
                if (asset.isVideo) {
                    androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                        VideoView(context).apply {
                            setVideoURI(Uri.parse(asset.contentUrl),
                                if (asset.needsAuth) mapOf("Authorization" to "Bearer $deviceToken") else emptyMap())
                            setMediaController(android.widget.MediaController(context))
                            start()
                        }
                    }, modifier = Modifier.fillMaxSize())
                } else {
                    var zoom by remember(asset.id) { mutableFloatStateOf(1f) }
                    var panX by remember(asset.id) { mutableFloatStateOf(0f) }
                    var panY by remember(asset.id) { mutableFloatStateOf(0f) }
                    Box(Modifier.fillMaxSize().pointerInput(asset.id) {
                        detectTransformGestures { _, delta, change, _ ->
                            zoom = (zoom * change).coerceIn(1f, 5f)
                            zoomedPage = if (zoom > 1f) index else -1
                            if (zoom > 1f) { panX += delta.x; panY += delta.y }
                            else { panX = 0f; panY = 0f }
                        }
                    }, contentAlignment = Alignment.Center) {
                        RemoteImage(asset.detailUrl, deviceToken, asset.needsAuth, asset.name,
                            Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom,
                                translationX = panX, translationY = panY), ContentScale.Fit)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding()
                .background(Ink.copy(alpha = .72f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✕", color = Cream, fontSize = 22.sp,
                    modifier = Modifier.clickable(onClick = close).padding(end = 20.dp))
                Column(Modifier.weight(1f)) {
                    Text(assets[pager.currentPage].name, color = Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1} / ${assets.size} · On server", color = Muted, fontSize = 12.sp)
                }
            }
            ViewerControls(pager.currentPage, assets.size,
                previous = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                next = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}
