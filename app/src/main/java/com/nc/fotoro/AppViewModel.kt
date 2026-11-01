package com.nc.fotoro

import android.app.Application
import android.net.Uri
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nc.fotoro.data.AccountSession
import com.nc.fotoro.data.ApiFailure
import com.nc.fotoro.data.BackupStatus
import com.nc.fotoro.data.DashboardMetrics
import com.nc.fotoro.data.FotoroApi
import com.nc.fotoro.data.LinkedDevice
import com.nc.fotoro.data.LocalAsset
import com.nc.fotoro.data.MediaCatalog
import com.nc.fotoro.data.PairedServer
import com.nc.fotoro.data.PendingBrowserSignIn
import com.nc.fotoro.data.SecureStore
import com.nc.fotoro.data.ServerAsset
import com.nc.fotoro.data.ServerDiagnostics
import com.nc.fotoro.data.ProcessingSchedule
import com.nc.fotoro.data.SearchHit
import com.nc.fotoro.data.toBackupStatus
import com.nc.fotoro.data.toDashboardMetrics
import com.nc.fotoro.data.toLinkedDevice
import com.nc.fotoro.data.toProcessingSchedule
import com.nc.fotoro.data.toServerDiagnostics
import com.nc.fotoro.data.toServerAsset
import com.nc.fotoro.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject

data class AppUiState(
    val booting: Boolean = true,
    val busy: Boolean = false,
    val account: AccountSession? = null,
    val reauthRequired: Boolean = false,
    val browserSignInPending: Boolean = false,
    val server: PairedServer? = null,
    val serverOnline: Boolean = false,
    val mediaGranted: Boolean = false,
    val autoSync: Boolean = true,
    val transfersPaused: Boolean = false,
    val wifiOnly: Boolean = true,
    val media: List<LocalAsset> = emptyList(),
    val localTotal: Int = 0,
    val remoteMedia: List<ServerAsset> = emptyList(),
    val remoteTotal: Int = 0,
    val remoteLoading: Boolean = false,
    val visibleCount: Int = 120,
    val syncState: WorkInfo.State? = null,
    val syncDone: Int = 0,
    val syncTotal: Int = 0,
    val syncName: String = "",
    val selectedUploadActive: Boolean = false,
    val backedUp: Int = 0,
    val primaryOnly: Int = 0,
    val lastSync: Long = 0,
    val metrics: DashboardMetrics? = null,
    val metricsCached: Boolean = false,
    val devices: List<LinkedDevice> = emptyList(),
    val storage: BackupStatus? = null,
    val diagnostics: ServerDiagnostics? = null,
    val schedule: ProcessingSchedule? = null,
    val dashboardLoading: Boolean = false,
    val searchQuery: String = "",
    val searchHits: List<SearchHit> = emptyList(),
    val searchLoading: Boolean = false,
    val searchToken: String = "",
    val searchedAsset: ServerAsset? = null,
    val pairingPayload: String = "",
    val pairingExpiresAt: String = "",
    val documentAssetId: String = "",
    val documentFile: File? = null,
    val documentLoading: Boolean = false,
    val verifyingOriginal: Boolean = false,
    val originalVerified: String = "",
    val originalVerificationId: String = "",
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureStore(application)
    private val api = FotoroApi(store)
    private val catalog = MediaCatalog(application)
    private val prefs = application.getSharedPreferences("fotoro_sync", 0)
    private var lastDashboardRefresh = 0L
    private var browserPollingJob: Job? = null
    private val workManager = WorkManager.getInstance(application)
    private val workLiveData = workManager.getWorkInfosForUniqueWorkLiveData(SyncScheduler.MANUAL)
    private val autoWorkLiveData = workManager.getWorkInfosForUniqueWorkLiveData(SyncScheduler.AUTO)
    private val selectedWorkLiveData = workManager.getWorkInfosByTagLiveData(SyncScheduler.SELECTED_TAG)
    private val observer = androidx.lifecycle.Observer<List<WorkInfo>> {
        val jobs = workLiveData.value.orEmpty() + autoWorkLiveData.value.orEmpty() + selectedWorkLiveData.value.orEmpty()
        val job = jobs.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: jobs.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
            ?: jobs.lastOrNull()
        _state.update { it.copy(syncState = job?.state,
            transfersPaused = SyncScheduler.isPaused(application),
            selectedUploadActive = job?.tags?.contains(SyncScheduler.SELECTED_TAG) == true,
            syncDone = job?.progress?.getInt("done", 0) ?: 0,
            syncTotal = job?.progress?.getInt("total", 0) ?: 0,
            syncName = job?.progress?.getString("name").orEmpty(),
            error = job?.progress?.getString("error") ?: it.error) }
        if (job?.state == WorkInfo.State.SUCCEEDED) loadSummary()
    }
    private val _state = MutableStateFlow(AppUiState())
    val state = _state.asStateFlow()

    init {
        workLiveData.observeForever(observer)
        autoWorkLiveData.observeForever(observer)
        selectedWorkLiveData.observeForever(observer)
        val auto = prefs.getBoolean("auto", true)
        val wifi = prefs.getBoolean("wifi_only", true)
        _state.update { it.copy(account = store.account, server = store.server,
            mediaGranted = catalog.hasAccess(), autoSync = auto, wifiOnly = wifi,
            transfersPaused = SyncScheduler.isPaused(application), booting = false) }
        prefs.getString("dashboard_metrics", null)?.let { saved ->
            runCatching { JSONObject(saved).toDashboardMetrics() }.onSuccess { metrics ->
                _state.update { it.copy(metrics = metrics, metricsCached = true) }
            }
        }
        loadSummary()
        if (store.account != null) viewModelScope.launch {
            runCatching { api.validAccountToken() }.onFailure { error ->
                _state.update { it.copy(reauthRequired = error is ApiFailure && error.status == 401,
                    error = if (error is ApiFailure && error.status == 401)
                        "Your account session expired. Continue with Google to reconnect."
                    else error.message ?: "Could not check your account session") }
            }
        }
        store.pendingBrowserSignIn?.let { pending ->
            if (pending.expiresAt > System.currentTimeMillis()) pollBrowserSignIn(pending)
            else store.pendingBrowserSignIn = null
        }
        if (store.server != null) { checkServer(); loadRemote(); refreshDashboard() }
        if (catalog.hasAccess()) refreshMedia()
        if (store.server != null && catalog.hasAccess()) SyncScheduler.scheduleAuto(application, auto, wifi)
    }

    fun signIn(googleToken: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        runCatching { api.signIn(googleToken) }
            .onSuccess { account ->
                browserPollingJob?.cancel()
                store.pendingBrowserSignIn = null
                if (store.server != null && state.value.account?.userId != account.userId) store.server = null
                _state.update { it.copy(account = account, server = store.server, busy = false,
                    reauthRequired = false, browserSignInPending = false) }
                if (store.server != null) { checkServer(); loadRemote(); refreshDashboard() }
            }
            .onFailure { error -> _state.update { it.copy(busy = false, error = error.message ?: "Sign-in failed") } }
    }

    fun startBrowserSignIn(openBrowser: (String) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        runCatching { api.startBrowserSignIn() }
            .onSuccess { (pending, url) ->
                _state.update { it.copy(busy = false, browserSignInPending = true) }
                val browser = runCatching { openBrowser(url) }
                if (browser.isFailure) {
                    store.pendingBrowserSignIn = null
                    _state.update { it.copy(browserSignInPending = false,
                        error = browser.exceptionOrNull()?.message ?: "Could not open browser") }
                    return@onSuccess
                }
                pollBrowserSignIn(pending)
            }
            .onFailure { error -> _state.update { it.copy(busy = false,
                error = error.message ?: "Could not start browser sign-in") } }
    }

    private fun pollBrowserSignIn(pending: PendingBrowserSignIn) {
        browserPollingJob?.cancel()
        browserPollingJob = viewModelScope.launch {
            _state.update { it.copy(browserSignInPending = true) }
            while (isActive && System.currentTimeMillis() < pending.expiresAt) {
                val result = runCatching { api.pollBrowserSignIn(pending.state) }
                val account = result.getOrNull()
                if (account != null) {
                    if (state.value.account != null && state.value.account?.userId != account.userId) store.server = null
                    _state.update { it.copy(account = account, server = store.server,
                        reauthRequired = false, browserSignInPending = false, error = null) }
                    if (store.server != null) { checkServer(); loadRemote(); refreshDashboard() }
                    return@launch
                }
                val error = result.exceptionOrNull()
                if (error is ApiFailure && error.status == 410) break
                delay(5_000)
            }
            store.pendingBrowserSignIn = null
            _state.update { it.copy(browserSignInPending = false,
                error = "Browser sign-in expired. Try again.") }
        }
    }

    fun pair() = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        runCatching { api.pair() }
            .onSuccess { server ->
                _state.update { it.copy(server = server, serverOnline = true, busy = false) }
                loadRemote()
                refreshDashboard()
                SyncScheduler.scheduleAuto(getApplication(), state.value.autoSync, state.value.wifiOnly)
                if (state.value.mediaGranted && state.value.autoSync) SyncScheduler.syncNow(getApplication(), state.value.wifiOnly)
            }
            .onFailure { error -> _state.update { it.copy(busy = false) }; reportAccountFailure(error, "Pairing failed") }
    }

    fun pairFromQr(payload: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        runCatching { api.pairFromQr(payload) }
            .onSuccess { server ->
                _state.update { it.copy(server = server, serverOnline = true, busy = false) }
                loadRemote()
                refreshDashboard()
                SyncScheduler.scheduleAuto(getApplication(), state.value.autoSync, state.value.wifiOnly)
                if (state.value.mediaGranted && state.value.autoSync) SyncScheduler.syncNow(getApplication(), state.value.wifiOnly)
            }
            .onFailure { error -> _state.update { it.copy(busy = false) }; reportAccountFailure(error, "QR pairing failed") }
    }

    fun checkServer() = viewModelScope.launch {
        val online = api.testServer()
        _state.update { it.copy(serverOnline = online) }
    }

    fun onForeground() {
        _state.update { it.copy(transfersPaused = SyncScheduler.isPaused(getApplication())) }
        if (store.server == null) return
        checkServer()
        if (System.currentTimeMillis() - lastDashboardRefresh > 45_000) refreshDashboard()
    }

    fun refreshDashboard() = viewModelScope.launch {
        if (store.server == null || state.value.dashboardLoading) return@launch
        _state.update { it.copy(dashboardLoading = true) }
        runCatching { api.validAccountToken() }.onFailure { error ->
            _state.update { it.copy(dashboardLoading = false,
                reauthRequired = error is ApiFailure && error.status == 401,
                error = if (error is ApiFailure && error.status == 401)
                    "Your account session expired. Continue with Google to reconnect."
                else error.message ?: "Could not check your account session") }
            return@launch
        }
        supervisorScope {
            listOf(
                async { runCatching { api.dashboardMetrics() }.onSuccess { response ->
                    prefs.edit().putString("dashboard_metrics", response.toString()).apply()
                    lastDashboardRefresh = System.currentTimeMillis()
                    _state.update { it.copy(metrics = response.toDashboardMetrics(), metricsCached = false) }
                }.onFailure { error -> reportAccountFailure(error, "Could not load dashboard") } },
                async { runCatching { api.linkedDevices().optJSONArray("devices") }.onSuccess { list ->
                    val devices = (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it).toLinkedDevice() }
                    _state.update { it.copy(devices = devices) }
                } },
                async { runCatching { api.storageStatus().toBackupStatus() }.onSuccess { storage ->
                    _state.update { it.copy(storage = storage) }
                } },
                async { runCatching { api.diagnostics().toServerDiagnostics() }.onSuccess { diagnostics ->
                    _state.update { it.copy(diagnostics = diagnostics) }
                } },
                async { runCatching { api.processingSchedule().toProcessingSchedule() }.onSuccess { schedule ->
                    _state.update { it.copy(schedule = schedule) }
                } },
            ).awaitAll()
        }
        _state.update { it.copy(dashboardLoading = false) }
    }

    fun setProcessingEnabled(enabled: Boolean) = viewModelScope.launch {
        runCatching { api.updateProcessingSchedule(enabled); api.processingSchedule().toProcessingSchedule() }
            .onSuccess { updated -> _state.update { it.copy(schedule = updated) } }
            .onFailure { error -> reportAccountFailure(error, "Could not update schedule") }
    }

    fun syncServerStorage() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        runCatching { api.syncStorage() }.onSuccess { response ->
            _state.update { it.copy(storage = response.getJSONObject("storage").toBackupStatus()) }
            refreshDashboard()
        }.onFailure { error -> reportAccountFailure(error, "Backup failed") }
        _state.update { it.copy(busy = false) }
    }

    fun verifyServerStorage() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        runCatching { api.verifyStorage() }.onSuccess { response ->
            _state.update { it.copy(storage = response.getJSONObject("storage").toBackupStatus()) }
        }.onFailure { error -> reportAccountFailure(error, "Verification failed") }
        _state.update { it.copy(busy = false) }
    }

    fun search(query: String) = viewModelScope.launch {
        _state.update { it.copy(searchQuery = query, searchLoading = query.isNotBlank(), searchHits = emptyList()) }
        if (query.isBlank()) return@launch
        runCatching {
            val response = api.search(query).optJSONArray("results")
            val token = api.validAccountToken()
            val server = requireNotNull(store.server)
            val hits = (0 until (response?.length() ?: 0)).map { index ->
                val row = response!!.getJSONObject(index)
                SearchHit(row.optString("hash"), row.optString("caption"), row.optString("category"),
                    row.optDouble("score"), server.url + row.optString("thumbnail"))
            }.filter { it.hash.isNotBlank() }
            token to hits
        }.onSuccess { (token, hits) -> _state.update { it.copy(searchToken = token, searchHits = hits, searchLoading = false) } }
            .onFailure { error -> _state.update { it.copy(searchLoading = false) }; reportAccountFailure(error, "Search failed") }
    }

    fun openSearchHit(hit: SearchHit) = viewModelScope.launch {
        runCatching {
            val token = api.validAccountToken()
            _state.update { it.copy(searchToken = token) }
            try { api.mediaItem(hit.hash).toServerAsset(requireNotNull(store.server)) }
            catch (error: ApiFailure) {
                if (error.status != 404 || !hit.hash.matches(Regex("[a-fA-F0-9]{64}"))) throw error
                val server = requireNotNull(store.server)
                ServerAsset(hit.hash, hit.caption.ifBlank { "Search result" }, false, "photo", "image/jpeg",
                    0, "", false, 0, hit.thumbnailUrl,
                    "${server.url}/api/image/${hit.hash}", "${server.url}/api/image/${hit.hash}", true)
            }
        }
            .onSuccess { asset -> _state.update { it.copy(searchedAsset = asset) } }
            .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not open item") } }
    }

    fun closeSearchHit() { _state.update { it.copy(searchedAsset = null) } }

    fun createPairingInvitation() = viewModelScope.launch {
        runCatching { api.pairingInvitation() }.onSuccess { response ->
            _state.update { it.copy(pairingPayload = response.optString("qr_payload"),
                pairingExpiresAt = response.optString("expires_at")) }
        }.onFailure { error -> reportAccountFailure(error, "Could not create pairing code") }
    }

    fun clearPairingInvitation() { _state.update { it.copy(pairingPayload = "", pairingExpiresAt = "") } }

    private fun reportAccountFailure(error: Throwable, fallback: String) {
        val expired = error is ApiFailure && error.status == 401
        _state.update { it.copy(reauthRequired = it.reauthRequired || expired,
            error = if (expired) "Your account session expired. Continue with Google to reconnect."
                else error.message ?: fallback) }
    }

    fun loadDocument(asset: ServerAsset) = viewModelScope.launch {
        if (state.value.documentAssetId == asset.id &&
            (state.value.documentLoading || state.value.documentFile != null)) return@launch
        _state.update { it.copy(documentAssetId = asset.id, documentFile = null, documentLoading = true) }
        runCatching { api.downloadDocument(asset, getApplication()) }
            .onSuccess { file -> _state.update { it.copy(documentFile = file, documentLoading = false) } }
            .onFailure { error -> _state.update { it.copy(documentLoading = false,
                error = error.message ?: "Could not open document") } }
    }

    fun verifyOriginal(asset: ServerAsset) = viewModelScope.launch {
        _state.update { it.copy(verifyingOriginal = true, originalVerified = "", originalVerificationId = asset.id) }
        runCatching { api.verifyOriginal(asset) }
            .onSuccess { matches -> _state.update { it.copy(originalVerified = if (matches)
                "Original matches the server checksum" else "Downloaded original does not match its checksum") } }
            .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not verify original") } }
        _state.update { it.copy(verifyingOriginal = false) }
    }

    fun loadRemote(reset: Boolean = true) = viewModelScope.launch {
        val server = store.server ?: return@launch
        if (state.value.remoteLoading) return@launch
        val page = if (reset) 1 else state.value.remoteMedia.size / 100 + 1
        _state.update { it.copy(remoteLoading = true, error = null) }
        runCatching { api.listMedia(page) }
            .onSuccess { response ->
                val array = response.getJSONArray("media")
                val incoming = (0 until array.length()).map { array.getJSONObject(it).toServerAsset(server) }
                _state.update { it.copy(remoteMedia = if (reset) incoming else it.remoteMedia + incoming,
                    remoteTotal = response.optInt("total"), remoteLoading = false) }
            }
            .onFailure { error -> _state.update { it.copy(remoteLoading = false, error = error.message ?: "Could not load server library") } }
    }

    fun refreshMedia() = viewModelScope.launch {
        val granted = catalog.hasAccess()
        _state.update { it.copy(mediaGranted = granted) }
        if (granted) {
            runCatching { catalog.count() }.onSuccess { total -> _state.update { it.copy(localTotal = total) } }
            runCatching { catalog.list(limit = 1000) }
                .onSuccess { assets -> _state.update { it.copy(media = assets) } }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not read your photos") } }
            if (store.server != null) {
                SyncScheduler.scheduleAuto(getApplication(), state.value.autoSync, state.value.wifiOnly)
                if (state.value.autoSync && prefs.getLong("last_sync", 0) == 0L) SyncScheduler.syncNow(getApplication(), state.value.wifiOnly)
            }
        }
    }

    fun loadMore() {
        val next = state.value.visibleCount + 120
        _state.update { it.copy(visibleCount = next) }
        if (next >= state.value.media.size && state.value.media.size == 1000) viewModelScope.launch {
            runCatching { catalog.list(limit = state.value.media.size + 1000) }
                .onSuccess { assets -> _state.update { it.copy(media = assets) } }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        prefs.edit().putBoolean("auto", enabled).apply()
        _state.update { it.copy(autoSync = enabled) }
        SyncScheduler.scheduleAuto(getApplication(), enabled && store.server != null && catalog.hasAccess(), state.value.wifiOnly)
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean("wifi_only", enabled).apply()
        _state.update { it.copy(wifiOnly = enabled) }
        SyncScheduler.scheduleAuto(getApplication(), state.value.autoSync && store.server != null && catalog.hasAccess(), enabled)
    }

    fun syncOnNewMedia() {
        if (state.value.autoSync && !state.value.transfersPaused && store.server != null && catalog.hasAccess()) {
            refreshMedia()
            SyncScheduler.syncNow(getApplication(), state.value.wifiOnly)
        }
    }

    fun syncNow() {
        if (SyncScheduler.isPaused(getApplication())) { reportError("Resume transfers before starting a backup"); return }
        _state.update { it.copy(error = null) }
        SyncScheduler.syncNow(getApplication())
    }

    fun uploadSelectedFiles(uris: List<Uri>) {
        if (store.server == null) { reportError("Pair this phone to your server first"); return }
        if (SyncScheduler.isPaused(getApplication())) { reportError("Resume transfers before offloading files"); return }
        uris.forEach { SyncScheduler.uploadSelected(getApplication(), it) }
    }

    fun stopTransfers() {
        SyncScheduler.stop(getApplication())
        _state.update { it.copy(transfersPaused = true, error = null) }
    }

    fun resumeTransfers() {
        val context = getApplication<Application>()
        SyncScheduler.resume(context, state.value.autoSync && store.server != null && catalog.hasAccess(), state.value.wifiOnly)
        _state.update { it.copy(transfersPaused = false, error = null) }
        if (store.server != null && catalog.hasAccess()) SyncScheduler.syncNow(context, state.value.wifiOnly)
    }

    fun signOut() {
        browserPollingJob?.cancel()
        SyncScheduler.scheduleAuto(getApplication(), false)
        workManager.cancelUniqueWork(SyncScheduler.MANUAL)
        workManager.cancelUniqueWork(SyncScheduler.SELECTED)
        store.clear()
        viewModelScope.launch {
            runCatching { CredentialManager.create(getApplication()).clearCredentialState(ClearCredentialStateRequest()) }
        }
        val auto = prefs.getBoolean("auto", true)
        val wifi = prefs.getBoolean("wifi_only", true)
        prefs.edit().clear().putBoolean("auto", auto).putBoolean("wifi_only", wifi).apply()
        _state.update { AppUiState(booting = false, mediaGranted = catalog.hasAccess(), autoSync = auto, wifiOnly = wifi) }
    }

    fun clearError() { _state.update { it.copy(error = null) } }
    fun reportError(message: String) { _state.update { it.copy(error = message) } }

    private fun loadSummary() {
        _state.update { it.copy(backedUp = prefs.getInt("backed_up", 0),
            primaryOnly = prefs.getInt("primary_only", 0), lastSync = prefs.getLong("last_sync", 0)) }
    }

    override fun onCleared() {
        workLiveData.removeObserver(observer)
        autoWorkLiveData.removeObserver(observer)
        selectedWorkLiveData.removeObserver(observer)
        super.onCleared()
    }
}
