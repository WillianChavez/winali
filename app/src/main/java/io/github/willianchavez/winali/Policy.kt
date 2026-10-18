package io.github.willianchavez.winali

import java.util.Locale

/** Everything that can go wrong between catalog and installed app, with a user-facing message. */
enum class Failure(private val es: String, private val en: String) {
    InsufficientStorage(
        "No hay espacio suficiente. Libera almacenamiento e inténtalo de nuevo.",
        "Not enough storage space. Free up space and try again.",
    ),
    Corrupt(
        "La descarga está dañada y se descartó. Vuelve a descargarla.",
        "The download is damaged and was discarded. Download it again.",
    ),
    SignatureMismatch(
        "La app instalada está firmada con otra clave. Winali no la desinstalará; si quieres esta versión, desinstala la actual tú mismo (se borrarán sus datos).",
        "The installed app is signed with a different key. Winali will not uninstall it; if you want this version, uninstall the current one yourself (its data will be erased).",
    ),
    CertificateMismatch(
        "El paquete no está firmado con la clave que publica el catálogo. Se descartó.",
        "The package is not signed with the key the catalog publishes. It was discarded.",
    ),
    InvalidPackage(
        "El archivo descargado no es un paquete válido para esta app.",
        "The downloaded file is not a valid package for this app.",
    ),
    Incompatible(
        "Esta versión no es compatible con tu dispositivo.",
        "This version is not compatible with your device.",
    ),
    Downgrade(
        "Ya tienes instalada una versión más reciente.",
        "A newer version is already installed.",
    ),
    Network(
        "Sin conexión o conexión interrumpida. Inténtalo de nuevo cuando vuelvas a tener red.",
        "No connection, or the connection dropped. Try again when you are back online.",
    ),
    Server(
        "El servidor no pudo entregar el archivo.",
        "The server could not deliver the file.",
    ),
    PermissionDenied(
        "Winali no tiene permiso para instalar apps. Actívalo en los ajustes de Android y vuelve a intentarlo.",
        "Winali is not allowed to install apps. Enable it in Android settings and try again.",
    ),
    InvalidCatalog(
        "El catálogo recibido no es válido. Se mantiene el catálogo guardado.",
        "The received catalog is not valid. The saved catalog is kept.",
    ),
    Blocked(
        "Android o Play Protect bloqueó la instalación.",
        "Android or Play Protect blocked the installation.",
    ),
    Cancelled("Instalación cancelada.", "Installation cancelled."),
    Conflict(
        "Android rechazó la instalación por un conflicto con otra app.",
        "Android rejected the installation because of a conflict with another app.",
    ),
    Unknown("No se pudo completar la operación.", "The operation could not be completed.");

    fun message(spanish: Boolean, detail: String? = null): String {
        val base = if (spanish) es else en
        return if (detail.isNullOrBlank() || this != Unknown && this != Server) base
        else "$base ($detail)"
    }

    companion object {
        fun of(name: String?): Failure = entries.firstOrNull { it.name == name } ?: Unknown
    }
}

/** Failures that prove the downloaded bytes unusable, so the file must not be kept. */
fun Failure.discardsDownload() =
    this == Failure.Corrupt || this == Failure.InvalidPackage || this == Failure.CertificateMismatch

/** A verification or install problem that carries its [Failure]; still an IllegalArgumentException. */
class DistributionException(val failure: Failure, val detail: String? = null) :
    IllegalArgumentException(failure.name + (detail?.let { ": $it" } ?: ""))

/** Maps a PackageInstaller status (and the system's message) to a failure. Null means no failure. */
fun installFailure(status: Int, message: String?): Failure? =
    when (status) {
        STATUS_SUCCESS,
        STATUS_PENDING_USER_ACTION -> null
        STATUS_FAILURE_BLOCKED -> Failure.Blocked
        STATUS_FAILURE_ABORTED -> Failure.Cancelled
        STATUS_FAILURE_INVALID -> Failure.InvalidPackage
        STATUS_FAILURE_CONFLICT ->
            if (
                message != null &&
                    (message.contains("UPDATE_INCOMPATIBLE") ||
                        message.contains("signatures", true) ||
                        message.contains("signed", true))
            )
                Failure.SignatureMismatch
            else Failure.Conflict
        STATUS_FAILURE_STORAGE -> Failure.InsufficientStorage
        STATUS_FAILURE_INCOMPATIBLE ->
            if (message != null && message.contains("DOWNGRADE")) Failure.Downgrade
            else Failure.Incompatible
        else ->
            if (message != null && message.contains("UPDATE_INCOMPATIBLE"))
                Failure.SignatureMismatch
            else Failure.Unknown
    }

// Mirrors android.content.pm.PackageInstaller.STATUS_* so this file stays pure Kotlin.
const val STATUS_PENDING_USER_ACTION = -1
const val STATUS_SUCCESS = 0
const val STATUS_FAILURE_BLOCKED = 2
const val STATUS_FAILURE_ABORTED = 3
const val STATUS_FAILURE_INVALID = 4
const val STATUS_FAILURE_CONFLICT = 5
const val STATUS_FAILURE_STORAGE = 6
const val STATUS_FAILURE_INCOMPATIBLE = 7

/** What a download request is doing, independent of WorkManager. */
sealed interface DownloadStatus {
    data object Idle : DownloadStatus

    data object Queued : DownloadStatus

    data class Running(val percent: Int, val bytes: Long = 0, val total: Long = 0) : DownloadStatus

    data class Failed(val failure: Failure, val detail: String? = null) : DownloadStatus
}

enum class InstallPhase {
    None,
    Installing,
    AwaitingConfirmation,
}

/** The single primary action of an app row or detail page. */
sealed interface ActionState {
    data object ComingSoon : ActionState

    data object Incompatible : ActionState

    data object Queued : ActionState

    data class Downloading(val percent: Int) : ActionState

    data object Installing : ActionState

    data object AwaitingConfirmation : ActionState

    data object Open : ActionState

    data object InstallDownload : ActionState

    data class Retry(val failure: Failure) : ActionState

    data object Update : ActionState

    data object Install : ActionState
}

fun resolveAction(
    release: Release?,
    compatible: Boolean,
    installed: Long?,
    downloaded: Boolean,
    download: DownloadStatus,
    install: InstallPhase,
): ActionState =
    when {
        release == null -> ActionState.ComingSoon
        !compatible -> ActionState.Incompatible
        download is DownloadStatus.Running -> ActionState.Downloading(download.percent)
        download is DownloadStatus.Queued -> ActionState.Queued
        install == InstallPhase.AwaitingConfirmation -> ActionState.AwaitingConfirmation
        install == InstallPhase.Installing -> ActionState.Installing
        installed != null && installed >= release.versionCode -> ActionState.Open
        downloaded -> ActionState.InstallDownload
        download is DownloadStatus.Failed -> ActionState.Retry(download.failure)
        installed != null -> ActionState.Update
        else -> ActionState.Install
    }

/** Installed catalog apps with a newer, compatible release, in catalog order. */
fun pendingUpdates(
    apps: List<CatalogApp>,
    installed: Map<String, Long>,
    compatible: (CatalogApp) -> Boolean = { it.compatible() },
): List<CatalogApp> =
    apps.filter { app ->
        val version = installed[app.packageName]
        val release = app.release
        version != null && release != null && compatible(app) && release.versionCode > version
    }

/** Identifies a set of updates so the same set is notified only once. */
fun updateKey(updates: List<CatalogApp>): String =
    updates
        .mapNotNull { app -> app.release?.let { "${app.packageName}:${it.versionCode}" } }
        .sorted()
        .joinToString(",")

fun shouldNotify(updates: List<CatalogApp>, lastNotifiedKey: String?): Boolean =
    updates.isNotEmpty() && updateKey(updates) != lastNotifiedKey

/** Updates for "update all": Winali replaces its own process, so it goes last. */
fun updateOrder(updates: List<CatalogApp>, ownPackage: String): List<CatalogApp> =
    updates.filter { it.packageName != ownPackage } + updates.filter { it.packageName == ownPackage }

fun hasSpace(usable: Long, needed: Long, reserve: Long = 10_000_000): Boolean =
    usable > needed + reserve

/** Where a resumed download should start; restarts when the partial file cannot be valid. */
fun resumeOffset(existing: Long, total: Long): Long = if (existing in 1 until total) existing else 0

/** Network failures are retried a few times automatically; everything else is final. */
fun shouldRetry(failure: Failure, attempt: Int, limit: Int = 3): Boolean =
    failure == Failure.Network && attempt < limit

/** `<package>-<versionCode>.apk` files that are no longer useful because the app caught up. */
fun staleDownload(fileName: String, installed: (String) -> Long?): Boolean {
    val match = Regex("(.+)-(\\d+)\\.apk").matchEntire(fileName) ?: return false
    val version = installed(match.groupValues[1]) ?: return false
    return version >= match.groupValues[2].toLong()
}

fun formatSize(bytes: Long, spanish: Boolean): String {
    val megabytes = bytes / 1_048_576.0
    val text =
        if (megabytes >= 1) String.format(Locale.ROOT, "%.1f MB", megabytes)
        else String.format(Locale.ROOT, "%d KB", maxOf(1L, (bytes + 512) / 1024))
    return if (spanish) text.replace('.', ',') else text
}

fun loopbackAllowed(debug: Boolean, scheme: String?, host: String?) =
    debug && scheme == "http" && (host == "127.0.0.1" || host == "localhost")

fun androidName(sdk: Int): String =
    when (sdk) {
        26 -> "8.0"
        27 -> "8.1"
        28 -> "9"
        29 -> "10"
        30 -> "11"
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        else -> "(API $sdk)"
    }

fun ActionState.label(spanish: Boolean): String =
    when (this) {
        ActionState.ComingSoon -> if (spanish) "Próximamente" else "Coming soon"
        ActionState.Incompatible -> if (spanish) "No compatible" else "Incompatible"
        ActionState.Queued,
        is ActionState.Downloading -> if (spanish) "Cancelar" else "Cancel"
        ActionState.Installing -> if (spanish) "Instalando…" else "Installing…"
        ActionState.AwaitingConfirmation -> if (spanish) "Confirmar instalación" else "Confirm installation"
        ActionState.Open -> if (spanish) "Abrir" else "Open"
        ActionState.InstallDownload -> if (spanish) "Instalar descarga" else "Install download"
        is ActionState.Retry -> if (spanish) "Reintentar" else "Retry"
        ActionState.Update -> if (spanish) "Actualizar" else "Update"
        ActionState.Install -> if (spanish) "Instalar" else "Install"
    }

/** "hace 5 min" / "5 min ago": coarse on purpose, it only says how stale the catalog is. */
fun relativeTime(elapsedMillis: Long, spanish: Boolean): String {
    val minutes = elapsedMillis / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> if (spanish) "hace un momento" else "just now"
        minutes < 60 -> if (spanish) "hace $minutes min" else "$minutes min ago"
        hours < 24 -> if (spanish) "hace $hours h" else "$hours h ago"
        else -> if (spanish) "hace $days d" else "$days d ago"
    }
}
