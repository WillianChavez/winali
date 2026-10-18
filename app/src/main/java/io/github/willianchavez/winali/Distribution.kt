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
import androidx.core.content.IntentCompat
import androidx.work.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

fun Context.installedVersionName(pkg: String): String? =
    runCatching { packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

fun Context.downloadFile(app: CatalogApp) =
    File(filesDir, "downloads/${app.packageName}-${app.release!!.versionCode}.apk").apply {
        parentFile?.mkdirs()
    }

private fun partFile(output: File) = File(output.parentFile, "${output.name}.part")

/** Whether Android lets Winali install packages. [override] lets tests simulate the denial. */
object InstallPermission {
    @Volatile var override: Boolean? = null

    fun granted(context: Context) = override ?: context.packageManager.canRequestPackageInstalls()
}

/** True while the Winali activity is on screen; background results then become notifications. */
object AppVisibility {
    @Volatile var started = 0
    val visible: Boolean
        get() = started > 0
}

fun Context.notificationChannel(id: String, es: String, en: String, importance: Int): String {
    getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(id, words(es, en), importance))
    return id
}

fun Context.canNotify() =
    Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION")
fun Context.verifyDownload(app: CatalogApp, file: File) {
    val release = app.release ?: error("No release")
    if (file.length() != release.size) throw DistributionException(Failure.Corrupt, "size")
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    if (digest.digest().hex() != release.sha256) throw DistributionException(Failure.Corrupt, "hash")
    val flags =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
    val info =
        packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw DistributionException(Failure.InvalidPackage, "unreadable")
    if (info.packageName != app.packageName)
        throw DistributionException(Failure.InvalidPackage, "package")
    val version =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    if (version != release.versionCode) throw DistributionException(Failure.InvalidPackage, "version")
    val signatures =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    if (
        signatures?.size != 1 ||
            MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray()).hex() !=
                release.certificateSha256
    )
        throw DistributionException(Failure.CertificateMismatch)
    val installed = runCatching {
        packageManager.getPackageInfo(app.packageName, flags)
    }
        .getOrNull()
    if (installed != null) {
        val existing =
            if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners
            else installed.signatures
        if (existing?.any { certificate -> signatures.any { it == certificate } } != true)
            throw DistributionException(Failure.SignatureMismatch, "installed")
        if ((installedVersion(app.packageName) ?: 0) > version)
            throw DistributionException(Failure.Downgrade)
    }
}

private val downloadMutex = Mutex()

fun tagFor(packageName: String) = "pkg:$packageName"

fun Context.workName(packageName: String) = "download-$packageName"

class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    private var startedAt = Long.MAX_VALUE

    override suspend fun doWork(): Result {
        try {
            setProgress(workDataOf("queued" to true))
        } catch (_: IllegalStateException) {}
        return downloadMutex.withLock { withContext(Dispatchers.IO) { download() } }
    }

    private fun failure(failure: Failure, detail: String? = null) =
        Result.failure(workDataOf("failure" to failure.name, "detail" to detail))

    private suspend fun download(): Result {
        startedAt = System.currentTimeMillis()
        val app = runCatching { parseApp(JSONObject(inputData.getString("app")!!)) }
            .getOrElse {
                return failure(Failure.InvalidCatalog)
            }
        val release = app.release ?: return failure(Failure.InvalidCatalog)
        val autoInstall = inputData.getBoolean("autoInstall", true)
        val output = applicationContext.downloadFile(app)
        val temp = partFile(output)
        try {
            if (!app.compatible()) return failure(Failure.Incompatible)
            if (output.exists()) {
                // A verified file from an earlier run is reused instead of downloaded again.
                runCatching { applicationContext.verifyDownload(app, output) }
                    .onSuccess {
                        return finish(app, autoInstall, output)
                    }
                output.delete()
            }
            var offset = resumeOffset(temp.length(), release.size)
            if (offset == 0L) temp.delete()
            if (!hasSpace(output.parentFile!!.usableSpace, release.size - offset))
                return failure(Failure.InsufficientStorage)
            progress(app.name, offset, release.size)
            val connection = URL(release.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            try {
                val code = connection.responseCode
                if (code == 416) {
                    // The partial file does not match the server's file any more.
                    temp.delete()
                    return retryOrFail(Failure.Network)
                }
                if (code != 200 && code != 206) return failure(Failure.Server, "HTTP $code")
                if (
                    connection.url.protocol != "https" &&
                        !loopbackAllowed(BuildConfig.DEBUG, connection.url.protocol, connection.url.host)
                )
                    return failure(Failure.Server, "HTTPS")
                if (code == 200) offset = 0
                var bytes = offset
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(temp, offset > 0).use { out ->
                        val buffer = ByteArray(65536)
                        var last = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > release.size)
                                throw DistributionException(Failure.Corrupt, "size")
                            out.write(buffer, 0, count)
                            val now = System.currentTimeMillis()
                            if (now - last > 500) {
                                progress(app.name, bytes, release.size)
                                last = now
                            }
                        }
                        out.fd.sync()
                    }
                }
                if (bytes < release.size) throw IOException("Truncated download")
            } finally {
                connection.disconnect()
            }
            applicationContext.verifyDownload(app, temp)
            check(temp.renameTo(output)) { "Cannot save APK" }
            return finish(app, autoInstall, output)
        } catch (e: CancellationException) {
            // The partial file is kept so a later run (or retry) can resume; the UI deletes it on user cancel.
            throw e
        } catch (e: DistributionException) {
            if (e.failure.discardsDownload()) temp.delete()
            return failure(e.failure, e.detail)
        } catch (e: IOException) {
            return retryOrFail(Failure.Network)
        } catch (e: Exception) {
            temp.delete()
            return failure(Failure.Unknown, e.message)
        }
    }

    private fun retryOrFail(failure: Failure): Result =
        if (shouldRetry(failure, runAttemptCount)) Result.retry() else failure(failure)

    private suspend fun finish(app: CatalogApp, autoInstall: Boolean, output: File): Result {
        if (autoInstall) {
            val pkg = app.packageName
            if (!InstallPermission.granted(applicationContext)) {
                InstallLog.finish(applicationContext, pkg, Failure.PermissionDenied)
                notifyInstallFailure(applicationContext, app.name, Failure.PermissionDenied, null)
            } else {
                try {
                    applicationContext.install(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failure = (e as? DistributionException)?.failure ?: Failure.Unknown
                    InstallLog.finish(applicationContext, pkg, failure, e.message)
                    if (!AppVisibility.visible) notifyInstallFailure(applicationContext, app.name, failure, e.message)
                }
            }
        }
        return Result.success(workDataOf("path" to output.absolutePath))
    }

    private suspend fun progress(name: String, bytes: Long, total: Long) {
        val percent = (bytes * 100 / total).toInt()
        // setProgress fails when the work was finished concurrently (cancel); nothing to report then.
        try {
            setProgress(
                workDataOf("queued" to false, "percent" to percent, "bytes" to bytes, "total" to total),
            )
        } catch (_: IllegalStateException) {}
        // Promoting a download that ends within moments only invites a service start/stop race, so
        // only transfers that are still running after a second get the foreground notification.
        if (System.currentTimeMillis() - startedAt > 1_000)
            try {
                setForeground(foreground(name, percent))
            } catch (_: IllegalStateException) {}
    }

    private fun foreground(name: String, percent: Int): ForegroundInfo {
        val channel =
            applicationContext.notificationChannel(
                "downloads",
                "Descargas",
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            )
        val notification =
            NotificationCompat.Builder(applicationContext, channel)
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

/** The request is stripped of display-only fields so it fits WorkManager's input limit. */
fun downloadRequest(app: CatalogApp, autoInstall: Boolean = true): OneTimeWorkRequest =
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
                "autoInstall" to autoInstall,
            ),
        )
        .addTag("download")
        .addTag(tagFor(app.packageName))
        .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()

fun Context.enqueueDownload(app: CatalogApp, autoInstall: Boolean = true): Operation {
    val manager = WorkManager.getInstance(this)
    // Drop finished entries (an earlier failure) so only the new request represents this app.
    manager.pruneWork()
    // Cancelling one app must not cancel the other requests in the queue.
    return manager.enqueueUniqueWork(
        workName(app.packageName),
        ExistingWorkPolicy.KEEP,
        downloadRequest(app, autoInstall),
    )
}

/** User cancel: stop the work and discard its partial file. */
suspend fun Context.cancelDownload(app: CatalogApp) {
    val manager = WorkManager.getInstance(this)
    manager.cancelUniqueWork(workName(app.packageName)).await()
    withContext(Dispatchers.IO) { partFile(downloadFile(app)).delete() }
}

fun WorkInfo.downloadStatus(): DownloadStatus =
    when (state) {
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED -> DownloadStatus.Queued
        WorkInfo.State.RUNNING ->
            if (progress.getBoolean("queued", true)) DownloadStatus.Queued
            else
                DownloadStatus.Running(
                    progress.getInt("percent", 0),
                    progress.getLong("bytes", 0),
                    progress.getLong("total", 0),
                )
        WorkInfo.State.FAILED ->
            DownloadStatus.Failed(
                Failure.of(outputData.getString("failure")),
                outputData.getString("detail"),
            )
        else -> DownloadStatus.Idle
    }

/** One status per package: active work first, otherwise a retained failure. */
fun downloadStatuses(works: List<WorkInfo>): Map<String, DownloadStatus> {
    val result = mutableMapOf<String, DownloadStatus>()
    for (info in works) {
        val pkg = info.tags.firstOrNull { it.startsWith("pkg:") }?.removePrefix("pkg:") ?: continue
        val status = info.downloadStatus()
        val current = result[pkg]
        if (status is DownloadStatus.Idle) continue
        if (current == null || current is DownloadStatus.Failed) result[pkg] = status
    }
    return result
}

/** Persistent record of installation sessions so state survives process death. */
object InstallLog {
    private fun prefs(context: Context) = context.getSharedPreferences("install", 0)

    fun begin(context: Context, pkg: String, sessionId: Int) {
        prefs(context)
            .edit()
            .putInt("session:$pkg", sessionId)
            .putBoolean("awaiting:$pkg", false)
            .remove("result:$pkg")
            .apply()
    }

    fun awaiting(context: Context, pkg: String) {
        prefs(context).edit().putBoolean("awaiting:$pkg", true).apply()
    }

    fun finish(context: Context, pkg: String, failure: Failure?, detail: String? = null) {
        prefs(context)
            .edit()
            .remove("session:$pkg")
            .remove("awaiting:$pkg")
            .putString(
                "result:$pkg",
                if (failure == null) "ok" else "fail|${failure.name}|${detail ?: ""}",
            )
            .apply()
    }

    fun phase(context: Context, pkg: String): InstallPhase {
        val prefs = prefs(context)
        val session = prefs.getInt("session:$pkg", -1)
        if (session < 0) return InstallPhase.None
        val alive = runCatching { context.packageManager.packageInstaller.getSessionInfo(session) }
            .getOrNull() != null
        if (!alive) {
            prefs.edit().remove("session:$pkg").remove("awaiting:$pkg").apply()
            return InstallPhase.None
        }
        return if (prefs.getBoolean("awaiting:$pkg", false)) InstallPhase.AwaitingConfirmation
        else InstallPhase.Installing
    }

    /** Null while undecided; the failure of the last attempt otherwise. */
    fun lastFailure(context: Context, pkg: String): Pair<Failure, String?>? {
        val value = prefs(context).getString("result:$pkg", null) ?: return null
        val parts = value.split("|", limit = 3)
        return if (parts[0] == "fail") Failure.of(parts.getOrNull(1)) to parts.getOrNull(2)?.ifBlank { null }
        else null
    }

    fun lastSucceeded(context: Context, pkg: String) = prefs(context).getString("result:$pkg", null) == "ok"

    fun clearResult(context: Context, pkg: String) {
        prefs(context).edit().remove("result:$pkg").apply()
    }

    /** Packages with any recorded installation state, for observers. */
    fun registerListener(context: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(context: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
}

fun Context.abandonOwnSessions(pkg: String) {
    val installer = packageManager.packageInstaller
    for (session in installer.mySessions)
        if (session.appPackageName == pkg) runCatching { installer.abandonSession(session.sessionId) }
}

suspend fun Context.install(app: CatalogApp) =
    withContext(Dispatchers.IO) {
        if (!InstallPermission.granted(this@install))
            throw DistributionException(Failure.PermissionDenied)
        val file = downloadFile(app)
        try {
            if (!file.exists()) throw DistributionException(Failure.Corrupt, "missing")
            verifyDownload(app, file)
        } catch (e: DistributionException) {
            // A damaged or foreign file is discarded; nothing installed is touched.
            if (e.failure.discardsDownload()) file.delete()
            throw e
        }
        abandonOwnSessions(app.packageName)
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
                        .putExtra("name", app.name)
                val pending =
                    PendingIntent.getBroadcast(
                        this@install,
                        id,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
                    )
                InstallLog.begin(this@install, app.packageName, id)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            val failure =
                if (e is IOException && e.message?.contains("ENOSPC") == true)
                    Failure.InsufficientStorage
                else Failure.Unknown
            InstallLog.finish(this@install, app.packageName, failure, e.message)
            throw DistributionException(failure, e.message)
        }
    }

fun notifyInstall(context: Context, id: Int, title: String, text: String, open: PendingIntent?) {
    if (!context.canNotify()) return
    val channel =
        context.notificationChannel(
            "installs",
            "Instalaciones",
            "Installations",
            NotificationManager.IMPORTANCE_HIGH,
        )
    val builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
    if (open != null) builder.setContentIntent(open)
    context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
}

fun notifyInstallFailure(context: Context, name: String, failure: Failure, detail: String?) {
    val open =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    notifyInstall(
        context,
        5000 + name.hashCode().and(0xfff),
        context.words("No se instaló $name", "$name was not installed"),
        failure.message(context.es(), detail),
        open,
    )
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("package") ?: return
        val name = intent.getStringExtra("name") ?: pkg
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            InstallLog.awaiting(context, pkg)
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                ?: return
            if (AppVisibility.visible) {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                notifyInstall(
                    context,
                    5000 + pkg.hashCode().and(0xfff),
                    context.words("Confirma la instalación de $name", "Confirm the installation of $name"),
                    context.words("Toca para continuar.", "Tap to continue."),
                    PendingIntent.getActivity(
                        context,
                        pkg.hashCode(),
                        confirm,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            return
        }
        val failure = installFailure(status, message)
        if (failure == null) {
            // Remove what the installed version now covers before announcing success.
            val installed = context.installedVersion(pkg)
            File(context.filesDir, "downloads")
                .listFiles()
                ?.filter { it.name.startsWith("$pkg-") && it.extension == "apk" }
                ?.filter { file ->
                    val version = file.name.removePrefix("$pkg-").removeSuffix(".apk").toLongOrNull()
                    installed == null || version == null || version <= installed
                }
                ?.forEach { it.delete() }
            context
                .getSystemService(NotificationManager::class.java)
                .cancel(5000 + pkg.hashCode().and(0xfff))
        }
        InstallLog.finish(context, pkg, failure, if (failure == Failure.Unknown) message else null)
        if (failure == null) return
        if (failure != Failure.Cancelled && !AppVisibility.visible) {
            notifyInstallFailure(context, name, failure, message)
        }
    }
}

/** Removes downloads the installed version already covers and abandoned partial files. */
fun Context.cleanDownloads(maxPartAgeMillis: Long = 7 * 24 * 3_600_000L) {
    val directory = File(filesDir, "downloads")
    val now = System.currentTimeMillis()
    directory.listFiles()?.forEach { file ->
        when {
            file.extension == "apk" && staleDownload(file.name) { installedVersion(it) } -> file.delete()
            file.extension == "part" && now - file.lastModified() > maxPartAgeMillis -> file.delete()
        }
    }
}
