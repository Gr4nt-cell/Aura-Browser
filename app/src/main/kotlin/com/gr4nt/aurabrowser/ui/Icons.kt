package com.gr4nt.aurabrowser.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Ic {
    Back, Forward, Reload, Close, Plus, Tabs, More, Lock, Share, Search, Sidebar, Star, StarFilled, Download,
    Shield, Book, Clock, Sliders, Check, ChevronDown, ChevronRight, Pause, Play, Trash, External, Desktop, Copy, Home, Grid, Globe, Warning
}

/** 24×24 line icons drawn in Compose, so the app needs no icon font or vector-asset dependency. */
@Composable
fun Icon(ic: Ic, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        withTransform({ scale(k, k, pivot = Offset.Zero) }) { drawIc(ic, tint) }
    }
}

private fun line(w: Float = 2f) = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.poly(c: Color, vararg p: Float, close: Boolean = false, w: Float = 2f) {
    val path = Path()
    path.moveTo(p[0], p[1])
    var i = 2
    while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
    if (close) path.close()
    drawPath(path, c, style = line(w))
}

private fun DrawScope.drawIc(ic: Ic, c: Color) {
    when (ic) {
        Ic.Back -> poly(c, 15f, 5f, 8f, 12f, 15f, 19f, w = 2.4f)
        Ic.Forward -> poly(c, 9f, 5f, 16f, 12f, 9f, 19f, w = 2.4f)
        Ic.ChevronDown -> poly(c, 6f, 9f, 12f, 15f, 18f, 9f, w = 2.4f)
        Ic.ChevronRight -> poly(c, 9f, 6f, 15f, 12f, 9f, 18f, w = 2.4f)
        Ic.Reload -> {
            drawArc(c, startAngle = -50f, sweepAngle = 285f, useCenter = false, topLeft = Offset(5f, 5f), size = Size(14f, 14f), style = line())
            poly(c, 15.5f, 2.5f, 17f, 6f, 13.5f, 7f, w = 2f)
        }
        Ic.Close -> { poly(c, 6f, 6f, 18f, 18f, w = 2.2f); poly(c, 18f, 6f, 6f, 18f, w = 2.2f) }
        Ic.Plus -> { poly(c, 12f, 5f, 12f, 19f, w = 2.2f); poly(c, 5f, 12f, 19f, 12f, w = 2.2f) }
        Ic.Tabs -> {
            drawRoundRect(c, Offset(4f, 8f), Size(12f, 12f), CornerRadius(2.5f), style = line())
            poly(c, 8f, 8f, 8f, 5f, 19f, 5f, 19f, 16f, 16f, 16f)
        }
        Ic.More -> { for (x in listOf(5f, 12f, 19f)) drawCircle(c, 1.9f, Offset(x, 12f)) }
        Ic.Lock -> {
            drawRoundRect(c, Offset(6f, 11f), Size(12f, 9f), CornerRadius(2.5f), style = line(2f))
            drawArc(c, 180f, 180f, false, Offset(8.5f, 4.5f), Size(7f, 7f), style = line(2f))
        }
        Ic.Share -> {
            poly(c, 12f, 15f, 12f, 4f); poly(c, 8f, 8f, 12f, 4f, 16f, 8f)
            poly(c, 8f, 11f, 6f, 11f, 6f, 20f, 18f, 20f, 18f, 11f, 16f, 11f)
        }
        Ic.Search -> { drawCircle(c, 6f, Offset(10.5f, 10.5f), style = line()); poly(c, 15f, 15f, 20f, 20f) }
        Ic.Sidebar -> {
            drawRoundRect(c, Offset(3f, 5f), Size(18f, 14f), CornerRadius(3f), style = line())
            poly(c, 9.5f, 5f, 9.5f, 19f)
        }
        Ic.Star, Ic.StarFilled -> {
            val p = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 9f else 4.2f
                val a = -PI / 2 + i * PI / 5
                val x = (12 + r * cos(a)).toFloat(); val y = (12.6 + r * sin(a)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            if (ic == Ic.StarFilled) drawPath(p, c) else drawPath(p, c, style = line(1.9f))
        }
        Ic.Download -> { poly(c, 12f, 4f, 12f, 15f); poly(c, 7f, 10.5f, 12f, 15.5f, 17f, 10.5f); poly(c, 5f, 20f, 19f, 20f) }
        Ic.Shield -> {
            val p = Path()
            p.moveTo(12f, 3f); p.lineTo(19f, 6f); p.lineTo(19f, 12f)
            p.cubicTo(19f, 16.5f, 16f, 19.5f, 12f, 21f)
            p.cubicTo(8f, 19.5f, 5f, 16.5f, 5f, 12f); p.lineTo(5f, 6f); p.close()
            drawPath(p, c, style = line())
        }
        Ic.Book -> {
            drawRoundRect(c, Offset(5f, 4f), Size(14f, 16f), CornerRadius(2f), style = line())
            poly(c, 9f, 4f, 9f, 20f)
        }
        Ic.Clock -> { drawCircle(c, 8f, Offset(12f, 12f), style = line()); poly(c, 12f, 7f, 12f, 12f, 15.5f, 14f) }
        Ic.Sliders -> {
            poly(c, 4f, 7f, 20f, 7f); poly(c, 4f, 17f, 20f, 17f)
            drawCircle(c, 2.4f, Offset(9f, 7f)); drawCircle(c, 2.4f, Offset(15f, 17f))
        }
        Ic.Check -> poly(c, 5f, 12.5f, 10f, 17.5f, 19f, 7f, w = 2.4f)
        Ic.Pause -> { poly(c, 9f, 6f, 9f, 18f, w = 3f); poly(c, 15f, 6f, 15f, 18f, w = 3f) }
        Ic.Play -> { val p = Path(); p.moveTo(8f, 5f); p.lineTo(19f, 12f); p.lineTo(8f, 19f); p.close(); drawPath(p, c) }
        Ic.Trash -> {
            poly(c, 4f, 7f, 20f, 7f); poly(c, 9f, 7f, 9f, 4.5f, 15f, 4.5f, 15f, 7f)
            poly(c, 6.5f, 7f, 7.5f, 20f, 16.5f, 20f, 17.5f, 7f)
        }
        Ic.External -> {
            poly(c, 13f, 4f, 20f, 4f, 20f, 11f); poly(c, 20f, 4f, 11f, 13f)
            poly(c, 18f, 14f, 18f, 19f, 5f, 19f, 5f, 6f, 10f, 6f)
        }
        Ic.Desktop -> {
            drawRoundRect(c, Offset(3f, 4f), Size(18f, 12f), CornerRadius(2f), style = line())
            poly(c, 8f, 20f, 16f, 20f); poly(c, 12f, 16f, 12f, 20f)
        }
        Ic.Copy -> {
            drawRoundRect(c, Offset(9f, 9f), Size(11f, 11f), CornerRadius(2.5f), style = line())
            poly(c, 15f, 9f, 15f, 6f, 6f, 6f, 6f, 15f, 9f, 15f)
        }
        Ic.Home -> poly(c, 4f, 11f, 12f, 4f, 20f, 11f, 20f, 20f, 14f, 20f, 14f, 14f, 10f, 14f, 10f, 20f, 4f, 20f, close = true)
        Ic.Grid -> {
            for (x in listOf(4f, 13.5f)) for (y in listOf(4f, 13.5f))
                drawRoundRect(c, Offset(x, y), Size(6.5f, 6.5f), CornerRadius(1.8f), style = line())
        }
        Ic.Globe -> {
            drawCircle(c, 8.5f, Offset(12f, 12f), style = line(1.8f))
            drawOval(c, Offset(8f, 3.5f), Size(8f, 17f), style = line(1.8f))
            poly(c, 3.5f, 12f, 20.5f, 12f, w = 1.8f)
        }
        Ic.Warning -> { poly(c, 12f, 4f, 21f, 20f, 3f, 20f, close = true); poly(c, 12f, 10f, 12f, 14f); drawCircle(c, 1f, Offset(12f, 17f)) }
    }
}
