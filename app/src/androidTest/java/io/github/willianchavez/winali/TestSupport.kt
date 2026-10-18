package io.github.willianchavez.winali

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

const val FIXTURE_PACKAGE = "io.github.willianchavez.winali.fixture"

val context: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

fun shell(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
        it.readBytes().toString(Charsets.UTF_8)
    }
}

fun waitUntil(timeoutMs: Long = 30_000, message: String = "condition", check: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (check()) return
        Thread.sleep(200)
    }
    throw AssertionError("Timed out waiting for $message")
}

fun setLanguage(code: String): () -> Unit {
    val prefs = context.getSharedPreferences("appearance", 0)
    val original = prefs.getString("language", null)
    prefs.edit().putString("language", code).commit()
    return {
        if (original == null) prefs.edit().remove("language").commit()
        else prefs.edit().putString("language", original).commit()
    }
}

/** One request seen by [LocalServer]. */
data class Hit(val path: String, val range: String?)

/** What the server does for a path. [dropAfter] closes the connection after that many body bytes. */
class Route(
    val body: ByteArray,
    val status: Int = 200,
    val ranges: Boolean = true,
    val dropAfter: Int? = null,
    val chunkDelayMs: Long = 0,
)

/** A loopback HTTP server so tests exercise the real download code without the internet. */
class LocalServer : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val routes = HashMap<String, Route>()
    val hits = CopyOnWriteArrayList<Hit>()
    val port get() = socket.localPort
    fun url(path: String) = "http://127.0.0.1:$port$path"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
    }

    private fun serve(client: Socket) =
        client.use {
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = input.readLine() ?: return
            val path = request.split(" ")[1]
            var range: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) range = line.substringAfter(":").trim()
            }
            hits += Hit(path, range)
            val route = routes[path]
            val out = client.getOutputStream()
            if (route == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            if (route.status != 200) {
                out.write("HTTP/1.1 ${route.status} Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            var start = 0
            var status = "200 OK"
            var extra = ""
            if (range != null && route.ranges) {
                start = range!!.removePrefix("bytes=").substringBefore("-").toInt()
                status = "206 Partial Content"
                extra = "Content-Range: bytes $start-${route.body.size - 1}/${route.body.size}\r\n"
            }
            val length = route.body.size - start
            out.write("HTTP/1.1 $status\r\nContent-Length: $length\r\n${extra}Connection: close\r\n\r\n".toByteArray())
            var sent = 0
            while (sent < length) {
                val count = minOf(16_384, length - sent)
                if (route.dropAfter != null && sent + count > route.dropAfter) {
                    out.write(route.body, start + sent, route.dropAfter - sent)
                    out.flush()
                    return // close with the body incomplete
                }
                out.write(route.body, start + sent, count)
                out.flush()
                sent += count
                if (route.chunkDelayMs > 0) Thread.sleep(route.chunkDelayMs)
            }
        }

    override fun close() = socket.close()
}

fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

/** A catalog entry describing one of the packaged fixture APKs, served at [url]. */
@Suppress("DEPRECATION")
class Fixture(val asset: String, val url: String) {
    val bytes: ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { it.readBytes() }

    val app: CatalogApp

    init {
        val temp = File(context.cacheDir, "inspect-$asset")
        temp.writeBytes(bytes)
        val flags =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(temp.absolutePath, flags)!!
        val signature =
            if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners.single()
            else info.signatures!!.single()
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        temp.delete()
        app =
            CatalogApp(
                "fixture",
                info.packageName,
                "Fixture",
                "Fixture app",
                "Fixture app for installation tests.",
                "Tests",
                "https://github.com/WillianChavez/winali",
                emptyList(),
                emptyList(),
                Release(
                    code,
                    info.versionName ?: code.toString(),
                    26,
                    listOf("universal"),
                    url,
                    bytes.size.toLong(),
                    sha256(bytes),
                    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).hex(),
                    "Fixture release $code",
                ),
            )
    }

    /** Places the verified bytes where the installer expects the download. */
    fun place(): File = context.downloadFile(app).also { it.writeBytes(bytes) }
}

/** The app-op is granted by the Gradle task allowInstallSource (changing it here would kill the test process). */
fun requireInstallSource() {
    if (!context.packageManager.canRequestPackageInstalls())
        throw AssertionError("Run through Gradle (connectedDebugAndroidTest) so allowInstallSource grants the app-op first")
}

/** Simulates Android denying installs for the duration of [block]. */
fun <T> withInstallDenied(block: () -> T): T {
    InstallPermission.override = false
    try {
        return block()
    } finally {
        InstallPermission.override = null
    }
}

fun uninstallFixture() {
    shell("pm uninstall $FIXTURE_PACKAGE")
    waitUntil(10_000, "fixture removal") { context.installedVersion(FIXTURE_PACKAGE) == null }
}

fun clearDownloads() {
    File(context.filesDir, "downloads").listFiles()?.forEach { it.delete() }
}

fun ByteArray.corrupted(): ByteArray = copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }

fun ByteArrayOutputStream.textOf() = toString(Charsets.UTF_8)

val Int.seconds get() = TimeUnit.SECONDS.toMillis(this.toLong())
