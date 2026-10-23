package io.github.willianchavez.winali

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogParsingTest {
    private fun catalog() = JSONObject(bundledCatalogText())

    private fun parse(json: JSONObject) = parseCatalog(json.toString(), allowLoopback = false)

    private fun mutated(change: (JSONObject) -> Unit): JSONObject =
        catalog().also { change(it.getJSONArray("apps").getJSONObject(0)) }

    private fun rejected(json: JSONObject) {
        try {
            parse(json)
            fail("Expected the catalog to be rejected")
        } catch (_: Exception) {}
    }

    @Test fun bundledCatalogHasEveryAppWithReleases() {
        val apps = parse(catalog())
        assertEquals(setOf("apk", "focus", "read", "winali", "deck", "books"), apps.map { it.id }.toSet())
        assertTrue(apps.all { it.release != null && it.permissions.isNotEmpty() })
    }

    @Test fun releaseSerializationRoundTrips() {
        val original = parse(catalog()).first()
        val again = parseApp(original.json(), allowLoopback = false)
        assertEquals(original, again)
    }

    @Test fun unsupportedSchemaOrRevisionIsRejected() {
        rejected(catalog().put("schemaVersion", 2))
        rejected(catalog().put("revision", 0))
        rejected(catalog().put("revision", -4))
    }

    @Test fun insecureOrMalformedReleaseFieldsAreRejected() {
        rejected(mutated { it.getJSONObject("release").put("url", "http://example.com/app.apk") })
        rejected(mutated { it.getJSONObject("release").put("sha256", "abc") })
        rejected(mutated { it.getJSONObject("release").put("certificateSha256", "z".repeat(64)) })
        rejected(mutated { it.getJSONObject("release").put("size", 0) })
        rejected(mutated { it.getJSONObject("release").put("size", 2_000_000_000L) })
        rejected(mutated { it.getJSONObject("release").put("versionCode", 0) })
    }

    @Test fun identityRepositoryAndScreenshotsAreValidated() {
        rejected(mutated { it.put("packageName", "nodots") })
        rejected(mutated { it.put("id", "Bad Id") })
        rejected(mutated { it.put("repository", "https://example.com/owner/repo") })
        rejected(mutated { it.getJSONArray("screenshots").put("http://example.com/a.png") })
        rejected(catalog().also { it.getJSONArray("apps").put(it.getJSONArray("apps").getJSONObject(0)) })
    }

    @Test fun malformedDocumentsAreRejected() {
        for (text in listOf("", "not json", "[]", "{}", "{\"schemaVersion\":1}")) {
            try {
                parseCatalog(text, allowLoopback = false)
                fail("Expected rejection of: $text")
            } catch (_: Exception) {}
        }
    }

    @Test fun unpublishedAppsParseWithoutRelease() {
        val apps = parse(mutated { it.put("release", JSONObject.NULL) })
        assertNull(apps.first().release)
    }

    @Test fun loopbackHttpIsOnlyAcceptedWhenExplicitlyAllowed() {
        assertTrue(secureUrl("http://127.0.0.1:8099/app.apk", allowLoopback = true))
        assertTrue(secureUrl("http://localhost:8099/app.apk", allowLoopback = true))
        assertFalse(secureUrl("http://127.0.0.1:8099/app.apk", allowLoopback = false))
        assertFalse(secureUrl("http://example.com/app.apk", allowLoopback = true))
        assertFalse(secureUrl("https://user:pw@example.com/app.apk", allowLoopback = true))
        assertTrue(secureUrl("https://example.com/app.apk"))
        val local = mutated { it.getJSONObject("release").put("url", "http://127.0.0.1:8099/x.apk") }
        rejected(local)
        assertNotNull(parseCatalog(local.toString(), allowLoopback = true).first().release)
    }

    @Test fun compatibilityChecksSdkAndAbi() {
        val abis = arrayOf("x86_64", "x86")
        assertTrue(app(release = release(minSdk = 26)).compatible(26, abis))
        assertFalse(app(release = release(minSdk = 27)).compatible(26, abis))
        assertTrue(app(release = release(architectures = listOf("x86_64"))).compatible(30, abis))
        assertFalse(app(release = release(architectures = listOf("arm64-v8a"))).compatible(30, abis))
        assertTrue(app(release = release(architectures = emptyList())).compatible(30, abis))
        assertTrue(app(release = null).compatible(1, abis))
    }

    @Test fun appIconIsOptionalButMustBeHttps() {
        val apps = parse(catalog()).associateBy { it.id }
        assertEquals("https://raw.githubusercontent.com/WillianChavez/winali/main/docs/icons/deck.png", apps.getValue("deck").icon)
        assertEquals("https://raw.githubusercontent.com/WillianChavez/winali/main/docs/icons/apk.png", apps.getValue("apk").icon)
        assertEquals("https://raw.githubusercontent.com/WillianChavez/winali/main/docs/icons/focus.png", apps.getValue("focus").icon)
        assertEquals("https://raw.githubusercontent.com/WillianChavez/winali/main/docs/icons/winali.png", apps.getValue("winali").icon)
        rejected(mutated { it.put("icon", "http://example.com/icon.png") })
        val again = parseApp(apps.getValue("deck").json(), allowLoopback = false)
        assertEquals(apps.getValue("deck"), again)
    }
}
