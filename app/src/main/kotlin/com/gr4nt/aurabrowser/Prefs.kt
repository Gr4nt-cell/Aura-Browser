package com.gr4nt.aurabrowser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

enum class ExternalLinks(val label: String) {
    Ask("Ask first"), Always("Open automatically"), Never("Never")
}

enum class SearchEngine(val label: String, val template: String) {
    Google("Google", "https://www.google.com/search?q=%s"),
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    Brave("Brave Search", "https://search.brave.com/search?q=%s"),
    Bing("Bing", "https://www.bing.com/search?q=%s"),
    Ecosia("Ecosia", "https://www.ecosia.org/search?q=%s"),
    Startpage("Startpage", "https://www.startpage.com/do/search?q=%s"),
}

/** Settings that are observable by Compose and persisted in SharedPreferences. */
object Prefs {
    private lateinit var sp: SharedPreferences

    var blockAds by mutableStateOf(true)
    var blockPopups by mutableStateOf(true)
    var blockRedirects by mutableStateOf(true)
    var hideCookieBanners by mutableStateOf(true)
    var blockThirdPartyCookies by mutableStateOf(false)
    var youtubeAdSkip by mutableStateOf(true)
    var externalLinks by mutableStateOf(ExternalLinks.Ask)
    var searchEngine by mutableStateOf(SearchEngine.Google)
    var downloadConnections by mutableStateOf(16)
    var desktopModePhone by mutableStateOf(false)
    var totalBlocked by mutableStateOf(0L)
    var allowSites by mutableStateOf<Set<String>>(emptySet())

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("aura", Context.MODE_PRIVATE)
        blockAds = sp.getBoolean("blockAds", true)
        blockPopups = sp.getBoolean("blockPopups", true)
        blockRedirects = sp.getBoolean("blockRedirects", true)
        hideCookieBanners = sp.getBoolean("hideCookie", true)
        blockThirdPartyCookies = sp.getBoolean("block3pc", false)
        youtubeAdSkip = sp.getBoolean("ytAds", true)
        externalLinks = runCatching { ExternalLinks.valueOf(sp.getString("external", "Ask")!!) }.getOrDefault(ExternalLinks.Ask)
        searchEngine = runCatching { SearchEngine.valueOf(sp.getString("search", "Google")!!) }.getOrDefault(SearchEngine.Google)
        downloadConnections = sp.getInt("connections", 16).coerceIn(1, 32)
        desktopModePhone = sp.getBoolean("desktopPhone", false)
        totalBlocked = sp.getLong("totalBlocked", 0)
        allowSites = sp.getStringSet("allowSites", emptySet())?.toSet() ?: emptySet()
    }

    fun save() {
        sp.edit()
            .putBoolean("blockAds", blockAds)
            .putBoolean("blockPopups", blockPopups)
            .putBoolean("blockRedirects", blockRedirects)
            .putBoolean("hideCookie", hideCookieBanners)
            .putBoolean("block3pc", blockThirdPartyCookies)
            .putBoolean("ytAds", youtubeAdSkip)
            .putString("external", externalLinks.name)
            .putString("search", searchEngine.name)
            .putInt("connections", downloadConnections)
            .putBoolean("desktopPhone", desktopModePhone)
            .putLong("totalBlocked", totalBlocked)
            .putStringSet("allowSites", allowSites)
            .apply()
    }

    fun toggleSiteProtection(host: String) {
        val h = host.lowercase(Locale.ROOT).removePrefix("www.")
        allowSites = if (h in allowSites) allowSites - h else allowSites + h
        save()
    }

    fun isSiteAllowed(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase(Locale.ROOT).removePrefix("www.")
        return allowSites.any { h == it || h.endsWith(".$it") }
    }

    // ---- bookmarks & history (small JSON blobs; plenty for a browser of this size) ----

    data class Entry(val title: String, val url: String, val time: Long = System.currentTimeMillis())

    fun loadEntries(key: String): List<Entry> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Entry(o.optString("t"), o.getString("u"), o.optLong("d"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveEntries(key: String, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    fun loadString(key: String): String? = sp.getString(key, null)
    fun saveString(key: String, value: String?) { sp.edit().putString(key, value).apply() }

    fun searchUrl(query: String): String =
        searchEngine.template.replace("%s", URLEncoder.encode(query, "UTF-8"))
}

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(v, u[i]) else "%.1f %s".format(v, u[i])
}

/** Turns whatever the user typed into a URL or a search. */
fun resolveInput(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(t)) return t
    if (t.startsWith("about:") || t.startsWith("file:") || t.startsWith("data:")) return t
    val hostLike = Regex("^(localhost|(\\d{1,3}\\.){3}\\d{1,3}|([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,})(:\\d+)?([/?#].*)?$")
    if (!t.contains(' ') && hostLike.matches(t)) {
        val local = t.startsWith("localhost") || Regex("^(\\d{1,3}\\.){3}\\d{1,3}.*").matches(t)
        return (if (local) "http://" else "https://") + t
    }
    return Prefs.searchUrl(t)
}
