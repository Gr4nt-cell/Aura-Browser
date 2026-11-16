package com.gr4nt.aurabrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.gr4nt.aurabrowser.Browser
import com.gr4nt.aurabrowser.TabState
import com.gr4nt.aurabrowser.Web
import com.gr4nt.aurabrowser.dl.Downloads

/**
 * Tablet / desktop: laid out like Safari for Mac. A frosted toolbar (sidebar, back/forward, centred address
 * field, share / new tab / tab overview), a tab strip, an optional sidebar and the page itself.
 */
@Composable
fun LargeLayout(ui: UiState, form: Form) {
    val tab = Browser.current ?: return
    val desktop = form == Form.Desktop
    Column(Modifier.fillMaxSize().background(Ink.background())) {
        Toolbar(ui, tab, desktop)
        TabStrip(ui, tab)
        if (ui.find) FindBar(ui, tab, Modifier.widthIn(max = 560.dp).align(Alignment.CenterHorizontally))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.sidebar) Sidebar(ui, tab, if (desktop) 290.dp else 250.dp)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (tab.isBlank) StartPage(tab, form) else WebHost(tab, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun Toolbar(ui: UiState, tab: TabState, desktop: Boolean) {
    val dark = LocalDark.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        ChromeStrip(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars), tab.sample, fromBottom = false)
        ChromeStrip(Modifier.fillMaxWidth().height(52.dp), tab.sample, fromBottom = false) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(Ic.Sidebar, { ui.sidebar = !ui.sidebar }, active = ui.sidebar)
                    Spacer(Modifier.width(6.dp))
                    ToolButton(Ic.Back, { Web.back(tab) }, enabled = tab.canBack)
                    ToolButton(Ic.Forward, { Web.forward(tab) }, enabled = tab.canForward)
                }
                Box(Modifier.weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    DesktopAddress(ui, tab, Modifier.widthIn(max = if (desktop) 640.dp else 520.dp).fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(Ic.Share, { Web.Externals.share(ctx, tab.url) }, enabled = !tab.isBlank)
                    ToolButton(Ic.Plus, { Browser.newTab(); ui.editing = true })
                    ToolButton(Ic.Tabs, { openSwitcher(ui) })
                    ToolButton(Ic.Download, { ui.downloads = true }, active = Downloads.hasActive())
                    ToolButton(Ic.More, { ui.more = true })
                }
            }
        }
        // hairline under the toolbar, like Safari
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)))
    }
}

@Composable
private fun ToolButton(ic: Ic, onClick: () -> Unit, enabled: Boolean = true, active: Boolean = false) {
    val dark = LocalDark.current
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))
            .background(if (active) (if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(ic, if (enabled) (if (active) Ink.accent else Ink.primary()) else Ink.tertiary(), 20.dp) }
}

@Composable
private fun TabStrip(ui: UiState, current: TabState) {
    val dark = LocalDark.current
    val tabs = Browser.tabs
    Row(
        Modifier.fillMaxWidth().height(38.dp).background(if (dark) Color.Black.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.035f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val tabW: Dp = ((maxWidth) / tabs.size.coerceAtLeast(1)).coerceIn(130.dp, 250.dp)
            Row(Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tabs.forEach { t -> StripTab(t, t.id == current.id, tabW - 4.dp) }
            }
        }
        ToolButton(Ic.Plus, { Browser.newTab(); ui.editing = true })
    }
}

@Composable
private fun StripTab(t: TabState, selected: Boolean, width: Dp) {
    val dark = LocalDark.current
    val bg = when {
        selected -> if (dark) Color.White.copy(alpha = 0.16f) else Color.White
        else -> Color.Transparent
    }
    Row(
        Modifier.width(width).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(bg)
            .clickable { Browser.select(t.id) }.padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fav = t.favicon
        if (fav != null) Image(fav, null, Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))) else Icon(Ic.Globe, Ink.secondary(), 14.dp)
        Text(
            t.title.ifEmpty { t.displayHost }.ifEmpty { "Start Page" },
            color = if (selected) Ink.primary() else Ink.secondary(), fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Box(Modifier.size(22.dp).clip(CircleShape).clickable { Browser.close(t.id) }, contentAlignment = Alignment.Center) {
            Icon(Ic.Close, Ink.tertiary(), 11.dp)
        }
    }
}

@Composable
private fun Sidebar(ui: UiState, tab: TabState, width: Dp) {
    val dark = LocalDark.current
    Column(
        Modifier.width(width).fillMaxHeight().background(if (dark) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.55f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SidebarTab("Bookmarks", ui.sidebarTab == 0) { ui.sidebarTab = 0 }
            SidebarTab("History", ui.sidebarTab == 1) { ui.sidebarTab = 1 }
        }
        LibraryList(ui.sidebarTab, Modifier.weight(1f)) { url -> Browser.load(tab, url) }
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)))
}

@Composable
private fun SidebarTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val dark = LocalDark.current
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        color = if (selected) Ink.primary() else Ink.secondary(),
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) (if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.07f)) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
