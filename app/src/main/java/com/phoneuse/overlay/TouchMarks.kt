package com.phoneuse.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.i18n.tr

/** One touch of the agent, in screen pixels: a tap or long press at (x, y), or a swipe to (x2, y2). */
data class TouchMark(
    val id: Long,
    val x: Int,
    val y: Int,
    val x2: Int? = null,
    val y2: Int? = null,
    val long: Boolean = false,
    val success: Boolean = true,
    val label: String? = null,
)

/** Live position of the glowing AI pointer targeting an element on screen. */
data class PointerCursorState(
    val x: Int,
    val y: Int,
    val label: String? = null,
    val visible: Boolean = true,
)

private val TouchCore = Color(0xFFFFFFFF)
private val TouchRing = Color(0xFFE4572E)
private val TouchFail = Color(0xFFD9383A)
private val LabelBg = Color(0xCC181A1F)

/**
 * Visible AI touch pointer + post-gesture outcome marks drawn on the non-touchable border overlay layer:
 * - While moving/targeting: draws a glowing cursor dot at the clamped screen pixel and optional short label.
 * - After a successful touch/swipe/long-press: animates ripple/hold/trail.
 * - After a failed touch/swipe: draws a crisp failed cross indicator and never animates a fake click.
 */
@Composable
fun TouchPointerOverlay(
    pointer: PointerCursorState?,
    marks: List<TouchMark>,
    showLabels: Boolean = true,
) {
    Box(Modifier.fillMaxSize()) {
        if (pointer != null && pointer.visible) {
            LiveCursor(pointer, showLabels)
        }
        TouchMarks(marks)
    }
}

@Composable
private fun LiveCursor(pointer: PointerCursorState, showLabels: Boolean) {
    val pos by animateIntOffsetAsState(
        targetValue = IntOffset(pointer.x, pointer.y),
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "aiPointer",
    )
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(pos.x.toFloat(), pos.y.toFloat())
        drawCircle(TouchRing.copy(alpha = 0.24f), 18.dp.toPx(), center)
        drawCircle(TouchRing.copy(alpha = 0.9f), 9.dp.toPx(), center, style = Stroke(2.5.dp.toPx()))
        drawCircle(TouchCore, 4.dp.toPx(), center)
    }
    val label = pointer.label?.takeIf { showLabels && it.isNotBlank() }
    if (label != null) {
        val density = LocalDensity.current
        val xOff = with(density) { 16.dp.roundToPx() }
        val yOff = with(density) { 28.dp.roundToPx() }
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TouchCore,
            modifier = Modifier
                .offset { IntOffset((pos.x + xOff).coerceAtLeast(12), (pos.y - yOff).coerceAtLeast(16)) }
                .clip(RoundedCornerShape(8.dp))
                .background(LabelBg)
                .border(1.dp, TouchRing.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/**
 * Where the agent touches, drawn on the border layer (which never takes touches): a ripple for a tap, a ring that
 * fills for a long press, a fading trail with a moving dot for a swipe, or a failure X if the gesture failed.
 */
@Composable
fun TouchMarks(marks: List<TouchMark>) {
    for (mark in marks) {
        key(mark.id) { Mark(mark) }
    }
}

@Composable
private fun Mark(mark: TouchMark) {
    val swipe = mark.x2 != null && mark.y2 != null
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val duration = when {
            !mark.success -> 520
            mark.long -> 900
            else -> 650
        }
        progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
    }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        val start = Offset(mark.x.toFloat(), mark.y.toFloat())
        val fade = 1f - p
        if (!mark.success) {
            // Never animate a fake click when Android rejected or failed the gesture.
            val arm = 10.dp.toPx()
            drawCircle(TouchFail.copy(alpha = 0.18f * fade), 18.dp.toPx(), start)
            drawCircle(TouchFail.copy(alpha = 0.9f * fade), 15.dp.toPx(), start, style = Stroke(2.5.dp.toPx()))
            drawLine(
                color = TouchFail.copy(alpha = 0.95f * fade),
                start = Offset(start.x - arm, start.y - arm),
                end = Offset(start.x + arm, start.y + arm),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = TouchFail.copy(alpha = 0.95f * fade),
                start = Offset(start.x + arm, start.y - arm),
                end = Offset(start.x - arm, start.y + arm),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }
        if (swipe) {
            val end = Offset(mark.x2!!.toFloat(), mark.y2!!.toFloat())
            val head = start + (end - start) * minOf(1f, p * 1.4f)
            drawLine(TouchRing.copy(alpha = 0.55f * fade), start, head, strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(TouchCore.copy(alpha = 0.9f * fade), 11.dp.toPx(), head)
            drawCircle(TouchRing.copy(alpha = fade), 11.dp.toPx(), head, style = Stroke(3.dp.toPx()))
            return@Canvas
        }
        // The finger: a soft dot that shrinks away, with a ring that spreads out.
        drawCircle(TouchCore.copy(alpha = 0.85f * fade), (14 - 6 * p).dp.toPx(), start)
        drawCircle(TouchRing.copy(alpha = 0.9f * fade), (14 + 26 * p).dp.toPx(), start, style = Stroke(4.dp.toPx()))
        if (mark.long) {
            // A long press fills its ring while it is held.
            drawCircle(TouchRing.copy(alpha = 0.25f * fade), (14 + 26 * p).dp.toPx(), start)
        }
    }
    if (!mark.success && !mark.label.isNullOrBlank()) {
        val density = LocalDensity.current
        val yOff = with(density) { 28.dp.roundToPx() }
        Text(
            text = tr("Failed: ${mark.label}", "실패: ${mark.label}"),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TouchCore,
            modifier = Modifier
                .offset { IntOffset((mark.x + 12).coerceAtLeast(12), (mark.y - yOff).coerceAtLeast(16)) }
                .clip(RoundedCornerShape(8.dp))
                .background(TouchFail.copy(alpha = 0.85f))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}
