package io.github.sherazi5110.cloudstream

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lagradost.cloudstream3.CommonActivity
import io.github.sherazi5110.cloudstream.ui.AppRoot
import io.github.sherazi5110.cloudstream.ui.CloudStreamTheme
import io.github.sherazi5110.cloudstream.ui.Nav
import io.github.sherazi5110.cloudstream.ui.PendingRepo
import io.github.sherazi5110.cloudstream.ui.Tab

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CommonActivity.activity = this
        handle(intent)
        setContent { CloudStreamTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        if (CommonActivity.activity === this) CommonActivity.activity = null
        super.onDestroy()
    }

    /** cloudstreamrepo://… and https://cs.repo/… links open the Add repository dialog. */
    private fun handle(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        PendingRepo.url = data
        Nav.select(Tab.EXTENSIONS)
    }
}
