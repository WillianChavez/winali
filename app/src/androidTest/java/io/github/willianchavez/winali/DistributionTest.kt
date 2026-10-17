package io.github.willianchavez.winali

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class DistributionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    private fun fixture(): Pair<CatalogApp, File> {
        val info =
            context.packageManager.getPackageInfo(
                context.packageName,
                if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                else PackageManager.GET_SIGNATURES,
            )
        val signature =
            if (android.os.Build.VERSION.SDK_INT >= 28)
                info.signingInfo!!.apkContentsSigners.single()
            else info.signatures!!.single()
        val source = File(info.applicationInfo!!.sourceDir)
        val file = File(context.cacheDir, "verification.apk")
        source.copyTo(file, true)
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex()
        val certificate = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).hex()
        val version = context.installedVersion(context.packageName)!!
        return CatalogApp(
            "winali",
            context.packageName,
            "Winali",
            "Test",
            "Test",
            "Store",
            "https://github.com/WillianChavez/winali",
            emptyList(),
            emptyList(),
            Release(
                version,
                "test",
                26,
                listOf("universal"),
                "https://example.com/app.apk",
                file.length(),
                digest,
                certificate,
                "Test",
            ),
        ) to file
    }

    @Test
    fun verifiesActualInstalledApkIdentityAndCertificate() {
        val (app, file) = fixture()
        try {
            context.verifyDownload(app, file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsTruncationPackageAndCertificateSubstitution() {
        val (app, file) = fixture()
        try {
            for (invalid in
                listOf(
                    app.copy(packageName = "other.package"),
                    app.copy(release = app.release!!.copy(certificateSha256 = "0".repeat(64))),
                    app.copy(release = app.release!!.copy(sha256 = "0".repeat(64))),
                )) {
                try {
                    context.verifyDownload(invalid, file)
                    fail("Expected integrity rejection")
                } catch (_: IllegalArgumentException) {}
            }
            file.writeText("truncated")
            try {
                context.verifyDownload(app, file)
                fail("Expected length rejection")
            } catch (_: IllegalArgumentException) {}
        } finally {
            file.delete()
        }
    }

    @Test
    fun bundledCatalogRejectsDuplicatesAndInsecureUrls() {
        val text = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        assertEquals(3, parseCatalog(text).size)
        val json = org.json.JSONObject(text)
        val list = json.getJSONArray("apps")
        list.put(list.getJSONObject(0))
        try {
            parseCatalog(json.toString())
            fail("Expected duplicate rejection")
        } catch (_: IllegalArgumentException) {}
        assertFalse(secureUrl("http://example.com/app.apk"))
        assertFalse(secureUrl("https://user:password@example.com/app.apk"))
    }
}
