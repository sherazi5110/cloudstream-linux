package com.lagradost.cloudstream3.linux

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import java.io.File

/** Where things live on disk, following the XDG base directory spec. */
object AppDirs {
    val data: File = File(
        System.getProperty("cloudstream.home")
            ?: System.getenv("CLOUDSTREAM_HOME")
            ?: ((System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share") + "/cloudstream-linux")
    )
}

/**
 * The single Context every plugin receives, the Linux equivalent of the Android
 * MainActivity. Its files dir mirrors Android's layout: files/Extensions/<repo>/<plugin>.cs3
 */
object Platform {
    val context: Context by lazy {
        AppCompatActivity(AppDirs.data).also {
            CloudStreamApp.context = it
            CommonActivity.activity = it
        }
    }

    fun init() {
        context
    }
}
