package com.gr4nt.aurabrowser.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gr4nt.aurabrowser.Browser
import com.gr4nt.aurabrowser.TabState
import kotlinx.coroutines.launch
import kotlin.math.abs

private fun tabLabel(t: TabState): String =
    t.title.ifEmpty { t.displayHost }.ifEmpty { "Start Page" }

private fun closeSwitcherAndOpen(ui: UiState, id: String) {
    Browser.select(id)
    ui.switcher = false
}

/** Full-screen tab overview. A horizontal "Recents" deck on phones, a Safari-style grid on larger windows. */
@Composable
fun TabSwitcher(ui: UiState, form: Form) {
    if (form == Form.Phone) PhoneSwitcher(ui) else GridSwitcher(ui)
}

// ---------------------------------------------------------------------------------------------
//  Phone: iOS "Recents"
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneSwitcher(ui: UiState) {
    val tabs = Browser.tabs
    val startIndex = remember { tabs.indexOfFirst { it.id == Browser.selectedId }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val screenWPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    Box(Modifier.fillMaxSize().background(Ink.background())) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (LocalDark.current) 0.35f else 0.06f)))

        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val cardH = (maxHeight * 0.50f).coerceIn(240.dp, 420.dp)
            val pitch = 118.dp               // visible strip of every card that is covered by the next one
            val overlap = cardH - pitch

            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Close All", color = Ink.danger, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(CircleShape).clickable { Browser.closeAll(); ui.switcher = false }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Text("${tabs.size} ${if (tabs.size == 1) "Tab" else "Tabs"}", color = Ink.secondary(), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "Done", color = Ink.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).clickable { ui.switcher = false }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }

                // Vertical stack: each card is overlapped by the next, like iOS' stacked Recents.
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = overlap + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(-overlap),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        StackCard(
                            tab = tab, listState = listState, screenWPx = screenWPx,
                            modifier = Modifier.animateItem().fillMaxWidth().height(cardH),
                            onOpen = { closeSwitcherAndOpen(ui, tab.id) },
                        )
                    }
                }

                Box(Modifier.fillMaxWidth().padding(bottom = 18.dp, top = 8.dp), contentAlignment = Alignment.Center) {
                    GlassButton(Ic.Plus, { Browser.newTab(); ui.switcher = false; ui.editing = true }, size = 54.dp)
                }
            }
        }
    }
}

@Composable
private fun StackCard(
    tab: TabState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    screenWPx: Float,
    modifier: Modifier,
    onOpen: () -> Unit,
) {
    var drag by remember(tab.id) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(24.dp)
    val dark = LocalDark.current

    Box(
        modifier
            .graphicsLayer {
                // Cards further up the screen recede slightly (smaller, tilted back) so the pile has depth.
                val info = listState.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.key == tab.id }
                var t = 1f
                if (item != null) {
                    val span = (info.viewportEndOffset - info.viewportStartOffset).toFloat().coerceAtLeast(1f)
                    t = ((item.offset - info.viewportStartOffset) / span).coerceIn(0f, 1f)
                }
                val s = 0.90f + 0.10f * t
                scaleX = s
                scaleY = s
                cameraDistance = 14f * density
                rotationX = (1f - t) * 14f
                translationX = drag
                alpha = (1f - (abs(drag) / screenWPx) * 1.4f).coerceIn(0f, 1f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> drag += delta },
                onDragStopped = { velocity ->
                    if (abs(drag) > screenWPx * 0.32f || abs(velocity) > 1600f) {
                        val dir = if (drag + velocity * 0.05f >= 0f) 1f else -1f
                        animate(drag, dir * screenWPx, animationSpec = tween(180)) { v, _ -> drag = v }
                        Browser.close(tab.id)
                    } else {
                        animate(drag, 0f, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)) { v, _ -> drag = v }
                    }
                },
            ),
    ) {
        CardFace(tab, shape, Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen))
        // title bar pinned to the top of every card (the only part visible when the card is covered)
        Row(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = if (dark) 0.62f else 0.50f), Color.Black.copy(alpha = 0.18f))))
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fav = tab.favicon
            if (fav != null) Image(fav, null, Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)))
            else Icon(Ic.Globe, Color.White.copy(alpha = 0.85f), 18.dp)
            Text(
                tabLabel(tab), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 9.dp),
            )
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))
                    .clickable { scope.launch { animate(drag, screenWPx, animationSpec = tween(180)) { v, _ -> drag = v }; Browser.close(tab.id) } },
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Close, Color.White, 13.dp) }
        }
    }
}

@Composable
private fun CardFace(tab: TabState, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier) {
    val selected = tab.id == Browser.selectedId
    Box(
        modifier
            .shadow(if (selected) 22.dp else 14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.30f))
            .clip(shape)
            .background(Ink.solidBg()),
    ) {
        val thumb = tab.thumbnail
        when {
            tab.isBlank || thumb == null -> {
                Box(Modifier.fillMaxSize().background(Ink.background()), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (tab.isBlank) Ic.Search else Ic.Globe, Ink.tertiary(), 34.dp)
                        Text(
                            if (tab.isBlank) "Start Page" else tab.displayHost,
                            color = Ink.tertiary(), fontSize = 14.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp),
                        )
                    }
                }
            }
            else -> Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        }
    }
}

// ---------------------------------------------------------------------------------------------
//  Tablet / desktop: Safari tab overview grid
// ---------------------------------------------------------------------------------------------

@Composable
private fun GridSwitcher(ui: UiState) {
    Box(Modifier.fillMaxSize().background(Ink.background())) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (LocalDark.current) 0.35f else 0.04f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(Ic.Plus, { Browser.newTab(); ui.switcher = false; ui.editing = true }, size = 40.dp, iconSize = 20.dp)
                    Text("${Browser.tabs.size} ${if (Browser.tabs.size == 1) "Tab" else "Tabs"}", color = Ink.primary(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Close All", { Browser.closeAll(); ui.switcher = false }, filled = false, danger = true)
                    PillButton("Done", { ui.switcher = false })
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(250.dp),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(Browser.tabs, key = { it.id }) { tab ->
                    GridCard(tab, onOpen = { closeSwitcherAndOpen(ui, tab.id) })
                }
            }
        }
    }
}

@Composable
private fun GridCard(tab: TabState, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val selected = tab.id == Browser.selectedId
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(1.32f)) {
            CardFace(
                tab, shape,
                Modifier.fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
            )
            if (selected) {
                Box(Modifier.fillMaxSize().border(2.5.dp, Ink.accent, shape))
            }
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp).size(26.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)).clickable { Browser.close(tab.id) },
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Close, Color.White, 13.dp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            val fav = tab.favicon
            if (fav != null) Image(fav, null, Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))) else Icon(Ic.Globe, Ink.secondary(), 16.dp)
            Text(
                tabLabel(tab), color = if (selected) Ink.accent else Ink.primary(), fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
