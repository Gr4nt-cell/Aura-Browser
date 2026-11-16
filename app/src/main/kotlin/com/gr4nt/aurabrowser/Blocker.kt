package com.gr4nt.aurabrowser

import android.content.Context
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Request-level ad / tracker / popup-domain blocker.
 *
 * Data (compiled by tools/compile_filters.py from EasyList, EasyPrivacy, uBlock filters, AdGuard base
 * servers and StevenBlack hosts) is loaded from assets/filters on a background thread:
 *
 *   domains.txt  - the domain and all of its subdomains are blocked
 *   rules.txt    - host+path prefixes (H), plain substrings (S), regexes (R), optional third-party flag
 *   allow.txt    - exceptions in the same format
 *   popups.txt   - hosts / substrings that may never open as a pop-up or script redirect
 */
object Blocker {
    @Volatile var ready = false
        private set

    private val domains = HashSet<String>(200_000)
    private val hostRules = HashMap<String, MutableList<Rule>>()
    private val substrings = ArrayList<Rule>()
    private val regexes = ArrayList<Pair<Pattern, Boolean>>()
    private val allowHost = HashMap<String, MutableList<Rule>>()
    private val allowSub = ArrayList<Rule>()
    private val popupHosts = HashSet<String>()
    private val popupSubs = ArrayList<String>()
    private val cache = ConcurrentHashMap<String, Boolean>()

    private class Rule(val pattern: String, val thirdOnly: Boolean)

    /** JSON for the page-side cosmetic filter, built once and handed to the main frame of every page. */
    @Volatile var cosmeticData: String = ""
        private set
    @Volatile var siteHide: Map<String, String> = emptyMap()
        private set

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ load(app) }, "aura-blocker-load").apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }

    private fun lines(ctx: Context, name: String, block: (String) -> Unit) {
        ctx.assets.open("filters/$name").bufferedReader().useLines { seq -> seq.forEach { if (it.isNotEmpty()) block(it) } }
    }

    private fun load(ctx: Context) {
        try {
            lines(ctx, "domains.txt") { domains.add(it) }
            lines(ctx, "rules.txt") { l -> parseRule(l, hostRules, substrings, true) }
            lines(ctx, "allow.txt") { l -> parseRule(l, allowHost, allowSub, false) }
            lines(ctx, "popups.txt") { l -> if (l.contains('/') || l.startsWith(".") || l.startsWith("&")) popupSubs.add(l.lowercase(Locale.ROOT)) else popupHosts.add(l.lowercase(Locale.ROOT)) }

            val simple = Regex("^[.#][\\w-]+$")
            val sb = StringBuilder()
            val simpleAds = ArrayList<String>()
            val complex = ArrayList<String>()
            ctx.assets.open("filters/hide.css").bufferedReader().useLines { s -> s.forEach { if (it.isNotEmpty()) { if (simple.matches(it)) simpleAds.add(it) else complex.add(it) } } }
            val simpleCookie = ArrayList<String>()
            val complexCookie = ArrayList<String>()
            ctx.assets.open("filters/hide_cookie.css").bufferedReader().useLines { s -> s.forEach { if (it.isNotEmpty()) { if (simple.matches(it)) simpleCookie.add(it) else complexCookie.add(it) } } }
            val sites = HashMap<String, StringBuilder>()
            lines(ctx, "hide_sites.tsv") { l ->
                val t = l.indexOf('\t')
                if (t > 0) sites.getOrPut(l.substring(0, t)) { StringBuilder() }.append(l, t + 1, l.length).append("\n")
            }
            siteHide = sites.mapValues { it.value.toString().trimEnd('\n') }

            sb.append("{")
            sb.append("\"simpleAds\":").append(jsString(simpleAds.joinToString(" "))).append(",")
            sb.append("\"complexAds\":").append(jsString(complex.joinToString("\n"))).append(",")
            sb.append("\"simpleCookie\":").append(jsString(simpleCookie.joinToString(" "))).append(",")
            sb.append("\"complexCookie\":").append(jsString(complexCookie.joinToString("\n"))).append(",")
            sb.append("\"sites\":").append(siteHideJson()).append("}")
            cosmeticData = sb.toString()
            ready = true
        } catch (e: Exception) {
            ready = true
        }
    }

    private fun siteHideJson(): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in siteHide) {
            if (!first) sb.append(',')
            first = false
            sb.append(jsString(k)).append(':').append(jsString(v))
        }
        return sb.append('}').toString()
    }

    private fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            ' ' -> sb.append("\\u2028")
            ' ' -> sb.append("\\u2029")
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    private fun parseRule(line: String, hosts: HashMap<String, MutableList<Rule>>, subs: ArrayList<Rule>, collectRegex: Boolean) {
        val p = line.split('\t', limit = 3)
        if (p.size < 3) return
        val third = p[1] == "t"
        when (p[0]) {
            "H" -> {
                val v = p[2]
                val slash = v.indexOf('/')
                val host = if (slash >= 0) v.substring(0, slash) else v
                val path = if (slash >= 0) v.substring(slash) else "/"
                hosts.getOrPut(host) { ArrayList(2) }.add(Rule(path, third))
            }
            "S" -> subs.add(Rule(p[2], third))
            "R" -> if (collectRegex) {
                try { regexes.add(Pattern.compile(p[2], Pattern.CASE_INSENSITIVE) to third) } catch (_: Exception) {}
            }
        }
    }

    // ---------------------------------------------------------------- matching

    private val twoLevel = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

    fun registrable(host: String): String {
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val last = parts[parts.size - 1]
        val second = parts[parts.size - 2]
        return if (last.length == 2 && second in twoLevel && parts.size >= 3) parts.takeLast(3).joinToString(".") else parts.takeLast(2).joinToString(".")
    }

    fun hostOf(url: String): String? {
        val i = url.indexOf("://")
        if (i < 0) return null
        var j = url.indexOf('/', i + 3)
        if (j < 0) j = url.indexOf('?', i + 3)
        if (j < 0) j = url.indexOf('#', i + 3)
        if (j < 0) j = url.length
        var h = url.substring(i + 3, j)
        val at = h.lastIndexOf('@')
        if (at >= 0) h = h.substring(at + 1)
        val c = h.lastIndexOf(':')
        if (c >= 0 && !h.endsWith("]")) h = h.substring(0, c)
        return h.lowercase(Locale.ROOT).ifEmpty { null }
    }

    private fun domainBlocked(host: String): Boolean {
        var h = host
        while (true) {
            if (domains.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    private fun hostRuleHit(map: HashMap<String, MutableList<Rule>>, host: String, rest: String, third: Boolean): Boolean {
        var h = host
        while (true) {
            val list = map[h]
            if (list != null) for (r in list) if ((!r.thirdOnly || third) && rest.startsWith(r.pattern)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** True when this sub-resource request should be dropped. Safe to call from any thread. */
    fun shouldBlock(url: String, pageHost: String?): Boolean {
        if (!ready) return false
        val host = hostOf(url) ?: return false
        if (pageHost != null && Prefs.isSiteAllowed(pageHost)) return false
        val key = url.length.toString() + "|" + (pageHost ?: "") + "|" + url
        cache[key]?.let { return it }
        val r = decide(url, host, pageHost)
        if (cache.size > 6000) cache.clear()
        cache[key] = r
        return r
    }

    private fun decide(url: String, host: String, pageHost: String?): Boolean {
        val third = pageHost == null || registrable(host) != registrable(pageHost)
        val afterHost = url.indexOf('/', url.indexOf("://") + 3).let { if (it < 0) "/" else url.substring(it) }
        val lower = url.lowercase(Locale.ROOT)
        val lowerRest = afterHost.lowercase(Locale.ROOT)

        // Exceptions first (but never for pure domain blocks coming from the first-party site).
        val domainHit = domainBlocked(host)
        var hit = domainHit
        if (!hit) hit = hostRuleHit(hostRules, host, lowerRest, third)
        if (!hit && third) {
            for (r in substrings) if (lower.contains(r.pattern)) { hit = true; break }
            if (!hit) for ((p, t) in regexes) if (p.matcher(url).find()) { hit = true; break }
        } else if (!hit) {
            for (r in substrings) if (!r.thirdOnly && lower.contains(r.pattern)) { hit = true; break }
        }
        if (!hit) return false
        if (hostRuleHit(allowHost, host, lowerRest, third)) return false
        for (r in allowSub) if ((!r.thirdOnly || third) && lower.contains(r.pattern)) return false
        // A first-party request to its own domain is never an ad (avoids breaking sites on hosts-list false positives).
        if (!third && domainHit && pageHost != null && host == pageHost) return false
        return true
    }

    /** Navigations (not sub-resources) to hosts that exist only to serve ads / redirects. */
    fun isAdNavigation(url: String): Boolean {
        if (!ready) return false
        val host = hostOf(url) ?: return false
        if (domainBlocked(host)) return true
        val lower = url.lowercase(Locale.ROOT)
        var h = host
        while (true) {
            if (popupHosts.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        for (s in popupSubs) if (lower.contains(s)) return true
        return false
    }

    fun clearCache() = cache.clear()

    fun stats(): String = "${domains.size} domains, ${hostRules.values.sumOf { it.size } + substrings.size + regexes.size} URL rules"
}
