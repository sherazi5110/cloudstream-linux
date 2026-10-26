package android.widget

import android.content.Context

class Toast private constructor(private val text: CharSequence?) {
    fun show() {
        text?.let { showHandler(it.toString()) }
    }

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        /** The desktop UI replaces this to show a notification. */
        @JvmStatic var showHandler: (String) -> Unit = { System.err.println("[toast] $it") }

        @JvmStatic fun makeText(context: Context?, text: CharSequence?, duration: Int) = Toast(text)
        @JvmStatic fun makeText(context: Context?, resId: Int, duration: Int) = Toast(context?.getString(resId))
    }
}
