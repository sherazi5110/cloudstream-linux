package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.LiveTv
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.geometry.Pos
import javafx.scene.control.ScrollPane
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.RowConstraints
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle
import javafx.util.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The TV guide: channels down the side, time across the top, one block per programme.
 * The channel names and the time ruler stay put while the programmes scroll; a red line marks now.
 * Clicking a channel or one of its programmes plays the channel.
 */
class GuideGrid(private val channels: List<LiveTv.Channel>, private val onPlay: (LiveTv.Channel) -> Unit) : GridPane() {
    private val start = (System.currentTimeMillis() / HALF_HOUR) * HALF_HOUR - HALF_HOUR
    private val end = start + HOURS * 3600_000L
    private val width = x(end)
    private val body = Pane().apply { styleClass += "guide-body"; setMinSize(width, channels.size * ROW); setPrefSize(width, channels.size * ROW) }
    private val scroll = ScrollPane(body).apply { styleClass += "guide-scroll"; isPannable = false }
    private val ruler = Pane().apply { setMinSize(width, RULER); setPrefSize(width, RULER) }
    private val names = VBox().apply { minWidth = NAME; prefWidth = NAME }
    private val nowLine = Region().apply { styleClass += "guide-now"; isMouseTransparent = true }
    private val nowTick = Region().apply { styleClass += "guide-now"; isMouseTransparent = true }
    private val clock = Timeline(KeyFrame(Duration.seconds(30.0), { placeNow() })).apply { cycleCount = Timeline.INDEFINITE }

    init {
        styleClass += "guide"
        columnConstraints += ColumnConstraints(NAME, NAME, NAME)
        columnConstraints += ColumnConstraints().apply { hgrow = Priority.ALWAYS; minWidth = 0.0 }
        rowConstraints += RowConstraints(RULER, RULER, RULER)
        rowConstraints += RowConstraints().apply { vgrow = Priority.ALWAYS; minHeight = 0.0 }

        // Ruler: a label every half hour.
        var t = start
        while (t < end) {
            ruler.children += label(clock(t), if (t % 3600_000L == 0L) "guide-hour" else "guide-half").apply { layoutX = x(t) + 6; layoutY = 8.0 }
            ruler.children += Region().apply { styleClass += "guide-tick"; layoutX = x(t); layoutY = RULER - 10; setPrefSize(1.0, 10.0) }
            t += HALF_HOUR
        }
        ruler.children += nowTick

        channels.forEachIndexed { i, c ->
            names.children += hbox(10.0, label(c.number.takeIf { it > 0 }?.toString() ?: "", "guide-number"), label(c.name, "guide-channel")).apply {
                styleClass += "guide-name"
                if (i % 2 == 1) styleClass += "odd"
                setMinSize(NAME, ROW); setPrefSize(NAME, ROW); setMaxSize(NAME, ROW)
                pad(0.0, 12.0, 0.0, 14.0)
                onClick { onPlay(c) }
            }
            body.children += Region().apply {
                styleClass += "guide-row"
                if (i % 2 == 1) styleClass += "odd"
                layoutY = i * ROW; setPrefSize(width, ROW)
            }
            val programmes = LiveTv.programmes(c, start, end)
            if (programmes.isEmpty()) {
                body.children += block(c, i, start, end, "No guide information", null, null)
            } else programmes.forEach { p -> body.children += block(c, i, p.start, p.stop, p.title, "${clock(p.start)} – ${clock(p.stop)}", p.description) }
        }
        body.children += nowLine

        val corner = StackPane(button("Now", null, "guide-jump") { jumpToNow() }).apply { styleClass += "guide-corner"; alignment = Pos.CENTER_LEFT; pad(0.0, 0.0, 0.0, 10.0) }
        val rulerClip = clipped(ruler).apply { styleClass += "guide-ruler" }
        val namesClip = clipped(names)
        add(corner, 0, 0); add(rulerClip, 1, 0); add(namesClip, 0, 1); add(scroll, 1, 1)

        // Keep the ruler and the names in step with the programmes.
        val sync = {
            val vp = scroll.viewportBounds
            ruler.translateX = -scroll.hvalue * (width - vp.width).coerceAtLeast(0.0)
            names.translateY = -scroll.vvalue * (channels.size * ROW - vp.height).coerceAtLeast(0.0)
        }
        scroll.hvalueProperty().addListener { _, _, _ -> sync() }
        scroll.vvalueProperty().addListener { _, _, _ -> sync() }
        scroll.viewportBoundsProperty().addListener { _, _, _ -> sync() }
        placeNow()
        sceneProperty().addListener { _, _, s -> if (s != null) clock.play() else clock.stop() }
    }

    private fun block(c: LiveTv.Channel, row: Int, from: Long, to: Long, title: String, time: String?, desc: String?): Region {
        val now = System.currentTimeMillis()
        val left = x(from.coerceAtLeast(start)); val right = x(to.coerceAtMost(end))
        val box = VBox(2.0, label(title, "guide-title")).apply {
            styleClass += "guide-prog"
            when {
                time == null -> styleClass += "empty"
                now in from until to -> styleClass += "now"
                to <= now -> styleClass += "past"
            }
            if (time != null) children += label(time, "guide-time")
            layoutX = left + 2; layoutY = row * ROW + 3
            setPrefSize((right - left - 4).coerceAtLeast(2.0), ROW - 6); setMaxSize(prefWidth, prefHeight); minWidth = 0.0
            pad(7.0, 10.0, 7.0, 10.0)
            alignment = Pos.CENTER_LEFT
            onClick { onPlay(c) }
            tooltip(listOfNotNull(title, time, desc?.take(400)).joinToString("\n"))
        }
        box.clip = Rectangle().apply { widthProperty().bind(box.widthProperty()); heightProperty().bind(box.heightProperty()); arcWidth = 14.0; arcHeight = 14.0 }
        return box
    }

    private fun clipped(node: javafx.scene.Node) = Pane(node).apply {
        minWidth = 0.0; minHeight = 0.0
        clip = Rectangle().also { r -> r.widthProperty().bind(widthProperty()); r.heightProperty().bind(heightProperty()) }
    }

    private fun placeNow() {
        val nx = x(System.currentTimeMillis())
        nowLine.layoutX = nx; nowLine.setPrefSize(2.0, channels.size * ROW); nowLine.resize(2.0, channels.size * ROW)
        nowTick.layoutX = nx; nowTick.layoutY = 0.0; nowTick.setPrefSize(2.0, RULER); nowTick.resize(2.0, RULER)
    }

    /** Scrolls back so now is near the left edge. */
    fun jumpToNow() {
        val vp = scroll.viewportBounds.width
        val target = (x(System.currentTimeMillis()) - 60 * PX_PER_MIN).coerceAtLeast(0.0)
        scroll.hvalue = if (width > vp) (target / (width - vp)).coerceIn(0.0, 1.0) else 0.0
    }

    private fun x(t: Long) = (t - start) / 60_000.0 * PX_PER_MIN

    private fun clock(t: Long) = clockFormat.format(Instant.ofEpochMilli(t))

    companion object {
        const val PX_PER_MIN = 4.0
        const val ROW = 64.0
        const val NAME = 220.0
        const val RULER = 38.0
        const val HOURS = 12
        const val HALF_HOUR = 1800_000L
        /** More rows than this get slow; the page asks to narrow by category or search. */
        const val MAX_CHANNELS = 250
        private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    }
}
