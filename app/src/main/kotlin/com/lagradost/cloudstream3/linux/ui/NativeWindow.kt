package com.lagradost.cloudstream3.linux.ui

import javafx.stage.Stage

/**
 * The X11 window id of a JavaFX stage, so mpv can render into it (--wid). JavaFX always
 * runs on X11 (XWayland under Wayland sessions), and its glass windows expose the native
 * handle; we're on the classpath, so the internal API is reachable without --add-opens.
 */
object NativeWindow {
    fun xid(stage: Stage): Long? = runCatching {
        // Call through the public base class: the GTK subclass itself is package-private.
        val glass = Class.forName("com.sun.glass.ui.Window")
        val windows = glass.getMethod("getWindows").invoke(null) as List<*>
        val title = glass.getMethod("getTitle")
        val match = windows.firstOrNull { it != null && title.invoke(it) == stage.title } ?: windows.firstOrNull()
        match?.let { glass.getMethod("getNativeWindow").invoke(it) as Long }
    }.onFailure { System.err.println("W/player: no native window id: $it") }.getOrNull()?.takeIf { it != 0L }
}
