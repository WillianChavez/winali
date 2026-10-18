package io.github.willianchavez.winali

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The three glyphs the Material core icon set lacks. Keeping them here (24 dp, Material path data)
 * avoids shipping the extended icon library, which would add several megabytes before shrinking.
 */
object WinaliIcons {
    private fun vector(name: String, path: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()

    val Explore by lazy {
        vector(
            "Explore",
            "M12 10.9c-0.61 0-1.1 0.49-1.1 1.1s0.49 1.1 1.1 1.1c0.61 0 1.1-0.49 1.1-1.1s-0.49-1.1-1.1-1.1zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM14.19 14.19L6 18l3.81-8.19L18 6l-3.81 8.19z",
        )
    }

    val SystemUpdate by lazy {
        vector(
            "SystemUpdate",
            "M17 1.01L7 1c-1.1 0-2 0.9-2 2v18c0 1.1 0.9 2 2 2h10c1.1 0 2-0.9 2-2V3c0-1.1-0.9-1.99-2-1.99zM17 19H7V5h10v14zm-1-6h-3V8h-2v5H8l4 4 4-4z",
        )
    }

    val Code by lazy {
        vector(
            "Code",
            "M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z",
        )
    }
}
