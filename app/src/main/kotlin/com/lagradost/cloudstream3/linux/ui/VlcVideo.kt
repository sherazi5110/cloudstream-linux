package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Settings
import javafx.application.Platform
import javafx.scene.image.ImageView
import javafx.scene.image.PixelBuffer
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.Region
import javafx.scene.shape.Rectangle
import javafx.stage.Screen
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** libVLC, loaded once. Null when VLC isn't installed (sudo apt install vlc). */
object Vlc {
    val factory: MediaPlayerFactory? by lazy {
        runCatching {
            // vlcj only looks in the x86 library folders; add ARM ones (e.g. ARM Chromebooks).
            val dirs = listOf("/usr/lib/aarch64-linux-gnu", "/usr/lib/arm-linux-gnueabihf", "/usr/lib/riscv64-linux-gnu")
                .filter { java.io.File(it, "libvlc.so.5").exists() }
            if (dirs.isNotEmpty()) {
                val current = System.getProperty("jna.library.path")
                System.setProperty("jna.library.path", (listOfNotNull(current) + dirs).joinToString(java.io.File.pathSeparator))
            }
            if (!NativeDiscovery().discover()) return@runCatching null
            val args = buildList {
                add("--quiet")
                add("--no-video-title-show")       // no file name flashed over the video
                add("--no-snapshot-preview")
                add("--no-xlib")                   // frames go to callbacks; never touch X11 from VLC
                add("--avcodec-hw=any")            // VAAPI/VDPAU when available, software otherwise
                add("--freetype-rel-fontsize=16")  // subtitle size (VLC's "normal" is 16)
                add("--freetype-outline-thickness=4")
                if (Settings.batterySaver) {
                    add("--avcodec-skiploopfilter=4")  // skip H.264 deblocking: much less CPU
                    add("--avcodec-fast")
                }
            }
            MediaPlayerFactory(args)
        }.onFailure { System.err.println("W/vlc: libVLC not available: $it") }.getOrNull()
    }

    val version: String? get() = factory?.application()?.version()
}

enum class Zoom(val label: String, val aspect: Double?) {
    FIT("Fit", null), FILL("Fill (crop)", null), STRETCH("Stretch", null), ORIGINAL("Original size", null),
    R16_9("16:9", 16.0 / 9), R4_3("4:3", 4.0 / 3), R21_9("21:9", 21.0 / 9),
}

/**
 * Shows libVLC's video inside the JavaFX scene.
 *
 * VLC's "vmem" output (modules/video_output/vmem.c) calls back for each frame instead of
 * opening a window. Frames are BGRA, written straight into a buffer that a JavaFX
 * PixelBuffer wraps, so a new frame costs one texture upload and no copy in Java.
 * VLC blends subtitles into the frame itself (src/video_output/video_output.c).
 * Frames larger than the screen are scaled down by VLC first: a 4K stream on a 1080p
 * screen moves a quarter of the pixels through the CPU and GPU.
 */
class VideoView : Region() {
    private val image = ImageView().apply { isSmooth = true; isPreserveRatio = false; isCache = false }
    @Volatile private var pixelBuffer: PixelBuffer<ByteBuffer>? = null
    private val pending = AtomicBoolean(false)
    @Volatile private var closed = false
    private var bufferW = 0
    private var bufferH = 0
    /** Display size: buffer size corrected for non-square pixels (anamorphic video). */
    private var displayW = 0.0
    private var displayH = 0.0

    var zoom: Zoom = Zoom.FIT
        set(v) { field = v; requestLayout() }

    init {
        style = "-fx-background-color: black;"
        children += image
        clip = Rectangle().also { it.widthProperty().bind(widthProperty()); it.heightProperty().bind(heightProperty()) }
    }

    val surface: CallbackVideoSurface = CallbackVideoSurface(
        object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                val (w, h) = fitToScreen(sourceWidth, sourceHeight)
                bufferW = w; bufferH = h
                return BufferFormat("BGRA", w, h, intArrayOf(w * 4), intArrayOf(h))
            }

            override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {
                // displayWidth/Height include the sample aspect ratio; scale them to our buffer.
                val sx = if (displayWidth > 0) displayWidth.toDouble() / max(1, bufferWidth) else 1.0
                val sy = if (displayHeight > 0) displayHeight.toDouble() / max(1, bufferHeight) else 1.0
                val ratio = (sx / sy).takeIf { it.isFinite() && it > 0 } ?: 1.0
                Platform.runLater {
                    displayW = bufferW * ratio
                    displayH = bufferH.toDouble()
                    requestLayout()
                }
            }

            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {
                val pb = PixelBuffer(bufferW, bufferH, buffers[0], PixelFormat.getByteBgraPreInstance())
                pixelBuffer = pb
                Platform.runLater {
                    if (displayW == 0.0) { displayW = bufferW.toDouble(); displayH = bufferH.toDouble() }
                    image.image = WritableImage(pb)
                    requestLayout()
                }
            }
        },
        object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) = Unit
            override fun unlock(mediaPlayer: MediaPlayer) = Unit
            override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
                // At most one repaint queued: if JavaFX falls behind, frames are dropped, not queued.
                if (closed || !pending.compareAndSet(false, true)) return
                val pb = pixelBuffer ?: return pending.set(false)
                Platform.runLater {
                    pending.set(false)
                    if (!closed && pb === pixelBuffer) pb.updateBuffer { null }
                }
            }
        },
        true,
        VideoSurfaceAdapters.getVideoSurfaceAdapter(),
    )

    /** Stop drawing before the player is released (its buffers are freed with it). */
    fun close() {
        closed = true
        pixelBuffer = null
        image.image = null
    }

    private fun fitToScreen(w: Int, h: Int): Pair<Int, Int> {
        val screens = runCatching { Screen.getScreens() }.getOrNull().orEmpty()
        val maxW = screens.maxOfOrNull { it.bounds.width * it.outputScaleX } ?: 1920.0
        val maxH = screens.maxOfOrNull { it.bounds.height * it.outputScaleY } ?: 1080.0
        val scale = min(1.0, min(maxW / w, maxH / h))
        if (scale >= 1.0) return w to h
        // Even sizes keep VLC's chroma converter happy.
        return ((w * scale).roundToInt() and 1.inv()).coerceAtLeast(2) to ((h * scale).roundToInt() and 1.inv()).coerceAtLeast(2)
    }

    override fun layoutChildren() {
        val w = width
        val h = height
        if (displayW <= 0 || displayH <= 0 || w <= 0 || h <= 0) return
        val aspect = zoom.aspect ?: (displayW / displayH)
        val (iw, ih) = when (zoom) {
            Zoom.STRETCH -> w to h
            Zoom.ORIGINAL -> displayW to displayH
            Zoom.FILL -> max(w / aspect, h).let { hh -> hh * aspect to hh }
            else -> min(w / aspect, h).let { hh -> hh * aspect to hh }
        }
        image.fitWidth = iw
        image.fitHeight = ih
        image.relocate((w - iw) / 2, (h - ih) / 2)
    }
}
