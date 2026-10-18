# Winali implementation ledger

The agreed target is three public, independently signed Android apps: Winali, APK, Focus. Original Kotlin/Compose code, MIT license, no login/backend/ads. Blue App Store inspired design with familiar Play Store navigation. Android 8 minimum; Spanish/English; dark/light/system.

## Required delivery gates

- [x] Toolchain: apktool resources through REA; JDK; SDK; Gradle; signing; emulator
- [ ] REA feature inventory, evidence and reference resource mapping (static evidence is not runtime proof)
- [x] Three independently buildable public repositories and original adaptive icons
- [ ] Winali explore/search/categories/detail/screenshots/permissions/changelog/source
- [x] Catalog v1 schema, validation, synchronization, examples and publishing documentation
- [ ] Persistent download queue, integrity/package/version/signature checks, PackageInstaller results and recovery
- [ ] Installed state, updates, 12-hour checks, notifications, self-update and offline catalog
- [ ] APK list/search/sort/system filters/details/selection/batch extraction/share/icons/naming/SAF/split bundles
- [ ] Focus tasks/subtasks/notes/priority/dates/recurrence/reminders/estimates
- [ ] Focus projects/folders/tags/search/filters/completed/calendar
- [ ] Focus persistent timer, breaks/cycles/continuous/fullscreen/sounds/vibration/ambient
- [ ] Focus editable history/goals/reports/forest/achievements
- [ ] Focus seven widget categories and quick-add tile
- [ ] Focus optional distraction controls/silencing with permission-denied behavior and accessible exit
- [ ] Focus versioned backup/restore/report export; Room schemas/migrations
- [ ] Release CI, three protected signing keys, signed APKs and public Releases
- [ ] Unit/instrumentation/runtime tests, screenshots and acceptance audit

Reference artifacts stay outside version control. Do not mark a delivery gate complete from a scaffold or a compile alone.

## Current evidence (2026-10-17)

- Public repositories: `WillianChavez/winali`, `WillianChavez/apk`, `WillianChavez/focus`; initial GitHub build/test/lint jobs succeeded in all three.
- Local tools: Apktool 3.0.3 through the REA CLI (resource decode succeeded), REA 6.4.0, existing JDK 21, Gradle 8.13, SDK/platform 36, Build Tools 35/36, ADB, accelerated headless Android 16 emulator.
- Catalog: seven Node tests pass; JSON Schema and sync/publishing tools are present.
- Winali: four device tests pass, including verification of an actual installed APK and independent queued download cancellation. Build and lint pass.
- APK: three device tests pass: byte-preserving standalone extraction, complete split archive export, and safe file names. Build and lint pass. WorkManager was removed to avoid unrelated background/network-state permissions.
- Focus: eight recurrence/timer unit tests and six device tests pass. Device coverage includes backup round-trip, invalid-backup preservation, recurring parent/subtask creation, task creation through Compose, countdown completion in background, and stable pause. Build and lint pass. Weekly weekday selection and daily-goal/project report presentation were added afterward and still require final UI acceptance.
- Three separate signing keys are retained outside the repositories with private filesystem permissions; GitHub signing secrets are configured. Local APK and Focus release builds and signature/metadata generation succeeded.

Still required: complete behavior review against the reference feature inventory; visual/accessibility review; device matrix; public signed Releases and catalog population; store-driven installation/update/self-update and failure-recovery acceptance. These remain real delivery gates, not assumed complete from compilation.
