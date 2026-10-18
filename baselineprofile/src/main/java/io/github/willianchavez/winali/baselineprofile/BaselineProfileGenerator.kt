package io.github.willianchavez.winali.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the screens a user opens in the first minute: the catalog, a listing, the Updates tab and
 * Settings. `./gradlew :app:generateReleaseBaselineProfile` records the classes and methods used.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startupAndMainJourneys() =
        rule.collect(packageName = "io.github.willianchavez.winali", includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("APK")), 15_000)
            device.findObject(By.text("APK"))?.click()
            device.wait(Until.hasObject(By.text("Permisos")), 5_000)
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
            device.waitForIdle()
            device.pressBack()
            device.wait(Until.hasObject(By.text("Actualizaciones")), 5_000)
            device.findObject(By.text("Actualizaciones"))?.click()
            device.waitForIdle()
            device.findObject(By.text("Ajustes"))?.click()
            device.waitForIdle()
            device.findObject(By.text("Explorar"))?.click()
            device.waitForIdle()
        }
}
