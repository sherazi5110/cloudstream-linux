package com.lagradost.cloudstream3

import android.app.Activity
import android.widget.Toast

object CommonActivity {
    var activity: Activity? = null

    fun showToast(message: Int, duration: Int? = null) {
        activity?.let { showToast(it, it.getString(message), duration) }
    }

    fun showToast(message: String?, duration: Int? = null) = showToast(activity, message, duration)

    fun showToast(act: Activity?, message: Int, duration: Int? = null) {
        showToast(act, act?.getString(message), duration)
    }

    fun showToast(act: Activity?, message: String?, duration: Int? = null) {
        if (message == null) return
        Toast.makeText(act, message, duration ?: Toast.LENGTH_SHORT).show()
    }
}
