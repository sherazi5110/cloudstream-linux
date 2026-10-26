package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/** Same shape as app/src/main/java/com/lagradost/cloudstream3/plugins/Plugin.kt upstream. */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    var resources: Resources? = null

    /** Android settings screens are fragments; the Linux UI shows these as "not supported". */
    var openSettings: ((context: Context) -> Unit)? = null
}
