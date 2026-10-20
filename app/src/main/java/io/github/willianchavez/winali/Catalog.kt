package io.github.willianchavez.winali

import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

fun InputStream.boundedBytes(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size() + count <= limit) { "Response too large" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

data class Release(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val architectures: List<String>,
    val url: String,
    val size: Long,
    val sha256: String,
    val certificateSha256: String,
    val changelog: String,
) {
    fun json() =
        JSONObject()
            .put("versionCode", versionCode)
            .put("versionName", versionName)
            .put("minSdk", minSdk)
            .put("architectures", JSONArray(architectures))
            .put("url", url)
            .put("size", size)
            .put("sha256", sha256)
            .put("certificateSha256", certificateSha256)
            .put("changelog", changelog)
}

data class CatalogText(
    val summary: String,
    val description: String,
    val category: String,
    val permissions: List<String>,
) {
    fun json() =
        JSONObject()
            .put("summary", summary)
            .put("description", description)
            .put("category", category)
            .put("permissions", JSONArray(permissions))
}

data class CatalogApp(
    val id: String,
    val packageName: String,
    val name: String,
    val summary: String,
    val description: String,
    val category: String,
    val repository: String,
    val screenshots: List<String>,
    val permissions: List<String>,
    val release: Release?,
    val english: CatalogText? = null,
    /** Optional HTTPS PNG shown in the store; apps without one use a neutral tile. */
    val icon: String? = null,
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("packageName", packageName)
            .put("name", name)
            .put("summary", summary)
            .put("description", description)
            .put("category", category)
            .put("repository", repository)
            .put("screenshots", JSONArray(screenshots))
            .put("permissions", JSONArray(permissions))
            .put("release", release?.json())
            .put("locales", english?.let { JSONObject().put("en", it.json()) })
            .put("icon", icon)

    fun localized(context: Context): CatalogApp =
        if (context.es() || english == null) this
        else
            copy(
                summary = english.summary,
                description = english.description,
                category = english.category,
                permissions = english.permissions,
            )

    fun compatible(
        sdk: Int = Build.VERSION.SDK_INT,
        abis: Array<String> = Build.SUPPORTED_ABIS,
    ) =
        release?.let {
            it.minSdk <= sdk &&
                (it.architectures.isEmpty() ||
                    it.architectures.contains("universal") ||
                    it.architectures.any { abi -> abi in abis })
        } ?: true
}

private fun JSONArray.strings() = (0 until length()).map { getString(it) }

/** HTTPS only; debug builds may also use http on the loopback interface for local test servers. */
fun secureUrl(value: String, allowLoopback: Boolean = false): Boolean = runCatching {
    val uri = URI(value)
    !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        (uri.scheme == "https" || loopbackAllowed(allowLoopback, uri.scheme, uri.host))
}
    .getOrDefault(false)

fun parseApp(value: JSONObject, allowLoopback: Boolean = BuildConfig.ALLOW_LOOPBACK): CatalogApp {
    val release =
        value.optJSONObject("release")?.let { r ->
            require(r.getLong("versionCode") > 0 && r.getLong("size") in 1..1_073_741_824L)
            require(secureUrl(r.getString("url"), allowLoopback))
            for (field in listOf("sha256", "certificateSha256")) require(
                Regex("[a-fA-F0-9]{64}").matches(r.getString(field)),
            ) {
                "Invalid $field"
            }
            Release(
                r.getLong("versionCode"),
                r.getString("versionName"),
                r.getInt("minSdk"),
                r.getJSONArray("architectures").strings(),
                r.getString("url"),
                r.getLong("size"),
                r.getString("sha256").lowercase(),
                r.getString("certificateSha256").lowercase(),
                r.getString("changelog"),
            )
        }
    require(Regex("[a-z][a-z0-9-]{0,63}").matches(value.getString("id")))
    require(
        Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
            .matches(value.getString("packageName")),
    )
    val repository = value.getString("repository")
    require(secureUrl(repository) && URI(repository).host == "github.com")
    val screenshots = value.getJSONArray("screenshots").strings()
    require(screenshots.all(::secureUrl))
    val icon = value.optString("icon").takeIf { it.isNotEmpty() }
    require(icon == null || secureUrl(icon))
    val english =
        value.optJSONObject("locales")?.optJSONObject("en")?.let {
            CatalogText(
                it.getString("summary"),
                it.getString("description"),
                it.getString("category"),
                it.getJSONArray("permissions").strings(),
            )
        }
    return CatalogApp(
        value.getString("id"),
        value.getString("packageName"),
        value.getString("name"),
        value.getString("summary"),
        value.getString("description"),
        value.getString("category"),
        repository,
        screenshots,
        value.getJSONArray("permissions").strings(),
        release,
        english,
        icon,
    )
}

fun parseCatalog(text: String, allowLoopback: Boolean = BuildConfig.ALLOW_LOOPBACK): List<CatalogApp> {
    val root = JSONObject(text)
    require(root.getInt("schemaVersion") == 1) { "Unsupported catalog version" }
    require(!root.has("revision") || root.getLong("revision") > 0) { "Invalid catalog revision" }
    val array = root.getJSONArray("apps")
    require(array.length() <= 1000)
    val apps = (0 until array.length()).map { parseApp(array.getJSONObject(it), allowLoopback) }
    require(
        apps.map { it.id }.distinct().size == apps.size &&
            apps.map { it.packageName }.distinct().size == apps.size,
    ) {
        "Duplicate application identity"
    }
    return apps
}

/**
 * The catalog source and its on-disk cache. [namespace] separates cache files and bookkeeping so
 * tests and alternative sources never touch the real cache.
 */
class CatalogStore(
    private val context: Context,
    private val url: String = BuildConfig.CATALOG_URL,
    private val namespace: String = "catalog",
) {
    companion object {
        private val cacheLock = Any()
        const val URL = "https://raw.githubusercontent.com/WillianChavez/winali/main/catalog/index.json"
    }

    private val file = File(context.filesDir, "$namespace.json")
    private val prefs = context.getSharedPreferences(namespace, 0)

    /** Epoch millis of the last successful network check, or 0. */
    fun lastCheck(): Long = prefs.getLong("lastCheck", 0)

    private fun cachedText(): String {
        val bundled = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        val saved = runCatching { file.readText().also { parseCatalog(it) } }.getOrNull()
        return if (
            saved != null &&
                JSONObject(saved).optLong("revision", 0) >=
                    JSONObject(bundled).optLong("revision", 0)
        )
            saved
        else bundled
    }

    fun cached(): List<CatalogApp> = parseCatalog(cachedText())

    fun refresh(force: Boolean = false): List<CatalogApp> =
        synchronized(cacheLock) { refreshLocked(force) }

    private fun refreshLocked(force: Boolean): List<CatalogApp> {
        if (!force && System.currentTimeMillis() - prefs.getLong("lastCheck", 0) < 900_000)
            return cached()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        if (file.exists())
            prefs.getString("etag", null)?.let {
                connection.setRequestProperty("If-None-Match", it)
            }
        if (force || !file.exists()) connection.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (connection.responseCode == 304) {
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                return cached()
            }
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val data = connection.inputStream.use { it.boundedBytes(2_000_000) }
            val text = data.toString(Charsets.UTF_8)
            val parsed =
                try {
                    parseCatalog(text)
                } catch (e: Exception) {
                    throw DistributionException(Failure.InvalidCatalog)
                }
            if (
                JSONObject(text).optLong("revision", 0) <
                    JSONObject(cachedText()).optLong("revision", 0)
            )
                return cached()
            val temp = File(context.filesDir, "$namespace.tmp")
            temp.writeText(text)
            java.nio.file.Files.move(
                temp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            prefs
                .edit()
                .putString("etag", connection.getHeaderField("ETag"))
                .putLong("lastCheck", System.currentTimeMillis())
                .apply()
            return parsed
        } finally {
            connection.disconnect()
        }
    }
}
