package io.github.willianchavez.winali

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

fun Context.distributionPrefs() = getSharedPreferences("distribution", 0)

@Suppress("DEPRECATION")
fun Context.installedVersion(pkg: String): Long? = runCatching {
    packageManager.getPackageInfo(pkg, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }
}
    .getOrNull()

fun Context.downloadFile(app: CatalogApp) =
    File(filesDir, "downloads/${app.packageName}-${app.release!!.versionCode}.apk").apply {
        parentFile?.mkdirs()
    }

@Suppress("DEPRECATION")
fun Context.verifyDownload(app: CatalogApp, file: File) {
    val release = app.release ?: error("No release")
    require(file.length() == release.size) { words("Tamaño incorrecto", "Incorrect file size") }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    require(digest.digest().hex() == release.sha256) {
        words("La descarga está dañada", "Download integrity check failed")
    }
    val flags =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
    val info =
        packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error(words("APK no válido", "Invalid APK"))
    require(info.packageName == app.packageName) { "Unexpected package" }
    val version =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    require(version == release.versionCode) { "Unexpected version" }
    val signatures =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    require(
        signatures?.size == 1 &&
            signatures.single().toByteArray().let {
                MessageDigest.getInstance("SHA-256").digest(it).hex()
            } == release.certificateSha256,
    ) {
        words("La firma no coincide", "Signing certificate mismatch")
    }
    val installed = runCatching {
        packageManager.getPackageInfo(app.packageName, flags)
    }
        .getOrNull()
    if (installed != null) {
        val existing =
            if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners
            else installed.signatures
        require(existing?.any { certificate -> signatures!!.any { it == certificate } } == true) {
            words(
                "La app instalada usa otra firma",
                "The installed app uses a different signing key",
            )
        }
        require((installedVersion(app.packageName) ?: 0) <= version) {
            "Downgrades are not supported"
        }
    }
}

private val downloadMutex = Mutex()

class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        setProgress(workDataOf("queued" to true))
        return downloadMutex.withLock {
            withContext(Dispatchers.IO) {
                val app = runCatching {
                    parseApp(JSONObject(inputData.getString("app")!!))
                }
                    .getOrElse {
                        return@withContext Result.failure(
                            workDataOf("error" to "Invalid download request"),
                        )
                    }
                val release = app.release ?: return@withContext Result.failure()
                val output = applicationContext.downloadFile(app)
                val temp = File(output.parentFile, "${output.name}.part")
                try {
                    check(app.compatible()) { "Incompatible device" }
                    setProgress(workDataOf("queued" to false, "percent" to 0))
                    setForeground(foreground(app.name, 0))
                    val connection = URL(release.url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 30_000
                    try {
                        check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                        require(connection.url.protocol == "https")
                        require(output.parentFile!!.usableSpace > release.size + 10_000_000) {
                            applicationContext.words("Espacio insuficiente", "Insufficient storage")
                        }
                        connection.inputStream.use { input ->
                            temp.outputStream().use { out ->
                                val buffer = ByteArray(65536)
                                var bytes = 0L
                                var last = 0L
                                while (true) {
                                    if (isStopped) throw kotlinx.coroutines.CancellationException()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    bytes += count
                                    require(bytes <= release.size) {
                                        "Download exceeds declared size"
                                    }
                                    out.write(buffer, 0, count)
                                    val now = System.currentTimeMillis()
                                    if (now - last > 700) {
                                        val percent = (bytes * 100 / release.size).toInt()
                                        setProgress(workDataOf("percent" to percent))
                                        setForeground(foreground(app.name, percent))
                                        last = now
                                    }
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                    applicationContext.verifyDownload(app, temp)
                    check(temp.renameTo(output)) { "Cannot save APK" }
                    Result.success(workDataOf("path" to output.absolutePath))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    temp.delete()
                    throw e
                } catch (e: Exception) {
                    temp.delete()
                    Result.failure(workDataOf("error" to (e.message ?: "Download failed")))
                }
            }
        }
    }

    private fun foreground(name: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "downloads",
                applicationContext.words("Descargas", "Downloads"),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification =
            NotificationCompat.Builder(applicationContext, "downloads")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(name)
                .setContentText(applicationContext.words("Descargando", "Downloading"))
                .setProgress(100, percent, false)
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_delete,
                    applicationContext.words("Cancelar", "Cancel"),
                    WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
                )
                .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(3000, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(3000, notification)
    }
}

fun Context.enqueueDownload(app: CatalogApp): Operation {
    val manager = WorkManager.getInstance(this)
    val task =
        OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    "app" to
                        app.copy(
                                summary = "",
                                description = "",
                                screenshots = emptyList(),
                                permissions = emptyList(),
                                english = null,
                                release = app.release?.copy(changelog = ""),
                            )
                            .json()
                            .toString(),
                ),
            )
            .addTag("download")
            .addTag(app.packageName)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
    // Cancelling one app must not cancel the other requests in the queue.
    return manager.enqueueUniqueWork("download-${app.packageName}", ExistingWorkPolicy.KEEP, task)
}

suspend fun Context.install(app: CatalogApp) =
    withContext(Dispatchers.IO) {
        val file = downloadFile(app)
        verifyDownload(app, file)
        val installer = packageManager.packageInstaller
        val parameters =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(file.length())
                // Declare an app-store origin and a user-initiated install; this is the honest signal Play Protect uses.
                if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                if (Build.VERSION.SDK_INT >= 34) setInstallerPackageName(packageName)
                if (Build.VERSION.SDK_INT >= 31)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        val id = installer.createSession(parameters)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent =
                    Intent(this@install, InstallReceiver::class.java)
                        .putExtra("package", app.packageName)
                val pending =
                    PendingIntent.getBroadcast(
                        this@install,
                        id,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
                    )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }

class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null)
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val error =
                if (status == PackageInstaller.STATUS_SUCCESS) ""
                else
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: "Installation failed"
            context
                .distributionPrefs()
                .edit()
                .putString(
                    "installResult",
                    error.ifBlank {
                        context.words("Instalación completada", "Installation completed")
                    },
                )
                .apply()
            if (status == PackageInstaller.STATUS_SUCCESS) {
                val pkg = intent.getStringExtra("package")
                File(context.filesDir, "downloads")
                    .listFiles()
                    ?.filter { it.name.startsWith("$pkg-") }
                    ?.forEach { it.delete() }
            }
        }
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                val apps = CatalogStore(applicationContext).refresh(true)
                val updates = apps.filter { app ->
                    val installed = applicationContext.installedVersion(app.packageName)
                    installed != null &&
                        app.compatible() &&
                        (app.release?.versionCode ?: 0) > installed
                }
                if (updates.isNotEmpty()) {
                    val manager =
                        applicationContext.getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(
                        NotificationChannel(
                            "updates",
                            applicationContext.words("Actualizaciones", "Updates"),
                            NotificationManager.IMPORTANCE_DEFAULT,
                        ),
                    )
                    if (
                        Build.VERSION.SDK_INT < 33 ||
                            applicationContext.checkSelfPermission(
                                android.Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val open =
                            PendingIntent.getActivity(
                                applicationContext,
                                0,
                                Intent(applicationContext, MainActivity::class.java)
                                    .putExtra("updates", true),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            )
                        manager.notify(
                            4000,
                            NotificationCompat.Builder(applicationContext, "updates")
                                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                                .setContentTitle(
                                    applicationContext.words(
                                        "Actualizaciones disponibles",
                                        "Updates available",
                                    ),
                                )
                                .setContentText(updates.joinToString { it.name })
                                .setContentIntent(open)
                                .setAutoCancel(true)
                                .build(),
                        )
                    }
                }
                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
}
