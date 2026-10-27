package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import javafx.animation.FadeTransition
import javafx.animation.Interpolator
import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.PauseTransition
import javafx.animation.ScaleTransition
import javafx.animation.Timeline
import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.image.ImageView
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle
import javafx.util.Duration

const val POSTER_W = 152.0
const val POSTER_H = 228.0

fun roundedClip(node: Region, radius: Double) {
    val clip = Rectangle()
    clip.arcWidth = radius * 2
    clip.arcHeight = radius * 2
    clip.widthProperty().bind(node.widthProperty())
    clip.heightProperty().bind(node.heightProperty())
    node.clip = clip
}

fun qualityText(q: SearchQuality?): String? = when (q) {
    null -> null
    SearchQuality.FourK -> "4K"
    SearchQuality.BlueRay -> "BluRay"
    SearchQuality.HdCam -> "HDCAM"
    SearchQuality.CamRip -> "CAMRIP"
    SearchQuality.WebRip -> "WEB"
    else -> q.name.uppercase()
}

/** Gentle grow on hover, like the Android TV / desktop media centers. */
fun Node.hoverGrow(scale: Double = 1.045) {
    val grow = ScaleTransition(Duration.millis(140.0), this).apply { toX = scale; toY = scale; interpolator = Interpolator.EASE_OUT }
    val shrink = ScaleTransition(Duration.millis(140.0), this).apply { toX = 1.0; toY = 1.0; interpolator = Interpolator.EASE_OUT }
    setOnMouseEntered { shrink.stop(); grow.playFromStart() }
    setOnMouseExited { grow.stop(); shrink.playFromStart() }
}

/** Poster with rounded corners, quality/score badges and an optional progress bar. */
class PosterCard(
    title: String,
    posterUrl: String?,
    headers: Map<String, String>? = null,
    topLeft: String? = null,
    topRight: String? = null,
    progress: Double? = null,
    width: Double = POSTER_W,
    height: Double = POSTER_H,
    onOpen: () -> Unit,
) : VBox() {
    init {
        styleClass += "poster-card"
        maxWidth = width
        prefWidth = width
        val image = ImageView()
        Images.load(image, posterUrl, width, height, headers)
        val frame = StackPane(image).apply {
            styleClass += "poster-frame"
            setMinSize(width, height)
            setMaxSize(width, height)
            if (posterUrl.isNullOrBlank()) children += label(title.take(40), "muted", "small").apply {
                isWrapText = true; maxWidth = width - 20; alignment = Pos.CENTER
            }
            topLeft?.let { children += label(it, "badge").also { b -> StackPane.setAlignment(b, Pos.TOP_LEFT); StackPane.setMargin(b, javafx.geometry.Insets(8.0)) } }
            topRight?.let { children += label(it, "badge", "accent").also { b -> StackPane.setAlignment(b, Pos.TOP_RIGHT); StackPane.setMargin(b, javafx.geometry.Insets(8.0)) } }
            if (progress != null && progress > 0) children += progressBar(progress, width).also { StackPane.setAlignment(it, Pos.BOTTOM_LEFT) }
            children += Region().apply { styleClass += "poster-ring"; setMinSize(width, height) }
        }
        roundedClip(frame, 10.0)
        val name = label(title, "poster-title").apply {
            isWrapText = true
            maxWidth = width
            maxHeight = 34.0
        }
        children.addAll(frame, name)
        frame.hoverGrow()
        onClick(onOpen)
    }

    companion object {
        fun of(item: SearchResponse, onOpen: () -> Unit) = PosterCard(
            item.name, item.posterUrl, item.posterHeaders,
            topLeft = item.score?.toDouble(10)?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
            topRight = qualityText(item.quality),
            onOpen = onOpen,
        )
    }
}

fun progressBar(value: Double, width: Double, height: Double = 4.0): Node {
    val track = Region().apply { styleClass += "progress-track"; setMinSize(width, height); setMaxSize(width, height) }
    val fill = Region().apply {
        styleClass += "progress-fill"
        val w = (width * value.coerceIn(0.0, 1.0)).coerceAtLeast(3.0)
        setMinSize(w, height); setMaxSize(w, height)
    }
    return StackPane(track, fill).apply { alignment = Pos.CENTER_LEFT; setMaxSize(width, height) }
}

/** 16:9 card with a title overlay, for Continue Watching. */
class WideCard(title: String, subtitle: String?, imageUrl: String?, progress: Double?, onOpen: () -> Unit) : StackPane() {
    init {
        val w = 300.0
        val h = 169.0
        styleClass += "poster-card"
        setMinSize(w, h); setMaxSize(w, h)
        val image = ImageView()
        Images.load(image, imageUrl, w, h)
        val shade = Region().apply {
            style = "-fx-background-color: linear-gradient(to bottom, transparent 35%, rgba(0,0,0,0.92) 100%);"
        }
        val text = vbox(2.0,
            label(title, "wide-title").apply { maxWidth = w - 28 },
            label(subtitle, "wide-sub").apply { maxWidth = w - 28; isVisible = !subtitle.isNullOrBlank() },
        ).apply { alignment = Pos.BOTTOM_LEFT; pad(0.0, 14.0, 16.0, 14.0) }
        val play = StackPane(icon(Icons.PLAY, 22.0)).apply {
            style = "-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 999; -fx-border-color: rgba(255,255,255,0.6); -fx-border-radius: 999;"
            setMaxSize(52.0, 52.0)
            opacity = 0.0
            (children[0] as Region).style += "-fx-background-color: white;"
        }
        val frame = StackPane(image, shade, text, play).apply { styleClass += "poster-frame"; setMinSize(w, h); setMaxSize(w, h) }
        if (progress != null) frame.children += progressBar(progress, w).also { StackPane.setAlignment(it, Pos.BOTTOM_LEFT) }
        frame.children += Region().apply { styleClass += "poster-ring" }
        roundedClip(frame, 12.0)
        children += frame
        frame.hoverGrow(1.03)
        setOnMouseEntered { FadeTransition(Duration.millis(150.0), play).apply { toValue = 1.0 }.play() }
        setOnMouseExited { FadeTransition(Duration.millis(150.0), play).apply { toValue = 0.0 }.play() }
        onClick(onOpen)
    }
}

/**
 * A titled horizontal row. Mouse-wheel scrolling passes through to the page, while
 * trackpad horizontal swipes, shift+wheel and the arrow buttons move the row.
 */
class Shelf(title: String, private val onViewAll: (() -> Unit)? = null, trailing: Node? = null) : VBox(12.0) {
    val row = HBox(14.0).apply { alignment = Pos.TOP_LEFT }
    private val viewport = Pane(row)
    private var target = 0.0
    private var animation: Timeline? = null

    init {
        val header = HBox(10.0, label(title, "shelf-title"), spacer()).apply { styleClass += "shelf-header" }
        trailing?.let { header.children += it }
        onViewAll?.let { header.children += label("View all", "link-button").onClick(it) }
        viewport.minHeightProperty().bind(row.heightProperty())
        viewport.prefHeightProperty().bind(row.heightProperty())
        val clip = Rectangle()
        clip.widthProperty().bind(viewport.widthProperty())
        clip.heightProperty().bind(viewport.heightProperty().add(30))
        clip.y = -14.0
        viewport.clip = clip

        val left = arrow(Icons.CHEVRON_LEFT) { scrollBy(-1) }
        val right = arrow(Icons.CHEVRON_RIGHT) { scrollBy(1) }
        val stack = StackPane(viewport, left, right)
        StackPane.setAlignment(left, Pos.CENTER_LEFT)
        StackPane.setAlignment(right, Pos.CENTER_RIGHT)
        left.opacity = 0.0
        right.opacity = 0.0
        stack.setOnMouseEntered { updateArrows(left, right, show = true) }
        stack.setOnMouseExited { left.opacity = 0.0; right.opacity = 0.0 }
        row.translateXProperty().addListener { _, _, _ -> if (stack.isHover) updateArrows(left, right, true) }

        viewport.addEventFilter(ScrollEvent.SCROLL) { e ->
            val dx = if (e.isShiftDown) e.deltaY else e.deltaX
            if (dx == 0.0) return@addEventFilter
            move(target + dx * 2.5)
            e.consume()
        }
        children.addAll(header, stack)
    }

    private fun updateArrows(left: Node, right: Node, show: Boolean) {
        left.opacity = if (show && row.translateX < -1) 1.0 else 0.0
        right.opacity = if (show && row.translateX > -(maxOffset() - 1)) 1.0 else 0.0
    }

    private fun maxOffset() = (row.width - viewport.width).coerceAtLeast(0.0)

    private fun arrow(path: String, action: () -> Unit) = Button().apply {
        styleClass += "shelf-arrow"
        graphic = icon(path, 22.0)
        setOnAction { action() }
    }

    private fun scrollBy(direction: Int) = move(target - direction * viewport.width * 0.8)

    private fun move(to: Double) {
        target = to.coerceIn(-maxOffset(), 0.0)
        animation?.stop()
        animation = Timeline(KeyFrame(Duration.millis(260.0), KeyValue(row.translateXProperty(), target, Interpolator.EASE_OUT))).apply { play() }
    }

    fun add(node: Node) {
        row.children += node
    }
}

/** Shimmer-free placeholder shown while a row loads. */
fun skeletonShelf(): Node {
    val row = HBox(14.0)
    repeat(8) { row.children += Region().apply { styleClass += "skeleton"; setMinSize(POSTER_W, POSTER_H) } }
    val title = Region().apply { styleClass += "skeleton"; setMinSize(180.0, 20.0); setMaxSize(180.0, 20.0) }
    return VBox(14.0, title, row).apply {
        val pulse = Timeline(
            KeyFrame(Duration.ZERO, KeyValue(opacityProperty(), 0.45)),
            KeyFrame(Duration.millis(800.0), KeyValue(opacityProperty(), 0.9)),
        )
        pulse.isAutoReverse = true
        pulse.cycleCount = Timeline.INDEFINITE
        pulse.play()
        sceneProperty().addListener { _, _, s -> if (s == null) pulse.stop() }
    }
}

/** Single-selection row of pill chips. */
class ChipGroup(private val onSelect: (Int) -> Unit) : HBox(8.0) {
    private val chips = mutableListOf<Label>()
    var selected = -1
        private set

    init {
        alignment = Pos.CENTER_LEFT
    }

    fun setItems(items: List<String>, select: Int = 0) {
        chips.clear()
        children.clear()
        items.forEachIndexed { i, text ->
            // Never shrink a chip into "S…": rows of chips scroll or wrap instead.
            val chip = label(text, "chip").onClick { select(i) }.apply { minWidth = Region.USE_PREF_SIZE }
            chips += chip
            children += chip
        }
        if (items.isNotEmpty()) select(select.coerceIn(0, items.lastIndex), notify = false)
    }

    fun select(index: Int, notify: Boolean = true) {
        if (index !in chips.indices) return
        chips.getOrNull(selected)?.styleClass?.remove("selected")
        selected = index
        chips[index].styleClass += "selected"
        if (notify) onSelect(index)
    }
}

/** iOS-style switch bound to a boolean. */
class Switch(initial: Boolean, onChange: (Boolean) -> Unit) : StackPane() {
    val on = SimpleBooleanProperty(initial)

    init {
        styleClass += "switch"
        val knob = Region().apply { styleClass += "switch-knob"; setMaxSize(18.0, 18.0); setMinSize(18.0, 18.0) }
        children += knob
        fun render(animated: Boolean) {
            styleClass.remove("on")
            if (on.get()) styleClass += "on"
            val x = if (on.get()) 10.0 else -10.0
            if (animated) animate(knob.translateXProperty(), x, 150.0) else knob.translateX = x
        }
        render(false)
        onClick {
            on.set(!on.get())
            render(true)
            onChange(on.get())
        }
    }
}

fun emptyState(iconPath: String, title: String, message: String, action: Node? = null): Node =
    VBox(12.0, icon(iconPath, 56.0), label(title, "h3"), label(message, "muted").apply { isWrapText = true; maxWidth = 420.0; alignment = Pos.CENTER }).apply {
        styleClass += "empty-state"
        alignment = Pos.CENTER
        pad(60.0)
        action?.let { children += it }
    }

/** Bottom-center notifications. */
object Toasts {
    lateinit var host: VBox

    fun show(message: String, action: Pair<String, () -> Unit>? = null, seconds: Double = 4.0) {
        if (!::host.isInitialized) return
        val box = hbox(14.0, label(message).apply { isWrapText = true; maxWidth = 520.0 }).apply {
            styleClass += "toast"
            maxWidth = javafx.scene.layout.Region.USE_PREF_SIZE
        }
        action?.let { (text, run) -> box.children += label(text, "link-button").onClick { run(); host.children.remove(box) } }
        box.opacity = 0.0
        host.children += box
        FadeTransition(Duration.millis(180.0), box).apply { toValue = 1.0 }.play()
        PauseTransition(Duration.seconds(seconds)).apply {
            setOnFinished {
                FadeTransition(Duration.millis(250.0), box).apply { toValue = 0.0; setOnFinished { host.children.remove(box) } }.play()
            }
        }.play()
    }
}
