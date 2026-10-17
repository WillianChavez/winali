# Winali catalog v1

`catalog/index.json` is the reviewed source of truth. `node tools/catalog.mjs bundle` validates it and refreshes the embedded offline copy. Phones fetch this single static public document, not the GitHub REST API.

Each app entry has `id`, Android `packageName`, `name`, `summary`, `description`, `category`, public GitHub `repository`, HTTPS `screenshots`, human-readable `permissions`, and `release` (null only before publication). Repository ownership is unrestricted. Adding apps is a file change, never an in-app upload flow.

A release has positive integer `versionCode`, display `versionName`, integer `minSdk`, `architectures` (`universal` or ABI names), immutable HTTPS APK `url`, byte `size`, lowercase SHA-256 `sha256`, lowercase signing certificate SHA-256 `certificateSha256`, and `changelog`. URLs must reference APK assets, not a GitHub source archive or HTML page.

Each app publishes `release.json` as a Release asset: `{ "schemaVersion": 1, "packageName": "...", "release": { ... } }`. Metadata must be generated from the signed APK, not guessed from tags. Keep the signing key stable. A higher versionCode is an update. Replacing a published version or changing its key is rejected.

Run `node tools/catalog.mjs sync apk focus` after publishing new app Releases. It reads their public latest release.json assets, validates package/version/certificate continuity, updates the catalog and embedded copy, and leaves a reviewable diff. Commit/push the reviewed result. This is the only publication operation needed after a normal release; no store app rebuild is required. `node --test tools/catalog.test.mjs` covers rejection and continuity rules.

For a repository without release.json, generate a compatible manifest from its published APK using `tools/release-manifest.mjs`, place the reviewed release metadata in its central catalog entry and bundle. Do not import a random asset solely because its filename ends in .apk. The first certificate and metadata are explicitly trusted when you review the catalog entry.

The initial installer supports standalone APKs, not split bundles or AABs. APK (the extractor) can export split bundles; that does not imply Winali can install them. Catalog integrity uses HTTPS and reviewed repository changes; it is not a separately signed repository protocol.
