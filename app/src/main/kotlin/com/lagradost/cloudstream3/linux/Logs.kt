package com.lagradost.cloudstream3.linux

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Copies everything written to stderr (android.util.Log, the library, extension stack
 * traces) into a log file for bug reports and the in-app log viewer.
 */
object Logs {
    val file: File get() = File(AppDirs.data, "logs/cloudstream.log")

    fun init() {
        file.parentFile.mkdirs()
        if (file.length() > 2_000_000) file.renameTo(File(file.parentFile, "cloudstream.old.log"))
        val log = FileOutputStream(file, true)
        val original = System.err
        val tee = object : OutputStream() {
            override fun write(b: Int) {
                original.write(b)
                log.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                original.write(b, off, len)
                log.write(b, off, len)
            }

            override fun flush() {
                original.flush()
                log.flush()
            }
        }
        System.setErr(PrintStream(tee, true, Charsets.UTF_8))
        System.err.println("---- cloudstream-linux started ${java.time.LocalDateTime.now()} (java ${System.getProperty("java.version")})")
    }

    fun tail(maxLines: Int = 400): List<String> =
        runCatching { file.readLines().takeLast(maxLines) }.getOrDefault(emptyList())
}
