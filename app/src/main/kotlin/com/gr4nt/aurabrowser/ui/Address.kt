package com.gr4nt.aurabrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.gr4nt.aurabrowser.Blocker
import com.gr4nt.aurabrowser.Browser
import com.gr4nt.aurabrowser.Prefs
import com.gr4nt.aurabrowser.TabState
import com.gr4nt.aurabrowser.Web
import com.gr4nt.aurabrowser.resolveInput

data class Suggestion(val label: String, val sub: String, val url: String, val ic: Ic)

/** "Go to / Search for" first, then matching bookmarks, then history. */
fun suggest(query: String): List<Suggestion> {
    val q = query.trim()
    if (q.isEmpty()) return Browser.bookmarks.take(6).map { Suggestion(it.title.ifEmpty { it.url }, Blocker.hostOf(it.url) ?: it.url, it.url, Ic.Star) }
    val out = ArrayList<Suggestion>()
    val resolved = resolveInput(q)
    val isUrl = !resolved.startsWith(Prefs.searchEngine.template.substringBefore("%s"))
    out.add(
        if (isUrl) Suggestion(q, "Go to website", resolved, Ic.Globe)
        else Suggestion(q, "Search ${Prefs.searchEngine.label}", resolved, Ic.Search)
    )
    val low = q.lowercase()
    val seen = HashSet<String>()
    for (b in Browser.bookmarks) {
        if (out.size >= 7) break
        if ((b.title.lowercase().contains(low) || b.url.lowercase().contains(low)) && seen.add(b.url))
            out.add(Suggestion(b.title.ifEmpty { b.url }, Blocker.hostOf(b.url) ?: b.url, b.url, Ic.Star))
    }
    for (h in Browser.history) {
        if (out.size >= 9) break
        if ((h.title.lowercase().contains(low) || h.url.lowercase().contains(low)) && seen.add(h.url))
            out.add(Suggestion(h.title.ifEmpty { h.url }, Blocker.hostOf(h.url) ?: h.url, h.url, Ic.Clock))
    }
    return out
}

@Composable
private fun SuggestionRow(s: Suggestion, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(s.ic, Ink.secondary(), 18.dp)
        Column(Modifier.padding(start = 14.dp)) {
            Text(s.label, color = Ink.primary(), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.sub, color = Ink.tertiary(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------------------------
//  Phone: address entry docked above the keyboard
// ---------------------------------------------------------------------------------------------

@Composable
fun AddressOverlay(ui: UiState) {
    val tab = Browser.current ?: return
    var tf by remember { mutableStateOf(TextFieldValue(tab.url, TextRange(0, tab.url.length))) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    val sugg = remember(tf.text) { suggest(tf.text) }
    val go = { text: String -> Browser.load(tab, text); ui.editing = false; focusManager.clearFocus() }

    Box(Modifier.fillMaxSize().background(Ink.background())) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            // Best match sits right above the field, as on iOS.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(sugg) { s ->
                    SuggestionRow(s) { go(s.url) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassPanel(Modifier.weight(1f).height(48.dp), CircleShape, elevation = 6.dp) {
                    Row(Modifier.matchParentSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ic.Search, Ink.secondary(), 17.dp)
                        Box(Modifier.weight(1f).padding(start = 10.dp)) {
                            if (tf.text.isEmpty()) Text("Search or enter website", color = Ink.tertiary(), fontSize = 16.sp)
                            BasicTextField(
                                value = tf, onValueChange = { tf = it }, singleLine = true,
                                textStyle = TextStyle(color = Ink.primary(), fontSize = 16.sp),
                                cursorBrush = SolidColor(Ink.accent),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { go(tf.text) }),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                        if (tf.text.isNotEmpty()) PlainIconButton(Ic.Close, { tf = TextFieldValue("") }, size = 30.dp, iconSize = 14.dp, tint = Ink.secondary())
                    }
                }
                Text(
                    "Cancel", color = Ink.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(CircleShape).clickable { ui.editing = false; focusManager.clearFocus() }.padding(horizontal = 6.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
//  Tablet / desktop: inline Safari-style address field with a suggestions popup
// ---------------------------------------------------------------------------------------------

@Composable
fun DesktopAddress(ui: UiState, tab: TabState, modifier: Modifier = Modifier) {
    var tf by remember(tab.id) { mutableStateOf(TextFieldValue(if (tab.isBlank) "" else tab.displayHost)) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val fieldH = 38.dp
    val fieldHpx = with(LocalDensity.current) { fieldH.roundToPx() }

    LaunchedEffect(tab.url, focused, tab.id) {
        if (!focused) tf = TextFieldValue(if (tab.isBlank) "" else tab.displayHost)
    }
    LaunchedEffect(ui.editing) {
        if (ui.editing) { focus.requestFocus(); ui.editing = false }
    }
    val sugg = remember(tf.text, focused) { if (focused) suggest(tf.text) else emptyList() }
    val dark = LocalDark.current

    Box(modifier) {
        Box(
            Modifier.fillMaxWidth().height(fieldH).clip(RoundedCornerShape(10.dp))
                .background(if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.055f)),
        ) {
            if (tab.loading) {
                Box(
                    Modifier.fillMaxSize().padding(end = 0.dp)
                        .background(Brush.horizontalGradient(0f to Ink.accent.copy(alpha = 0.22f), tab.progress.coerceIn(0.03f, 1f) to Ink.accent.copy(alpha = 0.22f), (tab.progress + 0.001f).coerceAtMost(1f) to Color.Transparent, 1f to Color.Transparent))
                )
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (tab.isBlank) Ic.Search else if (tab.secure) Ic.Lock else Ic.Warning, Ink.secondary(), 14.dp)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (tf.text.isEmpty()) Text("Search or enter website name", color = Ink.tertiary(), fontSize = 14.sp, maxLines = 1)
                    BasicTextField(
                        value = tf, onValueChange = { tf = it }, singleLine = true,
                        textStyle = TextStyle(color = Ink.primary(), fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(Ink.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { Browser.load(tab, tf.text); focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
                            if (it.isFocused && !focused) tf = TextFieldValue(tab.url, TextRange(0, tab.url.length))
                            focused = it.isFocused
                        },
                    )
                }
                if (!tab.isBlank) PlainIconButton(if (tab.loading) Ic.Close else Ic.Reload, { Web.reload(tab) }, size = 28.dp, iconSize = 15.dp, tint = Ink.secondary())
            }
        }
        if (focused && sugg.isNotEmpty()) {
            Popup(alignment = Alignment.TopStart, offset = IntOffset(0, fieldHpx + 6), properties = PopupProperties(focusable = false)) {
                GlassPanel(Modifier.widthIn(min = 320.dp, max = 640.dp), RoundedCornerShape(16.dp), tintAlpha = 0.72f, elevation = 18.dp) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        sugg.take(8).forEach { s ->
                            SuggestionRow(s) { Browser.load(tab, s.url); focusManager.clearFocus() }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
//  Find in page
// ---------------------------------------------------------------------------------------------

@Composable
fun FindBar(ui: UiState, tab: TabState, modifier: Modifier = Modifier) {
    var tf by remember(tab.id) { mutableStateOf(TextFieldValue(tab.findQuery)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    GlassPanel(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).height(46.dp), CircleShape, tab.sample, elevation = 6.dp) {
        Row(Modifier.matchParentSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Search, Ink.secondary(), 16.dp)
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (tf.text.isEmpty()) Text("Find on Page", color = Ink.tertiary(), fontSize = 15.sp)
                BasicTextField(
                    value = tf, onValueChange = { tf = it; Web.find(tab, it.text) }, singleLine = true,
                    textStyle = TextStyle(color = Ink.primary(), fontSize = 15.sp),
                    cursorBrush = SolidColor(Ink.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { Web.findNext(tab, true) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (tab.findQuery.isNotEmpty()) {
                Text(
                    if (tab.findMatches > 0) "${tab.findIndex + 1}/${tab.findMatches}" else "0",
                    color = Ink.secondary(), fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp),
                )
            }
            PlainIconButton(Ic.ChevronDown, { Web.findNext(tab, true) }, size = 32.dp, iconSize = 18.dp)
            PlainIconButton(Ic.Close, { ui.find = false; Web.findClose(tab) }, size = 32.dp, iconSize = 16.dp)
        }
    }
}
