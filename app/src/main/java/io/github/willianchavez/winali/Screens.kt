package io.github.willianchavez.winali

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lambdas for the settings tab, so the screen holds no Android services. */
class SettingsModel(
    val checksEnabled: Boolean,
    val theme: String,
    val setChecks: (Boolean) -> Unit,
    val setTheme: (String) -> Unit,
    val toggleLanguage: () -> Unit,
    val clearDownloads: () -> Unit,
    val openUrl: (String) -> Unit,
)

@Composable private fun w(es: String, en: String) = LocalContext.current.words(es, en)

@Composable private fun largeText() = LocalDensity.current.fontScale > 1.3f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    state: StoreState,
    actions: StoreActions,
    settings: SettingsModel,
    snackbar: SnackbarHostState,
    requestedTab: Int? = null,
    onTabShown: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            detailId = null
            onTabShown()
        }
    }
    val detail = detailId?.let { id -> state.apps.firstOrNull { it.id == id } }
    val updates = state.updates
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (detail != null) "" else "Winali", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (detail != null)
                        IconButton(onClick = { detailId = null }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, w("Volver", "Back"))
                        }
                },
                actions = {
                    if (tab != 2 || detail != null)
                        IconButton(onClick = actions::refresh, enabled = !state.refreshing) {
                            Icon(Icons.Rounded.Refresh, w("Buscar actualizaciones", "Check updates"))
                        }
                },
            )
        },
        bottomBar = {
            if (detail == null)
                NavigationBar {
                    val labels =
                        listOf(
                            w("Explorar", "Explore"),
                            w("Actualizaciones", "Updates"),
                            w("Ajustes", "Settings"),
                        )
                    listOf(WinaliIcons.Explore, WinaliIcons.SystemUpdate, Icons.Rounded.Settings)
                        .forEachIndexed { i, icon ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                modifier = Modifier.testTag("tab-$i"),
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
                                label = { Text(labels[i], maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                detail != null -> DetailScreen(detail, state, actions, settings.openUrl)
                tab == 2 -> SettingsScreen(settings, state)
                tab == 1 -> UpdatesScreen(state, actions) { detailId = it.id }
                else ->
                    ExploreScreen(state, actions, query, { query = it }, category, { category = it }) {
                        detailId = it.id
                    }
            }
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    androidx.activity.compose.BackHandler(detail != null) { detailId = null }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun ExploreScreen(
    state: StoreState,
    actions: StoreActions,
    query: String,
    onQuery: (String) -> Unit,
    category: String,
    onCategory: (String) -> Unit,
    open: (CatalogApp) -> Unit,
) {
    val list =
        state.apps.filter {
            it.id != "winali" &&
                (category.isBlank() || it.category == category) &&
                (it.name.contains(query, true) || it.summary.contains(query, true))
        }
    LazyColumn(
        Modifier.fillMaxSize().testTag("explore"),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Title(w("Explorar", "Explore")) }
        item {
            OutlinedTextField(
                query,
                onQuery,
                Modifier.fillMaxWidth().testTag("search"),
                placeholder = { Text(w("Buscar apps", "Search apps")) },
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
                (listOf("") + state.apps.filter { it.id != "winali" }.map { it.category }.distinct())
                    .forEach { cat ->
                        FilterChip(
                            category == cat,
                            { onCategory(cat) },
                            label = { Text(cat.ifBlank { w("Todas", "All") }) },
                        )
                    }
            }
        }
        if (list.isEmpty())
            item {
                EmptyState(Icons.Rounded.Search, w("No hay resultados", "No results"))
            }
        items(list, key = { it.id }) { app -> AppCard(app, state, actions, open) }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, detail: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
        if (detail != null)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun UpdatesScreen(state: StoreState, actions: StoreActions, open: (CatalogApp) -> Unit) {
    val updates = state.updates
    // Updates show their own progress and failure; the queue lists downloads that are not updates.
    val queue = state.queue.filter { app -> updates.none { it.id == app.id } }
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().testTag("updates"),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Title(w("Actualizaciones", "Updates")) }
        if (queue.isNotEmpty()) {
            item { Section(w("Descargas", "Downloads")) }
            items(queue, key = { "queue-${it.id}" }) { app -> QueueRow(app, state, actions) }
        }
        if (updates.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (updates.size == 1) w("1 actualización disponible", "1 update available")
                        else w("${updates.size} actualizaciones disponibles", "${updates.size} updates available"),
                        Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                    )
                    Button(onClick = actions::updateAll, modifier = Modifier.testTag("update-all")) {
                        Text(w("Actualizar todo", "Update all"))
                    }
                }
            }
            items(updates, key = { it.id }) { app -> AppCard(app, state, actions, open, updateLine = true) }
        } else if (queue.isEmpty()) {
            item {
                EmptyState(
                    Icons.Rounded.CheckCircle,
                    w("Todo está al día", "Everything is up to date"),
                    lastCheckText(state.lastCheck, context),
                )
            }
        }
    }
}

private fun lastCheckText(lastCheck: Long, context: android.content.Context): String =
    if (lastCheck <= 0) context.words("Aún no se ha comprobado", "Not checked yet")
    else
        context.words("Última comprobación: ", "Last checked: ") +
            relativeTime(System.currentTimeMillis() - lastCheck, context.es())

@Composable
private fun QueueRow(app: CatalogApp, state: StoreState, actions: StoreActions) {
    val status = state.downloads[app.packageName] ?: DownloadStatus.Idle
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("queue-${app.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppMark(app, 40)
                Text(app.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                when (status) {
                    is DownloadStatus.Failed ->
                        FilledTonalButton(onClick = { actions.retry(app) }, modifier = Modifier.testTag("retry-${app.id}")) {
                            Text(w("Reintentar", "Retry"))
                        }
                    else ->
                        OutlinedButton(onClick = { actions.cancel(app) }, modifier = Modifier.testTag("cancel-${app.id}")) {
                            Text(w("Cancelar", "Cancel"))
                        }
                }
            }
            if (status is DownloadStatus.Failed)
                Text(
                    status.failure.message(state.spanish, status.detail),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            else DownloadProgress(app, status, state.spanish)
        }
    }
}

@Composable
private fun DownloadProgress(app: CatalogApp, status: DownloadStatus, spanish: Boolean) {
    val running = status as? DownloadStatus.Running
    Column(Modifier.testTag("progress-${app.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (running != null && running.percent > 0)
            LinearProgressIndicator(progress = { running.percent / 100f }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            when {
                running == null -> if (spanish) "En cola" else "Queued"
                running.total > 0 ->
                    "${running.percent}% · ${formatSize(running.bytes, spanish)} / ${formatSize(running.total, spanish)}"
                else -> "${running.percent}%"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AppCard(
    app: CatalogApp,
    state: StoreState,
    actions: StoreActions,
    open: (CatalogApp) -> Unit,
    updateLine: Boolean = false,
) {
    val action = state.action(app)
    val busy = action is ActionState.Downloading || action == ActionState.Queued
    val subtitle =
        if (updateLine)
            "${state.installedNames[app.packageName] ?: state.installed[app.packageName]} → ${app.release?.versionName}" +
                (app.release?.let { " · " + formatSize(it.size, state.spanish) } ?: "")
        else app.summary
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.testTag("card-${app.id}"),
    ) {
        val large = largeText()
        Column(Modifier.fillMaxWidth().clickable { open(app) }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AppMark(app, 56)
                Column(Modifier.weight(1f)) {
                    Text(app.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!large) ActionButton(app, action, actions)
            }
            if (large) ActionButton(app, action, actions)
            if (updateLine && app.release?.changelog?.isNotBlank() == true && !busy)
                Text(
                    app.release.changelog,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            if (busy) DownloadProgress(app, state.downloads[app.packageName] ?: DownloadStatus.Idle, state.spanish)
            state.problem(app)?.let { ProblemText(app, it) }
        }
    }
}

@Composable
private fun ProblemText(app: CatalogApp, text: String) {
    Text(
        text,
        Modifier.testTag("problem-${app.id}"),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun ActionButton(app: CatalogApp, action: ActionState, actions: StoreActions, modifier: Modifier = Modifier) {
    val spanish = LocalContext.current.es()
    val busy = action is ActionState.Downloading || action == ActionState.Queued
    val enabled = action != ActionState.ComingSoon && action != ActionState.Incompatible && action != ActionState.Installing
    val onClick = { if (busy) actions.cancel(app) else actions.primary(app) }
    val tag = Modifier.testTag("action-${app.id}")
    if (busy) OutlinedButton(onClick, modifier.then(tag), shape = RoundedCornerShape(30.dp)) { Text(action.label(spanish)) }
    else
        FilledTonalButton(onClick, modifier.then(tag), enabled = enabled, shape = RoundedCornerShape(30.dp)) {
            Text(action.label(spanish))
        }
}

@Composable
fun DetailScreen(app: CatalogApp, state: StoreState, actions: StoreActions, openUrl: (String) -> Unit) {
    val action = state.action(app)
    val release = app.release
    val spanish = state.spanish
    val installedName = state.installedNames[app.packageName]
    LazyColumn(
        Modifier.fillMaxSize().testTag("detail-${app.id}"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                AppMark(app, 82)
                Column(Modifier.weight(1f)) {
                    Text(app.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(app.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (action is ActionState.Downloading || action == ActionState.Queued) {
                    DownloadProgress(app, state.downloads[app.packageName] ?: DownloadStatus.Idle, spanish)
                    ActionButton(app, action, actions, Modifier.fillMaxWidth())
                } else {
                    ActionButton(app, action, actions, Modifier.fillMaxWidth())
                }
                if (installedName != null)
                    Text(
                        w("Versión instalada: $installedName", "Installed version: $installedName"),
                        Modifier.testTag("installed-version"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                state.problem(app)?.let { ProblemText(app, it) }
            }
        }
        item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Metric(w("Versión", "Version"), release?.versionName ?: "—")
                Metric(w("Tamaño", "Size"), release?.let { formatSize(it.size, spanish) } ?: "—")
                Metric("Android", release?.let { "${androidName(it.minSdk)}+" } ?: "8.0+")
            }
        }
        if (app.screenshots.isNotEmpty())
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    app.screenshots.forEachIndexed { i, url -> RemoteScreenshot(url, i + 1) }
                }
            }
        item {
            Section(w("Acerca de esta app", "About this app"))
            Text(app.description, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
        release?.let {
            item {
                Section(w("Novedades", "What's new"))
                Text(
                    it.versionName,
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(it.changelog, Modifier.padding(top = 6.dp))
            }
        }
        item {
            Section(w("Permisos", "Permissions"))
            if (app.permissions.isEmpty())
                Text(w("Sin permisos especiales", "No special permissions"), Modifier.padding(top = 10.dp))
            else
                Column(Modifier.padding(top = 10.dp).testTag("permissions"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    app.permissions.forEach { Text("•  $it") }
                }
        }
        release?.let {
            item {
                Section(w("Compatibilidad", "Compatibility"))
                Column(Modifier.padding(top = 10.dp).testTag("compatibility"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(w("Requiere Android ${androidName(it.minSdk)} o superior", "Requires Android ${androidName(it.minSdk)} or later"))
                    Text(
                        w("Arquitecturas: ", "Architectures: ") +
                            if (it.architectures.isEmpty() || "universal" in it.architectures)
                                w("todas", "all")
                            else it.architectures.joinToString(", "),
                    )
                    Text(
                        if (app.compatible()) w("Compatible con este dispositivo", "Compatible with this device")
                        else w("No es compatible con este dispositivo", "Not compatible with this device"),
                        fontWeight = FontWeight.Bold,
                        color = if (app.compatible()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = { openUrl(app.repository) }, modifier = Modifier.testTag("repository")) {
                Icon(WinaliIcons.Code, null)
                Spacer(Modifier.width(8.dp))
                Text(w("Ver código en GitHub", "View source on GitHub"))
            }
        }
    }
}

@Composable
private fun SettingsScreen(model: SettingsModel, state: StoreState) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().testTag("settings"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Title(w("Ajustes", "Settings")) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w("Buscar actualizaciones cada 12 horas", "Check for updates every 12 hours"))
                    Text(
                        lastCheckText(state.lastCheck, context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(model.checksEnabled, model.setChecks, Modifier.testTag("checks-switch"))
            }
        }
        item {
            Text(w("Tema", "Theme"), fontWeight = FontWeight.Bold)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system", "light", "dark").forEach { mode ->
                    FilterChip(
                        model.theme == mode,
                        { model.setTheme(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    "system" -> w("Auto", "Auto")
                                    "light" -> w("Claro", "Light")
                                    else -> w("Oscuro", "Dark")
                                },
                            )
                        },
                    )
                }
            }
        }
        item { OutlinedButton(onClick = model.toggleLanguage) { Text("Español / English") } }
        item {
            OutlinedButton(onClick = model.clearDownloads) {
                Text(w("Eliminar APK descargados", "Remove downloaded APKs"))
            }
        }
        item {
            Text("Winali ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { model.openUrl("https://github.com/WillianChavez/winali") }) { Text("GitHub") }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AppMark(app: CatalogApp, size: Int) {
    val shape = Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp))
    val bundled =
        when (app.id) {
            "apk" -> R.drawable.app_icon_apk
            "focus" -> R.drawable.app_icon_focus
            "winali" -> R.drawable.app_icon_winali
            else -> null
        }
    // Each app's own icon from the catalog; bundled copies keep the first apps available offline.
    var bitmap by remember(app.icon) { mutableStateOf(app.icon?.let { IconCache.get(it) }) }
    LaunchedEffect(app.icon) {
        val url = app.icon ?: return@LaunchedEffect
        if (bitmap != null) return@LaunchedEffect
        bitmap =
            withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    try {
                        val bytes = connection.inputStream.use { it.boundedBytes(1_000_000) }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { IconCache.put(url, it) }
                    } finally {
                        connection.disconnect()
                    }
                }
                    .getOrNull()
            }
    }
    val loaded = bitmap
    when {
        loaded != null -> Image(loaded.asImageBitmap(), null, shape.testTag("icon_${app.id}"), contentScale = ContentScale.Crop)
        bundled != null -> Image(painterResource(bundled), null, shape)
        else ->
            Box(shape.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Text(
                    app.name.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size * 0.42f).sp,
                )
            }
    }
}

private object IconCache : android.util.LruCache<String, Bitmap>(64)

private object ScreenshotCache :
    android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(4_000_000)) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@Composable
private fun RemoteScreenshot(url: String, number: Int) {
    var bitmap by remember(url) { mutableStateOf(ScreenshotCache.get(url)) }
    var failed by remember(url) { mutableStateOf(false) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    LaunchedEffect(url, attempt) {
        if (bitmap != null) return@LaunchedEffect
        failed = false
        bitmap =
            withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    try {
                        val bytes = connection.inputStream.use { it.boundedBytes(4_000_000) }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { ScreenshotCache.put(url, it) }
                    } finally {
                        connection.disconnect()
                    }
                }
                    .getOrNull()
            }
        failed = bitmap == null
    }
    val label = w("Captura $number", "Screenshot $number")
    val shape = RoundedCornerShape(20.dp)
    val image = bitmap
    if (image != null)
        Image(
            image.asImageBitmap(),
            label,
            Modifier.width(180.dp).height(360.dp).clip(shape),
        )
    else
        Box(
            Modifier.width(180.dp)
                .height(360.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = label }
                .clickable(enabled = failed) { attempt++ },
            contentAlignment = Alignment.Center,
        ) {
            if (failed)
                Text(
                    w("No se pudo cargar. Toca para reintentar.", "Could not load. Tap to retry."),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            else CircularProgressIndicator()
        }
}
