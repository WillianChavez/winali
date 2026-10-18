package io.github.willianchavez.winali

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: StoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (getSharedPreferences("appearance", 0).getBoolean("checks", true)) scheduleUpdateChecks()
        handle(intent)
        setContent { FamilyTheme(this) { Store() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (intent.getBooleanExtra("updates", false)) model.requestedTab.value = 1
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.started++
    }

    override fun onStop() {
        AppVisibility.started--
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        model.refreshLocal()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {}
    }

    @Composable
    private fun Store() {
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val state by model.state.collectAsState()
        val requested by model.requestedTab.collectAsState()
        val prefs = remember { getSharedPreferences("appearance", 0) }
        var checks by remember { mutableStateOf(prefs.getBoolean("checks", true)) }
        var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
        val notificationPermission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val installPermission =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val next = afterPermission
                afterPermission = null
                if (packageManager.canRequestPackageInstalls()) next?.invoke()
                else model.report(Failure.PermissionDenied)
            }
        LaunchedEffect(Unit) { model.messages.collect { snackbar.showSnackbar(it) } }

        fun ensureInstallPermission(next: () -> Unit) {
            if (packageManager.canRequestPackageInstalls()) {
                next()
                return
            }
            afterPermission = next
            installPermission.launch(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
        }

        fun begin(apps: List<CatalogApp>) {
            if (
                Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
            )
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            ensureInstallPermission { apps.forEach(model::start) }
        }

        val actions = remember {
            object : StoreActions {
                override fun primary(app: CatalogApp) {
                    when (model.state.value.action(app)) {
                        ActionState.Open ->
                            packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                                startActivity(it)
                            }
                        ActionState.Install,
                        ActionState.Update,
                        ActionState.InstallDownload,
                        ActionState.AwaitingConfirmation,
                        is ActionState.Retry -> begin(listOf(app))
                        else -> {}
                    }
                }

                override fun cancel(app: CatalogApp) = model.cancel(app)

                override fun retry(app: CatalogApp) = begin(listOf(app))

                override fun updateAll() {
                    val current = model.state.value
                    val todo =
                        updateOrder(current.updates, packageName).filter {
                            when (current.action(it)) {
                                ActionState.Update,
                                ActionState.InstallDownload,
                                is ActionState.Retry -> true
                                else -> false
                            }
                        }
                    if (todo.isNotEmpty()) begin(todo)
                }

                override fun refresh() {
                    scope.launch { model.refresh(true) }
                }
            }
        }
        val settings =
            SettingsModel(
                checksEnabled = checks,
                theme = prefs.getString("theme", "system") ?: "system",
                setChecks = {
                    checks = it
                    prefs.edit().putBoolean("checks", it).apply()
                    if (it) scheduleUpdateChecks() else cancelUpdateChecks()
                },
                setTheme = {
                    prefs.edit().putString("theme", it).apply()
                    recreate()
                },
                toggleLanguage = {
                    prefs.edit().putString("language", if (es()) "en" else "es").apply()
                    recreate()
                },
                clearDownloads = model::clearDownloads,
                openUrl = ::openUrl,
            )
        StoreScreen(
            state = state.copy(apps = state.apps.map { it.localized(this) }),
            actions = actions,
            settings = settings,
            snackbar = snackbar,
            requestedTab = requested,
            onTabShown = { model.requestedTab.value = null },
        )
    }
}
