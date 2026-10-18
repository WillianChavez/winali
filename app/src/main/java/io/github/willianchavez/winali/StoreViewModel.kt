package io.github.willianchavez.winali

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screens need, as plain data. */
data class StoreState(
    val apps: List<CatalogApp> = emptyList(),
    val installed: Map<String, Long> = emptyMap(),
    val installedNames: Map<String, String> = emptyMap(),
    val downloaded: Set<String> = emptySet(),
    val downloads: Map<String, DownloadStatus> = emptyMap(),
    val phases: Map<String, InstallPhase> = emptyMap(),
    val failures: Map<String, Pair<Failure, String?>> = emptyMap(),
    val refreshing: Boolean = false,
    val lastCheck: Long = 0,
    val spanish: Boolean = true,
    val compatible: (CatalogApp) -> Boolean = { it.compatible() },
) {
    fun action(app: CatalogApp): ActionState =
        resolveAction(
            app.release,
            compatible(app),
            installed[app.packageName],
            app.packageName in downloaded,
            downloads[app.packageName] ?: DownloadStatus.Idle,
            phases[app.packageName] ?: InstallPhase.None,
        )

    val updates: List<CatalogApp>
        get() = pendingUpdates(apps, installed, compatible)

    /** Text for the last problem with this app, if the primary action did not clear it. */
    fun problem(app: CatalogApp): String? {
        val action = action(app)
        if (action == ActionState.Open) return null
        val download = downloads[app.packageName]
        if (download is DownloadStatus.Failed) return download.failure.message(spanish, download.detail)
        return failures[app.packageName]?.let { (failure, detail) ->
            if (failure == Failure.Cancelled) null else failure.message(spanish, detail)
        }
    }

    /** Apps with an active, queued or failed download, in catalog order. */
    val queue: List<CatalogApp>
        get() = apps.filter { downloads[it.packageName]?.let { s -> s !is DownloadStatus.Idle } == true }
}

interface StoreActions {
    fun primary(app: CatalogApp)

    fun cancel(app: CatalogApp)

    fun retry(app: CatalogApp)

    fun updateAll()

    fun refresh()
}

class StoreViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context = application
    private val catalog = CatalogStore(application)
    private val _state = MutableStateFlow(StoreState(spanish = application.es()))
    val state: StateFlow<StoreState> = _state.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    val requestedTab = MutableStateFlow<Int?>(null)

    private val installListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == null || !key.startsWith("result:")) {
                refreshLocal()
                return@OnSharedPreferenceChangeListener
            }
            val pkg = key.removePrefix("result:")
            val name = _state.value.apps.firstOrNull { it.packageName == pkg }?.name ?: pkg
            val value = prefs.getString(key, null)
            when {
                value == "ok" ->
                    emit(context.words("$name instalada", "$name installed"))
                value?.startsWith("fail") == true -> {
                    val (failure, detail) = InstallLog.lastFailure(context, pkg) ?: (Failure.Unknown to null)
                    emit("$name: " + failure.message(context.es(), detail))
                }
            }
            refreshLocal()
        }

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refreshLocal()
        }

    init {
        InstallLog.registerListener(context, installListener)
        ContextCompat.registerReceiver(
            context,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { catalog.cached() }
            _state.update { it.copy(apps = apps) }
            withContext(Dispatchers.IO) { context.cleanDownloads() }
            refreshLocal()
            announceOwnUpdate()
            WorkManager.getInstance(context).getWorkInfosByTagFlow("download").collect { works ->
                _state.update { it.copy(downloads = downloadStatuses(works)) }
                refreshLocal()
            }
        }
        viewModelScope.launch { refresh(false) }
    }

    private fun announceOwnUpdate() {
        val prefs = context.distributionPrefs()
        val previous = prefs.getString("ownVersion", null)
        if (previous != null && previous != BuildConfig.VERSION_NAME)
            emit(
                context.words(
                    "Winali se actualizó a la versión ${BuildConfig.VERSION_NAME}",
                    "Winali was updated to version ${BuildConfig.VERSION_NAME}",
                ),
            )
        prefs.edit().putString("ownVersion", BuildConfig.VERSION_NAME).apply()
    }

    private fun emit(message: String) {
        _messages.trySend(message)
    }

    /** Re-reads device state that changes outside the app: installs, files, sessions. */
    fun refreshLocal() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _state.value
            val installed = context.installedVersions(current.apps)
            val names = installed.keys.mapNotNull { pkg -> context.installedVersionName(pkg)?.let { pkg to it } }.toMap()
            val downloaded =
                current.apps
                    .filter { it.release != null }
                    .filter { File(context.filesDir, "downloads/${it.packageName}-${it.release!!.versionCode}.apk").exists() }
                    .map { it.packageName }
                    .toSet()
            val phases =
                current.apps.associate { it.packageName to InstallLog.phase(context, it.packageName) }
            val failures =
                current.apps.mapNotNull { app ->
                    InstallLog.lastFailure(context, app.packageName)?.let { app.packageName to it }
                }.toMap()
            _state.update {
                it.copy(
                    installed = installed,
                    installedNames = names,
                    downloaded = downloaded,
                    phases = phases,
                    failures = failures,
                    lastCheck = catalog.lastCheck(),
                    spanish = context.es(),
                )
            }
        }
    }

    suspend fun refresh(force: Boolean) {
        _state.update { it.copy(refreshing = true) }
        try {
            val apps = withContext(Dispatchers.IO) { catalog.refresh(force) }
            _state.update { it.copy(apps = apps) }
        } catch (e: DistributionException) {
            emit(e.failure.message(context.es()))
        } catch (e: Exception) {
            emit(
                context.words(
                    "Sin conexión. Mostrando el catálogo guardado.",
                    "Offline. Showing the saved catalog.",
                ),
            )
        }
        _state.update { it.copy(refreshing = false) }
        refreshLocal()
    }

    /** Download (and then install) or install an existing download. Caller ensured permissions. */
    fun start(app: CatalogApp) {
        InstallLog.clearResult(context, app.packageName)
        if (app.packageName in _state.value.downloaded) installDownloaded(app)
        else context.enqueueDownload(app)
        refreshLocal()
    }

    fun installDownloaded(app: CatalogApp) {
        viewModelScope.launch {
            try {
                context.install(app)
            } catch (e: DistributionException) {
                // install() already recorded the failure; the listener reports it.
            }
            refreshLocal()
        }
    }

    fun cancel(app: CatalogApp) {
        viewModelScope.launch {
            context.cancelDownload(app)
            refreshLocal()
        }
    }

    fun report(failure: Failure, app: CatalogApp? = null) {
        val text = failure.message(context.es())
        emit(if (app != null) "${app.name}: $text" else text)
    }

    fun clearDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val busy = _state.value.downloads.values.any { it is DownloadStatus.Running || it is DownloadStatus.Queued }
            if (!busy)
                File(context.filesDir, "downloads").listFiles()
                    ?.filter { it.extension == "apk" || it.extension == "part" }
                    ?.forEach { it.delete() }
            refreshLocal()
            emit(
                if (busy) context.words("Hay descargas en curso", "Downloads are in progress")
                else context.words("Descargas eliminadas", "Downloads removed"),
            )
        }
    }

    override fun onCleared() {
        InstallLog.unregisterListener(context, installListener)
        runCatching { context.unregisterReceiver(packageReceiver) }
    }
}
