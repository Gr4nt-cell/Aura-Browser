package com.gr4nt.aurabrowser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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

@Composable
private fun PhoneSwitcher(ui: UiState) {
    val tabs = Browser.tabs
    val scope = rememberCoroutineScope()
    val last = (tabs.size - 1).coerceAtLeast(0)
    val pos = remember { Animatable(tabs.indexOfFirst { it.id == Browser.selectedId }.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(tabs.size) {
        if (tabs.isEmpty()) ui.switcher = false
        else pos.snapTo(pos.value.coerceIn(0f, last.toFloat()))
    }
    val dark = LocalDark.current

    Box(Modifier.fillMaxSize().background(Ink.background())) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (dark) 0.42f else 0.10f)))

        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val density = LocalDensity.current
            val cardW = maxWidth * 0.62f
            val cardH = (maxHeight * 0.56f).coerceAtLeast(260.dp)
            val pitch = cardW * 0.58f                      // how much of each card stays visible under the next one
            val pitchPx = with(density) { pitch.toPx() }
            val cardWPx = with(density) { cardW.toPx() }
            val screenWPx = with(density) { maxWidth.toPx() }
            val topPad = maxHeight * 0.14f

            // horizontal swipe scrolls the deck; releasing snaps to the nearest card
            Box(
                Modifier.fillMaxSize().draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d -> scope.launch { pos.snapTo((pos.value - d / pitchPx).coerceIn(0f, last.toFloat())) } },
                    onDragStopped = { v ->
                        val target = (pos.value - v / pitchPx * 0.22f).roundToInt().coerceIn(0, last)
                        scope.launch { pos.animateTo(target.toFloat(), spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) }
                    },
                ),
            ) {
                tabs.forEachIndexed { i, tab ->
                    key(tab.id) {
                        DeckCard(
                            tab = tab, index = i, isLast = i == last, pos = pos,
                            cardW = cardW, cardH = cardH, pitch = pitch, cardWPx = cardWPx, pitchPx = pitchPx,
                            screenWPx = screenWPx, topPad = topPad,
                            onOpen = {
                                if (abs(pos.value - i) < 0.35f) closeSwitcherAndOpen(ui, tab.id)
                                else scope.launch { pos.animateTo(i.toFloat(), spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) }
                            },
                        )
                    }
                }
            }

            // bottom bar: new tab, done, tab count
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassButton(Ic.Plus, { Browser.newTab(); ui.switcher = false; ui.editing = true }, size = 56.dp)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.width(170.dp).height(56.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = if (dark) 0.22f else 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape)
                        .clickable { ui.switcher = false },
                    contentAlignment = Alignment.Center,
                ) { Text("Done", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                Text(
                    "${tabs.size}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(56.dp).clip(CircleShape).clickable { Browser.closeAll(); ui.switcher = false }.padding(vertical = 14.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun DeckCard(
    tab: TabState, index: Int, isLast: Boolean, pos: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    cardW: androidx.compose.ui.unit.Dp, cardH: androidx.compose.ui.unit.Dp, pitch: androidx.compose.ui.unit.Dp,
    cardWPx: Float, pitchPx: Float, screenWPx: Float, topPad: androidx.compose.ui.unit.Dp, onOpen: () -> Unit,
) {
    var lift by remember(tab.id) { mutableFloatStateOf(0f) }       // vertical swipe-up to close
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(38.dp)
    val screenHPx = with(LocalDensity.current) { (cardH + topPad).toPx() }

    Column(
        Modifier
            .offset { IntOffset((screenWPx / 2f - cardWPx / 2f + (index - pos.value) * pitchPx).roundToInt(), 0) }
            .padding(top = topPad - 40.dp)
            .width(cardW)
            .graphicsLayer {
                translationY = lift.coerceAtMost(0f)
                alpha = (1f - (-lift / screenHPx) * 1.3f).coerceIn(0f, 1f)
            }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { d -> lift += d },
                onDragStopped = { v ->
                    if (lift < -screenHPx * 0.22f || v < -1500f) {
                        scope.launch { animate(lift, -screenHPx, animationSpec = tween(200)) { x, _ -> lift = x }; Browser.close(tab.id) }
                    } else scope.launch { animate(lift, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) { x, _ -> lift = x } }
                },
            ),
    ) {
        // title above the card: favicon + page title, limited to the part of the card that is not covered
        Row(Modifier.height(40.dp).width(if (isLast) cardW else pitch - 6.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val fav = tab.favicon
            if (fav != null) Image(fav, null, Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)))
            else Icon(Ic.Globe, Color.White.copy(alpha = 0.8f), 22.dp)
            Text(
                tabLabel(tab), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp),
            )
        }
        CardFace(
            tab, shape,
            Modifier.fillMaxWidth().height(cardH)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
        )
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
                if (tab.isBlank) {
                    Box(Modifier.fillMaxSize().background(Ink.background())) {
                        Column(Modifier.padding(start = 26.dp, top = 34.dp, end = 20.dp)) {
                            Text("Home", color = Ink.primary(), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("Wander the web, gently.", color = Ink.secondary(), fontSize = 17.sp, modifier = Modifier.padding(top = 14.dp))
                        }
                    }
                } else Box(Modifier.fillMaxSize().background(Ink.background()), contentAlignment = Alignment.Center) {
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
