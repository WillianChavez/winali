package io.github.willianchavez.winali

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private class Recorder : StoreActions {
    val log = mutableListOf<String>()

    override fun primary(app: CatalogApp) { log += "primary:${app.id}" }

    override fun cancel(app: CatalogApp) { log += "cancel:${app.id}" }

    override fun retry(app: CatalogApp) { log += "retry:${app.id}" }

    override fun updateAll() { log += "updateAll" }

    override fun refresh() { log += "refresh" }
}

/** Screens driven with plain state, in both languages, both themes and large text. */
class StoreScreenTest {
    @get:Rule val rule = createComposeRule()
    private val prefs = context.getSharedPreferences("appearance", 0)
    private var originalTheme: String? = null
    private lateinit var restoreLanguage: () -> Unit
    private val actions = Recorder()
    private val opened = mutableListOf<String>()
    private val checks = mutableListOf<Boolean>()

    private val apk =
        CatalogApp(
            "apk", "io.github.willianchavez.apk", "APK", "Extrae tus apps.", "Busca apps instaladas y extráelas.",
            "Utilidades", "https://github.com/WillianChavez/apk", emptyList(),
            listOf("Consultar las aplicaciones instaladas", "Guardar archivos en la carpeta que elijas"),
            Release(1001, "0.1.1", 26, listOf("arm64-v8a", "x86_64"), "https://example.com/apk.apk", 11_872_934, "a".repeat(64), "b".repeat(64), "Corrige la extracción de paquetes divididos."),
        )
    private val focus = apk.copy(id = "focus", packageName = "io.github.willianchavez.focus", name = "Focus", category = "Productividad", summary = "Organiza tus tareas.", release = apk.release!!.copy(versionName = "0.2.0", versionCode = 1002))
    private val winali = apk.copy(id = "winali", packageName = "io.github.willianchavez.winali", name = "Winali", category = "Tienda", summary = "Tu tienda.")

    @Before
    fun setUp() {
        originalTheme = prefs.getString("theme", null)
        restoreLanguage = setLanguage("es")
        prefs.edit().putString("theme", "light").commit()
    }

    @After
    fun tearDown() {
        restoreLanguage()
        if (originalTheme == null) prefs.edit().remove("theme").commit()
        else prefs.edit().putString("theme", originalTheme).commit()
    }

    private fun show(state: StoreState, fontScale: Float? = null, requestedTab: Int? = null) {
        val full = state.copy(compatible = { it.release?.minSdk != 99 }, spanish = context.es())
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
            ) {
                FamilyTheme(context) {
                    StoreScreen(
                        state = full,
                        actions = actions,
                        settings =
                            SettingsModel(
                                checksEnabled = true,
                                theme = "light",
                                setChecks = { checks += it },
                                setTheme = {},
                                toggleLanguage = {},
                                clearDownloads = {},
                                openUrl = { opened += it },
                            ),
                        snackbar = remember { SnackbarHostState() },
                        requestedTab = requestedTab,
                    )
                }
            }
        }
    }

    private fun scrollDetailTo(tag: String) {
        rule.onNodeWithTag("detail-apk").performScrollToNode(hasTestTag(tag))
    }

    private fun state(vararg apps: CatalogApp, build: StoreState.() -> StoreState = { this }) =
        StoreState(apps = apps.toList()).build()

    @Test
    fun exploreListsPublishedAppsAndKeepsWinaliOutOfTheCatalogList() {
        show(state(apk, focus, winali))
        rule.onNodeWithTag("card-apk").assertIsDisplayed()
        rule.onNodeWithTag("card-focus").assertIsDisplayed()
        rule.onNodeWithTag("card-winali").assertDoesNotExist()
        rule.onAllNodesWithText("Explorar").assertCountEquals(2) // title and navigation label
    }

    @Test
    fun searchFiltersByNameAndShowsAnEmptyResult() {
        show(state(apk, focus))
        rule.onNodeWithTag("search").performTextInput("Foc")
        rule.onNodeWithTag("card-focus").assertIsDisplayed()
        rule.onNodeWithTag("card-apk").assertDoesNotExist()
        rule.onNodeWithTag("search").performTextInput("zzz")
        rule.onNodeWithText("No hay resultados").assertIsDisplayed()
    }

    @Test
    fun theDetailPageHasEveryFieldOfTheListing() {
        show(state(apk, build = { copy(installed = mapOf(apk.packageName to 1000L), installedNames = mapOf(apk.packageName to "0.1.0")) }))
        rule.onNodeWithTag("card-apk").performClick()
        rule.onNodeWithTag("detail-apk").assertIsDisplayed()
        rule.onNodeWithText("Busca apps instaladas y extráelas.").assertExists()
        rule.onNodeWithText("Corrige la extracción de paquetes divididos.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("11,3 MB").assertExists()
        rule.onAllNodesWithText("0.1.1").assertCountEquals(2) // version metric and release notes heading
        rule.onNodeWithTag("installed-version").assertTextContains("0.1.0", substring = true)
        rule.onNodeWithText("Consultar las aplicaciones instaladas", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Guardar archivos en la carpeta que elijas", substring = true).assertExists()
        scrollDetailTo("compatibility")
        rule.onNodeWithTag("compatibility").assertIsDisplayed()
        rule.onNodeWithText("Requiere Android 8.0 o superior").assertExists()
        rule.onNodeWithText("Arquitecturas: arm64-v8a, x86_64").assertExists()
        rule.onNodeWithText("Compatible con este dispositivo").assertExists()
        scrollDetailTo("repository")
        rule.onNodeWithTag("repository").performClick()
        assertEquals(listOf("https://github.com/WillianChavez/apk"), opened)
    }

    @Test
    fun anAppWithoutPermissionsSaysSo() {
        show(state(apk.copy(permissions = emptyList())))
        rule.onNodeWithTag("card-apk").performClick()
        rule.onNodeWithText("Sin permisos especiales").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anIncompatibleAppExplainsWhyAndCannotBeInstalled() {
        val future = apk.copy(release = apk.release!!.copy(minSdk = 99))
        show(state(future))
        rule.onNodeWithTag("card-apk").performClick()
        rule.onNodeWithTag("action-apk").assertTextContains("No compatible").assertIsNotEnabled()
        rule.onNodeWithText("No es compatible con este dispositivo").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Requiere Android (API 99) o superior").assertExists()
    }

    private fun label(build: StoreState.() -> StoreState, expected: String, app: CatalogApp = apk, substring: Boolean = false) {
        show(state(app, build = build))
        rule.onNodeWithTag("action-apk").assertTextContains(expected, substring = substring)
    }

    @Test fun installLabel() = label({ this }, "Instalar")

    @Test fun updateLabel() = label({ copy(installed = mapOf(apk.packageName to 1000L)) }, "Actualizar")

    @Test fun openLabel() = label({ copy(installed = mapOf(apk.packageName to 1001L)) }, "Abrir")

    @Test fun installDownloadLabel() = label({ copy(downloaded = setOf(apk.packageName)) }, "Instalar descarga")

    @Test fun retryLabel() = label({ copy(downloads = mapOf(apk.packageName to DownloadStatus.Failed(Failure.Network))) }, "Reintentar")

    @Test fun installingLabel() =
        label({ copy(downloaded = setOf(apk.packageName), phases = mapOf(apk.packageName to InstallPhase.Installing)) }, "Instalando", substring = true)

    @Test fun confirmLabel() =
        label({ copy(downloaded = setOf(apk.packageName), phases = mapOf(apk.packageName to InstallPhase.AwaitingConfirmation)) }, "Confirmar instalación")

    @Test fun comingSoonLabel() = label({ this }, "Próximamente", apk.copy(release = null))

    @Test
    fun theSameStatesReadCorrectlyInEnglish() {
        prefs.edit().putString("language", "en").commit()
        show(state(apk, build = { copy(installed = mapOf(apk.packageName to 1000L)) }))
        rule.onNodeWithTag("action-apk").assertTextContains("Update")
        rule.onNodeWithText("Updates").assertExists()
        rule.onNodeWithTag("card-apk").performClick()
        rule.onNodeWithText("What's new").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Permissions").assertExists()
        rule.onNodeWithText("Compatible with this device").assertExists()
        rule.onNodeWithText("11.3 MB").assertExists()
    }

    @Test
    fun aRunningDownloadShowsProgressSizeAndCancels() {
        val running = DownloadStatus.Running(42, 5_000_000, 11_872_934)
        show(state(apk, build = { copy(downloads = mapOf(apk.packageName to running)) }))
        rule.onNodeWithTag("progress-apk", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("42% · 4,8 MB / 11,3 MB", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("action-apk").assertTextContains("Cancelar").performClick()
        assertEquals(listOf("cancel:apk"), actions.log)
    }

    @Test
    fun aQueuedDownloadSaysSoAndCancels() {
        show(state(apk, build = { copy(downloads = mapOf(apk.packageName to DownloadStatus.Queued)) }))
        rule.onNodeWithText("En cola").assertIsDisplayed()
        rule.onNodeWithTag("action-apk").performClick()
        assertEquals(listOf("cancel:apk"), actions.log)
    }

    @Test
    fun theDetailPageKeepsProgressAndShowsTheFailureReason() {
        show(state(apk, build = { copy(downloads = mapOf(apk.packageName to DownloadStatus.Failed(Failure.InsufficientStorage))) }))
        rule.onNodeWithTag("card-apk").performClick()
        rule.onNodeWithTag("problem-apk").assertTextContains(Failure.InsufficientStorage.message(true))
        rule.onNodeWithTag("action-apk").assertTextContains("Reintentar").performClick()
        assertEquals(listOf("primary:apk"), actions.log)
    }

    @Test
    fun aSignatureMismatchIsExplainedWithoutOfferingToUninstall() {
        show(
            state(apk, build = {
                copy(
                    installed = mapOf(apk.packageName to 1000L),
                    downloaded = setOf(apk.packageName),
                    failures = mapOf(apk.packageName to (Failure.SignatureMismatch to null)),
                )
            }),
        )
        rule.onNodeWithTag("card-apk").performClick()
        rule.onNodeWithTag("problem-apk").assertTextContains("no la desinstalará", substring = true)
        rule.onNodeWithText("Desinstalar").assertDoesNotExist()
    }

    @Test
    fun theUpdatesTabListsUpdatesWithVersionsAndUpdatesEachOrAll() {
        val installed = mapOf(apk.packageName to 1000L, focus.packageName to 1001L)
        val names = mapOf(apk.packageName to "0.1.0", focus.packageName to "0.1.1")
        show(state(apk, focus, build = { copy(installed = installed, installedNames = names) }))
        rule.onNodeWithTag("tab-1").performClick()
        rule.onNodeWithText("2 actualizaciones disponibles").assertIsDisplayed()
        rule.onNodeWithText("0.1.0 → 0.1.1 · 11,3 MB").assertIsDisplayed()
        rule.onNodeWithText("0.1.1 → 0.2.0 · 11,3 MB").assertIsDisplayed()
        rule.onAllNodesWithText("Corrige la extracción de paquetes divididos.").assertCountEquals(2)
        rule.onNode(hasTestTag("action-focus")).performClick()
        rule.onNodeWithTag("update-all").performClick()
        assertEquals(listOf("primary:focus", "updateAll"), actions.log)
    }

    @Test
    fun theUpdatesTabShowsTheDownloadQueueWithRetry() {
        val downloads = mapOf(
            apk.packageName to DownloadStatus.Running(10, 1_000_000, 11_872_934),
            focus.packageName to DownloadStatus.Failed(Failure.Corrupt),
        )
        show(state(apk, focus, build = { copy(downloads = downloads) }), requestedTab = 1)
        rule.onNodeWithText("Descargas").assertIsDisplayed()
        rule.onNodeWithTag("cancel-apk").performClick()
        rule.onNodeWithText(Failure.Corrupt.message(true)).assertIsDisplayed()
        rule.onNodeWithTag("retry-focus").performClick()
        assertEquals(listOf("cancel:apk", "retry:focus"), actions.log)
    }

    @Test
    fun withNothingToUpdateTheTabSaysEverythingIsCurrentAndWhenItWasChecked() {
        show(state(apk, build = { copy(installed = mapOf(apk.packageName to 1001L), lastCheck = System.currentTimeMillis() - 5 * 60_000) }), requestedTab = 1)
        rule.onNodeWithText("Todo está al día").assertIsDisplayed()
        rule.onNodeWithText("Última comprobación", substring = true).assertIsDisplayed()
    }

    @Test
    fun updatesAreBadgedOnTheNavigationBar() {
        show(state(apk, build = { copy(installed = mapOf(apk.packageName to 1000L)) }))
        rule.onNodeWithTag("tab-1").assertIsDisplayed()
        rule.onNode(hasText("1"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun settingsExposeTheTwelveHourSwitch() {
        show(state(apk), requestedTab = 2)
        rule.onNodeWithText("Buscar actualizaciones cada 12 horas").assertIsDisplayed()
        rule.onNodeWithTag("checks-switch").performClick()
        assertEquals(listOf(false), checks)
        rule.onNodeWithText("Winali ${BuildConfig.VERSION_NAME}").assertExists()
    }

    @Test
    fun enlargedTextKeepsEveryActionReachable() {
        show(state(apk, focus, build = { copy(downloaded = setOf(apk.packageName), installed = mapOf(focus.packageName to 1000L)) }), fontScale = 2f)
        rule.onNodeWithTag("action-apk").assertIsDisplayed().assertTextContains("Instalar descarga")
        rule.onNodeWithTag("action-focus").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("card-apk").performClick()
        rule.onNodeWithTag("action-apk").assertIsDisplayed()
        scrollDetailTo("repository")
        rule.onNodeWithTag("repository").assertIsDisplayed()
    }

    @Test
    fun theDarkThemeIsActuallyDark() {
        prefs.edit().putString("theme", "dark").commit()
        var background = 1f
        rule.setContent {
            FamilyTheme(context) { background = MaterialTheme.colorScheme.background.luminance() }
        }
        rule.waitForIdle()
        assertTrue("background luminance $background", background < 0.1f)
    }

    @Test
    fun theLightThemeIsActuallyLight() {
        var background = 0f
        rule.setContent {
            FamilyTheme(context) { background = MaterialTheme.colorScheme.background.luminance() }
        }
        rule.waitForIdle()
        assertTrue("background luminance $background", background > 0.8f)
    }
}
