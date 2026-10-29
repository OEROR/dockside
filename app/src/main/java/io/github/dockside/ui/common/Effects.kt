package io.github.dockside.ui.common

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.stopScroll
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.spring

/**
 * Night mode: maps every pixel's luminance onto a deep red, the way a bedside display
 * should look in a dark room. Implemented as a colour filter on the host view's
 * hardware layer, so photos, widgets and clocks are all tinted consistently.
 */
@Composable
fun NightFilter(fraction: Float) {
    val view = LocalView.current
    DisposableEffect(view) {
        onDispose { view.setLayerType(View.LAYER_TYPE_NONE, null) }
    }
    SideEffect {
        if (fraction <= 0.002f) {
            if (view.layerType != View.LAYER_TYPE_NONE) view.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(nightMatrix(fraction)) }
            if (view.layerType == View.LAYER_TYPE_HARDWARE) view.setLayerPaint(paint)
            else view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }
}

fun nightMatrix(f: Float): FloatArray {
    val identity = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
    // Luminance boosted a little so mid-greys stay legible once everything is red.
    val k = 1.15f
    val night = floatArrayOf(
        0.30f * k, 0.59f * k, 0.11f * k, 0f, 0f,
        0.02f, 0.04f, 0.01f, 0f, 0f,
        0.01f, 0.02f, 0.01f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
    return FloatArray(20) { identity[it] + (night[it] - identity[it]) * f }
}

/** The wobble widgets and photos do in edit mode. */
fun Modifier.jiggle(enabled: Boolean, seed: Int = 0): Modifier = composed {
    if (!enabled) return@composed this
    val transition = rememberInfiniteTransition(label = "jiggle")
    val angle by transition.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(130 + (seed % 3) * 17, easing = LinearEasing), RepeatMode.Reverse),
        label = "angle",
    )
    graphicsLayer { rotationZ = angle * 0.8f }
}

/**
 * Page dots that appear while a vertical stack is being scrolled and fade a moment later.
 * [pulse] should change whenever the page changes.
 */
@Composable
fun PageDots(
    count: Int,
    current: Int,
    pulse: Any?,
    modifier: Modifier = Modifier,
    vertical: Boolean = true,
    alwaysVisible: Boolean = false,
    dotSize: Dp = 7.dp,
) {
    var visible by remember { mutableStateOf(alwaysVisible) }
    LaunchedEffect(pulse, alwaysVisible) {
        visible = true
        if (!alwaysVisible) {
            delay(1600)
            visible = false
        }
    }
    if (count < 2) return
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut(tween(600))) {
        val dots: @Composable () -> Unit = {
            repeat(count) { i ->
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(dotSize)
                        .background(if (i == current) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
                )
            }
        }
        if (vertical) {
            Column(verticalArrangement = Arrangement.spacedBy(dotSize * 0.8f), horizontalAlignment = Alignment.CenterHorizontally) { dots() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(dotSize * 0.8f), verticalAlignment = Alignment.CenterVertically) { dots() }
        }
    }
}

/**
 * Light-touch paging: a short, slow drag (under a fifth of the page) is enough to move on,
 * instead of the default half page or a fast fling.
 */
@Composable
fun easyFling(state: PagerState): TargetedFlingBehavior = PagerDefaults.flingBehavior(
    state = state,
    pagerSnapDistance = PagerSnapDistance.atMost(1),
    snapPositionalThreshold = 0.18f,
)

/**
 * Vertical paging that only claims clearly vertical drags. The stock pager grabs any drag that
 * crosses the touch slop vertically first, so a slightly diagonal horizontal swipe got stuck in
 * a widget stack or clock list instead of moving to the next view. Here, until the gesture has
 * a direction, nothing is consumed; a mostly-horizontal drag is left entirely to the parent.
 * Use with `userScrollEnabled = false` on the pager.
 */
fun Modifier.lockedVerticalPaging(state: PagerState, enabled: Boolean = true): Modifier = composed {
    val scope = rememberCoroutineScope()
    val slop = LocalViewConfiguration.current.touchSlop
    val flingVelocity = with(LocalDensity.current) { 350.dp.toPx() }
    if (!enabled) return@composed this
    pointerInput(state, slop) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var total = Offset.Zero
            var dragging = false
            val startPage = state.currentPage
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                tracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.positionChange()
                if (!dragging) {
                    if (change.isConsumed) return@awaitEachGesture
                    total += delta
                    if (total.getDistance() < slop) continue
                    if (abs(total.y) < abs(total.x) * 1.2f) return@awaitEachGesture
                    dragging = true
                    scope.launch { state.stopScroll() }
                }
                change.consume()
                state.dispatchRawDelta(-delta.y)
            }
            if (!dragging) return@awaitEachGesture
            val v = tracker.calculateVelocity().y
            val position = state.currentPage + state.currentPageOffsetFraction
            val target = when {
                v < -flingVelocity -> floor(position).toInt() + 1
                v > flingVelocity -> ceil(position).toInt() - 1
                position - startPage > 0.18f -> startPage + 1
                position - startPage < -0.18f -> startPage - 1
                else -> startPage
            }.coerceIn(0, maxOf(0, state.pageCount - 1))
            scope.launch { state.animateScrollToPage(target, animationSpec = spring(dampingRatio = 0.9f, stiffness = 400f)) }
        }
    }
}

private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG)

/**
 * A soft radial light that fades to nothing at [radius], drawn with dithering so the falloff
 * doesn't break into visible rings on 8-bit screens.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.softGlow(
    center: androidx.compose.ui.geometry.Offset,
    radius: Float,
    vararg stops: Pair<Float, Color>,
) {
    if (radius <= 0f) return
    glowPaint.shader = android.graphics.RadialGradient(
        center.x, center.y, radius,
        IntArray(stops.size) { stops[it].second.toArgb() },
        FloatArray(stops.size) { stops[it].first },
        android.graphics.Shader.TileMode.CLAMP,
    )
    drawContext.canvas.nativeCanvas.drawCircle(center.x, center.y, radius, glowPaint)
}
