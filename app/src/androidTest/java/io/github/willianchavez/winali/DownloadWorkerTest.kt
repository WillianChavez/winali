package io.github.willianchavez.winali

import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The real worker against a loopback server: progress, resume, integrity, failures, cancellation. */
class DownloadWorkerTest {
    private lateinit var server: LocalServer
    private lateinit var fixture: Fixture
    private lateinit var manager: WorkManager
    private val app get() = fixture.app
    private val output get() = context.downloadFile(app)
    private val part get() = File(output.parentFile, "${output.name}.part")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        manager = WorkManager.getInstance(context)
        server = LocalServer()
        fixture = Fixture("fixture-1.apk", server.url("/fixture-1.apk"))
        server.routes["/fixture-1.apk"] = Route(fixture.bytes)
        clearDownloads()
    }

    @After
    fun tearDown() {
        manager.cancelAllWork().result.get()
        manager.pruneWork().result.get()
        server.close()
        clearDownloads()
    }

    private fun finishedInfo(request: OneTimeWorkRequest, timeoutMs: Long = 60_000): WorkInfo {
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
        waitUntil(timeoutMs, "download to finish") { manager.getWorkInfoById(request.id).get()!!.state.isFinished }
        return manager.getWorkInfoById(request.id).get()!!
    }

    private fun run(target: CatalogApp = app): WorkInfo {
        val request = downloadRequest(target, autoInstall = false)
        manager.enqueueUniqueWork(context.workName(target.packageName), ExistingWorkPolicy.REPLACE, request).result.get()
        return finishedInfo(request)
    }

    private fun failureOf(info: WorkInfo) = Failure.of(info.outputData.getString("failure"))

    @Test
    fun downloadsVerifiesAndStoresTheApk() {
        val info = run()
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertArrayEquals(fixture.bytes, output.readBytes())
        assertFalse(part.exists())
        assertNull("a fresh download must not ask for a range", server.hits.single().range)
    }

    @Test
    fun resumesAPartialDownloadWithARangeRequest() {
        output.parentFile!!.mkdirs()
        part.writeBytes(fixture.bytes.copyOf(3000))
        val info = run()
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals("bytes=3000-", server.hits.single().range)
        assertArrayEquals(fixture.bytes, output.readBytes())
    }

    @Test
    fun aServerThatIgnoresRangesRestartsFromTheBeginning() {
        server.routes["/fixture-1.apk"] = Route(fixture.bytes, ranges = false)
        output.parentFile!!.mkdirs()
        part.writeBytes(fixture.bytes.copyOf(3000))
        val info = run()
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals("bytes=3000-", server.hits.single().range)
        assertArrayEquals(fixture.bytes, output.readBytes())
    }

    @Test
    fun anAlreadyVerifiedFileIsNotDownloadedAgain() {
        fixture.place()
        val info = run()
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertTrue(server.hits.isEmpty())
    }

    @Test
    fun corruptedBytesAreRejectedAndNothingIsKept() {
        server.routes["/fixture-1.apk"] = Route(fixture.bytes.corrupted())
        val info = run()
        assertEquals(WorkInfo.State.FAILED, info.state)
        assertEquals(Failure.Corrupt, failureOf(info))
        assertFalse(output.exists())
        assertFalse(part.exists())
        val status = downloadStatuses(manager.getWorkInfosByTag("download").get())[app.packageName]
        assertEquals(DownloadStatus.Failed(Failure.Corrupt, "hash"), status)
    }

    @Test
    fun aDownloadLargerThanDeclaredIsRejected() {
        server.routes["/fixture-1.apk"] = Route(fixture.bytes + ByteArray(500))
        val info = run()
        assertEquals(WorkInfo.State.FAILED, info.state)
        assertEquals(Failure.Corrupt, failureOf(info))
        assertFalse(part.exists())
    }

    @Test
    fun aMissingFileReportsTheServerStatus() {
        server.routes.clear()
        val info = run()
        assertEquals(WorkInfo.State.FAILED, info.state)
        assertEquals(Failure.Server, failureOf(info))
        assertEquals("HTTP 404", info.outputData.getString("detail"))
        assertEquals(
            DownloadStatus.Failed(Failure.Server, "HTTP 404"),
            info.downloadStatus(),
        )
    }

    @Test
    fun anIncompatibleReleaseIsRefusedWithoutTouchingTheNetwork() {
        val incompatible = app.copy(release = app.release!!.copy(minSdk = 99))
        val info = run(incompatible)
        assertEquals(WorkInfo.State.FAILED, info.state)
        assertEquals(Failure.Incompatible, failureOf(info))
        assertTrue(server.hits.isEmpty())
    }

    @Test
    fun aRequestWithABrokenCatalogEntryFailsAsInvalidCatalog() {
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("app" to "{not json", "autoInstall" to false))
                .addTag("download")
                .build()
        manager.enqueue(request).result.get()
        val info = finishedInfo(request)
        assertEquals(Failure.InvalidCatalog, failureOf(info))
    }

    private fun worker(attempt: Int) =
        TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf("app" to app.json().toString(), "autoInstall" to false))
            .setRunAttemptCount(attempt)
            .build()

    @Test
    fun aDroppedConnectionKeepsTheBytesRetriesAndThenResumes() = runBlocking {
        server.routes["/fixture-1.apk"] = Route(fixture.bytes, dropAfter = 2000)
        assertTrue(worker(0).doWork() is ListenableWorker.Result.Retry)
        assertEquals(2000L, part.length())
        assertFalse(output.exists())
        // The server recovers: the next attempt asks only for the missing bytes.
        server.routes["/fixture-1.apk"] = Route(fixture.bytes)
        assertTrue(worker(1).doWork() is ListenableWorker.Result.Success)
        assertEquals("bytes=2000-", server.hits.last().range)
        assertArrayEquals(fixture.bytes, output.readBytes())
    }

    @Test
    fun afterThreeRetriesANetworkFailureBecomesFinal() = runBlocking {
        server.routes["/fixture-1.apk"] = Route(fixture.bytes, dropAfter = 2000)
        val result = worker(3).doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
        val data = (result as ListenableWorker.Result.Failure).outputData
        assertEquals(Failure.Network.name, data.getString("failure"))
    }

    @Test
    fun cancellingStopsTheTransferAndDiscardsThePartialFile() {
        val big = ByteArray(3_000_000) { (it % 251).toByte() }
        server.routes["/slow.apk"] = Route(big, chunkDelayMs = 25)
        val slow = app.copy(release = app.release!!.copy(url = server.url("/slow.apk"), size = big.size.toLong(), sha256 = sha256(big)))
        val request = downloadRequest(slow, autoInstall = false)
        manager.enqueueUniqueWork(context.workName(slow.packageName), ExistingWorkPolicy.REPLACE, request).result.get()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
        waitUntil(30_000, "progress") {
            val info = manager.getWorkInfoById(request.id).get()!!
            info.state == WorkInfo.State.RUNNING && info.progress.getInt("percent", 0) > 0
        }
        val running = manager.getWorkInfoById(request.id).get()!!.downloadStatus()
        assertTrue("expected progress, got $running", running is DownloadStatus.Running)
        assertTrue((running as DownloadStatus.Running).total == big.size.toLong())
        runBlocking { context.cancelDownload(slow) }
        waitUntil(10_000, "cancellation") { manager.getWorkInfoById(request.id).get()!!.state == WorkInfo.State.CANCELLED }
        assertFalse(context.downloadFile(slow).exists())
        assertFalse(File(context.downloadFile(slow).parentFile, "${context.downloadFile(slow).name}.part").exists())
        assertEquals(DownloadStatus.Idle, manager.getWorkInfoById(request.id).get()!!.downloadStatus())
    }

    @Test
    fun aFailedDownloadCanBeRetriedAndThenSucceeds() {
        server.routes["/fixture-1.apk"] = Route(fixture.bytes, status = 503)
        val failed = run()
        assertEquals(Failure.Server, failureOf(failed))
        server.routes["/fixture-1.apk"] = Route(fixture.bytes)
        // Retry is a fresh enqueue; the failed entry is pruned so only the new request represents the app.
        context.enqueueDownload(app, autoInstall = false).result.get()
        val infos = manager.getWorkInfosForUniqueWork(context.workName(app.packageName)).get()
        assertEquals(1, infos.count { !it.state.isFinished })
        val retry = infos.first { !it.state.isFinished }
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(retry.id)
        waitUntil(60_000, "retry") { manager.getWorkInfoById(retry.id).get()!!.state.isFinished }
        assertEquals(WorkInfo.State.SUCCEEDED, manager.getWorkInfoById(retry.id).get()!!.state)
        assertArrayEquals(fixture.bytes, output.readBytes())
    }
}
