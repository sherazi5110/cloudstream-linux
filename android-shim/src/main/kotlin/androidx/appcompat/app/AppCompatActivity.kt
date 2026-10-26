package androidx.appcompat.app

import android.app.Activity
import java.io.File

/** The Context handed to plugins, so `context as? AppCompatActivity` keeps working. */
open class AppCompatActivity(dataDir: File) : Activity(dataDir)
