package com.gr4nt.aurabrowser

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class TabState(val id: String = UUID.randomUUID().toString().take(10), initialUrl: String = "", initialTitle: String = "") {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf(initialTitle)
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var favicon by mutableStateOf<ImageBitmap?>(null)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
    /** tiny (≈1/12 scale) image of the page, used as the backdrop of the glass chrome */
    var sample by mutableStateOf<ImageBitmap?>(null)
    var blocked by mutableIntStateOf(0)
    var desktop by mutableStateOf<Boolean?>(null)
    var findQuery by mutableStateOf("")
    var findMatches by mutableIntStateOf(0)
    var findIndex by mutableIntStateOf(0)

    @Volatile var pageHost: String? = null
    val blockedCounter = AtomicInteger(0)
    @Volatile var lastTouch = 0L
    @Volatile var lastPopupAt = 0L
    @Volatile var pageLoadedOnce = false
    @Volatile var currentUA: String = ""

    var webView by mutableStateOf<WebView?>(null)
    var pendingUrl: String? = if (initialUrl.isNotEmpty()) initialUrl else null
    var transport: WebView.WebViewTransport? = null
    var openerId: String? = null
    var lastActive = System.currentTimeMillis()

    val isBlank get() = url.isEmpty() || url == "about:blank"
    val displayHost: String get() = Blocker.hostOf(url)?.removePrefix("www.") ?: ""
    val secure get() = url.startsWith("https://")

    fun destroyWebView() {
        webView?.let { wv ->
            try { (wv.parent as? android.view.ViewGroup)?.removeView(wv) } catch (_: Exception) {}
            try { wv.stopLoading(); wv.loadUrl("about:blank"); wv.removeAllViews(); wv.destroy() } catch (_: Exception) {}
        }
        webView = null
    }
}

/** Anything the UI has to ask the user while a page waits for the answer. */
sealed class Prompt {
    class Permission(val origin: String, val wantsCamera: Boolean, val wantsMic: Boolean, val request: PermissionRequest) : Prompt()
    class Geo(val origin: String, val callback: GeolocationPermissions.Callback) : Prompt()
    class JsAlert(val origin: String, val message: String, val result: JsResult) : Prompt()
    class JsConfirm(val origin: String, val message: String, val result: JsResult) : Prompt()
    class JsPrompt(val origin: String, val message: String, val default: String, val result: JsPromptResult) : Prompt()
    class External(val label: String, val action: () -> Unit) : Prompt()
    class LinkMenu(val url: String?, val imageUrl: String?, val fromTab: String) : Prompt()
    class HttpAuth(val host: String, val realm: String, val onOk: (String, String) -> Unit, val onCancel: () -> Unit) : Prompt()
    class PopupBlocked(val url: String, val onAllow: () -> Unit) : Prompt()
}

object Browser {
    val tabs = mutableStateListOf<TabState>()
    var selectedId by mutableStateOf<String?>(null)
    val prompts = mutableStateListOf<Prompt>()
    val bookmarks = mutableStateListOf<Prefs.Entry>()
    val history = mutableStateListOf<Prefs.Entry>()
    var fullscreenView by mutableStateOf<View?>(null)
    var toast by mutableStateOf<String?>(null)

    /** Collapsing toolbar on phones: 0 = expanded, 1 = collapsed. Driven by scroll direction. */
    var chromeCollapsed by mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())

    val current: TabState? get() = tabs.firstOrNull { it.id == selectedId }

    fun restore() {
        if (tabs.isNotEmpty()) return
        bookmarks.clear(); bookmarks.addAll(Prefs.loadEntries("bookmarks"))
        history.clear(); history.addAll(Prefs.loadEntries("history"))
        val raw = Prefs.loadString("tabs")
        var sel: String? = null
        if (raw != null) runCatching {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("tabs")
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                tabs.add(TabState(t.getString("id"), t.optString("url"), t.optString("title")).also { it.desktop = if (t.has("desktop")) t.getBoolean("desktop") else null })
            }
            sel = o.optString("sel")
        }
        if (tabs.isEmpty()) tabs.add(TabState())
        selectedId = tabs.firstOrNull { it.id == sel }?.id ?: tabs.last().id
    }

    fun persist() {
        val arr = JSONArray()
        tabs.forEach {
            val o = JSONObject().put("id", it.id).put("url", it.url).put("title", it.title)
            it.desktop?.let { d -> o.put("desktop", d) }
            arr.put(o)
        }
        Prefs.saveString("tabs", JSONObject().put("tabs", arr).put("sel", selectedId ?: "").toString())
    }

    fun newTab(url: String = "", select: Boolean = true, openerId: String? = null): TabState {
        val t = TabState(initialUrl = url)
        t.openerId = openerId
        tabs.add(t)
        if (select) select(t.id)
        persist()
        return t
    }

    fun select(id: String) {
        selectedId = id
        current?.lastActive = System.currentTimeMillis()
        chromeCollapsed = false
        trimWebViews()
        persist()
    }

    fun close(id: String) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val t = tabs[idx]
        val wasSelected = selectedId == id
        tabs.removeAt(idx)
        t.destroyWebView()
        if (tabs.isEmpty()) {
            val n = TabState(); tabs.add(n); selectedId = n.id
        } else if (wasSelected) {
            val opener = t.openerId?.let { o -> tabs.firstOrNull { it.id == o } }
            selectedId = (opener ?: tabs[(idx - 1).coerceAtLeast(0).coerceAtMost(tabs.size - 1)]).id
        }
        persist()
    }

    fun closeAll() {
        tabs.toList().forEach { it.destroyWebView() }
        tabs.clear()
        val n = TabState(); tabs.add(n); selectedId = n.id
        persist()
    }

    /** Keeps at most a handful of live WebViews; the rest are rebuilt from their URL when needed. */
    private fun trimWebViews() {
        val live = tabs.filter { it.webView != null && it.id != selectedId }.sortedByDescending { it.lastActive }
        live.drop(4).forEach {
            it.pendingUrl = it.url.ifEmpty { null }
            it.destroyWebView()
        }
    }

    fun load(tab: TabState, input: String) {
        val url = resolveInput(input)
        if (url.isEmpty()) return
        tab.url = url
        tab.pendingUrl = null
        val wv = tab.webView
        if (wv != null) wv.loadUrl(url) else tab.pendingUrl = url
    }

    fun addHistory(title: String, url: String) {
        if (url.isEmpty() || url.startsWith("data:") || url == "about:blank") return
        val last = history.firstOrNull()
        if (last != null && last.url == url) return
        history.add(0, Prefs.Entry(title.ifEmpty { url }, url))
        while (history.size > 600) history.removeAt(history.size - 1)
        Prefs.saveEntries("history", history)
    }

    fun clearHistory() { history.clear(); Prefs.saveEntries("history", history) }

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    fun toggleBookmark(tab: TabState) {
        if (tab.isBlank) return
        val i = bookmarks.indexOfFirst { it.url == tab.url }
        if (i >= 0) bookmarks.removeAt(i) else bookmarks.add(0, Prefs.Entry(tab.title.ifEmpty { tab.displayHost }, tab.url))
        Prefs.saveEntries("bookmarks", bookmarks)
    }

    fun removeBookmark(e: Prefs.Entry) { bookmarks.remove(e); Prefs.saveEntries("bookmarks", bookmarks) }

    fun showToast(msg: String) { toast = msg }

    fun post(r: Runnable) { main.post(r) }

    /** Blocked counters are updated from WebView's IO threads; sync them to Compose state at a calm rate. */
    private var syncScheduled = false
    fun scheduleBlockedSync(tab: TabState) {
        if (syncScheduled) return
        syncScheduled = true
        main.postDelayed({
            syncScheduled = false
            val n = tab.blockedCounter.get()
            if (n != tab.blocked) {
                Prefs.totalBlocked += (n - tab.blocked).coerceAtLeast(0)
                tab.blocked = n
            }
        }, 350)
    }

    fun clearData(ctx: android.content.Context) {
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        tabs.forEach { it.webView?.apply { clearCache(true); clearFormData(); clearHistory() } }
        clearHistory()
        Prefs.save()
    }

    fun bitmapToImage(b: Bitmap?): ImageBitmap? = b?.asImageBitmap()

    fun hostOfUri(u: Uri?): String = u?.host ?: ""
}
