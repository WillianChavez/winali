package io.github.willianchavez.winali

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The real activity with the bundled catalog: navigation, listing, detail and back. */
class MainActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var restoreLanguage: () -> Unit
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        restoreLanguage = setLanguage("es")
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        restoreLanguage()
    }

    @Test
    fun exploreOpensADetailPageAndBack() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("card-apk").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("card-apk").assertIsDisplayed()
        rule.onNodeWithTag("card-winali").assertDoesNotExist()
        rule.onNodeWithTag("card-apk").performClick()
        rule.onNodeWithTag("detail-apk").assertIsDisplayed()
        rule.onNodeWithTag("action-apk").assertIsDisplayed()
        rule.onNodeWithTag("detail-apk").performScrollToNode(hasTestTag("repository"))
        rule.onNodeWithTag("repository").assertIsDisplayed()
        rule.onNodeWithContentDescription("Volver").performClick()
        rule.onNodeWithTag("explore").assertIsDisplayed()
    }

    @Test
    fun theThreeTabsAreReachable() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("tab-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tab-1").performClick()
        rule.onNodeWithTag("updates").assertIsDisplayed()
        rule.onNodeWithTag("tab-2").performClick()
        rule.onNodeWithTag("settings").assertIsDisplayed()
        rule.onNodeWithText("Winali ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        rule.onNodeWithTag("tab-0").performClick()
        rule.onNodeWithTag("explore").assertIsDisplayed()
    }
}
