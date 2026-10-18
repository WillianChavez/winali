plugins {
 id("com.android.test")
 id("org.jetbrains.kotlin.android")
 id("androidx.baselineprofile")
}
android {
 namespace = "io.github.willianchavez.winali.baselineprofile"
 compileSdk = 36
 defaultConfig {
 minSdk = 28
 targetSdk = 36
 testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 // Profile generation is not timing-sensitive, so an emulator is acceptable.
 testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
 }
 targetProjectPath = ":app"
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
baselineProfile { useConnectedDevices = true }
dependencies {
 implementation("androidx.test.ext:junit:1.3.0")
 implementation("androidx.test.uiautomator:uiautomator:2.3.0")
 implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
