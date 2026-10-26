package android.os

open class Looper {
    companion object {
        private val main = Looper()
        @JvmStatic fun getMainLooper(): Looper = main
        @JvmStatic fun myLooper(): Looper = main
    }
}

open class Handler(@Suppress("UNUSED_PARAMETER") looper: Looper? = null) {
    open fun post(r: Runnable): Boolean { r.run(); return true }
    open fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        Thread { Thread.sleep(delayMillis); r.run() }.apply { isDaemon = true }.start()
        return true
    }
}
