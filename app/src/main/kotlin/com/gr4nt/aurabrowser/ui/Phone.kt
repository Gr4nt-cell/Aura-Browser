package com.gr4nt.aurabrowser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gr4nt.aurabrowser.Browser
import com.gr4nt.aurabrowser.TabState
import com.gr4nt.aurabrowser.Web
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Flat frosted strip used behind status / navigation bars and the collapsed toolbar. */
@Composable
fun ChromeStrip(modifier: Modifier, sample: ImageBitmap?, fromBottom: Boolean, content: @Composable () -> Unit = {}) {
    val dark = LocalDark.current
    val base = if (dark) Color(0xFF14131B) else Color(0xFFF7F5FB)
    Box(modifier.clipToBounds()) {
        if (sample != null) {
            BackdropCanvas(sample, fromBottom, Modifier.matchParentSize().let { if (android.os.Build.VERSION.SDK_INT >= 31) it.blur(18.dp, BlurredEdgeTreatment.Unbounded) else it })
            Box(Modifier.matchParentSize().background(base.copy(alpha = 0.62f)))
        } else Box(Modifier.matchParentSize().background(base))
        content()
    }
}

@Composable
fun PhoneLayout(ui: UiState) {
    val tab = Browser.current ?: return
    Column(Modifier.fillMaxSize()) {
        ChromeStrip(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars), tab.sample, fromBottom = false)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab.isBlank) StartPage(tab, Form.Phone) else WebHost(tab, Modifier.fillMaxSize())
        }
        if (ui.find) FindBar(ui, tab)
        PhoneBar(ui, tab)
    }
}

@Composable
private fun PhoneBar(ui: UiState, tab: TabState) {
    val collapsed = Browser.chromeCollapsed && !ui.editing && !tab.isBlank
    val h by animateDpAsState(if (collapsed) 40.dp else 76.dp, tween(220), label = "bar")
    ChromeStrip(Modifier.fillMaxWidth(), tab.sample, fromBottom = true) {
        Column {
            Box(Modifier.fillMaxWidth().height(h), contentAlignment = Alignment.Center) {
                if (collapsed) CollapsedPill(tab)
                else ExpandedBar(ui, tab)
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun CollapsedPill(tab: TabState) {
    GlassPanel(
        Modifier.height(32.dp).clickable { Browser.chromeCollapsed = false }.padding(horizontal = 0.dp),
        CircleShape, tab.sample, elevation = 4.dp,
    ) {
        Row(Modifier.padding(horizontal = 18.dp).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tab.secure) Icon(Ic.Lock, Ink.secondary(), 12.dp)
            Text(tab.displayHost.ifEmpty { "Search" }, color = Ink.primary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = if (tab.secure) 5.dp else 0.dp))
        }
    }
}

@Composable
private fun ExpandedBar(ui: UiState, tab: TabState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassButton(Ic.Back, { if (!Web.back(tab) && tab.openerId != null) Browser.close(tab.id) }, sample = tab.sample, enabled = tab.canBack || tab.openerId != null)
        AddressCapsule(ui, tab, Modifier.weight(1f))
        TabsButton(ui, tab)
        GlassButton(Ic.More, { ui.more = true }, sample = tab.sample)
    }
}

@Composable
private fun TabsButton(ui: UiState, tab: TabState) {
    var menu by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        GlassPanel(
            Modifier.size(44.dp).pointerInput(Unit) {
                detectTapGestures(onTap = { openSwitcher(ui) }, onLongPress = { menu = true })
            },
            CircleShape, tab.sample, elevation = 6.dp,
        ) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(22.dp).border(2.dp, Ink.primary(), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Text("${Browser.tabs.size}", color = Ink.primary(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (menu) {
            androidx.compose.ui.window.Popup(alignment = Alignment.BottomEnd, offset = IntOffset(0, -140), onDismissRequest = { menu = false }) {
                GlassPanel(Modifier, RoundedCornerShape(20.dp), tintAlpha = 0.7f, elevation = 18.dp) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        MenuRow("New Tab", Ic.Plus) { menu = false; Browser.newTab(); ui.editing = true }
                        MenuRow("Close This Tab", Ic.Close) { menu = false; Browser.close(tab.id) }
                        MenuRow("Close All Tabs", Ic.Trash, danger = true) { menu = false; Browser.closeAll() }
                    }
                }
            }
        }
    }
}

@Composable
fun MenuRow(label: String, ic: Ic, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).widthIn(min = 210.dp).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ic, if (danger) Ink.danger else Ink.primary(), 20.dp)
        Text(label, color = if (danger) Ink.danger else Ink.primary(), fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun AddressCapsule(ui: UiState, tab: TabState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val shown = if (ui.editing) "" else tab.displayHost
    GlassPanel(
        modifier.height(48.dp)
            .offset { IntOffset(dx.value.roundToInt(), 0) }
            .pointerInput(tab.id) { detectTapGestures(onTap = { ui.editing = true }) }
            .pointerInput(tab.id) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { c, d -> c.consume(); total += d; scope.launch { dx.snapTo((total * 0.35f).coerceIn(-60f, 60f)) } },
                    onDragEnd = {
                        val i = Browser.tabs.indexOfFirst { it.id == tab.id }
                        val n = Browser.tabs.size
                        if (abs(total) > 90f && n > 1) {
                            val next = if (total < 0) (i + 1) % n else (i - 1 + n) % n
                            Browser.select(Browser.tabs[next].id)
                        }
                        scope.launch { dx.animateTo(0f, spring(Spring.DampingRatioMediumBouncy)) }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f) } },
                )
            }
            .pointerInput(tab.id) {
                var up = 0f
                detectVerticalDragGestures(
                    onDragStart = { up = 0f },
                    onVerticalDrag = { c, d -> if (d < 0) { up += d } },
                    onDragEnd = { if (up < -60f) openSwitcher(ui) },
                )
            },
        CircleShape, tab.sample, elevation = 6.dp,
    ) {
        Box(Modifier.matchParentSize()) {
            Row(Modifier.matchParentSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (tab.isBlank) Ic.Search else if (tab.secure) Ic.Lock else Ic.Warning, Ink.secondary(), 15.dp)
                Text(
                    if (tab.isBlank) "Search or enter website" else shown,
                    color = if (tab.isBlank) Ink.tertiary() else Ink.primary(),
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (!tab.isBlank) PlainIconButton(if (tab.loading) Ic.Close else Ic.Reload, { Web.reload(tab) }, size = 32.dp, iconSize = 17.dp, tint = Ink.secondary())
            }
            if (tab.loading) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(tab.progress.coerceIn(0.04f, 1f)).height(2.5.dp).background(Ink.accent))
            }
        }
    }
}
