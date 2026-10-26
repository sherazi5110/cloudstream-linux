package android.app

import android.content.Context
import java.io.File

open class Activity(dataDir: File) : Context(dataDir) {
    open fun runOnUiThread(action: Runnable) = action.run()
}
