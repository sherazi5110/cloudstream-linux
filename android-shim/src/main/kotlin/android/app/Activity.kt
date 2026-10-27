package android.app

import android.content.ContextWrapper
import java.io.File

open class Activity(dataDir: File) : ContextWrapper(dataDir) {
    open fun runOnUiThread(action: Runnable) = action.run()
    open fun isFinishing(): Boolean = false
    open fun isDestroyed(): Boolean = false
}
