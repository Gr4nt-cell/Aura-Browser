package com.d4viddf.hyperbridge.overlay

import android.app.PendingIntent
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.ui.draw.blur
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.d4viddf.hyperbridge.service.IslandAccessibilityService

/** One notification as shown by the island. */
data class IslandItem(
    val key: String,
    val appName: String,
    val title: String,
    val text: String,
    val icon: ImageBitmap?,
    val contentIntent: PendingIntent?,
    val actions: List<Pair<String, PendingIntent>>,
    val whenMs: Long,
    val call: IslandCall? = null,
    val reply: IslandReply? = null,
    val icons: List<ImageBitmap?> = emptyList(),
    val media: Boolean = false,
    val art: ImageBitmap? = null,
    val code: String? = null,
    val progress: MediaProgress? = null,
    val appIcon: ImageBitmap? = null,
    val largeIcon: ImageBitmap? = null,
)

/** Snapshot of a media session's position; the UI extrapolates it while playing. */
class MediaProgress(val positionMs: Long, val durationMs: Long, val updatedAtMs: Long, val speed: Float, val playing: Boolean)

data class IslandCall(val decline: PendingIntent?, val answer: PendingIntent?, val incoming: Boolean)
class IslandReply(val label: String, val pi: PendingIntent, val inputs: Array<android.app.RemoteInput>)

private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    init { controller.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
}

/**
 * Own notification island. A single overlay window pinned to the very top of the screen and centred on the front
 * camera cut-out, so the island always wraps the camera: a one-row pill ("small expanded") that grows into a larger
 * rounded card ("large expanded") when tapped.
 */
data class IslandLayout(val left: Int = 10, val right: Int = 10, val topGap: Int = 2, val bottomPad: Int = 16, val scalePct: Int = 100, val radius: Int = 44, val belowCam: Int = 2, val durationSec: Int = 6, val glow: Boolean = true, val timerBar: Boolean = false, val rTR: Int = 44, val rBL: Int = 44, val rBR: Int = 44)

object GlassIslandOverlay {
    private var layout = IslandLayout()

    /** Applies new edge distances; a visible island is rebuilt so the change shows immediately. */
    fun setLayout(l: IslandLayout) {
        if (l == layout) return
        layout = l
        scope.launch { if (view != null) { removeWindow() } }
    }

    fun canDraw(@Suppress("UNUSED_PARAMETER") ctx: Context) = IslandAccessibilityService.instance != null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var current by mutableStateOf<IslandItem?>(null)
    private var expanded by mutableStateOf(true)
    private var visible by mutableStateOf(false)
    private var replying by mutableStateOf(false)
    private var winWpx by mutableStateOf(0)
    private var mediaItem: IslandItem? = null
    private val repliedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** True for a notification that is just the app echoing a reply we sent from the island. */
    fun isReplyEcho(key: String): Boolean {
        val now = System.currentTimeMillis()
        val pkg = key.split("|").getOrNull(1) ?: ""
        repliedAt.entries.removeIf { now - it.value > 10_000 }
        return (repliedAt[key]?.let { now - it < 10_000 } == true) || (repliedAt["pkg:$pkg"]?.let { now - it < 2_500 } == true)
    }
    private var mediaDismissed = false
    private val reveal = Animatable(0f)
    private var hideJob: Job? = null
    private var cutoutW by mutableStateOf(0.dp)
    private var cutoutH by mutableStateOf(0.dp)

    fun show(ctx: Context, item: IslandItem) {
        scope.launch {
            if (!canDraw(ctx)) return@launch
            val host: Context = IslandAccessibilityService.instance ?: return@launch
            ensureWindow(host)
            if (current?.key != item.key) replying = false
            current = item
            expanded = true
            visible = true
            if (item.call?.incoming == true) hideJob?.cancel() else if (!replying) scheduleHide()
        }
    }

    /**
     * Music: lives as a small pill around the camera while playing; tapping it opens the full card.
     * Track changes, pause and resume update it silently, they never pop the card up.
     */
    fun updateMedia(ctx: Context, item: IslandItem, playing: Boolean) {
        scope.launch {
            if (!canDraw(ctx)) return@launch
            if (!playing) {
                mediaDismissed = false
                if (mediaItem?.key == item.key) {
                    mediaItem = null
                    if (current?.key == item.key) hide()
                }
                return@launch
            }
            mediaItem = item
            if (mediaDismissed) return@launch
            val c = current
            when {
                c == null -> showCompact(item)
                c.media -> current = item // same session updating: swap content in place
                else -> Unit // a notification is on screen; the pill comes back when it leaves
            }
        }
    }

    private fun showCompact(item: IslandItem) {
        val host: Context = IslandAccessibilityService.instance ?: return
        ensureWindow(host)
        current = item
        expanded = false
        visible = true
    }

    /** A call or music notification went away (answered, declined, stopped): take its island down. */
    fun dismissCall(key: String) {
        scope.launch {
            if (mediaItem?.key == key) mediaItem = null
            val c = current
            if (c != null && c.key == key && (c.call != null || c.media)) hide()
        }
    }

    fun dismiss(key: String? = null) {
        scope.launch { if (key == null || current?.key == key) hide() }
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(layout.durationSec * 1000L)
            if (!replying) {
                val c = current
                if (c != null && c.media) expanded = false else hide()
            }
        }
    }

    /** Starts the exit animation (card melts back into the camera); the window is removed when it finishes. */
    private fun hide() {
        hideJob?.cancel()
        if (view == null) { current = null; return }
        visible = false
    }

    private fun setFocusable(f: Boolean) {
        val lp = params ?: return
        val v = view ?: return
        lp.flags = if (f) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private fun sendReply(r: IslandReply, text: String) {
        val ctx = IslandAccessibilityService.instance ?: return
        val intent = android.content.Intent()
        val bundle = android.os.Bundle()
        r.inputs.forEach { bundle.putCharSequence(it.resultKey, text) }
        android.app.RemoteInput.addResultsToIntent(r.inputs, intent, bundle)
        if (Build.VERSION.SDK_INT >= 28) android.app.RemoteInput.setResultsSource(intent, android.app.RemoteInput.SOURCE_FREE_FORM_INPUT)
        current?.key?.let { k ->
            val now = System.currentTimeMillis()
            repliedAt[k] = now
            repliedAt["pkg:" + (k.split("|").getOrNull(1) ?: "")] = now
        }
        try { r.pi.send(ctx, 0, intent) } catch (_: Exception) {}
        replying = false
        hide()
    }

    private fun accentOf(icon: ImageBitmap?): Color {
        val fallback = Color(0xFF7DB3FF)
        if (icon == null) return fallback
        return runCatching {
            val bm = android.graphics.Bitmap.createScaledBitmap(icon.asAndroidBitmap(), 24, 24, true)
            var r = 0f; var g = 0f; var b = 0f; var wsum = 0f
            val hsv = FloatArray(3)
            for (x in 0 until 24) for (y in 0 until 24) {
                val px = bm.getPixel(x, y)
                if (android.graphics.Color.alpha(px) < 200) continue
                android.graphics.Color.colorToHSV(px, hsv)
                val w = hsv[1] * hsv[2] * hsv[2]
                r += android.graphics.Color.red(px) * w; g += android.graphics.Color.green(px) * w; b += android.graphics.Color.blue(px) * w; wsum += w
            }
            if (wsum < 0.5f) fallback else {
                val c = android.graphics.Color.rgb((r / wsum).toInt(), (g / wsum).toInt(), (b / wsum).toInt())
                android.graphics.Color.colorToHSV(c, hsv)
                hsv[1] = hsv[1].coerceIn(0.45f, 0.9f); hsv[2] = hsv[2].coerceIn(0.75f, 1f)
                Color(android.graphics.Color.HSVToColor(hsv))
            }
        }.getOrDefault(fallback)
    }

    private fun finish() {
        replying = false
        current = null
        removeWindow()
        val m = mediaItem
        if (m != null && !mediaDismissed) scope.launch { if (current == null) showCompact(m) }
    }

    private fun ensureWindow(ctx: Context) {
        if (view != null) return
        val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = windowManager
        val density = ctx.resources.displayMetrics.density
        val metrics = windowManager.currentWindowMetrics
        val screenW = metrics.bounds.width()
        var winTopPx = 0
        val leftPx = (layout.left * density).toInt()
        val rightPx = (layout.right * density).toInt()
        val cutout = metrics.windowInsets.displayCutout
        val top = cutout?.boundingRectTop
        if (top != null && !top.isEmpty) {
            cutoutW = (top.width() / density).dp
            // window starts 2dp above the camera so the card hugs it
            // punch-hole cut-outs usually report a rect that starts at y=0, so estimate the real top of the camera
            val camTop = if (top.top > 0) top.top else maxOf(0, top.bottom - top.width())
            winTopPx = maxOf(0, camTop - (layout.topGap * density).toInt())
            cutoutH = ((top.bottom - winTopPx) / density).dp
        } else {
            // no notch reported: reserve the status-bar sized strip so content never sits under a punch-hole either
            cutoutW = 0.dp
            cutoutH = 0.dp
        }

        winWpx = screenW - leftPx - rightPx
        val lp = WindowManager.LayoutParams(
            screenW - leftPx - rightPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = (leftPx - rightPx) / 2
            y = winTopPx
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "HyperBridgeGlassIsland"
        }
        params = lp

        val o = OverlayOwner()
        owner = o
        val cv = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setContent {
                val item = current
                val base = androidx.compose.ui.platform.LocalDensity.current
                val k = layout.scalePct / 100f
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * k, base.fontScale)
                ) { if (item != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { IslandContent(item) } }
            }
        }
        view = cv
        try { windowManager.addView(cv, lp) } catch (_: Exception) { view = null }
    }

    private fun removeWindow() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        view = null
        owner?.destroy(); owner = null
        visible = false
        scope.launch { reveal.snapTo(0f) }
    }

    // ------------------------------------------------------------------------------------------------ UI

    @Composable
    private fun IslandContent(item: IslandItem) {
        val big = expanded
        val ctxW = cutoutW
        val topPad = (if (cutoutH > 0.dp) cutoutH else 18.dp) / (layout.scalePct / 100f)
        val shape = RoundedCornerShape(topStart = layout.radius.dp, topEnd = layout.rTR.dp, bottomEnd = layout.rBR.dp, bottomStart = layout.rBL.dp)
        val vis = visible
        val compact = item.media && !big
        val fullW = with(androidx.compose.ui.platform.LocalDensity.current) { winWpx.toDp() }
        val compactW = (if (ctxW > 0.dp) ctxW else 64.dp) + 92.dp
        val animW by androidx.compose.animation.core.animateDpAsState(if (compact) compactW else fullW, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "w")
        val widthMod = if (winWpx > 0) Modifier.width(animW) else Modifier.fillMaxWidth()
        LaunchedEffect(vis, item.key) {
            if (vis) {
                reveal.animateTo(1f, spring(dampingRatio = 0.66f, stiffness = 320f))
            } else {
                reveal.animateTo(0f, tween(620, easing = CubicBezierEasing(0.5f, 0f, 0.2f, 1f)))
                finish()
            }
        }
        var drag by remember(item.key) { mutableStateOf(0f) }
        val click = remember { MutableInteractionSource() }

        Box(
            Modifier
                .graphicsLayer {
                    translationY = drag.coerceAtMost(0f)
                    val r = reveal.value
                    // grows out of / melts back into the camera at the top centre
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    scaleX = 0.14f + 0.86f * r
                    scaleY = 0.07f + 0.93f * r
                    alpha = (r * 3f).coerceIn(0f, 1f)
                }
                .then(widthMod)
                .animateContentSize(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
                .clip(shape)
                .background(Color.Black.copy(alpha = if (big && item.call == null) 0.84f else 1f))
                .pointerInput(item.key) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (drag < -40f) {
                                if (item.media && big) { hideJob?.cancel(); expanded = false; drag = 0f }
                                else { if (item.media) { mediaDismissed = true; mediaItem = null }; hide() }
                            } else drag = 0f
                        },
                        onVerticalDrag = { _, dy -> drag += dy },
                    )
                }
                .clickable(interactionSource = click, indication = null, enabled = !replying) {
                    if (item.media) {
                        if (!big) { expanded = true; scheduleHide() }
                        else { try { item.contentIntent?.send() } catch (_: Exception) {}; hideJob?.cancel(); expanded = false }
                    } else {
                        try { item.contentIntent?.send() } catch (_: Exception) {}
                        hide()
                    }
                },
        ) {
            val accent = remember(item.key, item.icon) { if (item.call != null) Color(0xFF34C759) else accentOf(item.icon) }
            val glow = layout.glow
            val timer = remember(item.key) { Animatable(1f) }
            val autoHide = item.call?.incoming != true && !item.media
            LaunchedEffect(item.key, replying, vis) {
                if (vis && autoHide && !replying && layout.timerBar) {
                    timer.snapTo(1f)
                    timer.animateTo(0f, tween(layout.durationSec * 1000, easing = androidx.compose.animation.core.LinearEasing))
                }
            }
            // iOS keyline: hairline around the black pill, tinted with the app colour
            Box(Modifier.matchParentSize().border(0.8.dp, if (glow) accent.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.12f), shape))

            if (big && item.call == null) {
                // frosted, art-tinted glass: the cover blown up and blurred behind a dark scrim
                val a = item.art
                if (a != null) Image(a, null, Modifier.matchParentSize().graphicsLayer { scaleX = 1.6f; scaleY = 1.6f }.blur(44.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded), contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = 0.55f)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.30f)))))
            }
            Box(Modifier.graphicsLayer {
                val c = ((reveal.value - 0.4f) * 2.0f).coerceIn(0f, 1f)
                alpha = c; scaleX = 0.94f + 0.06f * c; scaleY = 0.94f + 0.06f * c
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }) {
                when {
                    item.call != null -> CallCard(item, topPad)
                    item.media -> if (big) MediaCard(item, topPad, accent) else CompactMedia(item, topPad, accent)
                    !big -> SmallRow(item, topPad, ctxW)
                    else -> LargeCard(item, topPad, accent)
                }
            }
            if (layout.timerBar && autoHide && !replying) {
                Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp).padding(bottom = 5.dp).fillMaxWidth().height(2.5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
                    Box(Modifier.fillMaxWidth(timer.value).height(2.5.dp).clip(CircleShape).background(accent.copy(alpha = 0.85f)))
                }
            }
        }
    }

    @Composable
    private fun SmallRow(item: IslandItem, topPad: Dp, cutW: Dp) {
        val h = maxOf(topPad + 8.dp, 46.dp)
        Row(Modifier.fillMaxWidth().height(h).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(item.icon, 24.dp)
            Spacer(Modifier.width(8.dp))
            BasicText(
                item.title.ifBlank { item.appName }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(maxOf(cutW, 36.dp) + 10.dp)) // the camera sits here
            BasicText(
                item.text.ifBlank { item.appName }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp),
                modifier = Modifier.weight(1f),
            )
        }
    }

    private fun iconFor(label: String): androidx.compose.ui.graphics.vector.ImageVector? {
        val l = label.lowercase()
        return when {
            Regex("mark.*read|^read|done|complete").containsMatchIn(l) -> Icons.Filled.Check
            Regex("archiv").containsMatchIn(l) -> Icons.Filled.Archive
            Regex("delet|trash|remove").containsMatchIn(l) -> Icons.Filled.Delete
            Regex("snooze|later|remind").containsMatchIn(l) -> Icons.Filled.Snooze
            Regex("like|heart|thumb").containsMatchIn(l) -> Icons.Filled.ThumbUp
            Regex("call|dial").containsMatchIn(l) -> Icons.Filled.Call
            Regex("share").containsMatchIn(l) -> Icons.Filled.Share
            Regex("open|view|show").containsMatchIn(l) -> Icons.AutoMirrored.Filled.OpenInNew
            Regex("dismiss|close|cancel|stop").containsMatchIn(l) -> Icons.Filled.Close
            Regex("pause").containsMatchIn(l) -> Icons.Filled.Pause
            Regex("play|resume").containsMatchIn(l) -> Icons.Filled.PlayArrow
            Regex("navigate|direction|map").containsMatchIn(l) -> Icons.Filled.Navigation
            else -> null
        }
    }

    @Composable
    private fun ActionLabel(label: String, icon: ImageBitmap?, tint: Color) {
        val vec = iconFor(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
            if (vec != null) androidx.compose.material3.Icon(vec, null, tint = tint, modifier = Modifier.size(16.dp))
            else if (icon != null) Image(icon, null, Modifier.size(16.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint))
            if (vec != null || icon != null) Spacer(Modifier.width(7.dp))
            BasicText(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp))
        }
    }

    @Composable
    private fun EqBars(color: Color, height: Dp = 22.dp) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "eq")
        val hs = listOf(520, 380, 640, 450).mapIndexed { i, d ->
            t.animateFloat(0.25f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(d, easing = androidx.compose.animation.core.LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(i * 90)), label = "b$i")
        }
        Row(Modifier.height(height), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            hs.forEach { h -> Box(Modifier.width(3.5.dp).fillMaxHeight(h.value).clip(CircleShape).background(color)) }
        }
    }

    @Composable
    private fun CompactMedia(item: IslandItem, topPad: Dp, accent: Color) {
        Row(Modifier.fillMaxWidth().height(topPad + 2.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val art = item.art ?: item.icon
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2C2C2E)), contentAlignment = Alignment.Center) {
                if (art != null) Image(art, null, Modifier.size(22.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else androidx.compose.material3.Icon(Icons.Filled.MusicNote, null, tint = IosGray, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.weight(1f))
            EqBars(accent, 16.dp)
        }
    }

    private fun mmss(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(t / 60, t % 60)
    }

    @Composable
    private fun MediaCard(item: IslandItem, topPad: Dp, accent: Color) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = ((layout.bottomPad - 2).coerceAtLeast(8) * 100f / layout.scalePct).dp)) {
            Spacer(Modifier.height(topPad + (layout.belowCam * 100f / layout.scalePct).dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2C2E)), contentAlignment = Alignment.Center) {
                    val art = item.art ?: item.icon
                    if (art != null) Image(art, null, Modifier.size(52.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    else androidx.compose.material3.Icon(Icons.Filled.MusicNote, null, tint = IosGray, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp))
                    BasicText(item.text.ifBlank { item.appName }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, letterSpacing = (-0.2).sp))
                }
                Spacer(Modifier.width(10.dp))
                EqBars(Color.White.copy(alpha = 0.85f))
            }
            if (item.actions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    item.actions.forEachIndexed { i, (label, pi) ->
                        val big = item.actions.size % 2 == 1 && i == item.actions.size / 2
                        val ic = item.icons.getOrNull(i)
                        val vec = iconFor(label)
                        Box(
                            Modifier.size(if (big) 46.dp else 40.dp).clip(CircleShape).clickable { try { pi.send() } catch (_: Exception) {}; scheduleHide() },
                            contentAlignment = Alignment.Center,
                        ) {
                            val sz = if (big) 32.dp else 26.dp
                            if (ic != null) Image(ic, label, Modifier.size(sz), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White))
                            else if (vec != null) androidx.compose.material3.Icon(vec, label, tint = Color.White, modifier = Modifier.size(sz))
                            else BasicText(label.take(4), style = TextStyle(color = Color.White, fontSize = 12.sp))
                        }
                    }
                }
            }
            val pr = item.progress
            if (pr != null && pr.durationMs > 0) {
                var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
                LaunchedEffect(pr) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(500) } }
                val pos = (if (pr.playing) pr.positionMs + ((now - pr.updatedAtMs) * pr.speed).toLong() else pr.positionMs).coerceIn(0, pr.durationMs)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(mmss(pos), style = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(pos.toFloat() / pr.durationMs).fillMaxHeight().clip(CircleShape).background(Color.White))
                    }
                    Spacer(Modifier.width(10.dp))
                    BasicText(mmss(pr.durationMs), style = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp))
                }
            }
        }
    }

    private val IosGray = Color(0xFF9A9AA0)
    private val IosFill = Color.White.copy(alpha = 0.15f)

    @Composable
    private fun LargeCard(item: IslandItem, topPad: Dp, accent: Color) {
        val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = ((layout.bottomPad - 4).coerceAtLeast(6) * 100f / layout.scalePct).dp)) {
            Spacer(Modifier.height(topPad + (layout.belowCam * 100f / layout.scalePct).dp)) // camera zone
            // same anatomy as the stock Android heads-up: small app icon + name + time, title, text, big icon on the right
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val ai = item.appIcon ?: item.icon
                        if (ai != null) Image(ai, null, Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(6.dp))
                        BasicText(item.appName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, fontWeight = FontWeight.Medium))
                        BasicText("  •  now", maxLines = 1, style = TextStyle(color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp))
                    }
                    Spacer(Modifier.height(5.dp))
                    BasicText(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp))
                    if (item.text.isNotBlank()) {
                        Spacer(Modifier.height(1.dp))
                        BasicText(item.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp, lineHeight = 18.sp))
                    }
                }
                val big = item.largeIcon
                if (big != null) {
                    Spacer(Modifier.width(12.dp))
                    Image(big, null, Modifier.size(44.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
            }
            if (item.code != null && !replying) {
                Spacer(Modifier.height(6.dp))
                BasicText(item.code, maxLines = 1, style = TextStyle(color = accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp))
            }
            val reply = item.reply
            if (replying && reply != null) {
                Spacer(Modifier.height(10.dp))
                var input by remember(item.key) { mutableStateOf("") }
                val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    setFocusable(true)
                    delay(120)
                    runCatching { fr.requestFocus() }
                    kb?.show()
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f).heightIn(min = 38.dp).clip(RoundedCornerShape(22.dp)).background(IosFill)
                            .padding(horizontal = 18.dp, vertical = 12.dp).focusRequester(fr),
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp, letterSpacing = (-0.2).sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                        maxLines = 4,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (input.isNotBlank()) sendReply(reply, input.trim()) }),
                        decorationBox = { inner ->
                            if (input.isEmpty()) BasicText("Reply to ${item.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = IosGray, fontSize = 14.sp))
                            inner()
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(if (input.isBlank()) IosFill else accent)
                            .clickable(enabled = input.isNotBlank()) { sendReply(reply, input.trim()) },
                        contentAlignment = Alignment.Center,
                    ) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.ArrowUpward, null, tint = if (input.isBlank()) IosGray else onAccent, modifier = Modifier.size(24.dp)) }
                }
            } else if (item.actions.isNotEmpty() || reply != null || item.code != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item.code != null) {
                        Box(
                            Modifier.weight(1f).height(38.dp).clip(CircleShape).background(accent)
                                .clickable {
                                    try {
                                        val cm = IslandAccessibilityService.instance?.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", item.code))
                                    } catch (_: Exception) {}
                                    hide()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(Icons.Filled.ContentCopy, null, tint = onAccent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                BasicText("Copy code", maxLines = 1, style = TextStyle(color = onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp))
                            }
                        }
                    }
                    if (reply != null) {
                        val rBg = if (item.code != null) IosFill else accent
                        val rFg = if (item.code != null) Color.White else onAccent
                        Box(
                            Modifier.weight(1f).height(38.dp).clip(CircleShape).background(rBg)
                                .clickable { replying = true; hideJob?.cancel() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.Reply, null, tint = rFg, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                BasicText(reply.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = rFg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp))
                            }
                        }
                    }
                    item.actions.take((2 - (if (reply != null) 1 else 0) - (if (item.code != null) 1 else 0)).coerceAtLeast(0)).forEachIndexed { i, (label, pi) ->
                        Box(
                            Modifier.weight(1f).height(38.dp).clip(CircleShape).background(IosFill)
                                .clickable { try { pi.send() } catch (_: Exception) {}; hide() },
                            contentAlignment = Alignment.Center,
                        ) {
                            ActionLabel(label, item.icons.getOrNull(i), Color.White)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CallCard(item: IslandItem, topPad: Dp) {
        val call = item.call ?: return
        // the avatar and buttons sit beside the camera, so no camera band is reserved: centre them in the pill, inset from the rounded ends
        Box(
            Modifier.fillMaxWidth().heightIn(min = topPad + (layout.belowCam * 100f / layout.scalePct).dp + 22.dp).padding(horizontal = 26.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val initial = item.title.trim().firstOrNull { it.isLetter() }?.uppercase()
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFF8E8E93), Color(0xFF636366)))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.art != null) Image(item.art, null, Modifier.size(42.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    else if (initial != null) BasicText(initial, style = TextStyle(color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold))
                    else androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Person, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(item.title.ifBlank { item.appName }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp))
                    BasicText(
                        item.text.ifBlank { if (call.incoming) "Incoming Call" else "Ongoing call" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = IosGray, fontSize = 13.sp, letterSpacing = (-0.2).sp),
                    )
                }
                if (call.decline != null) CallButton(Color(0xFFFF3B30), androidx.compose.material.icons.Icons.Default.CallEnd) {
                    try { call.decline.send() } catch (_: Exception) {}; hide()
                }
                if (call.incoming && call.answer != null) {
                    Spacer(Modifier.width(14.dp))
                    CallButton(Color(0xFF34C759), androidx.compose.material.icons.Icons.Default.Call) {
                        try { call.answer.send() } catch (_: Exception) {}; hide()
                    }
                }
            }
        }
    }

    @Composable
    private fun CallButton(color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
    }

    @Composable
    private fun AppIcon(icon: ImageBitmap?, size: Dp) {
        if (icon != null) Image(icon, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)))
        else Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Color.White.copy(alpha = 0.2f)))
    }
}

