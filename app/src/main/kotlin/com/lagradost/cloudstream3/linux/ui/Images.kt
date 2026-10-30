package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.linux.Network
import com.lagradost.cloudstream3.linux.Platform
import javafx.animation.FadeTransition
import javafx.geometry.Rectangle2D
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.stage.Screen
import javafx.util.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.IntBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Poster/backdrop loading that keeps scrolling smooth:
 * - downloads through the shared OkHttp client (DNS-over-HTTPS, poster headers), max 12 at once
 * - on a slow connection: the disk cache is read without waiting for a download slot, posters that
 *   left the screen (page closed, card reused) give their slot up, TMDB images are fetched at the
 *   size shown instead of "original", and a failed download is retried once
 * - decodes off the UI thread straight to the displayed size (never rescaled while painting)
 * - memory LRU + on-disk cache, WebP via ImageIO when JavaFX can't decode it
 */
object Images {
    private val scale: Double by lazy { runCatching { Screen.getPrimary().outputScaleX }.getOrDefault(1.0) }
    private val permits = Semaphore(12)
    private val memory = object : LinkedHashMap<String, Image>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>) = size > 500
    }
    private val diskDir by lazy { File(Platform.context.getCacheDir(), "images").apply { mkdirs() } }

    fun clearDisk() {
        diskDir.listFiles()?.forEach { it.delete() }
        synchronized(memory) { memory.clear() }
    }

    /**
     * Shows [url] in [view] cropped to cover [w]x[h] (like CSS object-fit: cover).
     * Stale results are dropped if the view was reused for another url meanwhile.
     */
    fun load(view: ImageView, url: String?, w: Double, h: Double, headers: Map<String, String>? = null) {
        view.fitWidth = w
        view.fitHeight = h
        view.isSmooth = true
        if (url.isNullOrBlank()) {
            (view.properties.remove(JOB) as? Job)?.cancel()
            view.properties.remove(RETRY)
            view.image = null
            return
        }
        val key = "$url@${w.toInt()}x${h.toInt()}"
        view.userData = key
        (view.properties.remove(JOB) as? Job)?.cancel()
        synchronized(memory) { memory[key] }?.let { show(view, it, w, h); return }
        view.image = null
        val job = ui.launch {
            val image = withContext(Dispatchers.IO) { fetch(url, headers, w * scale, h * scale) { view.userData == key && !left(view) } }
                ?: return@launch
            synchronized(memory) { memory[key] = image }
            if (view.userData == key) {
                show(view, image, w, h)
                FadeTransition(Duration.millis(220.0), view).apply { fromValue = 0.0; toValue = 1.0 }.play()
            }
        }
        view.properties[JOB] = job
        view.properties[RETRY] = { load(view, url, w, h, headers) }
        if (view.properties.put(WATCHED, true) == null) view.sceneProperty().addListener { _, _, scene ->
            if (scene == null) return@addListener
            view.properties[SHOWN] = true
            // Back on screen (e.g. after Back) with a download that was skipped meanwhile: try again.
            if (view.image == null && (view.properties[JOB] as? Job)?.isActive != true) (view.properties[RETRY] as? () -> Unit)?.invoke()
        }
    }

    private const val JOB = "cs.images.job"
    private const val SHOWN = "cs.images.shown"
    private const val WATCHED = "cs.images.watched"
    private const val RETRY = "cs.images.retry"

    /** Whether the view was on screen once and has been taken off since (its page was closed). */
    private fun left(view: ImageView): Boolean {
        if (view.scene != null) view.properties[SHOWN] = true
        return view.scene == null && view.properties[SHOWN] == true
    }

    /**
     * [url] decoded to cover [w]×[h] without cropping (show it with preserveRatio), e.g. a
     * channel logo. Null when it can't be downloaded or decoded (SVG, broken links).
     */
    suspend fun image(url: String, w: Double, h: Double): Image? {
        val key = "fit:$url@${w.toInt()}x${h.toInt()}"
        synchronized(memory) { memory[key] }?.let { return it }
        val image = withContext(Dispatchers.IO) { fetch(url, null, w * scale, h * scale) { true } } ?: return null
        synchronized(memory) { memory[key] = image }
        return image
    }

    private fun show(view: ImageView, image: Image, w: Double, h: Double) {
        view.image = image
        // Center-crop to the target aspect ratio.
        val iw = image.width
        val ih = image.height
        if (iw <= 0 || ih <= 0) return
        val target = w / h
        view.viewport = if (iw / ih > target) {
            val cw = ih * target
            Rectangle2D((iw - cw) / 2, 0.0, cw, ih)
        } else {
            val ch = iw / target
            Rectangle2D(0.0, (ih - ch) / 4, iw, ch) // bias toward the top: faces/titles live there
        }
    }

    /** Disk cache first; otherwise waits for a download slot, unless the poster is no longer [wanted] by then. */
    private suspend fun fetch(url: String, headers: Map<String, String>?, w: Double, h: Double, wanted: () -> Boolean): Image? = try {
        val bytes = readCached(url) ?: permits.withPermit {
            if (!withContext(Dispatchers.JavaFx) { wanted() }) return null
            downloadSized(url, headers, w)?.also { writeCached(url, it) }
        } ?: return null
        decode(bytes, w, h)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        System.err.println("W/images: $url: $e")
        null
    }

    /** TMDB serves every image in several widths: take the smallest that still covers [w] (falls back to the original). */
    internal fun tmdbSized(url: String, w: Double): String? {
        val m = TMDB.find(url) ?: return null
        val size = listOf(185, 342, 500, 780).firstOrNull { it >= w } ?: return null
        val current = m.groupValues[1].removePrefix("w").toIntOrNull()
        if (current != null && current <= size) return null
        return url.replaceRange(m.groups[1]!!.range, "w$size")
    }
    private val TMDB = Regex("""^https?://image\.tmdb\.org/t/p/(original|w\d+)/""")

    private suspend fun downloadSized(url: String, headers: Map<String, String>?, w: Double): ByteArray? {
        tmdbSized(url, w)?.let { small -> runCatching { download(small, headers) }.getOrNull()?.let { return it } }
        return try {
            download(url, headers)
        } catch (e: java.io.IOException) {
            delay(800) // one retry: slow or flaky connections often drop the first attempt
            download(url, headers)
        }
    }

    private fun decode(bytes: ByteArray, w: Double, h: Double): Image? {
        // Decode once, directly at the size needed to cover the box (dimensions come from the header).
        val (iw, ih) = dimensions(bytes) ?: return null
        val (rw, rh) = coverSize(iw.toDouble(), ih.toDouble(), w, h)
        val fx = Image(ByteArrayInputStream(bytes), rw, rh, true, true)
        if (!fx.isError && fx.width > 0) return fx
        // JavaFX lacks WebP/AVIF; ImageIO has WebP through webp-imageio.
        val buffered = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val (sw, sh) = coverSize(buffered.width.toDouble(), buffered.height.toDouble(), w, h)
        val scaled = java.awt.image.BufferedImage(sw.toInt().coerceAtLeast(1), sh.toInt().coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(buffered, 0, 0, scaled.width, scaled.height, null)
            dispose()
        }
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getRGB(0, 0, scaled.width, scaled.height, pixels, 0, scaled.width)
        return WritableImage(scaled.width, scaled.height).apply {
            pixelWriter.setPixels(0, 0, scaled.width, scaled.height, PixelFormat.getIntArgbInstance(), IntBuffer.wrap(pixels), scaled.width)
        }
    }

    private fun dimensions(bytes: ByteArray): Pair<Int, Int>? =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }

    private fun coverSize(iw: Double, ih: Double, w: Double, h: Double): Pair<Double, Double> {
        if (iw <= 0 || ih <= 0) return w to h
        val s = maxOf(w / iw, h / ih).coerceAtMost(1.0)
        return iw * s to ih * s
    }

    private fun download(url: String, headers: Map<String, String>?): ByteArray? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).apply {
            headers?.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.bytes()
        }
    }

    /** Shorter limits than page requests: a stuck poster shouldn't hold a download slot for long. */
    private val client by lazy {
        Network.client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    }

    private fun cacheFile(url: String) = File(diskDir, MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })

    private fun readCached(url: String): ByteArray? = cacheFile(url).takeIf { it.exists() }?.readBytes()

    private fun writeCached(url: String, bytes: ByteArray) {
        runCatching { cacheFile(url).writeBytes(bytes) }
    }
}
