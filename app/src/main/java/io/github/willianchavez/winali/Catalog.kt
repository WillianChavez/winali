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

    fun compatible() =
        release?.let {
            it.minSdk <= Build.VERSION.SDK_INT &&
                (it.architectures.isEmpty() ||
                    it.architectures.contains("universal") ||
                    it.architectures.any { abi -> abi in Build.SUPPORTED_ABIS })
        } ?: true
}

private fun JSONArray.strings() = (0 until length()).map { getString(it) }

fun secureUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
}
    .getOrDefault(false)

fun parseApp(value: JSONObject): CatalogApp {
    val release =
        value.optJSONObject("release")?.let { r ->
            require(r.getLong("versionCode") > 0 && r.getLong("size") in 1..1_073_741_824L)
            require(secureUrl(r.getString("url")))
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
    )
}

fun parseCatalog(text: String): List<CatalogApp> {
    val root = JSONObject(text)
    require(root.getInt("schemaVersion") == 1) { "Unsupported catalog version" }
    val array = root.getJSONArray("apps")
    require(array.length() <= 1000)
    val apps = (0 until array.length()).map { parseApp(array.getJSONObject(it)) }
    require(
        apps.map { it.id }.distinct().size == apps.size &&
            apps.map { it.packageName }.distinct().size == apps.size,
    ) {
        "Duplicate application identity"
    }
    return apps
}

class CatalogStore(private val context: Context) {
    companion object {
        const val URL =
            "https://raw.githubusercontent.com/WillianChavez/winali/main/catalog/index.json"
    }

    private val file = File(context.filesDir, "catalog.json")
    private val prefs = context.getSharedPreferences("catalog", 0)

    fun cached(): List<CatalogApp> = runCatching {
        if (file.exists()) parseCatalog(file.readText())
        else
            parseCatalog(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
    }
        .getOrElse {
            parseCatalog(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
        }

    fun refresh(force: Boolean = false): List<CatalogApp> {
        if (!force && System.currentTimeMillis() - prefs.getLong("lastCheck", 0) < 900_000)
            return cached()
        val connection = URL(URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        prefs.getString("etag", null)?.let { connection.setRequestProperty("If-None-Match", it) }
        try {
            if (connection.responseCode == 304) {
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                return cached()
            }
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val data = connection.inputStream.use { it.boundedBytes(2_000_000) }
            val text = data.toString(Charsets.UTF_8)
            val parsed = parseCatalog(text)
            val temp = File(context.filesDir, "catalog.tmp")
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
