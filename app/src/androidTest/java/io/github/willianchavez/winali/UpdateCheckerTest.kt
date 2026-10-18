package io.github.willianchavez.winali

import android.app.NotificationManager
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Update detection, notification and scheduling against a local catalog server. */
class UpdateCheckerTest {
    private lateinit var server: LocalServer
    private lateinit var store: CatalogStore
    private lateinit var restoreLanguage: () -> Unit
    private val installed = context.installedVersion(context.packageName)!!
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        restoreLanguage = setLanguage("en")
        server = LocalServer()
        store = CatalogStore(context, server.url("/index.json"), "update-test")
        context.distributionPrefs().edit().remove("notifiedUpdates").commit()
        notifications.cancel(UPDATE_NOTIFICATION)
    }

    @After
    fun tearDown() {
        server.close()
        restoreLanguage()
        File(context.filesDir, "update-test.json").delete()
        context.getSharedPreferences("update-test", 0).edit().clear().commit()
        context.distributionPrefs().edit().remove("notifiedUpdates").commit()
        notifications.cancel(UPDATE_NOTIFICATION)
    }

    /** The bundled catalog with Winali published at [code], at a revision newer than the bundled one. */
    private fun catalog(code: Long, revisionOffset: Long = 10, name: String = "9.9.$code"): String {
        val json = JSONObject(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
        json.put("revision", json.getLong("revision") + revisionOffset)
        val apps = json.getJSONArray("apps")
        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            if (app.getString("packageName") == context.packageName)
                app.getJSONObject("release").put("versionCode", code).put("versionName", name)
        }
        return json.toString()
    }

    private fun serve(text: String) {
        server.routes["/index.json"] = Route(text.toByteArray())
    }

    private fun updateNotification() = notifications.activeNotifications.firstOrNull { it.id == UPDATE_NOTIFICATION }

    /** Notifications are posted asynchronously by the system; wait for one to appear. */
    private fun awaitNotification() {
        waitUntil(5_000, "update notification") { updateNotification() != null }
    }

    @Test
    fun aNewerWinaliReleaseIsDetectedAndNotified() {
        serve(catalog(installed + 1))
        val updates = UpdateChecker(context, store).run()
        assertEquals(listOf("winali"), updates.map { it.id })
        awaitNotification()
        val shown = updateNotification()
        assertEquals("1 update available", shown!!.notification.extras.getString("android.title"))
        assertTrue(shown.notification.extras.getString("android.text")!!.contains("Winali 9.9.${installed + 1}"))
    }

    @Test
    fun theSameUpdateIsNotAnnouncedTwiceButANewerOneIs() {
        serve(catalog(installed + 1))
        UpdateChecker(context, store).run()
        awaitNotification()
        notifications.cancel(UPDATE_NOTIFICATION)
        waitUntil(5_000, "dismissal") { updateNotification() == null }
        UpdateChecker(context, store).run()
        Thread.sleep(1_500)
        assertNull("same update set must stay quiet", updateNotification())
        serve(catalog(installed + 2, revisionOffset = 11))
        UpdateChecker(context, store).run()
        awaitNotification()
    }

    @Test
    fun whenEverythingIsCurrentThereIsNoUpdateAndNoNotification() {
        serve(catalog(installed, name = "current"))
        assertTrue(UpdateChecker(context, store).run().isEmpty())
        Thread.sleep(1_000)
        assertNull(updateNotification())
        assertNull(context.distributionPrefs().getString("notifiedUpdates", null))
    }

    @Test
    fun anInvalidCatalogIsRejectedAndTheCachedOneKept() {
        serve(catalog(installed + 1))
        UpdateChecker(context, store).run()
        val before = store.cached().first { it.id == "winali" }.release!!.versionCode
        server.routes["/index.json"] = Route("{\"schemaVersion\":1,\"apps\":[{\"id\":\"x\"}]}".toByteArray())
        try {
            store.refresh(true)
            fail("Expected the broken catalog to be rejected")
        } catch (e: DistributionException) {
            assertEquals(Failure.InvalidCatalog, e.failure)
        }
        assertEquals(before, store.cached().first { it.id == "winali" }.release!!.versionCode)
    }

    @Test
    fun anOlderCatalogRevisionNeverReplacesANewerOne() {
        serve(catalog(installed + 5, revisionOffset = 20))
        store.refresh(true)
        serve(catalog(installed + 1, revisionOffset = 15))
        store.refresh(true)
        assertEquals(installed + 5, store.cached().first { it.id == "winali" }.release!!.versionCode)
    }

    @Test
    fun anUnreachableServerFailsWithoutLosingTheCache() {
        serve(catalog(installed + 1))
        store.refresh(true)
        val port = server.port
        server.close()
        try {
            store.refresh(true)
            fail("Expected a network failure")
        } catch (e: java.io.IOException) {
            assertTrue(port > 0)
        }
        assertEquals(installed + 1, store.cached().first { it.id == "winali" }.release!!.versionCode)
    }

    @Test
    fun checksRunEveryTwelveHoursOnlyWithANetwork() {
        val request = updateCheckRequest()
        assertEquals(TimeUnit.HOURS.toMillis(12), request.workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun schedulingIsIdempotentAndCancellable() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val manager = WorkManager.getInstance(context)
        context.scheduleUpdateChecks()
        context.scheduleUpdateChecks()
        val infos = manager.getWorkInfosForUniqueWork(UPDATE_WORK).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        context.cancelUpdateChecks()
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfosForUniqueWork(UPDATE_WORK).get().single().state)
    }
}
