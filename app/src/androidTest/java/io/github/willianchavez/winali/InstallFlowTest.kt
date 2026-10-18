package io.github.willianchavez.winali

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Installation through the real PackageInstaller, driving Android's own confirmation dialog. */
class InstallFlowTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var one: Fixture
    private lateinit var two: Fixture
    private lateinit var foreign: Fixture
    private val pkg = FIXTURE_PACKAGE
    private val notificationId get() = 5000 + pkg.hashCode().and(0xfff)

    @Before
    fun setUp() {
        requireInstallSource()
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        one = Fixture("fixture-1.apk", "https://example.invalid/fixture-1.apk")
        two = Fixture("fixture-2.apk", "https://example.invalid/fixture-2.apk")
        foreign = Fixture("fixture-2-foreign.apk", "https://example.invalid/fixture-2-foreign.apk")
        uninstallFixture()
        clearDownloads()
        InstallLog.finish(context, pkg, null)
        context.abandonOwnSessions(pkg)
        AppVisibility.started = 1 // the confirmation dialog opens directly, as with the store on screen
    }

    @After
    fun tearDown() {
        AppVisibility.started = 0
        InstallPermission.override = null
        context.abandonOwnSessions(pkg)
        uninstallFixture()
        clearDownloads()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun press(label: String) {
        val button =
            device.wait(
                Until.findObject(By.text(Pattern.compile(label, Pattern.CASE_INSENSITIVE)).clickable(true)),
                30_000,
            ) ?: throw AssertionError("No \"$label\" button on screen: ${device.dumpWindowHierarchyText()}")
        button.click()
    }

    private fun UiDevice.dumpWindowHierarchyText(): String {
        val out = java.io.ByteArrayOutputStream()
        dumpWindowHierarchy(out)
        return out.toString().take(1500)
    }

    private fun install(fixture: Fixture) {
        fixture.place()
        runBlocking { context.install(fixture.app) }
    }

    private fun installAndConfirm(fixture: Fixture, label: String = "install|update") {
        install(fixture)
        press(label)
        waitUntil(60_000, "installation of ${fixture.app.release!!.versionName}") {
            context.installedVersion(pkg) == fixture.app.release!!.versionCode
        }
        waitUntil(10_000, "result") { InstallLog.lastSucceeded(context, pkg) }
    }

    private fun thrownFailure(block: () -> Unit): Failure {
        try {
            block()
        } catch (e: DistributionException) {
            return e.failure
        }
        throw AssertionError("Expected a DistributionException")
    }

    @Test
    fun installsThroughThePackageInstallerAndCleansUp() {
        installAndConfirm(one)
        assertEquals(InstallPhase.None, InstallLog.phase(context, pkg))
        assertFalse("the downloaded APK is removed after success", context.downloadFile(one.app).exists())
        assertNull(InstallLog.lastFailure(context, pkg))
    }

    @Test
    fun updatingKeepsTheSameInstallationAndAppliesTheNewVersion() {
        installAndConfirm(one)
        val before = context.packageManager.getPackageInfo(pkg, 0)
        installAndConfirm(two)
        val after = context.packageManager.getPackageInfo(pkg, 0)
        assertEquals("update, not reinstall", before.firstInstallTime, after.firstInstallTime)
        assertTrue(after.lastUpdateTime >= before.lastUpdateTime)
        assertEquals("2", after.versionName)
    }

    @Test
    fun cancellingInAndroidsDialogLeavesTheInstalledVersionAndReportsCancelled() {
        installAndConfirm(one)
        install(two)
        press("cancel")
        waitUntil(30_000, "cancelled result") { InstallLog.lastFailure(context, pkg)?.first == Failure.Cancelled }
        assertEquals(1L, context.installedVersion(pkg))
        assertEquals(InstallPhase.None, InstallLog.phase(context, pkg))
        assertTrue("the download stays so it can be installed later", context.downloadFile(two.app).exists())
    }

    @Test
    fun aDifferentSigningKeyIsRefusedBeforeAnySessionAndNothingIsUninstalled() {
        installAndConfirm(one)
        foreign.place()
        val failure = thrownFailure { runBlocking { context.install(foreign.app) } }
        assertEquals(Failure.SignatureMismatch, failure)
        assertEquals(1L, context.installedVersion(pkg))
        assertEquals(InstallPhase.None, InstallLog.phase(context, pkg))
        assertTrue(Failure.SignatureMismatch.message(true).contains("no la desinstalará"))
    }

    @Test
    fun androidsOwnSignatureVerdictMapsToTheSameFailure() {
        installAndConfirm(one)
        // Bypass Winali's pre-check to see what the system itself reports for a foreign signature.
        foreign.place()
        val installer = context.packageManager.packageInstaller
        val id =
            installer.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(pkg)
                },
            )
        installer.openSession(id).use { session ->
            File(context.downloadFile(foreign.app).path).inputStream().use { input ->
                session.openWrite("base.apk", 0, foreign.bytes.size.toLong()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java).putExtra("package", pkg).putExtra("name", "Fixture")
            InstallLog.begin(context, pkg, id)
            session.commit(
                android.app.PendingIntent.getBroadcast(
                    context,
                    id,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                ).intentSender,
            )
        }
        // Android may ask first or refuse at once.
        device.wait(Until.findObject(By.text(Pattern.compile("install|update", Pattern.CASE_INSENSITIVE)).clickable(true)), 15_000)?.click()
        waitUntil(60_000, "system verdict") { InstallLog.lastFailure(context, pkg) != null }
        assertEquals(Failure.SignatureMismatch, InstallLog.lastFailure(context, pkg)!!.first)
        assertEquals(1L, context.installedVersion(pkg))
    }

    @Test
    fun aDowngradeIsRefused() {
        installAndConfirm(two)
        one.place()
        assertEquals(Failure.Downgrade, thrownFailure { runBlocking { context.install(one.app) } })
        assertEquals(2L, context.installedVersion(pkg))
    }

    @Test
    fun withoutPermissionInstallingFailsClearlyAndStartsNoSession() {
        one.place()
        val failure = withInstallDenied { thrownFailure { runBlocking { context.install(one.app) } } }
        assertEquals(Failure.PermissionDenied, failure)
        assertEquals(InstallPhase.None, InstallLog.phase(context, pkg))
        assertNull(context.installedVersion(pkg))
    }

    @Test
    fun aDamagedDownloadIsDiscardedAtInstallTime() {
        val file = one.place()
        file.writeBytes(one.bytes.corrupted())
        assertEquals(Failure.Corrupt, thrownFailure { runBlocking { context.install(one.app) } })
        assertFalse(file.exists())
        assertNull(context.installedVersion(pkg))
    }

    @Test
    fun aMissingDownloadIsReportedNotCrashed() {
        assertEquals(Failure.Corrupt, thrownFailure { runBlocking { context.install(one.app) } })
    }

    @Test
    fun whenTheStoreIsNotOnScreenAPendingConfirmationBecomesANotification() {
        AppVisibility.started = 0
        install(one)
        waitUntil(30_000, "awaiting confirmation") {
            InstallLog.phase(context, pkg) == InstallPhase.AwaitingConfirmation
        }
        // The phase is recorded just before the notification is posted, and posting is asynchronous.
        val manager = context.getSystemService(NotificationManager::class.java)
        waitUntil(10_000, "confirmation notification ${manager.activeNotifications.map { it.id }}") {
            manager.activeNotifications.any { it.id == notificationId }
        }
        context.abandonOwnSessions(pkg)
        waitUntil(10_000, "session cleanup") { InstallLog.phase(context, pkg) == InstallPhase.None }
    }

    @Test
    fun aSessionThatNoLongerExistsDoesNotLeaveTheAppStuckInstalling() {
        InstallLog.begin(context, pkg, 987654)
        assertEquals(InstallPhase.None, InstallLog.phase(context, pkg))
    }

    @Test
    fun theReceiverRecordsEveryInstallerOutcome() {
        val expectations =
            mapOf(
                PackageInstaller.STATUS_FAILURE_STORAGE to Failure.InsufficientStorage,
                PackageInstaller.STATUS_FAILURE_INVALID to Failure.InvalidPackage,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE to Failure.Incompatible,
                PackageInstaller.STATUS_FAILURE_BLOCKED to Failure.Blocked,
                PackageInstaller.STATUS_FAILURE_ABORTED to Failure.Cancelled,
                PackageInstaller.STATUS_FAILURE to Failure.Unknown,
            )
        for ((status, failure) in expectations) {
            deliver(status, "INSTALL_FAILED_TEST")
            assertEquals("status $status", failure, InstallLog.lastFailure(context, pkg)?.first)
        }
        clearDownloads()
        context.downloadFile(one.app).writeBytes(one.bytes)
        deliver(PackageInstaller.STATUS_SUCCESS, null)
        assertTrue(InstallLog.lastSucceeded(context, pkg))
        assertNull(InstallLog.lastFailure(context, pkg))
        assertFalse("success removes the downloaded APK", context.downloadFile(one.app).exists())
    }

    @Test
    fun failuresWhileTheStoreIsClosedBecomeANotificationButCancellationDoesNot() {
        AppVisibility.started = 0
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        deliver(PackageInstaller.STATUS_FAILURE_ABORTED, "User rejected")
        assertTrue(manager.activeNotifications.none { it.id == 5000 + "Fixture".hashCode().and(0xfff) })
        deliver(PackageInstaller.STATUS_FAILURE_STORAGE, "INSTALL_FAILED_INSUFFICIENT_STORAGE")
        waitUntil(5_000, "failure notification") {
            manager.activeNotifications.any { it.id == 5000 + "Fixture".hashCode().and(0xfff) }
        }
    }

    private fun deliver(status: Int, message: String?) {
        val intent =
            Intent(context, InstallReceiver::class.java)
                .putExtra("package", pkg)
                .putExtra("name", "Fixture")
                .putExtra(PackageInstaller.EXTRA_STATUS, status)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message)
        InstallReceiver().onReceive(context, intent)
    }

    @Test
    fun downloadThenInstallRunsAsOneActionFromTheQueue() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val manager = WorkManager.getInstance(context)
        LocalServer().use { server ->
            val served = Fixture("fixture-1.apk", server.url("/fixture-1.apk"))
            server.routes["/fixture-1.apk"] = Route(served.bytes)
            val request = downloadRequest(served.app, autoInstall = true)
            manager.enqueueUniqueWork(context.workName(pkg), ExistingWorkPolicy.REPLACE, request).result.get()
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
            press("install|update")
            waitUntil(60_000, "installation") { context.installedVersion(pkg) == 1L }
            waitUntil(30_000, "worker end") { manager.getWorkInfoById(request.id).get()!!.state.isFinished }
            assertEquals(WorkInfo.State.SUCCEEDED, manager.getWorkInfoById(request.id).get()!!.state)
        }
    }

    @Test
    fun withoutPermissionAnAutomaticInstallAfterDownloadRecordsTheDenial() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        InstallPermission.override = false
        val manager = WorkManager.getInstance(context)
        LocalServer().use { server ->
            val served = Fixture("fixture-1.apk", server.url("/fixture-1.apk"))
            server.routes["/fixture-1.apk"] = Route(served.bytes)
            val request = downloadRequest(served.app, autoInstall = true)
            manager.enqueueUniqueWork(context.workName(pkg), ExistingWorkPolicy.REPLACE, request).result.get()
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
            waitUntil(60_000, "worker end") { manager.getWorkInfoById(request.id).get()!!.state.isFinished }
            assertEquals(Failure.PermissionDenied, InstallLog.lastFailure(context, pkg)?.first)
            assertTrue("the download is kept for later", context.downloadFile(served.app).exists())
            assertNull(context.installedVersion(pkg))
        }
        InstallPermission.override = null
    }

    @Test
    fun cleanupRemovesCoveredDownloadsAndOldPartialFilesOnly() {
        val directory = File(context.filesDir, "downloads").apply { mkdirs() }
        val covered = File(directory, "${context.packageName}-1.apk").apply { writeText("x") }
        val newer = File(directory, "${context.packageName}-99999.apk").apply { writeText("x") }
        val oldPart = File(directory, "other.pkg-5.apk.part").apply { writeText("x"); setLastModified(1_000) }
        val freshPart = File(directory, "other.pkg-6.apk.part").apply { writeText("x") }
        context.cleanDownloads()
        assertFalse(covered.exists())
        assertTrue(newer.exists())
        assertFalse(oldPart.exists())
        assertTrue(freshPart.exists())
    }
}
