package android.content

import android.content.res.Resources
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Stand-in for android.content.Context. One instance backs the whole app; every
 * plugin gets it in Plugin.load(context), exactly like on Android.
 */
open class Context(val dataDir: File) {
    private val prefs = ConcurrentHashMap<String, FileSharedPreferences>()

    open fun getFilesDir(): File = File(dataDir, "files").apply { mkdirs() }
    open fun getCacheDir(): File = File(dataDir, "cache").apply { mkdirs() }
    open fun getExternalFilesDir(type: String?): File? = getFilesDir()
    open fun getApplicationContext(): Context = this
    open fun getPackageName(): String = "com.lagradost.cloudstream3"
    open fun getResources(): Resources = Resources.EMPTY
    open fun getString(resId: Int): String = getResources().getString(resId)
    open fun getClassLoader(): ClassLoader = Context::class.java.classLoader

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { FileSharedPreferences(File(dataDir, "prefs/$name.json")) }

    companion object {
        const val MODE_PRIVATE = 0
    }
}
