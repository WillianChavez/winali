# Winali catalog v1

`catalog/index.json` is the reviewed source of truth. `node tools/catalog.mjs bundle` validates it and refreshes the embedded offline copy. Phones fetch this single static public document, not the GitHub REST API.

The machine-readable definition is `catalog/schema.json` (JSON Schema 2020-12). The validator also checks uniqueness across entries and certificate/version continuity, which cannot be expressed by this schema alone.

The publisher maintains a positive `revision`, increasing it whenever the catalog content changes. Clients prefer the newest valid embedded/cached document and reject an older network revision, so a stale CDN response cannot replace a newer catalog. `bundle` and `sync` maintain this value automatically; repeated bundling without content changes keeps the revision stable.

Each app entry has `id`, Android `packageName`, `name`, `summary`, `description`, `category`, GitHub `repository` (the public store project; app source code stays in its own private repository), HTTPS `screenshots`, human-readable `permissions`, and `release` (null only before publication). Adding apps is a file change, never an in-app upload flow.

Optional `locales.en` supplies English `summary`, `description`, `category`, and `permissions`; the base text remains the fallback. Release notes retain the publisher's language.

A release has positive integer `versionCode`, display `versionName`, integer `minSdk`, `architectures` (`universal` or ABI names), immutable HTTPS APK `url`, byte `size`, lowercase SHA-256 `sha256`, lowercase signing certificate SHA-256 `certificateSha256`, and `changelog`. URLs must reference APK assets, not a GitHub source archive or HTML page.

Signed APKs and `release.json` are static files in the separate public `WillianChavez/winali-downloads` repository, under `apps/<id>/<version>/` and `apps/<id>/latest/`, served by GitHub Pages. The manifest is `{ "schemaVersion": 1, "packageName": "...", "release": { ... } }`. Metadata must be generated from the signed APK, not guessed from tags. Source tags `vX.Y.Z` remain in each private app repository. Keep the signing key stable. A higher versionCode is an update. Never overwrite a distributed version or change its certificate.

After `tools/publish-download.ps1 -App <id> -Version X.Y.Z -Push`, wait until Pages serves the new `apps/<id>/latest/release.json`, then run `node tools/catalog.mjs sync <id>`. Sync reads that static manifest without access to the private app repository, validates package/version/certificate continuity, and updates the reviewed catalog and embedded copy. Sync only the app being published; an unfiltered sync can advance other releases unintentionally. Validate and test the catalog, review the diff, and commit/push the result to `WillianChavez/winali`.

**Publish the catalog at the URL used by installed Winali builds.** `app/build.gradle.kts` sets `CATALOG_URL` to `https://willianchavez.github.io/winali-downloads/catalog.json`; changing only `winali/catalog/index.json` does not update this endpoint. After the reviewed catalog is merged, copy `winali/catalog/index.json` to `winali-downloads/catalog.json`, review the change, and commit/push `catalog.json` to `WillianChavez/winali-downloads` `main`. No Winali APK rebuild is needed for a catalog-only update.

Verify all three production endpoints after pushing: `https://raw.githubusercontent.com/WillianChavez/winali/main/catalog/index.json`, `https://raw.githubusercontent.com/WillianChavez/winali-downloads/main/catalog.json`, and the exact client endpoint `https://willianchavez.github.io/winali-downloads/catalog.json`. Confirm that all show the same catalog revision and the published app's version, versionCode, APK URL, size, SHA-256, and signing certificate. GitHub Pages/CDN may temporarily serve an earlier revision after the repository has changed; if the client endpoint is stale, wait and check it again. Publication is complete only when the exact Pages URL used by the client returns the new catalog. `node --test tools/catalog.test.mjs` covers rejection and continuity rules.

The local app build script signs the APK and generates `release.json` from its actual package, version, size, hash and certificate. Do not import an arbitrary APK solely because its filename ends in .apk. The first certificate and metadata are explicitly trusted when reviewing a new catalog entry. Signing credentials remain outside all repositories.

The initial installer supports standalone APKs, not split bundles or AABs. APK (the extractor) can export split bundles; that does not imply Winali can install them. Catalog integrity uses HTTPS and reviewed repository changes; it is not a separately signed repository protocol.

Winali updates itself through its own catalog entry, using the same signed static distribution process and `node tools/catalog.mjs sync winali`. Installed copies list the update in the Updates tab, download it, verify hash, package, version and signing certificate, and Android replaces the app keeping its data. Each release's `CHANGELOG.md` contains only that version's user-facing notes; the build includes its full contents in `release.json`. To rehearse the flow locally see `tools/local-update-server.mjs`.
