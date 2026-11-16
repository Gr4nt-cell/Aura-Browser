package com.gr4nt.aurabrowser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gr4nt.aurabrowser.Blocker
import com.gr4nt.aurabrowser.Browser
import com.gr4nt.aurabrowser.ExternalLinks
import com.gr4nt.aurabrowser.Prefs
import com.gr4nt.aurabrowser.SearchEngine
import com.gr4nt.aurabrowser.TabState
import com.gr4nt.aurabrowser.Web
import com.gr4nt.aurabrowser.dl.DownloadRow
import com.gr4nt.aurabrowser.dl.Downloads
import com.gr4nt.aurabrowser.dl.JobState
import com.gr4nt.aurabrowser.formatBytes

// ---------------------------------------------------------------------------------------------
//  Shared bits
// ---------------------------------------------------------------------------------------------

@Composable
internal fun SheetRow(
    label: String, ic: Ic?, onClick: (() -> Unit)?, modifier: Modifier = Modifier,
    danger: Boolean = false, sub: String? = null, trailing: (@Composable () -> Unit)? = null,
) {
    val tint = if (danger) Ink.danger else Ink.primary()
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ic != null) Icon(ic, if (danger) Ink.danger else Ink.accent, 19.dp)
        Column(Modifier.weight(1f).padding(start = if (ic != null) 13.dp else 0.dp)) {
            Text(label, color = tint, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Ink.tertiary(), fontSize = 11.5.sp, modifier = Modifier.padding(top = 1.dp))
        }
        trailing?.invoke()
    }
}

@Composable
internal fun ToggleRow(label: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SheetRow(label, null, { onChange(!checked) }, sub = sub) {
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Ink.green, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(), color = Ink.tertiary(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = when {
        selected -> Ink.accent
        LocalDark.current -> Color.White.copy(alpha = 0.12f)
        else -> Color.Black.copy(alpha = 0.07f)
    }
    Text(
        label, color = if (selected) Color.White else Ink.primary(), fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", text))
    Browser.showToast("Copied")
}

// ---------------------------------------------------------------------------------------------
//  More
// ---------------------------------------------------------------------------------------------

@Composable
fun MoreSheet(ui: UiState, form: Form) {
    val tab = Browser.current ?: return
    val ctx = LocalContext.current
    val close = { ui.more = false }
    GlassSheet(onDismiss = close, wide = form != Form.Phone) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetRow("New Tab", Ic.Plus, { close(); Browser.newTab(); ui.editing = true })
            if (!tab.isBlank) {
                SheetRow(if (Browser.isBookmarked(tab.url)) "Remove Bookmark" else "Add Bookmark", if (Browser.isBookmarked(tab.url)) Ic.StarFilled else Ic.Star, { close(); Browser.toggleBookmark(tab) })
                SheetRow("Share", Ic.Share, { close(); Web.Externals.share(ctx, tab.url) })
                SheetRow("Copy Link", Ic.Copy, { close(); copyText(ctx, tab.url) })
                SheetRow("Open in Another App", Ic.External, { close(); Web.Externals.chooser(ctx, tab.url) }, sub = "Choose any installed app that handles this link")
                SheetRow("Find on Page", Ic.Search, { close(); ui.find = true })
                SheetRow(if (Web.isDesktop(tab)) "Request Mobile Site" else "Request Desktop Site", Ic.Desktop, { close(); Web.setDesktop(tab, !Web.isDesktop(tab)) })
            }
            if (tab.media.isNotEmpty()) SheetRow("Save Video / Audio", Ic.Download, { close(); ui.media = true }, sub = "${tab.media.size} found on this page")
            SheetRow("Downloads", Ic.Download, { close(); ui.downloads = true }, sub = if (Downloads.hasActive()) "${formatBytes(Downloads.totalSpeed().toLong())}/s" else null)
            SheetRow("Bookmarks & History", Ic.Book, { close(); ui.library = true })
            SheetRow("Privacy Report", Ic.Shield, { close(); ui.privacy = true }, sub = "${Prefs.totalBlocked} blocked so far")
            SheetRow("Settings", Ic.Sliders, { close(); ui.settings = true })
        }
    }
}

// ---------------------------------------------------------------------------------------------
//  Settings
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsSheet(ui: UiState, form: Form) {
    val tab = Browser.current
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var conn by remember { mutableFloatStateOf(Prefs.downloadConnections.toFloat()) }
    val host = tab?.pageHost

    fun changed() { Prefs.save(); Blocker.clearCache() }

    GlassSheet(onDismiss = { ui.settings = false; Prefs.save() }, wide = form != Form.Phone, title = "Settings") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Blocking")
            ToggleRow("Block ads & trackers", "EasyList, EasyPrivacy, AdGuard and hosts lists", Prefs.blockAds) { Prefs.blockAds = it; changed() }
            ToggleRow("Block pop-ups", "New windows need your approval", Prefs.blockPopups) { Prefs.blockPopups = it; changed() }
            ToggleRow("Block ad redirects", "Stops pages that jump to ad sites on their own", Prefs.blockRedirects) { Prefs.blockRedirects = it; changed() }
            ToggleRow("Hide cookie banners", null, Prefs.hideCookieBanners) { Prefs.hideCookieBanners = it; changed() }
            ToggleRow("Skip YouTube ads", null, Prefs.youtubeAdSkip) { Prefs.youtubeAdSkip = it; changed() }
            ToggleRow("Block third-party cookies", null, Prefs.blockThirdPartyCookies) { v ->
                Prefs.blockThirdPartyCookies = v; changed()
                Browser.tabs.forEach { t -> t.webView?.let { CookieManager.getInstance().setAcceptThirdPartyCookies(it, !v) } }
            }
            if (!host.isNullOrEmpty()) {
                ToggleRow("Allow ads on $host", "Turns blocking off for this site only", Prefs.isSiteAllowed(host)) { Prefs.toggleSiteProtection(host); Blocker.clearCache() }
            }

            SectionLabel("Links")
            Column(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
                Text("Open links in other apps", color = Ink.primary(), fontSize = 16.sp)
                Text("When a link belongs to an installed app (YouTube, Maps, Play Store…)", color = Ink.tertiary(), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExternalLinks.entries.forEach { m -> Chip(m.label, Prefs.externalLinks == m) { Prefs.externalLinks = m; changed() } }
                }
            }

            SectionLabel("Search engine")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchEngine.entries.forEach { e -> Chip(e.label, Prefs.searchEngine == e) { Prefs.searchEngine = e; changed() } }
            }

            SectionLabel("Downloads")
            Column(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
                Text("${conn.toInt()} connections per download", color = Ink.primary(), fontSize = 16.sp)
                Text("More connections download faster until the server or your network is the limit.", color = Ink.tertiary(), fontSize = 12.sp)
                Slider(
                    value = conn, onValueChange = { conn = it }, valueRange = 1f..32f, steps = 30,
                    onValueChangeFinished = { Prefs.downloadConnections = conn.toInt(); changed() },
                )
            }

            SectionLabel("Browsing")
            ToggleRow("Always request desktop sites", "On phones", Prefs.desktopModePhone) { Prefs.desktopModePhone = it; changed() }
            SheetRow("Clear Browsing Data", Ic.Trash, { confirmClear = true }, danger = true, sub = "History, cookies, cache and site data")
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmClear) {
        GlassAlert(
            title = "Clear browsing data?", message = "This removes history, cookies, cache and site data. Bookmarks and downloads are kept.",
            confirm = "Clear", onConfirm = { Browser.clearData(ctx); confirmClear = false; Browser.showToast("Browsing data cleared") },
            dismiss = "Cancel", onDismiss = { confirmClear = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
//  Downloads
// ---------------------------------------------------------------------------------------------

@Composable
fun DownloadsSheet(ui: UiState, form: Form) {
    val rows = Downloads.rows
    val anyActive = rows.any { it.state == JobState.Downloading || it.state == JobState.Queued }
    val anyPaused = rows.any { it.state == JobState.Paused }
    GlassSheet(onDismiss = { ui.downloads = false }, wide = form != Form.Phone, title = "Downloads") {
        Column(Modifier.fillMaxWidth()) {
            if (rows.isEmpty()) {
                Text("No downloads yet", color = Ink.tertiary(), fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (anyActive) "${formatBytes(Downloads.totalSpeed().toLong())}/s" else "${rows.size} item${if (rows.size == 1) "" else "s"}",
                        color = Ink.secondary(), fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    if (anyActive) PillButton("Pause All", { Downloads.pauseAll() }, filled = false)
                    else if (anyPaused) PillButton("Resume All", { Downloads.resumeAll() })
                    PillButton("Clear", { Downloads.clearFinished() }, filled = false)
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    rows.forEach { DownloadItem(it) }
                }
            }
        }
    }
}

@Composable
private fun DownloadItem(r: DownloadRow) {
    val done = r.state == JobState.Completed
    Column(
        Modifier.fillMaxWidth().then(if (done) Modifier.clickable { Downloads.open(r) } else Modifier).padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, color = Ink.primary(), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when (r.state) {
                    JobState.Completed -> formatBytes(r.size.coerceAtLeast(r.downloaded)) + if (r.savedUri.isEmpty()) " · saving…" else " · tap to open"
                    JobState.Failed -> "Failed · ${r.error}"
                    JobState.Paused -> "Paused · ${formatBytes(r.downloaded)}" + if (r.size > 0) " of ${formatBytes(r.size)}" else ""
                    JobState.Queued -> "Waiting…"
                    JobState.Downloading ->
                        formatBytes(r.downloaded) + (if (r.size > 0) " of ${formatBytes(r.size)}" else "") +
                            (if (r.speedBps > 0) " · ${formatBytes(r.speedBps.toLong())}/s" else "") +
                            (if (r.speedBps > 1 && r.size > 0) " · ${eta(((r.size - r.downloaded) / r.speedBps).toLong())}" else "")
                }
                Text(status, color = if (r.state == JobState.Failed) Ink.danger else Ink.tertiary(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (r.state) {
                JobState.Downloading, JobState.Queued -> PlainIconButton(Ic.Pause, { Downloads.pause(r.id) }, tint = Ink.accent)
                JobState.Paused, JobState.Failed -> PlainIconButton(Ic.Play, { Downloads.resume(r.id) }, tint = Ink.accent)
                JobState.Completed -> if (r.savedUri.isNotEmpty()) PlainIconButton(Ic.Share, { Downloads.share(r) }, tint = Ink.accent)
            }
            PlainIconButton(Ic.Trash, { Downloads.remove(r.id, false) }, tint = Ink.tertiary())
        }
        if (!done && r.state != JobState.Failed) {
            if (r.size > 0) LinearProgressIndicator(
                progress = { r.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(CircleShape),
                color = if (r.state == JobState.Paused) Ink.tertiary() else Ink.accent, trackColor = Ink.tertiary().copy(alpha = 0.2f),
            )
            else if (r.state == JobState.Downloading) LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(CircleShape), color = Ink.accent, trackColor = Ink.tertiary().copy(alpha = 0.2f),
            )
        }
    }
}

private fun eta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s left"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s left"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
}

// ---------------------------------------------------------------------------------------------
//  Bookmarks & history
// ---------------------------------------------------------------------------------------------

@Composable
fun LibrarySheet(ui: UiState, form: Form) {
    val tab = Browser.current
    GlassSheet(onDismiss = { ui.library = false }, wide = form != Form.Phone, title = "Library") {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Bookmarks", ui.libraryTab == 0) { ui.libraryTab = 0 }
                Chip("History", ui.libraryTab == 1) { ui.libraryTab = 1 }
            }
            LibraryList(ui.libraryTab, Modifier.weight(1f, fill = false)) { url ->
                ui.library = false
                if (tab != null) Browser.load(tab, url)
            }
        }
    }
}

@Composable
fun LibraryList(index: Int, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val list = if (index == 0) Browser.bookmarks else Browser.history
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (list.isEmpty()) {
            Text(
                if (index == 0) "No bookmarks yet" else "No history yet", color = Ink.tertiary(), fontSize = 14.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(28.dp),
            )
        }
        list.toList().take(200).forEach { e ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(e.url) }.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (index == 0) Ic.Star else Ic.Clock, Ink.secondary(), 17.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(e.title.ifEmpty { e.url }, color = Ink.primary(), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Blocker.hostOf(e.url) ?: e.url, color = Ink.tertiary(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (index == 0) PlainIconButton(Ic.Close, { Browser.removeBookmark(e) }, size = 30.dp, iconSize = 14.dp, tint = Ink.tertiary())
            }
        }
        if (index == 1 && list.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) { PillButton("Clear History", { Browser.clearHistory() }, filled = false, danger = true) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
//  Privacy report
// ---------------------------------------------------------------------------------------------

@Composable
fun PrivacySheet(ui: UiState, form: Form) {
    val tab = Browser.current
    val host = tab?.pageHost
    GlassSheet(onDismiss = { ui.privacy = false }, wide = form != Form.Phone, title = "Privacy Report") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${Prefs.totalBlocked}", color = Ink.primary(), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text("ads, trackers and pop-ups blocked in total", color = Ink.secondary(), fontSize = 13.sp)
            }
            if (tab != null && !tab.isBlank) {
                SheetRow("This page", Ic.Globe, null, sub = tab.displayHost, trailing = { Text("${tab.blocked} blocked", color = Ink.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) })
            }
            if (!host.isNullOrEmpty()) {
                ToggleRow("Allow ads on $host", "Turns blocking off for this site only", Prefs.isSiteAllowed(host)) { Prefs.toggleSiteProtection(host); Blocker.clearCache() }
            }
            Text(
                if (Blocker.ready) "Filters: ${Blocker.stats()}" else "Loading filter lists…",
                color = Ink.tertiary(), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

internal fun TabState.hostLabel(): String = displayHost.ifEmpty { "Start Page" }

@Composable
fun MediaSheet(ui: UiState, form: Form) {
    val tab = Browser.current ?: return
    val title = tab.title.ifBlank { tab.displayHost }
    GlassSheet(onDismiss = { ui.media = false }, wide = form != Form.Phone, title = "Videos on this page") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (tab.media.isEmpty()) SheetRow("Nothing found yet. Press play, then check again.", null, null)
            tab.media.toList().asReversed().forEach { m ->
                SheetRow(m.label, Ic.Download, {
                    ui.media = false
                    if (m.hls) Downloads.enqueueHls(m.url, tab.currentUA, tab.url, title)
                    else Downloads.enqueue(m.url, tab.currentUA, "attachment; filename=\"" + title.replace('"', ' ') + "." + m.url.substringBefore('?').substringAfterLast('.', "mp4").take(5) + "\"", null, tab.url)
                }, sub = if (m.hls) "HLS stream · joined into one file" else Blocker.hostOf(m.url))
            }
        }
    }
}
