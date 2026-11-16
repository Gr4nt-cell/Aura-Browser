package com.gr4nt.aurabrowser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.ui.graphics.asImageBitmap

/** Page snapshots: tiny ones back the glass chrome, bigger ones are the tab-switcher cards. */
object Snapshots {
    private val main = Handler(Looper.getMainLooper())

    private fun activityOf(v: View): Activity? {
        var c: Context? = v.context
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    /** Captures [view] at [scale] (0..1). The callback runs on the main thread, with null on failure. */
    fun capture(view: View, scale: Float, cb: (Bitmap?) -> Unit) {
        val w = (view.width * scale).toInt().coerceAtLeast(1)
        val h = (view.height * scale).toInt().coerceAtLeast(1)
        if (view.width <= 0 || view.height <= 0 || !view.isShown) { cb(null); return }
        val act = activityOf(view)
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { cb(null); return }
        if (act != null && act.window != null) {
            try {
                PixelCopy.request(act.window, Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height), bmp, { r ->
                    if (r == PixelCopy.SUCCESS) cb(bmp) else cb(softwareDraw(view, scale))
                }, main)
                return
            } catch (_: Throwable) {
            }
        }
        cb(softwareDraw(view, scale))
    }

    private fun softwareDraw(view: View, scale: Float): Bitmap? = try {
        val bmp = Bitmap.createBitmap((view.width * scale).toInt().coerceAtLeast(1), (view.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        view.draw(c)
        bmp
    } catch (_: Throwable) { null }

    /** Refreshes the tab's thumbnail and the tiny glass-backdrop sample. */
    fun refresh(tab: TabState, thumbnail: Boolean = true) {
        val wv = tab.webView ?: return
        capture(wv, 0.08f) { b -> if (b != null) tab.sample = b.asImageBitmap() }
        if (thumbnail) capture(wv, 0.34f) { b -> if (b != null) tab.thumbnail = b.asImageBitmap() }
    }
}
