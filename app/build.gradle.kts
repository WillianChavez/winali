plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
 
}
android {
 namespace = "io.github.willianchavez.winali"
 compileSdk = 36
 defaultConfig {
 applicationId = "io.github.willianchavez.winali"
 minSdk = 26
 targetSdk = 36
 versionCode = (System.getenv("VERSION_CODE") ?: "1001").toInt()
 versionName = System.getenv("VERSION_NAME") ?: "0.1.1"
 buildConfigField("String", "CATALOG_URL", "\"https://raw.githubusercontent.com/WillianChavez/winali/main/catalog/index.json\"")
 testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 }
 signingConfigs { create("distribution") {
 val keyPath = System.getenv("SIGNING_KEYSTORE")
 if (keyPath != null) { storeFile = file(keyPath); storePassword = System.getenv("SIGNING_STORE_PASSWORD"); keyAlias = System.getenv("SIGNING_KEY_ALIAS"); keyPassword = System.getenv("SIGNING_KEY_PASSWORD") }
 } }
 buildTypes {
  // Debug builds can read a local catalog for end-to-end update tests; release builds always use the public one.
  debug { System.getenv("WINALI_CATALOG_URL")?.let { buildConfigField("String", "CATALOG_URL", "\"$it\"") } }
  release { isMinifyEnabled = false; if (System.getenv("SIGNING_KEYSTORE") != null) signingConfig = signingConfigs.getByName("distribution") }
 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.10.00"))
 implementation("androidx.activity:activity-compose:1.11.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.compose.ui:ui-tooling-preview")
 debugImplementation("androidx.compose.ui:ui-tooling")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
 implementation("androidx.work:work-runtime-ktx:2.10.5")
 implementation("androidx.documentfile:documentfile:1.1.0")
 implementation("androidx.core:core-ktx:1.17.0")
 
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 androidTestImplementation("androidx.test.ext:junit:1.3.0")
 androidTestImplementation("androidx.test:runner:1.7.0")
 androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
 androidTestImplementation("androidx.work:work-testing:2.10.5")
 androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.00"))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Installation tests drive the real PackageInstaller, which needs the "install unknown apps" app-op.
// Changing it inside a running instrumentation kills the process, so it is granted beforehand.
val adbExecutable = android.sdkDirectory.resolve("platform-tools/adb" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
val allowInstallSource = tasks.register<Exec>("allowInstallSource") {
 dependsOn("installDebug")
 commandLine(adbExecutable.path, "shell", "appops", "set", "io.github.willianchavez.winali", "REQUEST_INSTALL_PACKAGES", "allow")
}
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach { dependsOn(allowInstallSource) }
