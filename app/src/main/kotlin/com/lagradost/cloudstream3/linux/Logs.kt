package com.lagradost.cloudstream3.linux

import java.io.File
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Mirrors stderr (where android.util.Log and the library log) into a log file for bug reports. */
object Logs {
    val file: File get() = File(AppDirs.data, "logs/cloudstream.log")

    fun init() {
        file.parentFile.mkdirs()
        if (file.length() > 2_000_000) file.renameTo(File(file.parentFile, "cloudstream.old.log"))
        val out = java.io.FileOutputStream(file, true)
        val stamp = DateTimeFormatter.ofPattern("HH:mm:ss")
        val original = System.err
        System.setErr(object : PrintStream(original, true) {
            override fun println(x: String?) {
                original.println(x)
                synchronized(out) { out.write("${LocalDateTime.now().format(stamp)} $x\n".toByteArray()) }
            }
        })
        System.err.println("---- cloudstream-linux started (java ${System.getProperty("java.version")})")
    }
}
