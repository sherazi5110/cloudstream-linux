package com.lagradost.cloudstream3.linux.ui

import javafx.animation.Interpolator
import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.Timeline
import javafx.beans.property.DoubleProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.Duration
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.javafx.JavaFx

/** UI-thread scope for the whole app; screens own child jobs they cancel when closed. */
val ui = CoroutineScope(SupervisorJob() + Dispatchers.JavaFx + CoroutineExceptionHandler { _, t ->
    System.err.println("E/ui: $t")
})

/** Material Design icon paths (Apache 2.0), drawn as region shapes so CSS can colour them. */
object Icons {
    const val HOME = "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"
    const val SEARCH = "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
    const val LIBRARY = "M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 12.5v-9l6 4.5-6 4.5z"
    const val EXTENSION = "M20.5 11H19V7c0-1.1-.9-2-2-2h-4V3.5C13 2.12 11.88 1 10.5 1S8 2.12 8 3.5V5H4c-1.1 0-1.99.9-1.99 2v3.8H3.5c1.49 0 2.7 1.21 2.7 2.7s-1.21 2.7-2.7 2.7H2V20c0 1.1.9 2 2 2h3.8v-1.5c0-1.49 1.21-2.7 2.7-2.7 1.49 0 2.7 1.21 2.7 2.7V22H17c1.1 0 2-.9 2-2v-4h1.5c1.38 0 2.5-1.12 2.5-2.5S21.88 11 20.5 11z"
    const val SETTINGS = "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"
    const val PLAY = "M8 5v14l11-7z"
    const val BACK = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"
    const val REFRESH = "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
    const val ADD = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"
    const val DELETE = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"
    const val CLOSE = "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
    const val CHEVRON_LEFT = "M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z"
    const val CHEVRON_RIGHT = "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"
    const val BOOKMARK = "M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z"
    const val BOOKMARK_OUTLINE = "M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2zm0 15l-5-2.18L7 18V5h10v13z"
    const val DOWNLOAD = "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
    const val CHECK = "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"
    const val STAR = "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 5.73L5.82 21z"
    const val FOLDER = "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"
    const val FILE = "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11z"
    const val INFO = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"
    const val HISTORY = "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"
    const val SUBTITLES = "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z"
    const val COPY = "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"
    const val MOVIE = "M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z"
}

fun icon(path: String, size: Double = 20.0): Region = Region().apply {
    styleClass += "icon"
    style = "-fx-shape: \"$path\"; -fx-scale-shape: true;"
    setMinSize(size, size)
    setPrefSize(size, size)
    setMaxSize(size, size)
    isPickOnBounds = false
}

fun label(text: String?, vararg classes: String) = Label(text ?: "").apply { styleClass.addAll(classes) }

fun button(text: String?, iconPath: String? = null, vararg classes: String, action: () -> Unit): Button =
    Button(text).apply {
        styleClass.setAll("btn", *classes)
        if (iconPath != null) graphic = icon(iconPath, 18.0)
        if (text == null) styleClass += "btn-icon"
        setOnAction { action() }
    }

fun <T : Node> T.tooltip(text: String): T = apply { Tooltip.install(this, Tooltip(text).apply { showDelay = Duration.millis(300.0) }) }

fun hbox(spacing: Double = 8.0, vararg children: Node) = HBox(spacing, *children).apply { alignment = Pos.CENTER_LEFT }

fun vbox(spacing: Double = 8.0, vararg children: Node) = VBox(spacing, *children)

fun spacer(): Region = Region().apply { HBox.setHgrow(this, Priority.ALWAYS); VBox.setVgrow(this, Priority.ALWAYS) }

fun Region.pad(top: Double, right: Double = top, bottom: Double = top, left: Double = right) =
    apply { padding = Insets(top, right, bottom, left) }

fun <T : Node> T.onClick(action: () -> Unit): T = apply { setOnMouseClicked { if (it.button == javafx.scene.input.MouseButton.PRIMARY) action() } }

fun animate(property: DoubleProperty, to: Double, millis: Double = 220.0) {
    Timeline(KeyFrame(Duration.millis(millis), KeyValue(property, to, Interpolator.EASE_BOTH))).play()
}

/**
 * Vertical page scrolling. JavaFX scrolls very little per wheel notch on Linux, so this
 * scrolls ~one card row per notch with a short ease, which feels like a browser.
 */
fun pageScroll(content: Node): ScrollPane = ScrollPane(content).apply {
    isFitToWidth = true
    hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
    vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
    var target = 0.0
    var animation: Timeline? = null
    vvalueProperty().addListener { _, _, v -> if (animation == null) target = v.toDouble() }
    addEventFilter(ScrollEvent.SCROLL) { e ->
        if (e.deltaY == 0.0 || e.isShiftDown) return@addEventFilter
        val contentHeight = (this.content as Region).height - viewportBounds.height
        if (contentHeight <= 0) return@addEventFilter
        target = (target - e.deltaY * 3.2 / contentHeight).coerceIn(0.0, 1.0)
        animation?.stop()
        animation = Timeline(KeyFrame(Duration.millis(160.0), KeyValue(vvalueProperty(), target, Interpolator.EASE_OUT))).apply {
            setOnFinished { animation = null }
            play()
        }
        e.consume()
    }
}
