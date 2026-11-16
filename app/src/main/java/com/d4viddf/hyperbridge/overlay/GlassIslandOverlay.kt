package com.d4viddf.hyperbridge.overlay

import android.app.PendingIntent
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
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
)

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
object GlassIslandOverlay {
    fun canDraw(@Suppress("UNUSED_PARAMETER") ctx: Context) = IslandAccessibilityService.instance != null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var current by mutableStateOf<IslandItem?>(null)
    private var expanded by mutableStateOf(false)
    private var hideJob: Job? = null
    private var cutoutW by mutableStateOf(0.dp)
    private var cutoutH by mutableStateOf(0.dp)

    fun show(ctx: Context, item: IslandItem) {
        scope.launch {
            if (!canDraw(ctx)) return@launch
            val host: Context = IslandAccessibilityService.instance ?: return@launch
            ensureWindow(host)
            // same notification updating: keep expanded state
            if (current?.key != item.key) expanded = false
            current = item
            scheduleHide()
        }
    }

    fun dismiss(key: String? = null) {
        scope.launch { if (key == null || current?.key == key) hide() }
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(if (expanded) 12_000 else 5_500)
            hide()
        }
    }

    private fun hide() {
        hideJob?.cancel()
        current = null
        expanded = false
        removeWindow()
    }

    private fun ensureWindow(ctx: Context) {
        if (view != null) return
        val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = windowManager
        val density = ctx.resources.displayMetrics.density
        val metrics = windowManager.currentWindowMetrics
        val screenW = metrics.bounds.width()
        var shiftPx = 0
        val cutout = metrics.windowInsets.displayCutout
        val top = cutout?.boundingRectTop
        if (top != null && !top.isEmpty) {
            cutoutW = (top.width() / density).dp
            cutoutH = (top.bottom / density).dp
            shiftPx = top.centerX() - screenW / 2
        } else {
            // no notch reported: reserve the status-bar sized strip so content never sits under a punch-hole either
            cutoutW = 0.dp
            cutoutH = 0.dp
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
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
            x = shiftPx
            y = 0
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
                if (item != null) IslandContent(item)
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
    }

    // ------------------------------------------------------------------------------------------------ UI

    @Composable
    private fun IslandContent(item: IslandItem) {
        val big = expanded
        val ctxW = cutoutW
        val topPad = if (cutoutH > 0.dp) cutoutH else 24.dp
        val shape = RoundedCornerShape(if (big) 34.dp else 26.dp)
        var drag by remember(item.key) { mutableStateOf(0f) }
        val click = remember { MutableInteractionSource() }

        Box(
            Modifier
                .graphicsLayer { translationY = drag.coerceAtMost(0f) }
                .padding(horizontal = 6.dp)
                .widthIn(min = if (big) 340.dp else 250.dp, max = if (big) 392.dp else 360.dp)
                .animateContentSize(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
                .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.5f))
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xF2121217), Color(0xE60A0A0E))))
                .pointerInput(item.key) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (drag < -40f) hide() else drag = 0f },
                        onVerticalDrag = { _, dy -> drag += dy },
                    )
                }
                .clickable(interactionSource = click, indication = null) {
                    if (!expanded) { expanded = true; scheduleHide() } else {
                        try { item.contentIntent?.send() } catch (_: Exception) {}
                        hide()
                    }
                },
        ) {
            // glass sheen + rim
            Canvas(Modifier.matchParentSize()) {
                drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent, Color.White.copy(alpha = 0.05f)), Offset.Zero, Offset(size.width, size.height)))
            }
            Box(Modifier.matchParentSize().border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.22f))), shape))

            if (!big) SmallRow(item, topPad, ctxW) else LargeCard(item, topPad)
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

    @Composable
    private fun LargeCard(item: IslandItem, topPad: Dp) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
            Spacer(Modifier.height(maxOf(topPad, 24.dp) + 6.dp)) // camera zone
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(item.icon, 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(item.appName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp))
                    BasicText(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                }
            }
            if (item.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                BasicText(item.text, maxLines = 5, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 19.sp))
            }
            if (item.actions.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.actions.take(3).forEach { (label, pi) ->
                        Box(
                            Modifier.weight(1f).height(38.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.14f))
                                .border(0.8.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                                .clickable { try { pi.send() } catch (_: Exception) {}; hide() },
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium), modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppIcon(icon: ImageBitmap?, size: Dp) {
        if (icon != null) Image(icon, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)))
        else Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Color.White.copy(alpha = 0.2f)))
    }
}

