package com.gr4nt.aurabrowser

/** A video / audio stream noticed while a page loaded. */
data class MediaItem(val url: String, val hls: Boolean, val label: String)

/** Spots downloadable media in page requests (and via the page script) so it can be offered for download. */
object Media {
    private val ext = Regex("\\.(mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg|opus|flac|wav|m3u8)$", RegexOption.IGNORE_CASE)

    fun sniff(tab: TabState, url: String) {
        val path = url.substringBefore('#').substringBefore('?')
        val m = ext.find(path) ?: return
        add(tab, url, m.groupValues[1].lowercase() == "m3u8")
    }

    fun add(tab: TabState, url: String, hlsHint: Boolean = false) {
        if (!url.startsWith("http")) return
        val hls = hlsHint || url.substringBefore('?').endsWith(".m3u8", true)
        if (Prefs.blockAds && Blocker.shouldBlock(url, tab.pageHost)) return
        val name = url.substringBefore('?').substringAfterLast('/').ifEmpty { "media" }
        val label = if (hls) "Stream ($name)" else name
        Browser.post {
            if (tab.media.none { it.url == url }) {
                if (tab.media.size >= 40) tab.media.removeAt(0)
                tab.media.add(MediaItem(url, hls, label))
            }
        }
    }
}
