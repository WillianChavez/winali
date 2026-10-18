package io.github.willianchavez.winali

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun prefersNewerBundledOrCachedCatalogAndRecoversFromCorruption() {
        val bundled = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        val expected = parseCatalog(bundled).first().name
        val file = File(context.filesDir, "catalog.json")
        val original = file.takeIf { it.exists() }?.readBytes()
        try {
            val old = JSONObject(bundled)
            old.remove("revision")
            old.getJSONArray("apps").getJSONObject(0).put("name", "Old cache")
            file.writeText(old.toString())
            assertEquals(expected, CatalogStore(context).cached().first().name)
            val newer =
                JSONObject(bundled).put("revision", JSONObject(bundled).optLong("revision", 0) + 1)
            newer.getJSONArray("apps").getJSONObject(0).put("name", "New cache")
            file.writeText(newer.toString())
            assertEquals("New cache", CatalogStore(context).cached().first().name)
            file.writeText("broken JSON")
            assertEquals(expected, CatalogStore(context).cached().first().name)
        } finally {
            if (original == null) file.delete() else file.writeBytes(original)
        }
    }

    @Test
    fun englishCatalogTextIsLocalizedWithoutChangingAppIdentity() {
        val app = CatalogStore(context).cached().first()
        val english = app.english!!
        val prefs = context.getSharedPreferences("appearance", 0)
        val original = prefs.getString("language", null)
        try {
            prefs.edit().putString("language", "en").commit()
            val localized = app.localized(context)
            assertEquals(english.summary, localized.summary)
            assertEquals(english.permissions, localized.permissions)
            assertEquals(app.packageName, localized.packageName)
            assertEquals(app.release, localized.release)
        } finally {
            if (original == null) prefs.edit().remove("language").commit()
            else prefs.edit().putString("language", original).commit()
        }
    }
}
