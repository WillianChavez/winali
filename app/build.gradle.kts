plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
 id("androidx.baselineprofile")
}
android {
 namespace = "io.github.willianchavez.winali"
 compileSdk = 36
 defaultConfig {
 applicationId = "io.github.willianchavez.winali"
 minSdk = 26
 targetSdk = 36
 versionCode = (System.getenv("VERSION_CODE") ?: "1002").toInt()
 versionName = System.getenv("VERSION_NAME") ?: "0.1.2"
 buildConfigField("String", "CATALOG_URL", "\"https://raw.githubusercontent.com/WillianChavez/winali/main/catalog/index.json\"")
 buildConfigField("boolean", "ALLOW_LOOPBACK", "false")
 testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 }
 signingConfigs { create("distribution") {
 val keyPath = System.getenv("SIGNING_KEYSTORE")
 if (keyPath != null) { storeFile = file(keyPath); storePassword = System.getenv("SIGNING_STORE_PASSWORD"); keyAlias = System.getenv("SIGNING_KEY_ALIAS"); keyPassword = System.getenv("SIGNING_KEY_PASSWORD") }
 } }
 buildTypes {
  // Debug and the unpublished "stage" type may read a local catalog over loopback HTTP for end-to-end
  // update tests; the published release always uses the public catalog over HTTPS.
  debug {
   buildConfigField("boolean", "ALLOW_LOOPBACK", "true")
   System.getenv("WINALI_CATALOG_URL")?.let { buildConfigField("String", "CATALOG_URL", "\"$it\"") }
  }
  release {
   isMinifyEnabled = true
   isShrinkResources = true
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
   if (System.getenv("SIGNING_KEYSTORE") != null) signingConfig = signingConfigs.getByName("distribution")
  }
  // The exact release code (same R8 and resource shrinking), debug-signed and pointed at a local server.
  create("stage") {
   initWith(getByName("release"))
   matchingFallbacks += "release"
   signingConfig = signingConfigs.getByName("debug")
   buildConfigField("boolean", "ALLOW_LOOPBACK", "true")
   System.getenv("WINALI_CATALOG_URL")?.let { buildConfigField("String", "CATALOG_URL", "\"$it\"") }
  }
 }
 // The interface is Spanish and English only; drop the other languages libraries bring along.
 androidResources { localeFilters += listOf("en", "es") }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.10.00"))
 implementation("androidx.activity:activity-compose:1.11.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-core")
 implementation("androidx.work:work-runtime-ktx:2.10.5")
 implementation("androidx.core:core-ktx:1.17.0")
 // Installs the packaged baseline profile on devices where Play does not (side-loaded, store-installed).
 implementation("androidx.profileinstaller:profileinstaller:1.4.1")
 baselineProfile(project(":baselineprofile"))

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
