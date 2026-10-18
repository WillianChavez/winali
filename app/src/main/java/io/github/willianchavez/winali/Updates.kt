package io.github.willianchavez.winali

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val UPDATE_WORK = "catalog-updates"
const val UPDATE_NOTIFICATION = 4000

/** Installed versions of every catalog package that is on the device. */
fun Context.installedVersions(apps: List<CatalogApp>): Map<String, Long> =
    buildMap { apps.forEach { app -> installedVersion(app.packageName)?.let { put(app.packageName, it) } } }

fun updateCheckRequest(): PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
        .build()

fun Context.scheduleUpdateChecks() {
    WorkManager.getInstance(this)
        .enqueueUniquePeriodicWork(UPDATE_WORK, ExistingPeriodicWorkPolicy.KEEP, updateCheckRequest())
}

fun Context.cancelUpdateChecks() {
    WorkManager.getInstance(this).cancelUniqueWork(UPDATE_WORK)
}

/** Refreshes the catalog and notifies once per distinct set of available updates. */
class UpdateChecker(
    private val context: Context,
    private val store: CatalogStore = CatalogStore(context),
) {
    fun run(): List<CatalogApp> {
        val apps = store.refresh(true)
        val updates = pendingUpdates(apps, context.installedVersions(apps))
        val prefs = context.distributionPrefs()
        if (updates.isEmpty()) prefs.edit().remove("notifiedUpdates").apply()
        else if (shouldNotify(updates, prefs.getString("notifiedUpdates", null)) && context.canNotify()) {
            notify(updates.map { it.localized(context) })
            prefs.edit().putString("notifiedUpdates", updateKey(updates)).apply()
        }
        return updates
    }

    private fun notify(updates: List<CatalogApp>) {
        val channel =
            context.notificationChannel(
                "updates",
                "Actualizaciones",
                "Updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).putExtra("updates", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val lines =
            updates.map { "${it.name} ${it.release?.versionName.orEmpty()}" }
        context
            .getSystemService(NotificationManager::class.java)
            .notify(
                UPDATE_NOTIFICATION,
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(
                        context.words(
                            if (updates.size == 1) "1 actualización disponible"
                            else "${updates.size} actualizaciones disponibles",
                            if (updates.size == 1) "1 update available"
                            else "${updates.size} updates available",
                        ),
                    )
                    .setContentText(lines.joinToString(" · "))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                UpdateChecker(applicationContext).run()
                Result.success()
            } catch (e: DistributionException) {
                // A malformed catalog will not fix itself on the next retry; wait for the next period.
                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
}
