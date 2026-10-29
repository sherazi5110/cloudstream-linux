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
 * The app logo: "SC" over a white cloud on a black tile. The letters take the theme's
 * accent colour, so the logo changes with it. Drawn from vector paths (letters from Inter
 * Display Black, SIL Open Font License) on a 512 grid, the same as packaging/linux/cloudstream-linux.svg.
 */
class Logo(size: Double, accent: String? = null) : Pane() {
    private val letters = SVGPath().apply { content = LETTERS }

    init {
        val tile = Rectangle(512.0, 512.0, Color.BLACK).apply { arcWidth = 228.0; arcHeight = 228.0 }
        val cloud = SVGPath().apply { content = CLOUD; fill = Color.WHITE }
        children += Group(tile, cloud, letters).apply { transforms += Scale(size / 512, size / 512, 0.0, 0.0) }
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
        private const val CLOUD = "M88 276a82 82 0 1 1 164 0a82 82 0 1 1 -164 0Z M158 228a124 124 0 1 1 248 0a124 124 0 1 1 -248 0Z M306 294a74 74 0 1 1 148 0a74 74 0 1 1 -148 0Z M114 264H398a70 70 0 0 1 70 70V334a70 70 0 0 1 -70 70H114a70 70 0 0 1 -70 -70V334a70 70 0 0 1 70 -70Z"
        private const val LETTERS = "M171 403.1C216.7 403.1 242.9 382.9 242.9 347.6C242.9 320.6 226.5 305.7 187.7 298.6L174.2 296.1C156.6 292.9 149 288.6 149 280.5C149 272 158.3 266 171.5 266C185.6 266 195 273.3 196.1 285.1H239.4C238.3 249.7 212.8 228.9 170.6 228.9C129.6 228.9 101.7 250.8 101.7 282.8C101.7 307.8 119.3 325.2 152 331.4L168.7 334.6C187.7 338.2 196.4 343 196.4 351.1C196.4 360 186.4 366 171.5 366C154.4 366 143.7 357.9 143 344.2H98.5C98.5 382.2 124.2 403.1 171 403.1ZM335.1 403.1C378.3 403.1 408.8 376.8 413.5 337H366.1C363.1 352.8 352.2 362.3 336 362.3C313 362.3 300.7 345.6 300.7 316.1C300.7 286.5 313.2 269.7 336 269.7C352.2 269.7 363.2 279.3 366.1 295.3H413.5C408.9 255.4 378.3 228.9 335.1 228.9C289 228.9 253.1 259.7 253.1 316.1C253.1 372.1 289 403.1 335.1 403.1Z"

        /** The logo as an image in the current accent, e.g. for the window icon. */
        fun image(size: Double): Image = Logo(size, Theme.accent).snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    }
}
