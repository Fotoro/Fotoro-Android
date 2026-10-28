package com.nc.fotoro

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nc.fotoro.data.AccountSession
import com.nc.fotoro.data.FotoroApi
import com.nc.fotoro.data.LocalAsset
import com.nc.fotoro.data.MediaCatalog
import com.nc.fotoro.data.PairedServer
import com.nc.fotoro.data.SecureStore
import com.nc.fotoro.data.ServerAsset
import com.nc.fotoro.data.toServerAsset
import com.nc.fotoro.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val booting: Boolean = true,
    val busy: Boolean = false,
    val account: AccountSession? = null,
    val server: PairedServer? = null,
    val serverOnline: Boolean = false,
    val mediaGranted: Boolean = false,
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = true,
    val media: List<LocalAsset> = emptyList(),
    val remoteMedia: List<ServerAsset> = emptyList(),
    val remoteTotal: Int = 0,
    val remoteLoading: Boolean = false,
    val visibleCount: Int = 120,
    val syncState: WorkInfo.State? = null,
    val syncDone: Int = 0,
    val syncTotal: Int = 0,
    val syncName: String = "",
    val backedUp: Int = 0,
    val primaryOnly: Int = 0,
    val lastSync: Long = 0,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureStore(application)
    private val api = FotoroApi(store)
    private val catalog = MediaCatalog(application)
    private val prefs = application.getSharedPreferences("fotoro_sync", 0)
    private val workManager = WorkManager.getInstance(application)
    private val workLiveData = workManager.getWorkInfosForUniqueWorkLiveData(SyncScheduler.MANUAL)
    private val observer = androidx.lifecycle.Observer<List<WorkInfo>> { jobs ->
        val job = jobs.firstOrNull()
        _state.update { it.copy(syncState = job?.state,
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
        val auto = prefs.getBoolean("auto", true)
        val wifi = prefs.getBoolean("wifi_only", true)
        _state.update { it.copy(account = store.account, server = store.server,
            mediaGranted = catalog.hasAccess(), autoSync = auto, wifiOnly = wifi, booting = false) }
        loadSummary()
        if (store.account != null) viewModelScope.launch {
            runCatching { api.validAccountToken() }.onFailure { _state.update { it.copy(error = "Sign in again to renew your session") } }
        }
        if (store.server != null) { checkServer(); loadRemote() }
        if (catalog.hasAccess()) refreshMedia()
        if (store.server != null && catalog.hasAccess()) SyncScheduler.scheduleAuto(application, auto, wifi)
    }

    fun signIn(googleToken: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        runCatching { api.signIn(googleToken) }
            .onSuccess { account ->
                if (store.server != null && state.value.account?.userId != account.userId) store.server = null
                _state.update { it.copy(account = account, server = store.server, busy = false) }
                if (store.server != null) { checkServer(); loadRemote() }
            }
            .onFailure { error -> _state.update { it.copy(busy = false, error = error.message ?: "Sign-in failed") } }
    }

    fun pair() = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        runCatching { api.pair() }
            .onSuccess { server ->
                _state.update { it.copy(server = server, serverOnline = true, busy = false) }
                loadRemote()
                SyncScheduler.scheduleAuto(getApplication(), state.value.autoSync, state.value.wifiOnly)
                if (state.value.mediaGranted && state.value.autoSync) SyncScheduler.syncNow(getApplication(), state.value.wifiOnly)
            }
            .onFailure { error -> _state.update { it.copy(busy = false, error = error.message ?: "Pairing failed") } }
    }

    fun checkServer() = viewModelScope.launch {
        val online = api.testServer()
        _state.update { it.copy(serverOnline = online) }
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
        if (state.value.autoSync && store.server != null && catalog.hasAccess()) {
            refreshMedia()
            SyncScheduler.syncNow(getApplication(), state.value.wifiOnly)
        }
    }

    fun syncNow() {
        _state.update { it.copy(error = null) }
        SyncScheduler.syncNow(getApplication())
    }

    fun signOut() {
        SyncScheduler.scheduleAuto(getApplication(), false)
        workManager.cancelUniqueWork(SyncScheduler.MANUAL)
        store.clear()
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
        super.onCleared()
    }
}
