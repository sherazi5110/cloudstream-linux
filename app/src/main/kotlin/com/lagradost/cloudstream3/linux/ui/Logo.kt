package com.lagradost.cloudstream3.linux.ui

import javafx.scene.Group
import javafx.scene.SnapshotParameters
import javafx.scene.image.Image
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.transform.Scale

/**
 * The app logo: a big "C" with a small "s" inside it, over a white cloud on a black tile.
 * The letters take the theme's accent colour, so the logo changes with it. Drawn from vector
 * paths (letters from Inter Display Black, SIL Open Font License) on a 512 grid, the same as
 * packaging/linux/cloudstream-linux.svg.
 */
class Logo(size: Double, accent: String? = null) : Pane() {
    private val letters = SVGPath().apply { content = LETTERS }

    init {
        val tile = Rectangle(512.0, 512.0, Color.BLACK).apply { arcWidth = 228.0; arcHeight = 228.0 }
        val cloud = SVGPath().apply { content = CLOUD; fill = Color.web("#f2f2f2") }
        // A black outline behind the letters separates them from the cloud.
        val outline = SVGPath().apply { content = LETTERS; fill = Color.BLACK; stroke = Color.BLACK; strokeWidth = 10.0; strokeLineJoin = javafx.scene.shape.StrokeLineJoin.ROUND }
        children += Group(tile, cloud, outline, letters).apply { transforms += Scale(size / 512, size / 512, 0.0, 0.0) }
        setMinSize(size, size); setPrefSize(size, size); setMaxSize(size, size)
        isMouseTransparent = true
        recolor(accent ?: Theme.accent)
        if (accent == null) Theme.onChange { recolor(Theme.accent) }
    }

    private fun recolor(hex: String) {
        letters.fill = runCatching { Color.web(hex) }.getOrDefault(Color.web(DEFAULT_ACCENT))
    }

    companion object {
        const val DEFAULT_ACCENT = "#3b82f6"
        private const val CLOUD = "M74 236a86 86 0 1 1 172 0a86 86 0 1 1 -172 0Z M154 178a132 132 0 1 1 264 0a132 132 0 1 1 -264 0Z M312 252a80 80 0 1 1 160 0a80 80 0 1 1 -160 0Z M111 226H401a75 75 0 0 1 75 75V301a75 75 0 0 1 -75 75H111a75 75 0 0 1 -75 -75V301a75 75 0 0 1 75 -75Z"
        private const val LETTERS = "M261 442.7C331.7 442.7 381.6 399.6 389.4 334.3H311.6C306.8 360.3 289 375.9 262.5 375.9C224.8 375.9 204.6 348.4 204.6 300.2C204.6 251.8 225 224.1 262.5 224.1C289 224.1 307 239.9 311.6 266H389.4C381.8 200.7 331.7 157.3 261 157.3C185.5 157.3 126.6 207.8 126.6 300.2C126.6 391.8 185.5 442.7 261 442.7Z M283 359.8C309.2 359.8 326.5 346.9 326.5 328.3C326.5 315.2 317.9 307.6 299.4 304.5L280.7 301.4C275.4 300.5 272.5 298.5 272.5 295.7C272.5 292.5 276.1 290.1 282.5 290.1C288.9 290.1 293.8 294 294 299.1H324.2C323.7 280 307.6 268.2 281.2 268.2C254.9 268.2 239.5 279.5 239.5 297.6C239.5 311.8 249 320.8 266.6 323.6L283 326.3C289.6 327.4 292.8 329.3 292.8 332.1C292.8 335.5 289.1 337.7 282.5 337.7C275.4 337.7 271 334.2 270.2 328.9H237.5C239.3 347.5 256.9 359.8 283 359.8Z"

        /** The logo as an image in the current accent, e.g. for the window icon. */
        fun image(size: Double): Image = Logo(size, Theme.accent).snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    }
}
