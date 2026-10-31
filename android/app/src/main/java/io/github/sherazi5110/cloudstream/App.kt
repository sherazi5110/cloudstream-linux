package io.github.sherazi5110.cloudstream

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App-wide state: the context extensions receive, the network setup and extension loading. */
object App {
    lateinit var context: Context
        private set

    /** Work that outlives a screen (loading extensions, installs). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Android TV (remote) or phone/tablet (touch): decides the layout. Read once per launch. */
    val isTv: Boolean by lazy {
        when (Settings.layout) {
            "tv" -> true
            "phone" -> false
            else -> detectTv()
        }
    }

    fun detectTv(): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun init(context: Context) {
        this.context = context
        Network.init()
        scope.launch {
            val failures = PluginManager.loadAll()
            if (failures.isNotEmpty()) Log.w("plugins", "Failed: ${failures.keys}")
        }
    }

    /** Shows a short message from anywhere, like extensions do. */
    fun toast(message: String) = CommonActivity.showToast(message)
}
