package com.gr4nt.aurabrowser.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gr4nt.aurabrowser.Browser
import com.gr4nt.aurabrowser.Prefs
import com.gr4nt.aurabrowser.TabState
import com.gr4nt.aurabrowser.Web
import com.gr4nt.aurabrowser.Snapshots
import com.gr4nt.aurabrowser.dl.Downloads
import kotlinx.coroutines.delay

enum class Form { Phone, Tablet, Desktop }

@Stable
class UiState {
    var switcher by mutableStateOf(false)
    var more by mutableStateOf(false)
    var settings by mutableStateOf(false)
    var downloads by mutableStateOf(false)
    var library by mutableStateOf(false)
    var libraryTab by mutableIntStateOf(0)
    var find by mutableStateOf(false)
    var editing by mutableStateOf(false)
    var sidebar by mutableStateOf(false)
    var sidebarTab by mutableIntStateOf(0)
    var privacy by mutableStateOf(false)
    var media by mutableStateOf(false)

    fun anySheet() = more || settings || downloads || library || privacy || media
    fun closeSheets() { more = false; settings = false; downloads = false; library = false; privacy = false; media = false }
}

/** Captures the visible page first so the tab switcher shows a fresh card for the current tab. */
fun openSwitcher(ui: UiState) {
    val t = Browser.current
    val wv = t?.webView
    if (t == null || wv == null || t.isBlank) { ui.switcher = true; return }
    Snapshots.capture(wv, 0.34f) { b ->
        if (b != null) t.thumbnail = b.asImageBitmap()
        ui.switcher = true
    }
}

object Shortcuts { var onKey: ((KeyEvent) -> Boolean)? = null }

@Composable
fun BrowserRoot() {
    val cfg = LocalConfiguration.current
    val form = when {
        cfg.screenWidthDp >= 1000 -> Form.Desktop
        cfg.screenWidthDp >= 600 -> Form.Tablet
        else -> Form.Phone
    }
    SideEffect { Web.largeScreen = form != Form.Phone }
    val ui = remember { UiState() }
    val ctx = LocalContext.current

    DisposableEffect(form) {
        Shortcuts.onKey = { e -> handleShortcut(e, ui, form) }
        onDispose { Shortcuts.onKey = null }
    }

    BackHandler(enabled = true) {
        val tab = Browser.current
        when {
            Browser.fullscreenView != null -> { Browser.fullscreenView = null }
            ui.editing -> ui.editing = false
            ui.switcher -> ui.switcher = false
            ui.anySheet() -> ui.closeSheets()
            ui.find -> { ui.find = false; tab?.let { Web.findClose(it) } }
            tab != null && Web.back(tab) -> {}
            tab != null && tab.openerId != null && Browser.tabs.size > 1 -> Browser.close(tab.id)
            else -> (ctx as? Activity)?.moveTaskToBack(true)
        }
    }

    Box(Modifier.fillMaxSize().background(Ink.solidBg())) {
        if (form == Form.Phone) PhoneLayout(ui) else LargeLayout(ui, form)
        Overlays(ui, form)
        ToastHost(form)
        FullscreenHost()
    }
}

private fun handleShortcut(e: KeyEvent, ui: UiState, form: Form): Boolean {
    if (e.action != KeyEvent.ACTION_DOWN) return false
    val tab = Browser.current ?: return false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    if (e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { Web.back(tab); return true }
    if (e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { Web.forward(tab); return true }
    if (!ctrl) return false
    when (e.keyCode) {
        KeyEvent.KEYCODE_L -> ui.editing = true
        KeyEvent.KEYCODE_T -> { Browser.newTab(); ui.editing = true }
        KeyEvent.KEYCODE_W -> Browser.close(tab.id)
        KeyEvent.KEYCODE_R -> Web.reload(tab)
        KeyEvent.KEYCODE_F -> ui.find = true
        KeyEvent.KEYCODE_D -> Browser.toggleBookmark(tab)
        KeyEvent.KEYCODE_J -> ui.downloads = true
        KeyEvent.KEYCODE_COMMA -> ui.settings = true
        KeyEvent.KEYCODE_TAB -> {
            val i = Browser.tabs.indexOfFirst { it.id == tab.id }
            val n = Browser.tabs.size
            if (n > 1) Browser.select(Browser.tabs[if (e.isShiftPressed) (i - 1 + n) % n else (i + 1) % n].id)
        }
        else -> return false
    }
    return true
}

@Composable
fun WebHost(tab: TabState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    androidx.compose.runtime.key(tab.id) {
        AndroidView(
            modifier = modifier,
            factory = { c -> FrameLayout(c) },
            update = { frame ->
                val wv = Web.ensure(ctx, tab)
                if (wv.parent !== frame) {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    frame.removeAllViews()
                    frame.addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                wv.onResume()
            },
            onRelease = { frame -> frame.removeAllViews() },
        )
    }
}

@Composable
private fun ToastHost(form: Form) {
    val msg = Browser.toast ?: Downloads.toast.value
    LaunchedEffect(msg) {
        if (msg != null) { delay(2400); if (Browser.toast == msg) Browser.toast = null; if (Downloads.toast.value == msg) Downloads.toast.value = null }
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(msg != null, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            GlassPanel(Modifier, CircleShape, tintAlpha = 0.7f, elevation = 14.dp) {
                Text(msg ?: "", color = Ink.primary(), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), maxLines = 2)
            }
        }
    }
}

@Composable
private fun FullscreenHost() {
    val v = Browser.fullscreenView ?: return
    val act = LocalContext.current as? Activity
    DisposableEffect(v) {
        val w = act?.window
        val c = w?.let { WindowInsetsControllerCompat(it, it.decorView) }
        c?.hide(WindowInsetsCompat.Type.systemBars())
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { c ->
            FrameLayout(c).also { f ->
                (v.parent as? ViewGroup)?.removeView(v)
                f.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        onRelease = { f -> f.removeAllViews() },
    )
}
