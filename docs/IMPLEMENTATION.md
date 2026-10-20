# Winali implementation ledger

The agreed target is three public, independently signed Android apps: Winali, APK, Focus. Original Kotlin/Compose code, MIT license, no login/backend/ads. Blue App Store inspired design with familiar Play Store navigation. Android 8 minimum; Spanish/English; dark/light/system.

## Required delivery gates

- [x] Toolchain: apktool resources through REA; JDK; SDK; Gradle; signing; emulator
- [ ] REA feature inventory, evidence and reference resource mapping (static evidence is not runtime proof)
- [x] Three independently buildable public repositories and original adaptive icons
- [x] Winali explore/search/categories/detail/screenshots/permissions/changelog/source
- [x] Catalog v1 schema, validation, synchronization, examples and publishing documentation
- [x] Persistent download queue, integrity/package/version/signature checks, PackageInstaller results and recovery
- [x] Installed state, updates, 12-hour checks, notifications, self-update and offline catalog (verified with debug-signed builds; the signed 0.1.1 Release is still pending, see below)
- [x] APK list/search/sort/system filters/details/selection/batch extraction/share/icons/naming/SAF/split bundles (verified on API 36 only; see evidence)
- [ ] Focus tasks/subtasks/notes/priority/dates/recurrence/reminders/estimates
- [ ] Focus projects/folders/tags/search/filters/completed/calendar
- [ ] Focus persistent timer, breaks/cycles/continuous/fullscreen/sounds/vibration/ambient
- [ ] Focus editable history/goals/reports/forest/achievements
- [ ] Focus seven widget categories and quick-add tile
- [ ] Focus optional distraction controls/silencing with permission-denied behavior and accessible exit
- [x] Focus versioned backup/restore/report export; Room schemas/migrations
- [ ] Release CI, three protected signing keys, signed APKs and public Releases
- [ ] Unit/instrumentation/runtime tests, screenshots and acceptance audit

Reference artifacts stay outside version control. Do not mark a delivery gate complete from a scaffold or a compile alone.

## Current evidence (2026-10-17)

- Public repositories: `WillianChavez/winali`, `WillianChavez/apk`, `WillianChavez/focus`; initial GitHub build/test/lint jobs succeeded in all three.
- Local tools: Apktool 3.0.3 through the REA CLI (resource decode succeeded), REA 6.4.0, existing JDK 21, Gradle 8.13, SDK/platform 36, Build Tools 35/36, ADB, accelerated headless Android 16 emulator.
- Catalog: nine Node tests pass; JSON Schema and sync/publishing tools are present. Revision publishing is idempotent and changes monotonically; English catalog text is supported.
- Winali (superseded by the 2026-10-18 section below): six device tests pass, including verification of an actual installed APK, independent queued download cancellation, catalog cache precedence/corruption recovery, and English identity preservation. Build and lint pass. The earlier four-test store suite also passed in GitHub emulators on API 26, 31, 34 and 36.
- APK (2026-10-18, local, API 36 emulator): 17 unit tests and 34 device tests pass, with assembleDebug, assembleRelease and lintDebug (0 errors). Covered: user/system filter, accent-insensitive search, sorting by name/size/install date/update date in both directions, multi-selection, detail fields (package, version, version code, UID, size, dates, install source, min/target SDK, granted permissions), open app, batch extraction with duplicate-name handling and cancellation cleanup, single/multi share, icon PNG export, configurable file-name pattern, and split apps exported as `.apks` (base.apk + splits/) byte-exact and unsigned. Instrumented SAF writes use a debug-only DocumentsProvider with a self-granted tree URI.
- APK release optimization (same day): R8 minify + resource shrinking with rules, `material-icons-extended` replaced by six own vectors, unused dependencies removed (ui-tooling, ui-tooling-preview, lifecycle-runtime-ktx, lifecycle-viewmodel-compose, ksp plugin), Baseline Profile generated with a `:baselineprofile` module on the emulator (19.6k rules, committed under `app/src/release/generated`) plus profileinstaller for sideloaded installs. Signed release APK 11,938,736 B -> 1,360,227 B (-88.6%); classes 18,006 -> 2,732; uncompressed contents 43.9 MB -> 2.5 MB. Cold start (`am start -W` TotalTime, 24 runs per state, interleaved, shared emulator under load): median 728 ms -> 649 ms (min 570 -> 495); the difference is within the noise of that machine, so treat it as directional, not proven. Numbers use a throw-away measurement key; the published release is signed by CI.
- APK release runtime check (minified APK, real system folder picker): list of 249 apps, search, detail, simple extraction (SHA-256 of the extracted file equals the installed APK), batch of 2 apps (both hashes equal; the provider renamed the repeated file), icon PNG 512x512, and a real split install (base + config split) exported as `.apks` whose entries hash equal to the installed files.
- APK REA evidence (REA 6.4.0, static only; details and Evidence IDs in side-session-notes `apk-rea-evidence.md`): reference `Apk Extractor.apk` declares 17 permissions including INTERNET, ad and billing, with ad/billing SDK strings, 21,446 classes. Our release manifest declares only `QUERY_ALL_PACKAGES` and its own signature permission; no INTERNET; R8 mapping lists only own code, androidx, kotlin and kotlinx.coroutines (no third-party SDK). Reference->implementation matrix: list, search, user/system filter, select all, app info (incl. UID), extract to chosen folder, icon, share, file-name format, saved path, theme are covered; sorting, `.apks` splits and batch icons are extra; theme color, "show details before extracting" and "view on Play Store" are deliberate gaps; ads, billing, rating and consent are excluded.
- APK not yet verified: API 26/31/34 and physical devices; the benchmark was not run on a quiet machine; the reference's own split handling is unknown from static evidence.
- Focus: eight recurrence/timer unit tests and six device tests pass. Device coverage includes backup round-trip, invalid-backup preservation, recurring parent/subtask creation, task creation through Compose, countdown completion in background, and stable pause. Build and lint pass. Weekly weekday selection and daily-goal/project report presentation were added afterward and still require final UI acceptance.
- Three separate signing keys are retained outside the repositories with private filesystem permissions; GitHub signing secrets are configured. Local APK and Focus release builds and signature/metadata generation succeeded.
- Public signed APK/Focus v0.1.0 Releases were generated by GitHub Actions. Both downloaded assets matched their published size and SHA-256; their release metadata has been incorporated into the catalog.
- Manual APK acceptance: selected an Android document-tree folder, granted access and extracted the installed signed app. The resulting file matched the installed artifact byte for byte. Its actual manifest contains only package visibility and its internal receiver permission.
- Focus's API 26/31 matrix exposed a test scrolling issue: the save button is virtualized on smaller screens. The test now scrolls the task editor to compose that button before clicking; the latest local six-device-test suite passes. That matrix must be repeated on the corrected commit.

Still required: complete behavior review against the reference feature inventory; visual/accessibility review; device matrix; public signed Releases and catalog population; store-driven installation/update/self-update and failure-recovery acceptance. These remain real delivery gates, not assumed complete from compilation.

## Focus verification (2026-10-18)

Dedicated API 36 emulator (clone of winali36, own port): `assembleDebug`, `assembleRelease`, `lintDebug`, `testDebugUnitTest` (19 unit tests) and `connectedDebugAndroidTest` (28 device tests) all pass.

- Calendar, editable history, forest (one tree per session, withered when interrupted), nine achievements, daily sunlight: unit and device tests.
- Audio at system level: `dumpsys audio`/`media.audio_flinger` showed an active mono AudioTrack on the speaker with the music stream unmuted (index 5/15) while rain ambient played, stopped within the same second the timer was paused; the completion chime appeared as a USAGE_NOTIFICATION player (1.8 s) when a session finished. Sounds are original synthesis. Not verified: hearing it.
- Widgets, on the real launcher: all seven were added from the widget picker, render, and show distinct labels (previously all seven were labelled "Focus"). Tapping each opens the right screen (timer, reports x3, tasks, add-task editor) including when the app is already running (this exposed and fixed a bug: launches were dropped when Focus was open; MainActivity is now singleTop with onNewIntent routing). The timer widget starts/pauses the service and counts down live through a Chronometer. Widgets also refresh when the app opens.
- Special access via adb: notification silencing hid a notification only while the timer ran with access granted, and kept it when access was revoked or the timer paused; app restriction showed the exit gate for Chrome only with usage access and overlay granted, did nothing with them denied, and "Salir del modo de concentración" and "Volver a Focus" both worked; face-down mode paused and resumed with emulated accelerometer. Settings now states Granted/Not granted per access and explains a denied one. Do Not Disturb is not used by Focus.
- System pickers: backup saved through DocumentsUI (versioned JSON), restore through the picker and confirmation, CSV saved through DocumentsUI, text summary opened in the share sheet.
- Small screens and accessibility (720x1280, 320 dpi; font 1.0/1.3/2.0; dark; English; Spanish): captures of tasks, timer, reports, forest, settings, calendar and editor reviewed. Fixed: timer controls overlapped by the dial, navigation labels breaking mid-word at 2.0, calendar clipped, raw ISO month title, plural forms, English words in the Spanish summary, switch rows not exposing their label to accessibility.
- Room: schemas v1/v2, 1→2 migration tested with MigrationTestHelper.
- Release build: R8 and resource shrinking on. Signed with the debug key for a local test, the minified release installs, starts, and its database and timer service work. APK 12,252,418 B → 1,522,041 B (-87.6%). A hand-written baseline profile (app, Compose, Room, lifecycle) lowered cold start (am start -W, 12 launches each, emulator): median 1179.5 ms with R8 only (verify mode) → 825 ms with the profile in speed-profile mode (-30%). The profile is not generated by Macrobenchmark and was not measured on a physical phone.

Not verified: audible output, a physical device, widgets on other launchers, Android versions other than 36 for this round, and the Focus tasks/timer/projects gates remain unchecked pending the final acceptance audit.

## Winali store verification (2026-10-18)

Verified on a dedicated API 36 emulator (`winali_selfupdate`, port 5560; the shared `winali36` was not touched): `assembleDebug`, `lintDebug`, `testDebugUnitTest` (40 tests) and `connectedDebugAndroidTest` (71 tests) all pass. `connectedDebugAndroidTest` first runs the Gradle task `allowInstallSource`, because granting the "install unknown apps" app-op from inside a running instrumentation kills the process.

- Listing: the detail page shows screenshots (loaded over the network, with a retry placeholder), description, release notes with version, permissions, size, minimum Android, architectures, a compatible/incompatible verdict, the installed version and the repository link. UI tests cover every field in Spanish and English, light and dark, and 2.0 font scale; the same were inspected on screen.
- Button states (Install, Update, Open, Install download, Retry, Downloading/Queued with Cancel, Installing, Confirm installation, Incompatible, Coming soon) come from one pure function covered by unit tests and rendered by UI tests.
- Queue: the real worker against a loopback server. Progress, byte-range resume of a partial file, restart when the server ignores ranges, reuse of an already verified file, corruption and oversize rejection (partial file discarded), HTTP errors, an incompatible release, a dropped connection (partial kept, three automatic retries, then a final failure), cancellation (partial file discarded), and manual retry. Recovery after closing the app was also exercised by hand: force-stopping Winali mid-download and reopening it resumed with `Range: bytes=15728640-` and the hash verified.
- Installation: real `PackageInstaller` sessions with tiny fixture APKs (`tools/make-test-fixtures.mjs`), driving Android's own confirmation dialog: install, update that keeps `firstInstallTime`, user cancel (installed version untouched), a different signing key (refused before any session, and Android's own verdict maps to the same message), downgrade, permission denied, damaged download (discarded), pending confirmation while the store is closed (notification), stale sessions, every status code through the receiver, and download-then-install as one action. Nothing in the code uninstalls an app; the signature message tells the user so.
- Updates: a local catalog server exercises detection, one notification per distinct update set, rejection of a malformed catalog or an older revision (cache kept), offline failure, the 12-hour periodic request with a network constraint, and idempotent scheduling. The Updates tab shows versions, notes, per-app update, "Actualizar todo" (Winali last) and the download queue.
- Self-update by hand on the emulator, debug-signed 0.1.0 (1000) to 0.1.1 (1001) with a local catalog and APK (`tools/local-update-server.mjs`; the debug build alone accepts the local URL): the installed 0.1.0 listed the update, declining the unknown-sources permission showed the clear message, the download resumed after a force-stop, the hash/package/version/certificate checks passed, Android's dialog asked "Do you want to update this app?", and afterwards `versionName` was 0.1.1, `firstInstallTime` unchanged, a marker file and the chosen dark theme preserved, and Winali announced the update. With the clock moved forward 13 hours the real periodic job ran, fetched the catalog and posted "1 actualización disponible / Winali 0.1.1"; tapping it opened the Updates tab.
- Version: `versionCode`/`versionName` default to 1001/0.1.1, matching the release workflow's `major*1000000 + minor*1000 + patch`. `CHANGELOG.md` has a section per version and `tools/release-manifest.mjs` publishes only the section of the tagged version.

Not verified, still open:

- The signed 0.1.1 Release. The private signing key was not available on this machine (the secrets live in GitHub Actions only), so no signed APK was built. To publish: tag `v0.1.1` (the workflow signs, creates `winali.apk` and `release.json`), then run `node tools/catalog.mjs sync apk focus winali`, review and commit the catalog. The public catalog still lists Winali 0.1.0 until then, so the demonstration above used debug-signed builds, not the production key.
- The new device tests on API 26, 31 and 34 (only API 36 was run). The installation tests depend on the system dialog's wording and Play Protect behaviour on each image.
- Installing several different apps at once through "Actualizar todo" on a real device (APK and Focus were not installed on the test emulator); the ordering and action selection are unit tested and the button wiring is UI tested.
- The Android 13+ notification permission prompt (granted through `pm grant` in tests) and Play Protect warnings on a real phone.
- Split bundles/AABs are not installable by Winali.

## Winali size and startup optimization (2026-10-18)

Release now builds with R8 (`isMinifyEnabled`, `isShrinkResources`, rules in `app/proguard-rules.pro`), the Material extended icon library is gone (three glyphs are own vectors in `Icons.kt`, the rest come from the core set), unused dependencies were removed (`documentfile`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-ktx`, `ui-tooling`, `ui-tooling-preview`), locales are limited to `en`/`es`, the unused Room invalidation service and the EmojiCompat start-up initializer are removed from the merged manifest, and a generated Baseline Profile (`baselineprofile` module, `app/src/release/generated/baselineProfiles`) ships with the release together with `profileinstaller`. The enum `Failure` is kept by name because its names are persisted. Regenerate the profile with `./gradlew :app:generateReleaseBaselineProfile` on a device or emulator.

Measured on the API 36 emulator, release APKs signed with the debug key (the real key is not available here):

| | Before | After |
|---|---|---|
| APK size | 12,306,056 bytes (12.3 MB) | 1,410,110 bytes (1.41 MB), -88.5% |
| Classes (REA, JADX) | 18,404 | 3,008 |
| Resources / decoded files | 136 / 234 | 93 / 102 |
| Resource locales | 85 | 1 (`en`, `es` project strings) |
| Manifest components | 17 | 16 |
| Permissions | 10 | 10, unchanged |

Intermediate step, R8 + resource shrinking + icons without locale/manifest trimming: 1,514,378 bytes, 3,014 classes.

Cold start (`am start -W` TotalTime, median of 15-20 runs, force-stopped and sent Home before each): this emulator shares the host with other emulators and the numbers are noisy, ±0.3 s between identical runs, so only the size result is reliable. Observed medians: before (installed state, `verify`) 1229 ms and, in an interleaved A/B pass, 1486 and 1447 ms; after R8 only 1080 ms (first pass), 1499 and 1719 ms (A/B pass); after with the profile compiled (`speed-profile`) 937 ms, 1106 ms and, in the A/B pass, 1137 and 1698 ms. In the A/B rounds the profile run was faster in one round and equal in the other, so a startup gain is plausible but is not demonstrated here; measure on a quiet physical device before claiming it. The "before" APK already carried the Compose libraries' own profile; the app-specific profile adds 713 app-class rules.

Behaviour of the minified release was tested, not only the debug build:

- Release APK (`app-release`, live catalog): catalog loaded from GitHub, "Instalar" on APK showed the real Android 13+ notification prompt and the unknown-sources screen, downloaded the 11.8 MB release from GitHub, verified it, Android asked to install, and afterwards `installerPackageName` was `io.github.willianchavez.winali` and the button read "Abrir".
- `stage` build type (same R8/shrinking as release, not debuggable, debug-signed, loopback HTTP allowed, local catalog URL; never published): installed 0.1.0 (1000), chose the dark theme, updated through the store from a local server to 0.1.1 (1001): `firstInstallTime` unchanged, `versionName` 0.1.1, dark theme kept, the listing then showed no pending update. The 150 ms-per-256 KB local server and the unknown-sources prompt behaved as with the debug build.

REA evidence (`rea` 6.4.0: `decode-android-resources`, `inspect-android-package`, `search-android-classes`, `inspect-artifact`; JADX 0.7.1 provider; JSON outputs kept in `.local/optimize`, not committed):

- Manifest (apktool): only the declared permissions above. Each has a use: Internet and network state (catalog, downloads, WorkManager constraints), install packages, query all packages (installed versions of catalog apps), notifications, foreground service and data sync (downloads), wake lock and boot completed (WorkManager rescheduling), and AndroidX's signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Exported components: the launcher activity, WorkManager's job service and two DUMP-protected diagnostic receivers. The final manifest has no Room service and no EmojiCompat initializer.
- Classes: before, 11,417 of 18,404 were `androidx.compose.material` (the extended icons); after, none. Searches for `firebase`, `crashlytics`, `okhttp`, `retrofit`, `coil`, `sentry`, `analytics` and `facebook` find nothing in either APK; `com.google` had one class before and none after. Our own classes surviving by name are the activity, the two workers, the install receiver, `Failure` and `R`.
- Strings: the only URLs in the dex files are Android/Compose documentation links, Winali's repository and catalog URL, and a Kotlin issue link; no third-party endpoints. `assets/` holds only the bundled catalog and the baseline profile.
- The reference apps (`Apk Extractor.apk`, `Focus To-Do.apk`) were not needed to justify these decisions and were not opened.

Not verified: startup improvement on a physical device; the signed production release (see above); the release APK installing over the real 0.1.0 release (different signing key from the debug-signed builds used here).

## Deck 0.1.0 — catálogo revision 11 (2026-10-19)

Deck se añade como quinta app. Android y el servidor/editor Windows se publican juntos en WillianChavez/deck, tag v0.1.0, con deck.apk, release.json y deck-windows-x64.zip. Publicación local firmada y GitHub Actions deshabilitado. Certificado Android inicial revisado: 208bf7a7a1b7c207bc2a8e0bb483a30fce3b4cdaad488b88c2927f6965a70ca8.

El APK público se descargó y coincide con los 2.832.086 bytes y SHA-256 del manifiesto. La descarga latest del servidor Windows responde correctamente. Las dos capturas son del APK release firmado, en español, y muestran el nuevo logo y la descarga del servidor. Se mantienen fuera del README de Winali; se muestran en la ficha y en el README de Deck.

Verificación: 25 pruebas Rust, 6 unitarias Android, 5 de instrumentación debug y una integración automatizada Android/Windows aprobadas. El release Android con R8 fue probado mediante su interfaz real: importar QR, aprobación local, autenticación y ejecución de un Delay seguro, con una sola ejecución registrada. El release Windows abrió y respondió. La instrumentación directa release no pudo ejecutarse por la dependencia Trace eliminada por R8 del runner; esta limitación está documentada en Deck. OBS real, plugins externos y rendimiento en teléfono físico siguen sin verificar. Las nueve pruebas de catálogo pasan.

## Deck 0.1.10 — catálogo revision 32 (2026-10-20)

La corrección de Windows vuelve a mostrar la ventana desde el clic o el menú de la bandeja, incluso tras ocultarla varias veces; salir quita el icono antes de cerrar Deck. Código en `WillianChavez/deck`, commit `e7168d2`, tag `v0.1.10`. Release pública con `deck.apk`, `release.json` y `deck-windows-x64.exe`; el APK de 3.691.290 bytes verifica con SHA-256 `cf8060402b3fd0ae112383d4ab9d4f087c2f1e7768b3205d9d41e4d1fa578f39` y conserva el certificado registrado.

Verificación: 73 pruebas Rust, 9 unitarias Android, 6 de instrumentación API 36; dos casos de integración están omitidos. Compilación release de Windows y Android, lint y build firmado aprobados. El comportamiento visual de la bandeja no se probó en la interfaz de Windows. Las 10 pruebas del catálogo pasan. La ficha del catálogo sube a versionCode 1010 y revisión 32.
