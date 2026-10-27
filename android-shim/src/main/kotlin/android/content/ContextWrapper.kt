package android.content

import java.io.File

/** Same place in the hierarchy as on Android: Context <- ContextWrapper <- Activity. */
open class ContextWrapper(dataDir: File) : Context(dataDir) {
    open fun getBaseContext(): Context = this
}
