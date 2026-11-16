package com.gr4nt.aurabrowser.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/**
 * Liquid-glass surface.
 *
 * Android cannot blur "whatever is behind a view" for arbitrary content, and the chrome here deliberately never
 * overlaps the web page. So the glass is built from: a blurred, tiny snapshot of the page edge (so the bar looks
 * like the page continues underneath), a translucent tint, a soft top highlight and a bright gradient rim.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    sample: ImageBitmap? = null,
    fromBottom: Boolean = true,
    elevation: Dp = 10.dp,
    tintAlpha: Float = 0.26f,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = LocalDark.current
    val base = if (dark) Color(0xFF1C1B26) else Color.White
    val rimTop = if (dark) Color.White.copy(alpha = 0.55f) else Color.White
    val rimMid = if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.20f)
    val rimBottom = if (dark) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.85f)
    Box(
        modifier
            .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.20f))
            .clip(shape)
    ) {
        // 1. page edge, blurred and magnified a touch (lens effect)
        if (sample != null) {
            BackdropCanvas(sample, fromBottom, Modifier.matchParentSize().let { if (Build.VERSION.SDK_INT >= 31) it.blur(16.dp, BlurredEdgeTreatment.Unbounded) else it })
        } else {
            Box(Modifier.matchParentSize().background(base.copy(alpha = 0.40f)))
        }
        // 2. frost: lighter than before so the page shows through
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(base.copy(alpha = tintAlpha + 0.08f), base.copy(alpha = (tintAlpha - 0.10f).coerceAtLeast(0.04f))))))
        // 3. broad specular sheen across the top-left
        Box(
            Modifier.matchParentSize().background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.50f), Color.Transparent, Color.White.copy(alpha = if (dark) 0.07f else 0.20f)),
                    start = Offset(0f, 0f), end = Offset(520f, 380f)
                )
            )
        )
        // 4. soft inner glow hugging the bottom edge (light bending through the thick edge of the glass)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent, 0.72f to Color.Transparent,
                    1.0f to Color.White.copy(alpha = if (dark) 0.14f else 0.32f),
                )
            )
        )
        // 5. crisp rim, bright on the lit edges, plus a faint inner ring for thickness
        Box(Modifier.matchParentSize().border(1.2.dp, Brush.linearGradient(listOf(rimTop, rimMid, rimBottom), start = Offset(0f, 0f), end = Offset(500f, 500f)), shape))
        Box(Modifier.matchParentSize().padding(2.dp).border(0.6.dp, Color.White.copy(alpha = if (dark) 0.07f else 0.30f), shape))
        content()
    }
}

/** Blurs whatever is behind a dialog window (Android 12+, when the device allows cross-window blur). */
@Composable
private fun BlurBehind(radiusPx: Int) {
    val v = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        if (Build.VERSION.SDK_INT >= 31) {
            (v.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val a = w.attributes; a.blurBehindRadius = radiusPx; w.attributes = a
            }
        }
    }
}

@Composable
internal fun BackdropCanvas(sample: ImageBitmap, fromBottom: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = sample.width
        val h = sample.height
        val stripH = (h * 0.16f).toInt().coerceAtLeast(1)
        val srcY = if (fromBottom) h - stripH else 0
        drawImage(
            sample,
            srcOffset = IntOffset(0, srcY),
            srcSize = IntSize(w, stripH),
            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** Round glass button with an icon. */
@Composable
fun GlassButton(
    ic: Ic,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    sample: ImageBitmap? = null,
    fromBottom: Boolean = true,
    enabled: Boolean = true,
    tint: Color = Ink.primary(),
    iconSize: Dp = 22.dp,
) {
    GlassPanel(
        modifier.size(size).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick
        ),
        CircleShape, sample, fromBottom, elevation = 6.dp,
    ) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            Icon(ic, if (enabled) tint else tint.copy(alpha = 0.32f), iconSize)
        }
    }
}

/** Flat (no glass) circular icon button for use inside already-glassy rows. */
@Composable
fun PlainIconButton(ic: Ic, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 36.dp, tint: Color = Ink.primary(), enabled: Boolean = true, iconSize: Dp = 20.dp) {
    Box(
        modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(ic, if (enabled) tint else tint.copy(alpha = 0.3f), iconSize) }
}

/**
 * Bottom sheet on phones, centred card on larger windows. Dimmed scrim dismisses on tap.
 */
@Composable
fun GlassSheet(onDismiss: () -> Unit, wide: Boolean, title: String? = null, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BlurBehind(48)
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.16f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(240), initialScale = 0.92f),
                exit = fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(150), targetScale = 0.94f),
            ) {
                GlassPanel(
                    Modifier
                        .widthIn(max = if (wide) 400.dp else 330.dp).fillMaxWidth(if (wide) 0.6f else 0.84f)
                        .heightIn(max = if (wide) 520.dp else 480.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                    RoundedCornerShape(28.dp), tintAlpha = 0.40f, elevation = 28.dp,
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        if (title != null) {
                            Text(title, color = Ink.primary(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp, start = 14.dp, end = 14.dp), maxLines = 1)
                        }
                        content()
                    }
                }
            }
        }
    }
}

/** Small centred alert in glass. */
@Composable
fun GlassAlert(
    title: String,
    message: String? = null,
    confirm: String,
    onConfirm: () -> Unit,
    dismiss: String? = null,
    onDismiss: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BlurBehind(40)
        GlassPanel(Modifier.widthIn(max = 300.dp).fillMaxWidth(0.8f), RoundedCornerShape(28.dp), tintAlpha = 0.42f, elevation = 24.dp) {
            androidx.compose.foundation.layout.Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = Ink.primary(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (message != null) {
                    Text(message, color = Ink.secondary(), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp), maxLines = 8)
                }
                extra?.invoke()
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    if (dismiss != null) {
                        PillButton(dismiss, onDismiss, filled = false, modifier = Modifier.weight(1f))
                    }
                    PillButton(confirm, onConfirm, filled = true, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, danger: Boolean = false) {
    val bg = when {
        danger && filled -> Ink.danger
        filled -> Ink.accent
        else -> if (LocalDark.current) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.07f)
    }
    Box(
        modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (filled) Color.White else if (danger) Ink.danger else Ink.primary(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
