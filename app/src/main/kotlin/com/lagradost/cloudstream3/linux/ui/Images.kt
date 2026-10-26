package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.linux.Network
import com.lagradost.cloudstream3.linux.Platform
import javafx.geometry.Rectangle2D
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.stage.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.IntBuffer
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Poster/backdrop loading that keeps scrolling smooth:
 * - downloads through the shared OkHttp client (DNS-over-HTTPS, poster headers), max 8 at once
 * - decodes off the UI thread straight to the displayed size (never rescaled while painting)
 * - memory LRU + on-disk cache, WebP via ImageIO when JavaFX can't decode it
 */
object Images {
    private val scale: Double by lazy { runCatching { Screen.getPrimary().outputScaleX }.getOrDefault(1.0) }
    private val permits = Semaphore(8)
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
            view.image = null
            return
        }
        val key = "$url@${w.toInt()}x${h.toInt()}"
        view.userData = key
        synchronized(memory) { memory[key] }?.let { show(view, it, w, h); return }
        view.image = null
        ui.launch {
            val image = withContext(Dispatchers.IO) { permits.withPermit { fetch(url, headers, w * scale, h * scale) } }
                ?: return@launch
            synchronized(memory) { memory[key] = image }
            if (view.userData == key) show(view, image, w, h)
        }
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

    private fun fetch(url: String, headers: Map<String, String>?, w: Double, h: Double): Image? = runCatching {
        val bytes = readCached(url) ?: download(url, headers)?.also { writeCached(url, it) } ?: return null
        decode(bytes, w, h)
    }.onFailure { System.err.println("W/images: $url: $it") }.getOrNull()

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
        Network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.bytes()
        }
    }

    private fun cacheFile(url: String) = File(diskDir, MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })

    private fun readCached(url: String): ByteArray? = cacheFile(url).takeIf { it.exists() }?.readBytes()

    private fun writeCached(url: String, bytes: ByteArray) {
        runCatching { cacheFile(url).writeBytes(bytes) }
    }
}
