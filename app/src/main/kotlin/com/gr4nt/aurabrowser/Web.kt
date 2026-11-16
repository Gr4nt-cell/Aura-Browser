package com.gr4nt.aurabrowser

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.asImageBitmap
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.gr4nt.aurabrowser.dl.Downloads
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the Activity must provide to the web layer. */
interface WebHost {
    fun chooseFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean
    fun requestPermissions(perms: Array<String>, done: (Map<String, Boolean>) -> Unit)
}

object Web {
    var host: WebHost? = null
    @Volatile var largeScreen = false
    private val main = Handler(Looper.getMainLooper())
    private var guardScript: String = ""
    private val grantedOrigins = HashSet<String>()

    private lateinit var defaultUa: String

    fun mobileUa(): String = defaultUa.replace("; wv", "").replace(" Version/4.0", "")
    fun desktopUa(): String {
        val v = Regex("Chrome/(\\d+)").find(defaultUa)?.groupValues?.get(1) ?: "131"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v.0.0.0 Safari/537.36"
    }

    fun isDesktop(tab: TabState): Boolean = tab.desktop ?: (largeScreen || Prefs.desktopModePhone)

    private fun loadGuard(ctx: Context) {
        if (guardScript.isEmpty()) guardScript = ctx.assets.open("js/guard.js").bufferedReader().use { it.readText() }
    }

    // ------------------------------------------------------------------ creation

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun ensure(ctx: Context, tab: TabState): WebView {
        tab.webView?.let { return it }
        loadGuard(ctx)
        val wv = WebView(ctx)
        if (!::defaultUa.isInitialized) defaultUa = wv.settings.userAgentString
        tab.webView = wv
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.isVerticalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER

        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setGeolocationEnabled(true)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.saveFormData = false
        applyMode(tab, wv, isDesktop(tab))

        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            try { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet()) } catch (_: Exception) {}
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            try { WebSettingsCompat.setSafeBrowsingEnabled(s, true) } catch (_: Exception) {}
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            try { WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false) } catch (_: Exception) {}
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, !Prefs.blockThirdPartyCookies)

        wv.addJavascriptInterface(Bridge(tab), "AuraBridge")
        var docStart = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { WebViewCompat.addDocumentStartJavaScript(wv, guardScript, setOf("*")); docStart = true } catch (_: Exception) {}
        }
        wv.webViewClient = Client(tab, docStart)
        wv.webChromeClient = Chrome(tab)
        wv.setDownloadListener { url, ua, cd, mime, _ -> startDownload(tab, wv, url, ua, cd, mime) }
        wv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_UP) {
                tab.lastTouch = System.currentTimeMillis()
                try {
                    val t = (wv.hitTestResult).type
                    if (t == WebView.HitTestResult.SRC_ANCHOR_TYPE || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) tab.lastAnchorTap = tab.lastTouch
                } catch (_: Exception) {}
            }
            false
        }
        wv.setOnLongClickListener { v -> onLongPress(tab, v as WebView) }
        var accum = 0
        wv.setOnScrollChangeListener { _, _, y, _, oy ->
            val dy = y - oy
            if (y <= 8) { Browser.chromeCollapsed = false; accum = 0 }
            else {
                if ((dy > 0) != (accum > 0)) accum = 0
                accum += dy
                if (accum > 70) { if (!Browser.chromeCollapsed) Browser.chromeCollapsed = true; accum = 0 }
                else if (accum < -35) { if (Browser.chromeCollapsed) Browser.chromeCollapsed = false; accum = 0 }
            }
        }
        wv.setFindListener { active, matches, _ -> tab.findIndex = active; tab.findMatches = matches }

        if (tab.transport == null) {
            val u = tab.pendingUrl ?: tab.url.takeIf { it.isNotEmpty() }
            tab.pendingUrl = null
            if (u != null) wv.loadUrl(u)
        }
        return wv
    }

    fun applyMode(tab: TabState, wv: WebView, desktop: Boolean) {
        val ua = if (desktop) desktopUa() else mobileUa()
        tab.currentUA = ua
        wv.settings.userAgentString = ua
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = desktop
    }

    fun setDesktop(tab: TabState, desktop: Boolean) {
        tab.desktop = desktop
        tab.webView?.let { applyMode(tab, it, desktop); it.reload() }
    }

    // ------------------------------------------------------------------ JS bridge

    class Bridge(private val tab: TabState) {
        @JavascriptInterface fun config(host: String): String {
            val h = tab.pageHost ?: host
            val off = Prefs.isSiteAllowed(h)
            return JSONObject().put("ads", Prefs.blockAds).put("popups", Prefs.blockPopups)
                .put("redirects", Prefs.blockRedirects).put("cookie", Prefs.hideCookieBanners)
                .put("yt", Prefs.youtubeAdSkip).put("off", off).put("pad", dockPadDp).toString()
        }
        @JavascriptInterface fun media(url: String) { Media.add(tab, url) }
        @JavascriptInterface fun cosmetic(): String = Blocker.cosmeticData.ifEmpty { "{}" }
        @JavascriptInterface fun blocked(kind: String, url: String) {
            tab.blockedCounter.incrementAndGet(); Browser.scheduleBlockedSync(tab)
        }
        @JavascriptInterface fun chunk(token: String, index: Int, data: String, last: Boolean) = BlobSaver.chunk(token, data, last)
        @JavascriptInterface fun fail(token: String) = BlobSaver.fail(token)
    }

    private object BlobSaver {
        class Session(val name: String, val mime: String, val file: File, val out: FileOutputStream)
        private val sessions = ConcurrentHashMap<String, Session>()

        fun start(wv: WebView, url: String, name: String, mime: String) {
            val dir = File(Downloads.app.cacheDir, "blob").also { it.mkdirs() }
            val token = UUID.randomUUID().toString()
            val f = File(dir, token)
            sessions[token] = Session(name, mime, f, FileOutputStream(f))
            val js = "(function(u,t){fetch(u).then(function(r){return r.blob()}).then(function(b){var n=b.size,c=262144,i=0;" +
                "function next(){if(i>=n){AuraBridge.chunk(t,-1,'',true);return;}var fr=new FileReader();" +
                "fr.onload=function(){var d=fr.result;AuraBridge.chunk(t,i,d.substring(d.indexOf(',')+1),false);i+=c;next();};" +
                "fr.readAsDataURL(b.slice(i,i+c));}next();}).catch(function(){AuraBridge.fail(t)})})(" +
                JSONObject.quote(url) + "," + JSONObject.quote(token) + ")"
            wv.evaluateJavascript(js, null)
            Browser.showToast("Saving $name")
        }

        fun chunk(token: String, data: String, last: Boolean) {
            val s = sessions[token] ?: return
            try {
                if (data.isNotEmpty()) s.out.write(Base64.decode(data, Base64.DEFAULT))
                if (last) {
                    s.out.close(); sessions.remove(token)
                    Downloads.saveLocal(s.file, s.name, s.mime)
                }
            } catch (e: Exception) { fail(token) }
        }

        fun fail(token: String) {
            sessions.remove(token)?.let { try { it.out.close() } catch (_: Exception) {}; it.file.delete() }
            Browser.post { Browser.showToast("Download failed") }
        }
    }

    private fun startDownload(tab: TabState, wv: WebView, url: String, ua: String, cd: String?, mime: String?) {
        when {
            url.startsWith("blob:") -> {
                val name = URLUtil.guessFileName(url, cd, mime).let { if (it.endsWith(".bin") && mime != null) it else it }
                BlobSaver.start(wv, url, name, mime ?: "application/octet-stream")
            }
            url.startsWith("data:") -> {
                try {
                    val head = url.substringBefore(',')
                    val body = url.substringAfter(',')
                    val bytes = if (head.contains(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
                    val m = head.removePrefix("data:").substringBefore(';').ifEmpty { mime ?: "application/octet-stream" }
                    val f = File(Downloads.app.cacheDir, "blob").also { it.mkdirs() }.let { File(it, UUID.randomUUID().toString()) }
                    f.writeBytes(bytes)
                    Downloads.saveLocal(f, URLUtil.guessFileName(url, cd, m), m)
                } catch (_: Exception) { Browser.showToast("Could not save this file") }
            }
            else -> Downloads.enqueue(url, ua.ifEmpty { tab.currentUA }, cd, mime, tab.url)
        }
    }

    // ------------------------------------------------------------------ long-press

    private fun onLongPress(tab: TabState, wv: WebView): Boolean {
        val r = wv.hitTestResult
        val extra = r.extra
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE ->
                Browser.prompts.add(Prompt.LinkMenu(extra, null, tab.id))
            WebView.HitTestResult.IMAGE_TYPE ->
                Browser.prompts.add(Prompt.LinkMenu(null, extra, tab.id))
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val h = Handler(Looper.getMainLooper()) { m ->
                    val link = m.data?.getString("url")
                    val src = m.data?.getString("src") ?: extra
                    Browser.prompts.add(Prompt.LinkMenu(link ?: extra, src, tab.id)); true
                }
                wv.requestFocusNodeHref(h.obtainMessage())
            }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ external apps

    object Externals {
        private fun browsers(pm: PackageManager): Set<String> {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
            return pm.queryIntentActivities(i, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
        }

        /** An installed non-browser app that claims this https link, if any. */
        fun appFor(ctx: Context, uri: Uri): Pair<String, CharSequence>? {
            return try {
                val pm = ctx.packageManager
                val i = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                val skip = browsers(pm) + ctx.packageName
                val list = pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
                    .filter { it.activityInfo.packageName !in skip }
                val ri = list.firstOrNull() ?: return null
                ri.activityInfo.packageName to ri.loadLabel(pm)
            } catch (_: Exception) { null }
        }

        fun openIn(ctx: Context, uri: Uri, pkg: String): Boolean = try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }

        /** "Open in another app": a chooser that leaves this browser out. */
        fun chooser(ctx: Context, url: String) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val c = Intent.createChooser(i, "Open in…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx.packageName, MainActivity::class.java.name)))
            try { ctx.startActivity(c) } catch (_: Exception) { Browser.showToast("No other app can open this link") }
        }

        fun share(ctx: Context, url: String) {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun launch(ctx: Context, intent: Intent, label: String, tab: TabState, fallback: String?) {
            val act = {
                try { ctx.startActivity(intent) }
                catch (_: Exception) {
                    if (fallback != null) Browser.load(tab, fallback) else Browser.showToast("No app found for this link")
                }
            }
            if (Prefs.externalLinks == ExternalLinks.Always) act() else Browser.prompts.add(Prompt.External(label, act))
        }
    }

    private fun handleCustomScheme(ctx: Context, tab: TabState, url: String, gesture: Boolean): Boolean {
        val recentTouch = System.currentTimeMillis() - tab.lastTouch < 3000
        if (!gesture && !recentTouch) { tab.blockedCounter.incrementAndGet(); Browser.scheduleBlockedSync(tab); return true }
        try {
            if (url.startsWith("intent:")) {
                val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.component = null
                i.selector = null
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                val fb = i.getStringExtra("browser_fallback_url")
                val pm = ctx.packageManager
                val resolved = pm.queryIntentActivities(i, 0).firstOrNull { it.activityInfo.packageName != ctx.packageName }
                if (resolved != null) {
                    Externals.launch(ctx, i, "Open in ${resolved.loadLabel(pm)}?", tab, fb)
                } else if (fb != null && (fb.startsWith("http://") || fb.startsWith("https://"))) {
                    Browser.load(tab, fb)
                } else if (i.`package` != null) {
                    val m = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${i.`package`}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    Externals.launch(ctx, m, "Get ${i.`package`} on Google Play?", tab, null)
                } else Browser.showToast("No app found for this link")
            } else {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val simple = url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:") || url.startsWith("geo:")
                val pm = ctx.packageManager
                val label = pm.queryIntentActivities(i, 0).firstOrNull()?.loadLabel(pm)
                if (label == null && !simple) { Browser.showToast("No app found for this link"); return true }
                val what = if (simple) url.substringBefore(':') else (label ?: "app").toString()
                if (simple) Browser.prompts.add(Prompt.External("Open $what link?") { try { ctx.startActivity(i) } catch (_: Exception) {} })
                else Externals.launch(ctx, i, "Open in $what?", tab, null)
            }
        } catch (_: Exception) { Browser.showToast("Can't open this link") }
        return true
    }

    // ------------------------------------------------------------------ WebViewClient

    @Volatile var dockPadDp = 0

    private val emptyResponse get() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private class Client(val tab: TabState, val docStart: Boolean) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val scheme = request.url.scheme
            if (scheme != "http" && scheme != "https") return null
            if (!request.isForMainFrame) Media.sniff(tab, url)
            if (request.isForMainFrame) {
                if (tab.openerId != null && !tab.pageLoadedOnce && Prefs.blockPopups && Blocker.isAdNavigation(url)) {
                    tab.blockedCounter.incrementAndGet()
                    Browser.post { Browser.scheduleBlockedSync(tab); Browser.close(tab.id) }
                    return emptyResponse
                }
                return null
            }
            if (!Prefs.blockAds) return null
            if (Blocker.shouldBlock(url, tab.pageHost)) {
                tab.blockedCounter.incrementAndGet()
                Browser.scheduleBlockedSync(tab)
                return emptyResponse
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val url = uri.toString()
            val scheme = uri.scheme ?: return false
            val ctx = view.context
            if (scheme == "http" || scheme == "https") {
                if (!request.isForMainFrame) return false
                val gesture = request.hasGesture()
                val ad = Blocker.isAdNavigation(url)
                if (Prefs.blockPopups && ad && (!gesture || request.isRedirect)) {
                    tab.blockedCounter.incrementAndGet(); Browser.scheduleBlockedSync(tab)
                    if (tab.openerId != null && !tab.pageLoadedOnce) Browser.post { Browser.close(tab.id) }
                    return true
                }
                if (Prefs.blockRedirects && !gesture && !request.isRedirect && tab.pageLoadedOnce &&
                    System.currentTimeMillis() - tab.lastTouch > 1800) {
                    val from = tab.pageHost
                    val to = uri.host
                    if (from != null && to != null && Blocker.registrable(from) != Blocker.registrable(to)) {
                        tab.blockedCounter.incrementAndGet(); Browser.scheduleBlockedSync(tab)
                        return true
                    }
                }
                if (gesture && !request.isRedirect && Prefs.externalLinks != ExternalLinks.Never) {
                    val app = Externals.appFor(ctx, uri)
                    if (app != null) {
                        val open = { if (!Externals.openIn(ctx, uri, app.first)) Browser.load(tab, url) }
                        if (Prefs.externalLinks == ExternalLinks.Always) open()
                        else Browser.prompts.add(Prompt.External("Open in ${app.second}?", open))
                        return true
                    }
                }
                return false
            }
            return when (scheme) {
                "about", "data", "blob", "javascript", "view-source" -> false
                "file", "content" -> true
                else -> handleCustomScheme(ctx, tab, url, request.hasGesture())
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url = if (url == "about:blank" && tab.url.isNotEmpty()) tab.url else url
            tab.pageHost = Blocker.hostOf(url)
            tab.loading = true
            tab.progress = 0.05f
            tab.blockedCounter.set(0)
            tab.blocked = 0
            Browser.post { tab.media.clear() }
            if (tab.openerId != null && Browser.selectedId != tab.id && !Blocker.isAdNavigation(url) && url != "about:blank") {
                Browser.select(tab.id)
            }
            if (!docStart) view.evaluateJavascript(Web.guardScript, null)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (!docStart) view.evaluateJavascript(Web.guardScript, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.loading = false
            tab.progress = 1f
            tab.pageLoadedOnce = true
            tab.canBack = view.canGoBack()
            tab.canForward = view.canGoForward()
            if (url != "about:blank" && !url.startsWith("data:")) {
                tab.url = url
                Browser.addHistory(view.title ?: "", url)
                Browser.persist()
            }
            if (Browser.selectedId == tab.id) main.postDelayed({ Snapshots.refresh(tab) }, 350)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (!url.startsWith("data:") && url != "about:blank") {
                tab.url = url
                tab.pageHost = Blocker.hostOf(url)
            }
            tab.canBack = view.canGoBack()
            tab.canForward = view.canGoForward()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val code = if (Build.VERSION.SDK_INT >= 23) error.errorCode else 0
            if (code == ERROR_UNSUPPORTED_SCHEME) return
            val desc = if (Build.VERSION.SDK_INT >= 23) error.description.toString() else "Error"
            view.loadDataWithBaseURL(request.url.toString(), errorPage(request.url.toString(), desc), "text/html", "UTF-8", request.url.toString())
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            view.loadDataWithBaseURL(error.url, errorPage(error.url ?: "", "This site's security certificate can't be trusted."), "text/html", "UTF-8", error.url)
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            Browser.prompts.add(Prompt.HttpAuth(host, realm, { u, p -> handler.proceed(u, p) }, { handler.cancel() }))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val u = tab.url
            tab.destroyWebView()
            tab.pendingUrl = u.ifEmpty { null }
            Browser.showToast("The page crashed and was reloaded")
            return true
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun errorPage(url: String, desc: String): String =
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>:root{color-scheme:light dark}body{font:16px -apple-system,Roboto,sans-serif;margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:linear-gradient(160deg,#f6f2ff,#e8f1ff);color:#1c1c24}
@media(prefers-color-scheme:dark){body{background:linear-gradient(160deg,#17151f,#0e1520);color:#f2f2f7}.card{background:rgba(255,255,255,.07)!important;border-color:rgba(255,255,255,.14)!important}}
.card{max-width:420px;margin:24px;padding:28px;border-radius:28px;background:rgba(255,255,255,.6);border:1px solid rgba(255,255,255,.8);backdrop-filter:blur(24px);-webkit-backdrop-filter:blur(24px);box-shadow:0 12px 40px rgba(60,40,120,.12)}
h1{font-size:22px;margin:0 0 8px}p{margin:6px 0;opacity:.75;word-break:break-word}a{display:inline-block;margin-top:16px;padding:12px 22px;border-radius:999px;background:#0a84ff;color:#fff;text-decoration:none;font-weight:600}</style></head>
<body><div class="card"><h1>Can't open the page</h1><p>${esc(desc)}</p><p>${esc(url)}</p><a href="${esc(url)}">Try again</a></div></body></html>"""

    // ------------------------------------------------------------------ WebChromeClient

    private class Chrome(val tab: TabState) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress / 100f
            if (newProgress >= 100) tab.loading = false
        }

        override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank()) tab.title = title }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) { if (icon != null) tab.favicon = icon.asImageBitmap() }

        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
            host?.chooseFiles(params, filePathCallback) ?: run { filePathCallback.onReceiveValue(null); false }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (Prefs.blockPopups && !Prefs.isSiteAllowed(tab.pageHost ?: "")) {
                val now = System.currentTimeMillis()
                // A real tap must have happened just now; delayed, dialog-style or rapid-fire windows are pop-ups/pop-unders.
                val stale = now - tab.lastTouch > 1500 || now - tab.lastAnchorTap > 1500 // only a tap on a real link may open a window
                val burst = now - tab.lastPopupAt < 2500
                if (!isUserGesture || isDialog || stale || burst) {
                    tab.blockedCounter.incrementAndGet(); Browser.scheduleBlockedSync(tab)
                    return false
                }
                tab.lastPopupAt = now
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val child = Browser.newTab("", select = false, openerId = tab.id)
            child.transport = transport
            val wv = ensure(view.context, child)
            transport.webView = wv
            resultMsg.sendToTarget()
            // OAuth-style popups should appear immediately; everything else waits for its first non-ad navigation.
            main.postDelayed({ if (Browser.tabs.any { it.id == child.id } && Browser.selectedId != child.id && child.url.isNotEmpty() && !Blocker.isAdNavigation(child.url)) Browser.select(child.id) }, 700)
            return true
        }

        override fun onCloseWindow(window: WebView) {
            Browser.tabs.firstOrNull { it.webView === window }?.let { t -> Browser.post { Browser.close(t.id) } }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val origin = request.origin.toString()
            val cam = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            val mic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if (!cam && !mic) {
                val ok = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }.toTypedArray()
                if (ok.isNotEmpty()) request.grant(ok) else request.deny()
                return
            }
            if (origin in grantedOrigins) grantMedia(request, cam, mic)
            else Browser.prompts.add(Prompt.Permission(origin, cam, mic, request))
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            Browser.prompts.add(Prompt.Geo(origin, callback))
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            Browser.fullscreenView = view
            fullscreenCallback = callback
        }

        override fun onHideCustomView() {
            Browser.fullscreenView = null
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            Browser.prompts.add(Prompt.JsAlert(Blocker.hostOf(url) ?: "", message, result)); return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            Browser.prompts.add(Prompt.JsConfirm(Blocker.hostOf(url) ?: "", message, result)); return true
        }

        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
            Browser.prompts.add(Prompt.JsPrompt(Blocker.hostOf(url) ?: "", message, defaultValue ?: "", result)); return true
        }

        override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
            result.confirm(); return true
        }
    }

    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    /** Called by the prompt UI once the user said yes. */
    fun grantMedia(request: PermissionRequest, cam: Boolean, mic: Boolean) {
        val need = ArrayList<String>()
        if (cam) need.add(android.Manifest.permission.CAMERA)
        if (mic) need.add(android.Manifest.permission.RECORD_AUDIO)
        val h = host
        if (h == null) { request.deny(); return }
        h.requestPermissions(need.toTypedArray()) { res ->
            val granted = ArrayList<String>()
            if (cam && res[android.Manifest.permission.CAMERA] == true) granted.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            if (mic && res[android.Manifest.permission.RECORD_AUDIO] == true) granted.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            main.post {
                if (granted.isEmpty()) request.deny()
                else { grantedOrigins.add(request.origin.toString()); request.grant(granted.toTypedArray()) }
            }
        }
    }

    fun grantGeo(origin: String, cb: GeolocationPermissions.Callback, allow: Boolean) {
        if (!allow) { cb.invoke(origin, false, false); return }
        val h = host ?: run { cb.invoke(origin, false, false); return }
        h.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) { res ->
            val ok = res.values.any { it }
            main.post { cb.invoke(origin, ok, false) }
        }
    }

    // ------------------------------------------------------------------ small helpers used by the UI

    fun reload(tab: TabState) { tab.webView?.let { if (tab.loading) it.stopLoading() else it.reload() } ?: tab.url.takeIf { it.isNotEmpty() }?.let { Browser.load(tab, it) } }
    fun back(tab: TabState): Boolean { val wv = tab.webView ?: return false; return if (wv.canGoBack()) { wv.goBack(); true } else false }
    fun forward(tab: TabState) { tab.webView?.let { if (it.canGoForward()) it.goForward() } }

    fun find(tab: TabState, q: String) {
        tab.findQuery = q
        if (q.isEmpty()) tab.webView?.clearMatches() else tab.webView?.findAllAsync(q)
    }
    fun findNext(tab: TabState, forward: Boolean) { tab.webView?.findNext(forward) }
    fun findClose(tab: TabState) { tab.findQuery = ""; tab.webView?.clearMatches() }
}
