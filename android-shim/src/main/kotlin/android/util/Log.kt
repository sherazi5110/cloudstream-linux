package android.util

import java.io.PrintWriter
import java.io.StringWriter

object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    @JvmStatic var minLevel = System.getenv("CS_LOG_LEVEL")?.toIntOrNull() ?: INFO

    private fun log(level: Int, tag: String?, msg: String?, tr: Throwable? = null): Int {
        if (level < minLevel) return 0
        val letter = "??VDIWE".getOrElse(level) { '?' }
        System.err.println("$letter/$tag: $msg" + (tr?.let { "\n" + getStackTraceString(it) } ?: ""))
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String?) = log(VERBOSE, tag, msg)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?) = log(VERBOSE, tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String?) = log(DEBUG, tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?) = log(DEBUG, tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String?) = log(INFO, tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?) = log(INFO, tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String?) = log(WARN, tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?) = log(WARN, tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?) = log(WARN, tag, null, tr)
    @JvmStatic fun e(tag: String?, msg: String?) = log(ERROR, tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?) = log(ERROR, tag, msg, tr)
    @JvmStatic fun isLoggable(tag: String?, level: Int) = level >= minLevel

    @JvmStatic fun getStackTraceString(tr: Throwable?): String {
        if (tr == null) return ""
        return StringWriter().also { tr.printStackTrace(PrintWriter(it)) }.toString()
    }
}
