package io.github.willianchavez.winali

import java.io.File

fun release(
    code: Long = 1001,
    name: String = "0.1.1",
    minSdk: Int = 26,
    architectures: List<String> = listOf("universal"),
) =
    Release(
        code,
        name,
        minSdk,
        architectures,
        "https://github.com/WillianChavez/app/releases/download/v$name/app.apk",
        11_872_934,
        "a".repeat(64),
        "b".repeat(64),
        "Notes for $name",
    )

fun app(id: String = "apk", release: Release? = release()) =
    CatalogApp(
        id,
        "io.github.willianchavez.$id",
        id.uppercase(),
        "Summary",
        "Description",
        "Tools",
        "https://github.com/WillianChavez/$id",
        emptyList(),
        emptyList(),
        release,
    )

fun bundledCatalogText(): String = File("src/main/assets/catalog.json").readText()
