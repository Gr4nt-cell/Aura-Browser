package com.gr4nt.aurabrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gr4nt.aurabrowser.Blocker
import com.gr4nt.aurabrowser.Browser
import com.gr4nt.aurabrowser.Prefs
import com.gr4nt.aurabrowser.TabState

private data class Fav(val name: String, val url: String, val a: Color, val b: Color)

private val favorites = listOf(
    Fav("Google", "https://www.google.com", Color(0xFF4285F4), Color(0xFF34A853)),
    Fav("YouTube", "https://www.youtube.com", Color(0xFFFF3B30), Color(0xFFFF6B5E)),
    Fav("Play Console", "https://play.google.com/console", Color(0xFF00C27A), Color(0xFF0A84FF)),
    Fav("Gmail", "https://mail.google.com", Color(0xFFEA4335), Color(0xFFFBBC05)),
    Fav("Wikipedia", "https://www.wikipedia.org", Color(0xFF636366), Color(0xFF8E8E93)),
    Fav("GitHub", "https://github.com", Color(0xFF2B2B33), Color(0xFF6E5494)),
    Fav("Reddit", "https://www.reddit.com", Color(0xFFFF4500), Color(0xFFFF8A5B)),
    Fav("Maps", "https://maps.google.com", Color(0xFF34C759), Color(0xFF0A84FF)),
)

@Composable
fun StartPage(tab: TabState, form: Form, modifier: Modifier = Modifier, bottomInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val cols = when (form) { Form.Phone -> 4; Form.Tablet -> 6; Form.Desktop -> 8 }
    Box(modifier.fillMaxSize().background(Ink.background()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 28.dp + bottomInset),
        ) {
            Text("Favorites", color = Ink.primary(), fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp, top = 8.dp))
            val rows = favorites.chunked(cols)
            for (r in rows) {
                Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (f in r) FavTile(f, Modifier.weight(1f)) { Browser.load(tab, f.url) }
                    repeat(cols - r.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            if (Browser.bookmarks.isNotEmpty()) {
                Text("Bookmarks", color = Ink.primary(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), elevation = 4.dp) {
                    Column {
                        Browser.bookmarks.take(5).forEachIndexed { i, b ->
                            Row(Modifier.fillMaxWidth().clickable { Browser.load(tab, b.url) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Ic.Star, Ink.accent, 18.dp)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(b.title, color = Ink.primary(), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(Blocker.hostOf(b.url) ?: b.url, color = Ink.tertiary(), fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            Text("Privacy Report", color = Ink.primary(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
            GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), elevation = 4.dp) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Ink.accent, Ink.green))), contentAlignment = Alignment.Center) {
                        Icon(Ic.Shield, Color.White, 24.dp)
                    }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("${Prefs.totalBlocked}", color = Ink.primary(), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (Prefs.blockAds) "ads, trackers and pop-ups blocked so far" else "Ad blocking is turned off in Settings",
                            color = Ink.secondary(), fontSize = 13.sp
                        )
                    }
                }
            }
            Text(
                if (Blocker.ready) "Filters loaded: ${Blocker.stats()}" else "Loading filter lists…",
                color = Ink.tertiary(), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            )
        }
    }
}

@Composable
private fun FavTile(f: Fav, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        GlassPanel(Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(22.dp), elevation = 6.dp, tintAlpha = 0.28f) {
            Box(Modifier.matchParentSize().padding(9.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(f.a, f.b))), contentAlignment = Alignment.Center) {
                Text(f.name.take(1), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(f.name, color = Ink.primary(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}
