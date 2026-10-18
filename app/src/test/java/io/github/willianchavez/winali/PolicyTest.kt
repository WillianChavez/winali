package io.github.willianchavez.winali

import org.junit.Assert.*
import org.junit.Test

class ActionStateTest {
    private fun resolve(
        release: Release? = release(),
        compatible: Boolean = true,
        installed: Long? = null,
        downloaded: Boolean = false,
        download: DownloadStatus = DownloadStatus.Idle,
        install: InstallPhase = InstallPhase.None,
    ) = resolveAction(release, compatible, installed, downloaded, download, install)

    @Test fun notInstalledOffersInstall() = assertEquals(ActionState.Install, resolve())

    @Test fun installedOlderOffersUpdate() = assertEquals(ActionState.Update, resolve(installed = 1000))

    @Test fun installedCurrentOrNewerOffersOpen() {
        assertEquals(ActionState.Open, resolve(installed = 1001))
        assertEquals(ActionState.Open, resolve(installed = 2000))
    }

    @Test fun existingDownloadOffersInstallDownload() {
        assertEquals(ActionState.InstallDownload, resolve(downloaded = true))
        assertEquals(ActionState.InstallDownload, resolve(installed = 1000, downloaded = true))
    }

    @Test fun activeDownloadShowsProgressOverEverythingElse() {
        assertEquals(ActionState.Downloading(42), resolve(installed = 1000, download = DownloadStatus.Running(42)))
        assertEquals(ActionState.Queued, resolve(download = DownloadStatus.Queued))
    }

    @Test fun installSessionStatesFollowDownload() {
        assertEquals(ActionState.Installing, resolve(downloaded = true, install = InstallPhase.Installing))
        assertEquals(
            ActionState.AwaitingConfirmation,
            resolve(downloaded = true, install = InstallPhase.AwaitingConfirmation),
        )
    }

    @Test fun failedDownloadOffersRetryUnlessAnotherStateApplies() {
        val failed = DownloadStatus.Failed(Failure.Network)
        assertEquals(ActionState.Retry(Failure.Network), resolve(download = failed))
        assertEquals(ActionState.Open, resolve(installed = 1001, download = failed))
        assertEquals(ActionState.InstallDownload, resolve(downloaded = true, download = failed))
    }

    @Test fun unpublishedAndIncompatibleAppsHaveNoPrimaryAction() {
        assertEquals(ActionState.ComingSoon, resolve(release = null))
        assertEquals(ActionState.Incompatible, resolve(compatible = false, installed = 1000))
    }

    @Test fun everyStateHasALabelInBothLanguages() {
        val states =
            listOf(
                ActionState.ComingSoon,
                ActionState.Incompatible,
                ActionState.Queued,
                ActionState.Downloading(1),
                ActionState.Installing,
                ActionState.AwaitingConfirmation,
                ActionState.Open,
                ActionState.InstallDownload,
                ActionState.Retry(Failure.Unknown),
                ActionState.Update,
                ActionState.Install,
            )
        for (state in states) {
            assertTrue(state.label(true).isNotBlank())
            assertTrue(state.label(false).isNotBlank())
        }
        assertEquals("Instalar descarga", ActionState.InstallDownload.label(true))
        assertEquals("Install download", ActionState.InstallDownload.label(false))
        assertEquals("Actualizar", ActionState.Update.label(true))
    }
}

class UpdatesTest {
    private val apk = app("apk", release(1001, "0.1.1"))
    private val focus = app("focus", release(1000, "0.1.0"))
    private val winali = app("winali", release(1001, "0.1.1"))
    private val device: (CatalogApp) -> Boolean = { it.compatible(30, arrayOf("x86_64")) }

    @Test fun onlyInstalledAppsWithNewerCompatibleReleasesAreUpdates() {
        val installed = mapOf(apk.packageName to 1000L, focus.packageName to 1000L)
        assertEquals(listOf(apk), pendingUpdates(listOf(apk, focus, winali), installed, device))
    }

    @Test fun incompatibleOrUnpublishedReleasesAreNeverUpdates() {
        val newer = app("future", release(2000, "9.0", minSdk = 99))
        val none = app("none", null)
        val installed = mapOf(newer.packageName to 1L, none.packageName to 1L)
        assertTrue(pendingUpdates(listOf(newer, none), installed, device).isEmpty())
    }

    @Test fun updateKeyIgnoresOrderAndChangesWithVersion() {
        assertEquals(updateKey(listOf(apk, winali)), updateKey(listOf(winali, apk)))
        assertNotEquals(updateKey(listOf(apk)), updateKey(listOf(apk.copy(release = release(1002, "0.1.2")))))
    }

    @Test fun theSameUpdateSetIsNotifiedTwice() {
        assertTrue(shouldNotify(listOf(apk), null))
        assertFalse(shouldNotify(listOf(apk), updateKey(listOf(apk))))
        assertTrue(shouldNotify(listOf(apk, winali), updateKey(listOf(apk))))
        assertFalse(shouldNotify(emptyList(), null))
    }

    @Test fun winaliUpdatesItselfLast() {
        val order = updateOrder(listOf(winali, apk, focus), winali.packageName).map { it.id }
        assertEquals(listOf("apk", "focus", "winali"), order)
    }
}

class DownloadPolicyTest {
    @Test fun resumesOnlyFromAPlausiblePartialFile() {
        assertEquals(0, resumeOffset(0, 100))
        assertEquals(40, resumeOffset(40, 100))
        assertEquals(0, resumeOffset(100, 100))
        assertEquals(0, resumeOffset(150, 100))
    }

    @Test fun storageNeedsTheFileSizePlusAReserve() {
        assertTrue(hasSpace(50_000_001, 40_000_000))
        assertFalse(hasSpace(50_000_000, 40_000_000))
        assertFalse(hasSpace(1_000, 40_000_000))
    }

    @Test fun onlyNetworkFailuresAreRetriedAndOnlyThreeTimes() {
        assertTrue(shouldRetry(Failure.Network, 0))
        assertTrue(shouldRetry(Failure.Network, 2))
        assertFalse(shouldRetry(Failure.Network, 3))
        for (f in Failure.entries.filter { it != Failure.Network }) assertFalse(shouldRetry(f, 0))
    }

    @Test fun staleDownloadsAreOnesTheInstalledVersionCovers() {
        val installed = { pkg: String -> if (pkg == "a.b") 1001L else null }
        assertTrue(staleDownload("a.b-1000.apk", installed))
        assertTrue(staleDownload("a.b-1001.apk", installed))
        assertFalse(staleDownload("a.b-1002.apk", installed))
        assertFalse(staleDownload("other.app-1.apk", installed))
        assertFalse(staleDownload("a.b-1001.apk.part", installed))
    }

    @Test fun failuresThatProveBadBytesDiscardTheFile() {
        for (f in listOf(Failure.Corrupt, Failure.InvalidPackage, Failure.CertificateMismatch))
            assertTrue(f.discardsDownload())
        for (f in listOf(Failure.Network, Failure.SignatureMismatch, Failure.InsufficientStorage))
            assertFalse(f.discardsDownload())
    }

    @Test fun sizesAreFormattedForTheReader() {
        assertEquals("11,3 MB", formatSize(11_872_934, true))
        assertEquals("11.3 MB", formatSize(11_872_934, false))
        assertEquals("2 KB", formatSize(2048, false))
        assertEquals("1 KB", formatSize(10, false))
    }

    @Test fun relativeTimeIsCoarseAndLocalized() {
        assertEquals("hace un momento", relativeTime(10_000, true))
        assertEquals("hace 5 min", relativeTime(5 * 60_000L, true))
        assertEquals("3 h ago", relativeTime(3 * 3_600_000L, false))
        assertEquals("hace 2 d", relativeTime(50 * 3_600_000L, true))
    }

    @Test fun androidVersionsHaveReadableNames() {
        assertEquals("8.0", androidName(26))
        assertEquals("16", androidName(36))
        assertEquals("(API 99)", androidName(99))
    }
}

class FailureTest {
    @Test fun everyFailureExplainsItselfInBothLanguages() {
        for (f in Failure.entries) {
            val es = f.message(true)
            val en = f.message(false)
            assertTrue("$f es", es.length > 10)
            assertTrue("$f en", en.length > 10)
            assertNotEquals(es, en)
        }
    }

    @Test fun signatureMismatchPromisesNotToUninstall() {
        assertTrue(Failure.SignatureMismatch.message(false).contains("will not uninstall"))
        assertTrue(Failure.SignatureMismatch.message(true).contains("no la desinstalará"))
    }

    @Test fun systemDetailIsAppendedOnlyWhenItHelps() {
        assertTrue(Failure.Unknown.message(false, "INSTALL_FAILED_X").endsWith("(INSTALL_FAILED_X)"))
        assertTrue(Failure.Server.message(false, "HTTP 404").contains("HTTP 404"))
        assertEquals(Failure.Corrupt.message(false), Failure.Corrupt.message(false, "hash"))
    }

    @Test fun installerStatusesMapToSpecificFailures() {
        assertNull(installFailure(STATUS_SUCCESS, null))
        assertNull(installFailure(STATUS_PENDING_USER_ACTION, null))
        assertEquals(Failure.Blocked, installFailure(STATUS_FAILURE_BLOCKED, null))
        assertEquals(Failure.Cancelled, installFailure(STATUS_FAILURE_ABORTED, "INSTALL_FAILED_ABORTED: User rejected"))
        assertEquals(Failure.InvalidPackage, installFailure(STATUS_FAILURE_INVALID, null))
        assertEquals(Failure.InsufficientStorage, installFailure(STATUS_FAILURE_STORAGE, null))
        assertEquals(Failure.Incompatible, installFailure(STATUS_FAILURE_INCOMPATIBLE, "INSTALL_FAILED_OLDER_SDK"))
        assertEquals(Failure.Downgrade, installFailure(STATUS_FAILURE_INCOMPATIBLE, "INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertEquals(
            Failure.SignatureMismatch,
            installFailure(STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"),
        )
        assertEquals(Failure.Conflict, installFailure(STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_CONFLICTING_PROVIDER"))
        assertEquals(Failure.Unknown, installFailure(1, "mystery"))
    }

    @Test fun failureNamesSurviveWorkManagerRoundTrip() {
        for (f in Failure.entries) assertEquals(f, Failure.of(f.name))
        assertEquals(Failure.Unknown, Failure.of("renamed-in-a-later-version"))
        assertEquals(Failure.Unknown, Failure.of(null))
    }

    @Test fun distributionExceptionStaysAnIllegalArgument() {
        val e: IllegalArgumentException = DistributionException(Failure.Corrupt, "hash")
        assertEquals("Corrupt: hash", e.message)
    }
}

class StoreStateTest {
    private val apk = app("apk", release(1001, "0.1.1"))

    @Test fun problemTextExplainsTheFailedDownloadAndHidesOnceInstalled() {
        val failed =
            StoreState(
                apps = listOf(apk),
                downloads = mapOf(apk.packageName to DownloadStatus.Failed(Failure.InsufficientStorage)),
                spanish = false,
                compatible = { true },
            )
        assertEquals(Failure.InsufficientStorage.message(false), failed.problem(apk))
        assertNull(failed.copy(installed = mapOf(apk.packageName to 1001L)).problem(apk))
    }

    @Test fun cancelledInstallsAreNotReportedAsProblems() {
        val state =
            StoreState(
                apps = listOf(apk),
                downloaded = setOf(apk.packageName),
                failures = mapOf(apk.packageName to (Failure.Cancelled to null)),
                compatible = { true },
            )
        assertNull(state.problem(apk))
        val signature = state.copy(failures = mapOf(apk.packageName to (Failure.SignatureMismatch to null)))
        assertEquals(Failure.SignatureMismatch.message(true), signature.problem(apk))
    }

    @Test fun queueListsActiveAndFailedDownloadsInCatalogOrder() {
        val focus = app("focus", release(1000, "0.1.0"))
        val state =
            StoreState(
                apps = listOf(apk, focus),
                downloads =
                    mapOf(
                        focus.packageName to DownloadStatus.Running(5),
                        apk.packageName to DownloadStatus.Failed(Failure.Network),
                    ),
            )
        assertEquals(listOf("apk", "focus"), state.queue.map { it.id })
    }
}
