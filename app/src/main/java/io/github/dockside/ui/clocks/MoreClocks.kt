package io.github.dockside.ui.clocks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.dockside.ui.common.digitFloat
import io.github.dockside.ui.common.softGlow
import androidx.compose.animation.core.spring
import io.github.dockside.ui.common.stagger
import io.github.dockside.ui.common.LocalIntro
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.dockside.ui.common.CondensedFamily
import io.github.dockside.ui.common.Glyphs
import io.github.dockside.ui.common.Palette
import io.github.dockside.ui.common.RoundedFamily
import io.github.dockside.ui.common.TimeText
import io.github.dockside.ui.common.WideFamily
import io.github.dockside.ui.common.rememberFrameTime
import io.github.dockside.ui.common.rememberTicker
import io.github.dockside.ui.common.toLocal
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------- Lines

/**
 * An analog face that fills the whole landscape screen: the minute marks sit on the edges of
 * the display instead of on a circle, so the dial reads as a rectangle of light rays.
 */
@Composable
fun LinesClock(accent: Color, info: ClockInfo, modifier: Modifier = Modifier) {
    val frame = rememberFrameTime()
    val intro = LocalIntro.current
    val day by rememberTicker(60_000)
    val measurer = rememberTextMeasurer(cacheSize = 16)
    Box(modifier.fillMaxSize()) {
        // Static dial in its own layer; only the hands are redrawn each frame.
        Spacer(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val p = stagger(intro.value, 0, 1)
                    alpha = p; scaleX = 1.08f - 0.08f * p; scaleY = scaleX
                }
                .drawWithCache {
                val t = day.toLocal()
                val c = Offset(size.width / 2f, size.height / 2f)
                val h = size.height
                val halfW = size.width / 2f - h * 0.035f
                val halfH = h / 2f - h * 0.035f
                fun edge(angle: Float): Float {
                    val dx = abs(sin(angle)).coerceAtLeast(1e-4f)
                    val dy = abs(cos(angle)).coerceAtLeast(1e-4f)
                    return min(halfW / dx, halfH / dy)
                }
                val minor = Path()
                val major = Path()
                for (i in 0 until 60) {
                    val a = i / 60f * 2 * PI.toFloat()
                    val isMajor = i % 5 == 0
                    val d = edge(a)
                    val from = c + polarOffset(a, d - if (isMajor) h * 0.13f else h * 0.05f)
                    val to = c + polarOffset(a, d)
                    (if (isMajor) major else minor).apply { moveTo(from.x, from.y); lineTo(to.x, to.y) }
                }
                val numeral = TextStyle(color = Color.White, fontFamily = WideFamily, fontWeight = FontWeight.Medium, fontSize = (h * 0.13f).toSp())
                val numerals = listOf(12, 3, 6, 9).map { n ->
                    val a = n / 12f * 2 * PI.toFloat()
                    val layout = measurer.measure(n.toString(), numeral)
                    val p = c + polarOffset(a, edge(a) - if (n % 6 == 0) h * 0.21f else h * 0.34f)
                    layout to Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f)
                }
                val date = measurer.measure(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = accent)) { append(TimeText.weekdayShort(t).uppercase()) }
                        append(" " + t.dayOfMonth)
                    },
                    TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = (h * 0.055f).toSp()),
                )
                val dateAt = Offset(c.x + halfW * 0.48f - date.size.width / 2f, c.y - date.size.height / 2f)
                onDrawBehind {
                    drawPath(minor, Color.White.copy(alpha = 0.32f), style = Stroke(h * 0.004f, cap = StrokeCap.Round))
                    drawPath(major, Color.White.copy(alpha = 0.75f), style = Stroke(h * 0.006f, cap = StrokeCap.Round))
                    numerals.forEach { (layout, at) -> drawText(layout, topLeft = at) }
                    drawText(date, topLeft = dateAt)
                }
            }
        )
        Canvas(Modifier.fillMaxSize()) {
            val now = frame.value
            val t = now.toLocal()
            val c = center
            val h = size.height
            val p = stagger(intro.value, 0, 1)
            val sec = t.second + (now % 1000) / 1000f
            val min = t.minute + sec / 60f
            val hour = t.hour % 12 + min / 60f
            drawHand(hour / 12f * p, h * 0.27f, h * 0.026f, Color.White, c)
            drawHand(min / 60f * p, h * 0.43f, h * 0.022f, Color.White, c)
            val sa = sec / 60f * 2 * PI.toFloat() * p
            // the minute mark under the second hand lights up and fades behind it
            val halfW = size.width / 2f - h * 0.035f
            val halfH = h / 2f - h * 0.035f
            for (back in 0..4) {
                val i = (t.second - back + 60) % 60
                val a = i / 60f * 2 * PI.toFloat()
                val dx = abs(sin(a)).coerceAtLeast(1e-4f)
                val dy = abs(cos(a)).coerceAtLeast(1e-4f)
                val d = min(halfW / dx, halfH / dy)
                val len = if (i % 5 == 0) h * 0.13f else h * 0.05f
                val fade = (1f - (back + (now % 1000) / 1000f) / 5f).coerceIn(0f, 1f)
                drawLine(
                    accent.copy(alpha = 0.9f * fade), c + polarOffset(a, d - len), c + polarOffset(a, d),
                    strokeWidth = h * 0.008f, cap = StrokeCap.Round,
                )
            }
            drawLine(accent, c - polarOffset(sa, h * 0.07f), c + polarOffset(sa, h * 0.46f), strokeWidth = h * 0.005f, cap = StrokeCap.Round)
            drawCircle(accent, h * 0.016f, c)
            drawCircle(Color.Black, h * 0.006f, c)
        }
    }
}

private fun polarOffset(angle: Float, radius: Float) = Offset(sin(angle) * radius, -cos(angle) * radius)

// ---------------------------------------------------------------- Gradient

/** Large, light numerals over a soft colour wash that drifts slowly from side to side. */
@Composable
fun GradientClock(wash: Palette.Wash, info: ClockInfo, modifier: Modifier = Modifier) {
    val now by rememberTicker(1000)
    val frame = rememberFrameTime()
    val t = now.toLocal()
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The drifting wash lives in its own layer (drawn first, underneath) so the digits
        // aren't re-recorded every frame.
        val intro = LocalIntro.current
        Spacer(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = 0.35f + 0.65f * stagger(intro.value, 0, 1) }
                .drawBehind { drawWash(wash, frame.value) }
        )
        val hPx = constraints.maxHeight.toFloat()
        val wPx = constraints.maxWidth.toFloat()
        val text = TimeText.time(t, info.h24)
        val sizePx = min(hPx * 0.40f, wPx / (text.length * 0.86f))
        RollingText(
            text,
            TextStyle(
                color = wash.digits, fontFamily = WideFamily, fontWeight = FontWeight.Normal,
                fontSize = pxToSp(sizePx), lineHeight = pxToSp(sizePx * 1.1f),
                shadow = androidx.compose.ui.graphics.Shadow(wash.dark.copy(alpha = 0.35f), Offset(0f, sizePx * 0.02f), sizePx * 0.08f),
            ),
            Modifier.graphicsLayer(),
        )
    }
}

private fun DrawScope.drawWash(w: Palette.Wash, frame: Long) {
    val shift = 0.5f + 0.5f * sin(frame / 9000f)
    drawRect(
        Brush.linearGradient(
            0f to w.dark,
            (0.25f + 0.35f * shift) to lerpColor(w.dark, w.light, 0.55f),
            1f to w.light,
            start = Offset(0f, size.height * 0.7f),
            end = Offset(size.width, size.height * 0.3f),
        )
    )
    // two soft lights wander slowly across the wash, like light through a curtain
    for (k in 0..1) {
        val t = frame / (14_000f + k * 5_000f)
        val c = Offset(
            size.width * (0.5f + 0.38f * sin(t + k * 2.1f)),
            size.height * (0.5f + 0.35f * cos(t * 1.3f + k)),
        )
        val r = size.height * (0.9f + 0.15f * sin(t * 2f))
        softGlow(c, r, 0f to w.light.copy(alpha = 0.35f), 1f to Color.Transparent)
    }
}

// ---------------------------------------------------------------- Tall

/**
 * Full-height condensed digits. When a digit changes, the new one slides in while the old one
 * drifts aside and fades, leaving a brief ghost.
 */
@Composable
fun TallClock(accent: Color, info: ClockInfo, modifier: Modifier = Modifier) {
    val now by rememberTicker(1000)
    val t = now.toLocal()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()
        val wPx = constraints.maxWidth.toFloat()
        val text = TimeText.time(t, info.h24)
        // Oswald figures are roughly 0.47 em wide and 0.8 em tall.
        val sizePx = min(hPx * 1.02f, wPx * 0.70f / (text.length * 0.47f))
        val style = TextStyle(
            color = accent, fontFamily = CondensedFamily, fontWeight = FontWeight.Medium,
            fontSize = pxToSp(sizePx), lineHeight = pxToSp(sizePx),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        )
        val frame = rememberFrameTime()
        val intro = LocalIntro.current
        Row(Modifier.align(Alignment.CenterStart).padding(start = 40.dp)) {
            text.forEachIndexed { i, ch ->
                AnimatedContent(
                    targetState = ch,
                    // digits slide in from the left one by one on arrival and lean gently
                    modifier = Modifier.graphicsLayer {
                        val p = stagger(intro.value, i, text.length, 0.1f)
                        alpha = p
                        translationX = (1f - p) * -size.width * 1.2f
                        val (fx, fy, fr) = digitFloat(frame.value, i)
                        translationX += fx * size.width * 0.05f
                        translationY = fy * size.height * 0.025f
                        rotationZ = fr * 1.5f
                    },
                    transitionSpec = {
                        (slideInHorizontally(spring(dampingRatio = 0.7f, stiffness = 220f)) { -it / 2 } + fadeIn(tween(500))) togetherWith
                            (slideOutHorizontally(tween(1400, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(1400))) using
                            SizeTransform(clip = false)
                    },
                    label = "tall$i",
                ) { c -> Text(c.toString(), style = style, softWrap = false) }
            }
        }
        val small = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = pxToSp(hPx * 0.07f))
        Column(Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 28.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = accent)) { append(TimeText.weekdayShort(t).uppercase()) }
                    append(" " + t.dayOfMonth)
                },
                style = small,
            )
            info.temperature?.let { Text(it, style = small) }
            info.alarm?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyphs.Alarm, null, tint = Palette.Gray, modifier = Modifier.size(with(LocalDensity.current) { (hPx * 0.05f).toDp() }))
                    Text(" $it", style = small.copy(color = Palette.Gray, fontSize = small.fontSize * 0.8f))
                }
            }
        }
    }
}
