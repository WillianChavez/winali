package io.github.willianchavez.winali

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (getSharedPreferences("appearance", 0).getBoolean("checks", true)) scheduleChecks()
        setContent { FamilyTheme(this) { Store() } }
    }

    private fun scheduleChecks() {
        val request =
            PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("catalog-updates", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Store() {
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val catalog = remember { CatalogStore(this) }
        var apps by remember { mutableStateOf(catalog.cached()) }
        var tab by remember {
            mutableIntStateOf(if (intent.getBooleanExtra("updates", false)) 1 else 0)
        }
        var query by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("") }
        var detail by remember { mutableStateOf<CatalogApp?>(null) }
        var refreshing by remember { mutableStateOf(false) }
        var resume by remember { mutableIntStateOf(0) }
        val manager = remember { WorkManager.getInstance(this) }
        val works by manager.getWorkInfosByTagFlow("download").collectAsState(initial = emptyList())
        val notificationPermission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val installPermission =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                resume++
            }
        suspend fun refresh(force: Boolean) {
            refreshing = true
            try {
                apps = withContext(Dispatchers.IO) { catalog.refresh(force) }
            } catch (e: Exception) {
                snackbar.showSnackbar(
                    words(
                        "Sin conexión. Mostrando el catálogo guardado.",
                        "Offline. Showing the saved catalog.",
                    ),
                )
            }
            refreshing = false
        }
        DisposableEffect(Unit) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resume++
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(resume) {
            val message = distributionPrefs().getString("installResult", null)
            if (message != null) {
                distributionPrefs().edit().remove("installResult").apply()
                snackbar.showSnackbar(message)
            }
        }
        LaunchedEffect(Unit) { refresh(false) }
        fun activeWork(app: CatalogApp) =
            works.filter { app.packageName in it.tags }.firstOrNull { !it.state.isFinished }
        fun primary(app: CatalogApp) {
            val release = app.release ?: return
            if (!app.compatible()) return
            val work = activeWork(app)
            if (work != null) {
                manager.cancelWorkById(work.id)
                return
            }
            val installed = installedVersion(app.packageName)
            if (installed != null && installed >= release.versionCode) {
                packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) }
                return
            }
            if (downloadFile(app).exists()) {
                if (!packageManager.canRequestPackageInstalls())
                    installPermission.launch(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                else
                    scope.launch {
                        try {
                            install(app)
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: "Error")
                        }
                    }
            } else {
                if (
                    android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                )
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                enqueueDownload(app)
            }
        }
        @Composable
        fun Action(app: CatalogApp) {
            val release = app.release
            val work = activeWork(app)
            val installed =
                remember(app.packageName, resume, works) { installedVersion(app.packageName) }
            val text =
                when {
                    release == null -> words("Próximamente", "Coming soon")
                    !app.compatible() -> words("No compatible", "Incompatible")
                    work != null ->
                        if (work.state == WorkInfo.State.RUNNING)
                            "${work.progress.getInt("percent",0)}% · " + words("Cancelar", "Cancel")
                        else words("En cola · Cancelar", "Queued · Cancel")
                    installed != null && installed >= release.versionCode -> words("Abrir", "Open")
                    downloadFile(app).exists() -> words("Instalar descarga", "Install download")
                    installed != null -> words("Actualizar", "Update")
                    else -> words("Instalar", "Install")
                }
            FilledTonalButton(
                onClick = { primary(app) },
                enabled = release != null && app.compatible(),
                shape = RoundedCornerShape(30.dp),
            ) {
                Text(text)
            }
        }
        val updates = apps.filter {
            val version = installedVersion(it.packageName)
            version != null && (it.release?.versionCode ?: 0) > version
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (detail != null) "" else "Winali", fontWeight = FontWeight.Bold)
                    },
                    navigationIcon = {
                        if (detail != null)
                            IconButton(onClick = { detail = null }) {
                                Icon(Icons.Rounded.ArrowBack, words("Volver", "Back"))
                            }
                    },
                    actions = {
                        if (tab != 2)
                            IconButton(
                                onClick = { scope.launch { refresh(true) } },
                                enabled = !refreshing,
                            ) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    words("Buscar actualizaciones", "Check updates"),
                                )
                            }
                    },
                )
            },
            bottomBar = {
                if (detail == null)
                    NavigationBar {
                        listOf(
                                Icons.Rounded.Explore,
                                Icons.Rounded.SystemUpdate,
                                Icons.Rounded.Settings,
                            )
                            .forEachIndexed { i, icon ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (i == 1 && updates.isNotEmpty())
                                                    Badge { Text(updates.size.toString()) }
                                            },
                                        ) {
                                            Icon(icon, null)
                                        }
                                    },
                                    label = {
                                        Text(
                                            listOf(
                                                words("Explorar", "Explore"),
                                                words("Actualizaciones", "Updates"),
                                                words("Ajustes", "Settings"),
                                            )[i],
                                        )
                                    },
                                )
                            }
                    }
            },
        ) { padding ->
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
            val current = detail
            if (current != null) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(24.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            AppMark(current, 82)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    current.name,
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    current.summary,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item { Action(current) }
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Metric(words("Versión", "Version"), current.release?.versionName ?: "—")
                            Metric(
                                words("Tamaño", "Size"),
                                current.release?.let { "${it.size/1048576} MB" } ?: "—",
                            )
                            Metric("Android", current.release?.let { "API ${it.minSdk}+" } ?: "8+")
                        }
                    }
                    if (current.screenshots.isNotEmpty())
                        item {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                current.screenshots.forEach { RemoteScreenshot(it) }
                            }
                        }
                    item {
                        Text(
                            words("Acerca de esta app", "About this app"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            current.description,
                            Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    current.release?.let { release ->
                        item {
                            Text(
                                words("Novedades", "What's new"),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(release.changelog, Modifier.padding(top = 10.dp))
                        }
                    }
                    item {
                        Text(
                            words("Permisos", "Permissions"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            current.permissions.joinToString("\n").ifBlank {
                                words("Sin permisos especiales", "No special permissions")
                            },
                            Modifier.padding(top = 10.dp),
                        )
                    }
                    item {
                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(current.repository)),
                                )
                            },
                        ) {
                            Icon(Icons.Rounded.Code, null)
                            Spacer(Modifier.width(8.dp))
                            Text(words("Ver código en GitHub", "View source on GitHub"))
                        }
                    }
                    val failure =
                        works
                            .firstOrNull {
                                current.packageName in it.tags && it.state == WorkInfo.State.FAILED
                            }
                            ?.outputData
                            ?.getString("error")
                    if (failure != null)
                        item { Text(failure, color = MaterialTheme.colorScheme.error) }
                }
            } else if (tab == 2) {
                SettingsScreen(
                    schedule = { scheduleChecks() },
                    clear = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                java.io
                                    .File(filesDir, "downloads")
                                    .listFiles()
                                    ?.filter { it.extension == "apk" }
                                    ?.forEach { file ->
                                        if (works.none { !it.state.isFinished }) file.delete()
                                    }
                            }
                            resume++
                            snackbar.showSnackbar(
                                words("Descargas eliminadas", "Downloads removed"),
                            )
                        }
                    },
                )
            } else
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Text(
                            if (tab == 0) words("Descubre algo útil.", "Find something useful.")
                            else words("Todo al día.", "Stay up to date."),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    item {
                        Text(
                            if (tab == 0)
                                words(
                                    "Apps cuidadas. Sin cuentas ni complicaciones.",
                                    "Thoughtful apps. No accounts or complications.",
                                )
                            else
                                words(
                                    "Tus aplicaciones, con sus últimas mejoras.",
                                    "Your apps, with their latest improvements.",
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (tab == 0) {
                        item {
                            OutlinedTextField(
                                query,
                                { query = it },
                                Modifier.fillMaxWidth(),
                                placeholder = { Text(words("Buscar apps", "Search apps")) },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                shape = RoundedCornerShape(18.dp),
                                singleLine = true,
                            )
                        }
                        item {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                (listOf("") +
                                        apps
                                            .filter { it.id != "winali" }
                                            .map { it.category }
                                            .distinct())
                                    .forEach { cat ->
                                        FilterChip(
                                            category == cat,
                                            { category = cat },
                                            label = { Text(cat.ifBlank { words("Todas", "All") }) },
                                        )
                                    }
                            }
                        }
                        if (query.isBlank() && category.isBlank())
                            item {
                                Card(
                                    colors =
                                        CardDefaults.cardColors(containerColor = Color(0xFF125DEC)),
                                    shape = RoundedCornerShape(26.dp),
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                                        Text(
                                            words("HECHAS PARA TI", "MADE FOR YOU"),
                                            color = Color(0xFFCEE1FF),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            words(
                                                "Menos ruido.\nMás posibilidades.",
                                                "Less noise.\nMore possibility.",
                                            ),
                                            color = Color.White,
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            words(
                                                "Descarga directamente de sus creadores.",
                                                "Download directly from their creators.",
                                            ),
                                            color = Color.White,
                                        )
                                    }
                                }
                            }
                    }
                    val list =
                        if (tab == 1) updates
                        else
                            apps.filter {
                                it.id != "winali" &&
                                    (category.isBlank() || it.category == category) &&
                                    (it.name.contains(query, true) ||
                                        it.summary.contains(query, true))
                            }
                    if (list.isEmpty())
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    if (tab == 1) Icons.Rounded.CheckCircle
                                    else Icons.Rounded.Search,
                                    null,
                                    Modifier.size(52.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    if (tab == 1)
                                        words(
                                            "No hay actualizaciones pendientes",
                                            "No updates available",
                                        )
                                    else words("No hay resultados", "No results"),
                                    Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    items(list, key = { it.id }) { app ->
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().clickable { detail = app }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                AppMark(app, 56)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        app.name,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        app.summary,
                                        maxLines = 2,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Action(app)
                            }
                        }
                    }
                }
        }
        androidx.activity.compose.BackHandler(detail != null) { detail = null }
    }

    @Composable
    private fun SettingsScreen(schedule: () -> Unit, clear: () -> Unit) {
        val prefs = remember { getSharedPreferences("appearance", 0) }
        var enabled by remember { mutableStateOf(prefs.getBoolean("checks", true)) }
        Column(
            Modifier.fillMaxSize().padding(top = 120.dp, start = 24.dp, end = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                words("Ajustes", "Settings"),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    words(
                        "Buscar actualizaciones cada 12 horas",
                        "Check for updates every 12 hours",
                    ),
                    Modifier.weight(1f),
                )
                Switch(
                    enabled,
                    {
                        enabled = it
                        prefs.edit().putBoolean("checks", it).apply()
                        if (it) schedule()
                        else
                            WorkManager.getInstance(this@MainActivity)
                                .cancelUniqueWork("catalog-updates")
                    },
                )
            }
            Text(words("Tema", "Theme"), fontWeight = FontWeight.Bold)
            Row {
                listOf("system", "light", "dark").forEach { mode ->
                    FilterChip(
                        prefs.getString("theme", "system") == mode,
                        {
                            prefs.edit().putString("theme", mode).apply()
                            recreate()
                        },
                        label = {
                            Text(
                                when (mode) {
                                    "system" -> words("Auto", "Auto")
                                    "light" -> words("Claro", "Light")
                                    else -> words("Oscuro", "Dark")
                                },
                            )
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            OutlinedButton(
                onClick = {
                    prefs.edit().putString("language", if (es()) "en" else "es").apply()
                    recreate()
                },
            ) {
                Text("Español / English")
            }
            OutlinedButton(onClick = clear) {
                Text(words("Eliminar APK descargados", "Remove downloaded APKs"))
            }
            Text(
                "Winali ${BuildConfig.VERSION_NAME}\n" +
                    words(
                        "Código abierto · Sin anuncios · Sin cuenta",
                        "Open source · No ads · No account",
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/WillianChavez/winali"),
                        ),
                    )
                },
            ) {
                Text("GitHub")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppMark(app: CatalogApp, size: Int) {
    Image(
        androidx.compose.ui.res.painterResource(
            when (app.id) {
                "apk" -> R.drawable.app_icon_apk
                "focus" -> R.drawable.app_icon_focus
                else -> R.drawable.app_icon_winali
            },
        ),
        null,
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)),
    )
}

@Composable
private fun RemoteScreenshot(url: String) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap =
            withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    try {
                        val bytes = connection.inputStream.use { it.boundedBytes(4_000_000) }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } finally {
                        connection.disconnect()
                    }
                }
                    .getOrNull()
            }
    }
    val image = bitmap
    if (image != null)
        Image(
            image.asImageBitmap(),
            "Screenshot",
            Modifier.width(180.dp).height(360.dp).clip(RoundedCornerShape(20.dp)),
        )
}
