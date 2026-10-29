package com.lagradost.cloudstream3.linux.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.Arc
import javafx.scene.shape.ArcType
import javafx.scene.shape.Circle
import javafx.scene.shape.Ellipse
import javafx.scene.shape.Polygon
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.shape.Shape
import javafx.scene.shape.StrokeLineCap
import javafx.scene.transform.Scale
import java.io.File

/**
 * Profile pictures: twelve simple characters drawn from shapes (so they stay sharp at any
 * size) on a colour of your choice, or a picture file. Stored as "character:color" or "file:<path>".
 */
object Avatars {
    val characters = listOf("smile", "cat", "bear", "panda", "robot", "alien", "ghost", "fox", "frog", "owl", "monster", "astro")
    val colors = linkedMapOf(
        "blue" to "#3b82f6", "red" to "#ef4444", "green" to "#10b981", "purple" to "#8b5cf6",
        "orange" to "#f97316", "pink" to "#ec4899", "teal" to "#14b8a6", "yellow" to "#eab308",
    )

    fun node(avatar: String, size: Double, round: Boolean = false): Node {
        val pane = Pane().apply {
            setMinSize(size, size); setPrefSize(size, size); setMaxSize(size, size)
            isPickOnBounds = false
        }
        val radius = if (round) size else size * 0.22
        pane.clip = Rectangle(size, size).apply { arcWidth = radius; arcHeight = radius }
        val file = avatar.removePrefix("file:").takeIf { avatar.startsWith("file:") }?.let(::File)?.takeIf { it.isFile }
        if (file != null) {
            pane.children += ImageView(Image(file.toURI().toString(), size * 2, size * 2, false, true, true)).apply {
                fitWidth = size; fitHeight = size; isSmooth = true
            }
            return pane
        }
        val (character, color) = avatar.split(':').let { (it.getOrNull(0) ?: "smile") to (it.getOrNull(1) ?: "blue") }
        val base = Color.web(colors[color] ?: colors.getValue("blue"))
        val bg = Rectangle(100.0, 100.0).apply {
            fill = LinearGradient(0.0, 0.0, 1.0, 1.0, true, CycleMethod.NO_CYCLE, Stop(0.0, base.brighter()), Stop(1.0, base.darker()))
        }
        val drawing = Group(listOf(bg) + draw(character, base)).apply { transforms += Scale(size / 100, size / 100, 0.0, 0.0) }
        pane.children += drawing
        return pane
    }

    private val ink = Color.web("#14121f")
    private val white = Color.WHITE

    private fun circle(x: Double, y: Double, r: Double, c: Color) = Circle(x, y, r, c)
    private fun ellipse(x: Double, y: Double, rx: Double, ry: Double, c: Color) = Ellipse(x, y, rx, ry).apply { fill = c }
    private fun poly(c: Color, vararg p: Double) = Polygon(*p).apply { fill = c }
    private fun path(d: String, c: Color?, stroke: Color? = null, width: Double = 0.0) = SVGPath().apply {
        content = d; fill = c; if (stroke != null) { this.stroke = stroke; strokeWidth = width; strokeLineCap = StrokeLineCap.ROUND }
    }
    private fun eyes(y: Double = 54.0, dx: Double = 10.0, r: Double = 4.2) = listOf(circle(50 - dx, y, r, ink), circle(50 + dx, y, r, ink))
    private fun cheeks(c: Color, y: Double = 63.0) = listOf(circle(30.0, y, 4.5, c.deriveColor(0.0, 1.0, 1.0, 0.55)), circle(70.0, y, 4.5, c.deriveColor(0.0, 1.0, 1.0, 0.55)))

    private fun draw(character: String, base: Color): List<Shape> {
        val pinkish = Color.web("#ff8fab")
        return when (character) {
            "cat" -> listOf(
                poly(white, 24.0, 44.0, 28.0, 16.0, 46.0, 34.0), poly(white, 76.0, 44.0, 72.0, 16.0, 54.0, 34.0),
                poly(pinkish, 29.0, 37.0, 30.0, 24.0, 40.0, 33.0), poly(pinkish, 71.0, 37.0, 70.0, 24.0, 60.0, 33.0),
                ellipse(50.0, 58.0, 30.0, 26.0, white),
            ) + eyes(55.0, 11.0) + listOf(poly(pinkish, 46.0, 62.0, 54.0, 62.0, 50.0, 66.0), path("M44 69 Q47 72 50 68 Q53 72 56 69", null, ink, 2.6),
                path("M22 62 L34 63 M22 68 L34 66 M78 62 L66 63 M78 68 L66 66", null, ink, 1.8))
            "bear" -> listOf(circle(27.0, 33.0, 11.0, Color.web("#a0673f")), circle(73.0, 33.0, 11.0, Color.web("#a0673f")),
                circle(27.0, 33.0, 5.5, Color.web("#e9b48a")), circle(73.0, 33.0, 5.5, Color.web("#e9b48a")),
                ellipse(50.0, 57.0, 30.0, 28.0, Color.web("#a0673f")), ellipse(50.0, 66.0, 13.0, 10.0, Color.web("#e9b48a")),
                ellipse(50.0, 61.0, 5.0, 3.6, ink)) + eyes(51.0, 12.0, 3.8) + listOf(path("M45 70 Q50 74 55 70", null, ink, 2.6))
            "panda" -> listOf(circle(26.0, 32.0, 10.0, ink), circle(74.0, 32.0, 10.0, ink), ellipse(50.0, 57.0, 31.0, 28.0, white),
                ellipse(38.0, 54.0, 8.0, 10.0, ink).apply { rotate = 25.0 }, ellipse(62.0, 54.0, 8.0, 10.0, ink).apply { rotate = -25.0 },
                circle(39.0, 53.0, 3.0, white), circle(61.0, 53.0, 3.0, white), ellipse(50.0, 66.0, 4.5, 3.2, ink),
                path("M45 71 Q50 75 55 71", null, ink, 2.4))
            "robot" -> listOf(Rectangle(48.0, 14.0, 4.0, 14.0).apply { fill = white }, circle(50.0, 13.0, 5.0, Color.web("#ffd34d")),
                Rectangle(22.0, 28.0, 56.0, 48.0).apply { fill = white; arcWidth = 18.0; arcHeight = 18.0 },
                Rectangle(16.0, 44.0, 6.0, 16.0).apply { fill = Color.web("#d6dbe6"); arcWidth = 4.0; arcHeight = 4.0 },
                Rectangle(78.0, 44.0, 6.0, 16.0).apply { fill = Color.web("#d6dbe6"); arcWidth = 4.0; arcHeight = 4.0 },
                Rectangle(30.0, 40.0, 40.0, 16.0).apply { fill = ink; arcWidth = 12.0; arcHeight = 12.0 },
                circle(40.0, 48.0, 4.0, Color.web("#4ade80")), circle(60.0, 48.0, 4.0, Color.web("#4ade80")),
                Rectangle(38.0, 63.0, 24.0, 6.0).apply { fill = Color.web("#c7cdd9"); arcWidth = 6.0; arcHeight = 6.0 })
            "alien" -> listOf(path("M50 18 C72 18 82 36 80 52 C78 72 62 86 50 86 C38 86 22 72 20 52 C18 36 28 18 50 18 Z", Color.web("#9ef07a")),
                ellipse(37.0, 52.0, 10.0, 14.0, ink).apply { rotate = 30.0 }, ellipse(63.0, 52.0, 10.0, 14.0, ink).apply { rotate = -30.0 },
                circle(34.0, 47.0, 3.0, white), circle(60.0, 47.0, 3.0, white), path("M44 72 Q50 76 56 72", null, ink, 2.6))
            "ghost" -> listOf(path("M24 86 L24 46 C24 28 36 18 50 18 C64 18 76 28 76 46 L76 86 L67 78 L58 86 L50 78 L42 86 L33 78 Z", white),
                ellipse(41.0, 48.0, 5.0, 7.0, ink), ellipse(59.0, 48.0, 5.0, 7.0, ink), ellipse(50.0, 63.0, 5.0, 6.0, ink)) + cheeks(pinkish, 57.0)
            "fox" -> listOf(poly(Color.web("#ff8a3d"), 22.0, 48.0, 24.0, 16.0, 44.0, 34.0), poly(Color.web("#ff8a3d"), 78.0, 48.0, 76.0, 16.0, 56.0, 34.0),
                poly(ink, 27.0, 34.0, 26.0, 22.0, 36.0, 30.0), poly(ink, 73.0, 34.0, 74.0, 22.0, 64.0, 30.0),
                path("M50 30 C68 30 80 40 80 52 C80 64 62 82 50 84 C38 82 20 64 20 52 C20 40 32 30 50 30 Z", Color.web("#ff8a3d")),
                path("M50 84 C40 82 26 70 22 58 C32 62 42 64 50 72 C58 64 68 62 78 58 C74 70 60 82 50 84 Z", white),
                ellipse(50.0, 72.0, 5.0, 3.6, ink)) + eyes(54.0, 13.0, 3.8)
            "frog" -> listOf(circle(33.0, 34.0, 13.0, Color.web("#4ade80")), circle(67.0, 34.0, 13.0, Color.web("#4ade80")),
                ellipse(50.0, 60.0, 32.0, 24.0, Color.web("#4ade80")),
                circle(33.0, 34.0, 8.0, white), circle(67.0, 34.0, 8.0, white), circle(34.0, 35.0, 4.5, ink), circle(66.0, 35.0, 4.5, ink),
                path("M32 64 Q50 78 68 64", null, ink, 3.0)) + cheeks(pinkish, 66.0)
            "owl" -> listOf(poly(Color.web("#8b5a3c"), 24.0, 30.0, 34.0, 18.0, 38.0, 32.0), poly(Color.web("#8b5a3c"), 76.0, 30.0, 66.0, 18.0, 62.0, 32.0),
                ellipse(50.0, 56.0, 30.0, 30.0, Color.web("#8b5a3c")), ellipse(50.0, 66.0, 18.0, 18.0, Color.web("#e8c9a6")),
                circle(38.0, 47.0, 11.0, white), circle(62.0, 47.0, 11.0, white), circle(39.0, 48.0, 5.0, ink), circle(61.0, 48.0, 5.0, ink),
                poly(Color.web("#ffb020"), 45.0, 56.0, 55.0, 56.0, 50.0, 64.0))
            "monster" -> listOf(poly(white, 28.0, 34.0, 26.0, 14.0, 40.0, 28.0), poly(white, 72.0, 34.0, 74.0, 14.0, 60.0, 28.0),
                path("M50 24 C70 24 80 38 80 56 C80 74 68 84 50 84 C32 84 20 74 20 56 C20 38 30 24 50 24 Z", base.darker().darker()),
                circle(50.0, 48.0, 13.0, white), circle(50.0, 49.0, 6.0, ink), circle(52.0, 46.0, 2.0, white),
                path("M34 66 Q50 80 66 66 Z", ink), poly(white, 40.0, 68.0, 44.0, 68.0, 42.0, 73.0), poly(white, 56.0, 68.0, 60.0, 68.0, 58.0, 73.0))
            "astro" -> listOf(circle(50.0, 52.0, 32.0, white), Rectangle(28.0, 38.0, 44.0, 30.0).apply { fill = ink; arcWidth = 26.0; arcHeight = 26.0 },
                path("M34 46 Q38 41 46 41", null, Color.web("#7dd3fc"), 3.0), circle(41.0, 54.0, 3.5, Color.web("#7dd3fc")), circle(59.0, 54.0, 3.5, Color.web("#7dd3fc")),
                path("M44 61 Q50 65 56 61", null, Color.web("#7dd3fc"), 2.4), circle(78.0, 34.0, 5.0, Color.web("#ffd34d")))
            else -> listOf(circle(50.0, 54.0, 30.0, Color.web("#ffd34d"))) + eyes(50.0, 10.0, 4.0) + listOf(
                Arc(50.0, 58.0, 14.0, 12.0, 200.0, 140.0).apply { type = ArcType.OPEN; fill = null; stroke = ink; strokeWidth = 3.2; strokeLineCap = StrokeLineCap.ROUND },
            ) + cheeks(Color.web("#ff7a59"), 60.0)
        }
    }
}
