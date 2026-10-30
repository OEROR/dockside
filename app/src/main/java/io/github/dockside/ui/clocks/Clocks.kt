package io.github.dockside.ui.clocks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.dockside.ui.common.digitFloat
import io.github.dockside.ui.common.LocalMotion
import io.github.dockside.ui.common.springOr
import androidx.compose.ui.graphics.drawscope.clipRect
import io.github.dockside.ui.common.softGlow
import io.github.dockside.ui.common.stagger
import io.github.dockside.ui.common.LocalIntro
import kotlin.math.abs
import kotlin.math.tan
import kotlin.math.atan
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dockside.data.ClockStyle
import io.github.dockside.ui.common.BubbleFamily
import io.github.dockside.ui.common.Glyphs
import io.github.dockside.ui.common.Palette
import io.github.dockside.ui.common.RoundedFamily
import io.github.dockside.ui.common.TimeText
import io.github.dockside.ui.common.rememberFrameTime
import io.github.dockside.ui.common.rememberTicker
import io.github.dockside.ui.common.toLocal
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Extra information some clock faces show around the time. */
data class ClockInfo(
    val h24: Boolean,
    val temperature: String? = null,
    val alarm: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val placeName: String? = null,
    val worldCities: List<City> = emptyList(),
)

@Composable
fun FullScreenClock(style: ClockStyle, colorIndex: Int, info: ClockInfo, modifier: Modifier = Modifier) {
    when (style) {
        ClockStyle.DIGITAL -> DigitalClock(Palette.accents[colorIndex % Palette.accents.size], info, modifier)
        ClockStyle.ANALOG -> AnalogClock(Palette.accents[colorIndex % Palette.accents.size], info, modifier, showInfo = true)
        ClockStyle.FLOAT -> FloatClock(Palette.floatPairs[colorIndex % Palette.floatPairs.size], info, modifier)
        ClockStyle.SOLAR -> SolarClock(Palette.solar[colorIndex % Palette.solar.size], info, modifier)
        ClockStyle.WORLD -> WorldClock(info, modifier)
        ClockStyle.LINES -> LinesClock(Palette.accents[colorIndex % Palette.accents.size], info, modifier)
        ClockStyle.GRADIENT -> GradientClock(Palette.gradients[colorIndex % Palette.gradients.size], info, modifier)
        ClockStyle.TALL -> TallClock(Palette.accents[colorIndex % Palette.accents.size], info, modifier)
    }
}

/** Digits that roll vertically when they change. */
@Composable
fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier, float: Boolean = true) {
    // Everything here moves through layer properties read every frame (cheap): digits drift
    // gently, the colon breathes like a heartbeat, and on arrival the digits rise in one by one.
    val frame = rememberFrameTime()
    val intro = LocalIntro.current
    val springs = LocalMotion.current.springs
    val range = LocalMotion.current.floatRange
    Row(modifier) {
        text.forEachIndexed { i, ch ->
            val layer = Modifier.graphicsLayer {
                val p = stagger(intro.value, i, text.length)
                alpha = p
                translationY = (1f - p) * size.height * 0.45f
                if (float) {
                    // each digit hovers around its own place on its own rhythm; the range is
                    // kept small so digits never reach the date or temperature lines
                    val (fx, fy, fr) = digitFloat(frame.value, i)
                    translationX = fx * size.width * 0.06f * range
                    translationY += fy * size.height * 0.035f * range
                    rotationZ = fr * 0.6f * range
                }
                if (ch == ':') {
                    val phase = (frame.value % 1000) / 1000f
                    alpha *= 0.7f + 0.3f * cos(phase * PI.toFloat()).let { it * it }
                }
            }
            if (ch == ':') {
                Text(":", style = style, softWrap = false, modifier = layer)
                return@forEachIndexed
            }
            AnimatedContent(
                targetState = ch,
                modifier = layer,
                transitionSpec = {
                    // the new digit springs up from below, the old one flies off the top
                    (slideInVertically(springOr(springs, spring(dampingRatio = 0.62f, stiffness = 260f))) { it * 2 / 3 } +
                        scaleIn(springOr(springs, spring(dampingRatio = 0.55f, stiffness = 260f)), initialScale = 0.55f) +
                        fadeIn(tween(250))) togetherWith
                        (slideOutVertically(tween(380, easing = FastOutSlowInEasing)) { -it * 2 / 3 } +
                            scaleOut(tween(380), targetScale = 1.25f) + fadeOut(tween(300))) using
                        SizeTransform(clip = false)
                },
                label = "digit$i",
            ) { c -> Text(c.toString(), style = style, softWrap = false) }
        }
    }
}

@Composable
internal fun pxToSp(px: Float): TextUnit = with(LocalDensity.current) { px.toSp() }

// ---------------------------------------------------------------- Digital

@Composable
fun DigitalClock(color: Color, info: ClockInfo, modifier: Modifier = Modifier) {
    val now by rememberTicker(1000)
    val t = now.toLocal()
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val hPx = constraints.maxHeight.toFloat()
        val wPx = constraints.maxWidth.toFloat()
        val timeText = TimeText.time(t, info.h24)
        // Nunito digits are ~0.6em wide; fit both the height and the width.
        val sizePx = min(hPx * 0.70f, wPx / (timeText.length * 0.60f + 0.2f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                val small = TextStyle(
                    color = color, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold,
                    fontSize = pxToSp(hPx * 0.075f),
                )
                Text(TimeText.shortDateLine(t), style = small)
                info.temperature?.let { Text(it, style = small) }
                info.alarm?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyphs.Alarm, null, tint = color, modifier = Modifier.size(with(LocalDensity.current) { (hPx * 0.07f).toDp() }))
                        Text(" $it", style = small)
                    }
                }
            }
            RollingText(
                timeText,
                TextStyle(
                    color = color, fontFamily = RoundedFamily, fontWeight = FontWeight.Black,
                    fontSize = pxToSp(sizePx), lineHeight = pxToSp(sizePx * 1.02f),
                    letterSpacing = (-0.5).sp,
                ),
                Modifier.offset(y = with(LocalDensity.current) { (-sizePx * 0.06f).toDp() }),
            )
        }
    }
}

// ---------------------------------------------------------------- Analog

/**
 * How an analog dial is drawn: [CLASSIC] for the full-screen face, [BOLD] for the widget (big
 * numerals and a ring of marks straight on black), [MINI] for the small world-clock dials
 * (numerals only, with the city's code inside).
 */
enum class Dial { CLASSIC, BOLD, MINI }

@Composable
fun AnalogClock(
    accent: Color,
    info: ClockInfo,
    modifier: Modifier = Modifier,
    showInfo: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    faceColor: Color = Color(0xFF151517),
    dial: Dial = Dial.CLASSIC,
    label: String? = null,
) {
    val frame = rememberFrameTime()
    val intro = LocalIntro.current
    val day by rememberTicker(60_000)
    val measurer = rememberTextMeasurer(cacheSize = 24)
    // dark hands on a light (daytime) face
    val ink = if (faceColor.luminance() > 0.5f) Color.Black else Color.White
    Box(modifier.fillMaxSize()) {
        // The dial (face, marks, numerals, date) is recorded once into its own layer; only the
        // hands below are redrawn every frame.
        Spacer(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val p = stagger(intro.value, 0, 1)
                    alpha = p; scaleX = 0.92f + 0.08f * p; scaleY = scaleX
                }
                .drawWithCache {
                val t = day.toLocal(zone)
                val r = size.minDimension / 2f * 0.96f
                val c = Offset(size.width / 2f, size.height / 2f)
                val ticks = Path()
                val majors = Path()
                if (dial != Dial.MINI) for (i in 0 until 60) {
                    val a = i / 60f * 2 * PI.toFloat()
                    val major = i % 5 == 0
                    val inner = when {
                        dial == Dial.BOLD -> if (major) r * 0.86f else r * 0.89f
                        else -> r * 0.91f
                    }
                    val p = if (major) majors else ticks
                    p.moveTo(c.x + polar(a, inner).x, c.y + polar(a, inner).y)
                    p.lineTo(c.x + polar(a, r * 0.97f).x, c.y + polar(a, r * 0.97f).y)
                }
                val numeralStyle = TextStyle(
                    color = ink, fontFamily = RoundedFamily,
                    fontWeight = if (dial == Dial.CLASSIC) FontWeight.SemiBold else FontWeight.ExtraBold,
                    fontSize = (r * if (dial == Dial.CLASSIC) 0.17f else 0.22f).toSp(),
                )
                val numeralRadius = when (dial) {
                    Dial.CLASSIC -> 0.74f
                    Dial.BOLD -> 0.68f
                    Dial.MINI -> 0.78f
                }
                val numeralLayouts = (1..12).map { n ->
                    val layout = measurer.measure(n.toString(), numeralStyle)
                    val p = c + polar(n / 12f * 2 * PI.toFloat(), r * numeralRadius)
                    layout to Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f)
                }
                val labelLayout = label?.let {
                    val l = measurer.measure(
                        it,
                        TextStyle(color = Color(0xFF9A9AA0), fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = (r * 0.17f).toSp()),
                    )
                    l to Offset(c.x - l.size.width / 2f, c.y - r * 0.4f - l.size.height / 2f)
                }
                val info = if (showInfo) {
                    val small = (r * 0.09f).toSp()
                    val dayL = measurer.measure(
                        TimeText.weekdayShort(t).uppercase(),
                        TextStyle(color = accent, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = small),
                    )
                    val dateL = measurer.measure(
                        t.dayOfMonth.toString(),
                        TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = small),
                    )
                    listOf(
                        dayL to Offset(c.x - r * 0.36f - dayL.size.width / 2f, c.y - dayL.size.height / 2f),
                        dateL to Offset(c.x + r * 0.36f - dateL.size.width / 2f, c.y - dateL.size.height / 2f),
                    )
                } else emptyList()
                onDrawBehind {
                    drawCircle(faceColor, r, c)
                    if (dial == Dial.BOLD) {
                        drawPath(ticks, Color.White.copy(alpha = 0.4f), style = Stroke(r * 0.018f, cap = StrokeCap.Round))
                        drawPath(majors, Color.White, style = Stroke(r * 0.026f, cap = StrokeCap.Round))
                    } else {
                        drawPath(ticks, Color.White.copy(alpha = 0.35f), style = Stroke(r * 0.008f, cap = StrokeCap.Round))
                        drawPath(majors, Color.White, style = Stroke(r * 0.018f, cap = StrokeCap.Round))
                    }
                    (numeralLayouts + listOfNotNull(labelLayout) + info).forEach { (layout, at) -> drawText(layout, topLeft = at) }
                }
            }
        )
        Canvas(Modifier.fillMaxSize()) {
            val now = frame.value
            val t = now.toLocal(zone)
            val r = size.minDimension / 2f * 0.96f
            val c = center
            // on arrival the hands sweep round from 12 to the time
            val p = stagger(intro.value, 0, 1)
            val sec = t.second + (now % 1000) / 1000f
            val min = t.minute + sec / 60f
            val hour = t.hour % 12 + min / 60f
            val bold = dial != Dial.CLASSIC
            drawHand(hour / 12f * p, r * 0.50f, r * if (bold) 0.075f else 0.055f, ink, c)
            drawHand(min / 60f * p, r * 0.82f, r * if (bold) 0.06f else 0.045f, ink, c)
            drawCircle(ink, r * if (bold) 0.07f else 0.05f, c)
            // sweeping second hand
            val sa = sec / 60f * 2 * PI.toFloat() * p
            drawLine(accent, c - polar(sa, r * 0.16f), c + polar(sa, r * 0.90f), strokeWidth = r * if (bold) 0.02f else 0.014f, cap = StrokeCap.Round)
            drawCircle(accent, r * if (bold) 0.045f else 0.032f, c)
            drawCircle(if (faceColor.alpha < 0.5f) Color.Black else faceColor, r * if (bold) 0.018f else 0.012f, c)
        }
    }
}

private fun polar(angle: Float, radius: Float) = Offset(sin(angle) * radius, -cos(angle) * radius)

internal fun DrawScope.drawHand(fraction: Float, length: Float, width: Float, color: Color, c: Offset) {
    val a = fraction * 2 * PI.toFloat()
    // thin neck, then the wide blade, like a classic wall clock
    drawLine(color, c, c + polar(a, length * 0.18f), strokeWidth = width * 0.4f, cap = StrokeCap.Round)
    drawLine(color, c + polar(a, length * 0.18f), c + polar(a, length), strokeWidth = width, cap = StrokeCap.Round)
}

// ---------------------------------------------------------------- Float

@Composable
fun FloatClock(colors: Pair<Color, Color>, info: ClockInfo, modifier: Modifier = Modifier) {
    val now by rememberTicker(1000)
    // Read only inside the offset lambdas, so the gentle bobbing re-places the digits each
    // frame without recomposing them.
    val frame = rememberFrameTime()
    val t = now.toLocal()
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val hPx = constraints.maxHeight.toFloat()
        val wPx = constraints.maxWidth.toFloat()
        val digits = TimeText.hours(t, info.h24) + TimeText.minutes(t)
        // Fredoka figures are ~0.58 em wide; neighbours overlap by about a sixth of that.
        val sizePx = min(hPx * 0.9f, wPx * 0.94f / (digits.length * 0.49f + 0.3f))
        val overlap = with(LocalDensity.current) { (sizePx * 0.09f).toDp() }
        val range = LocalMotion.current.floatRange
        fun bob(i: Int) = digitFloat(frame.value, i + 2).second * sizePx * 0.04f * range
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(-overlap)) {
            digits.forEachIndexed { i, ch ->
                if (i == digits.length - 2) Colon(sizePx, Modifier.offset { IntOffset(0, bob(9).roundToInt()) })
                BubbleDigit(
                    ch, if (i % 2 == 0) colors.first else colors.second, sizePx,
                    Modifier.offset { IntOffset(0, bob(i).roundToInt()) },
                    label = "bubble$i",
                )
            }
        }
    }
}

/** Two soft, pearl-like dots between hours and minutes. */
@Composable
private fun Colon(sizePx: Float, modifier: Modifier) {
    val d = with(LocalDensity.current) { (sizePx * 0.17f).toDp() }
    Column(
        modifier.padding(horizontal = d * 0.75f),
        verticalArrangement = Arrangement.spacedBy(d * 0.9f),
    ) {
        repeat(2) {
            Box(
                Modifier.size(d).clip(CircleShape).background(
                    Brush.radialGradient(
                        listOf(Color(0xFFF5F7FA), Color(0xFFC9CDD3), Color(0xFF9EA3AA)),
                        center = Offset(with(LocalDensity.current) { d.toPx() } * 0.35f, with(LocalDensity.current) { d.toPx() } * 0.3f),
                    )
                )
            )
        }
    }
}

@Composable
private fun BubbleDigit(ch: Char, color: Color, sizePx: Float, modifier: Modifier, label: String) {
    val style = TextStyle(
        color = color, fontFamily = BubbleFamily, fontWeight = FontWeight.SemiBold,
        fontSize = pxToSp(sizePx), lineHeight = pxToSp(sizePx),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    val frame = rememberFrameTime()
    val intro = LocalIntro.current
    val seed = label.last().digitToIntOrNull() ?: 0
    val range = LocalMotion.current.floatRange
    // each digit swells and sways on its own rhythm, so the numbers feel afloat; on arrival
    // they pop in one after another
    Box(
        modifier.graphicsLayer {
            val p = stagger(intro.value, seed, 4, 0.18f)
            alpha = 0.93f * p
            val (fx, _, fr) = digitFloat(frame.value, seed)
            val breath = (1f + 0.03f * sin(frame.value / (2300f + seed * 310f) + seed)) * (0.3f + 0.7f * p)
            scaleX = breath; scaleY = breath
            translationX = fx * size.width * 0.05f * range
            rotationZ = fr * 3.5f * range + (1f - p) * -25f
        },
        contentAlignment = Alignment.Center,
    ) {
        // Bouncy pop when a digit changes.
        val springs = LocalMotion.current.springs
        AnimatedContent(
            targetState = ch,
            transitionSpec = {
                (scaleIn(springOr(springs, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow)), initialScale = 0.2f) + fadeIn()) togetherWith
                    (scaleOut(tween(220), targetScale = 1.4f) + fadeOut(tween(220))) using SizeTransform(clip = false)
            },
            label = label,
        ) { c -> Text(c.toString(), style = style, softWrap = false, maxLines = 1) }
    }
}

// ---------------------------------------------------------------- Solar

@Composable
fun SolarClock(gradient: List<Color>, info: ClockInfo, modifier: Modifier = Modifier) {
    val now by rememberTicker(1000)
    val frame = rememberFrameTime()
    val t = now.toLocal()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()
        val intro = LocalIntro.current
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                // the sun rises into place on arrival
                val p = stagger(intro.value, 0, 1)
                alpha = p; translationY = (1f - p) * size.height * 0.3f
            }
        ) { drawSolarGlow(gradient, now.toLocal(), frame.value) }
        Column(Modifier.align(Alignment.CenterStart).padding(start = 48.dp)) {
            Text(
                TimeText.dateLine(t).replaceFirstChar { it.titlecase() },
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.85f), fontFamily = RoundedFamily,
                    fontWeight = FontWeight.SemiBold, fontSize = pxToSp(hPx * 0.07f),
                ),
                modifier = Modifier.padding(start = 6.dp),
            )
            RollingText(
                TimeText.time(t, info.h24),
                TextStyle(
                    color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.Light,
                    fontSize = pxToSp(hPx * 0.46f), lineHeight = pxToSp(hPx * 0.5f),
                ),
            )
        }
    }
}

/** A sun-like flare whose height follows the time of day. */
private fun DrawScope.drawSolarGlow(g: List<Color>, t: LocalDateTime, frame: Long) {
    val minutes = t.hour * 60 + t.minute
    val dayPhase = ((minutes - 6 * 60) / (12f * 60)).coerceIn(-0.25f, 1.25f)
    val elevation = sin(dayPhase.coerceIn(0f, 1f) * PI.toFloat())
    val night = dayPhase < 0f || dayPhase > 1f
    val breathe = 1f + 0.07f * sin(frame / 2000f)
    val x = size.width * (0.55f + 0.35f * dayPhase.coerceIn(0f, 1f))
    val y = size.height * (1.15f - 0.75f * elevation)
    val r = size.height * (if (night) 0.75f else 0.95f) * breathe
    val alpha = if (night) 0.55f else 1f
    drawRect(Color.Black)
    softGlow(
        Offset(x, y), r,
        0f to g[0].copy(alpha = alpha),
        0.18f to g[1].copy(alpha = alpha),
        0.55f to g[2].copy(alpha = alpha * 0.7f),
        1f to Color.Transparent,
    )
    // soft glints drift round the sun; every light here fades to nothing, so no edges show
    for (k in 0 until 3) {
        val ang = frame / (9000f + k * 2300f) + k * 2.1f
        val dist = r * (0.55f + 0.12f * sin(frame / 3100f + k))
        val gc = Offset(x + cos(ang) * dist, y + sin(ang) * dist * 0.6f)
        val gr = r * (0.42f + 0.06f * sin(frame / 1700f + k * 1.3f))
        softGlow(gc, gr, 0f to g[1].copy(alpha = 0.22f * alpha), 0.5f to g[2].copy(alpha = 0.08f * alpha), 1f to Color.Transparent)
    }
    // faint secondary flare for depth
    softGlow(Offset(x - r * 0.9f, y - r * 0.2f), r * 0.7f, 0f to g[1].copy(alpha = 0.25f * alpha), 1f to Color.Transparent)
}

// ---------------------------------------------------------------- World

@Composable
fun WorldClock(info: ClockInfo, modifier: Modifier = Modifier) {
    val now by rememberTicker(1000)
    val minuteTick by rememberTicker(60_000)
    val frame = rememberFrameTime()
    val t = now.toLocal()
    val home = remember(info.lat, info.lon, info.placeName) {
        if (info.lat != null && info.lon != null) City("", info.placeName ?: "", info.lat, info.lon)
        else WorldCities.guessHome()
    }
    val accent = Palette.Orange
    BoxWithConstraints(modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()
        // Map, day/night line and city dots change by the minute: recorded once a minute into
        // their own layer. Only the home marker's pulse is drawn every frame.
        val intro = LocalIntro.current
        Spacer(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val p = stagger(intro.value, 0, 1)
                    alpha = p; scaleX = 1.06f - 0.06f * p; scaleY = scaleX
                }
                .drawWithCache {
                val minute = minuteTick
                val geo = WorldGeometry(size.width, size.height)
                val sun = subsolarPoint(minute)
                val dots = Array(5) { Path() }
                // the mask is sampled in 2×2 blocks: fewer, larger dots read better from a distance
                val step = 2
                for (row in 0 until WorldMapData.ROWS step step) {
                    val lat = WorldMapData.LAT_TOP - (row + step / 2.0) * (WorldMapData.LAT_TOP - WorldMapData.LAT_BOTTOM) / WorldMapData.ROWS
                    for (col in 0 until WorldMapData.COLS step step) {
                        var land = 0
                        for (dr in 0 until step) for (dc in 0 until step) if (WorldMapData.isLand(col + dc, row + dr)) land++
                        if (land * 2 < step * step) continue
                        val lon = -180 + (col + step / 2.0) * 360.0 / WorldMapData.COLS
                        // five brightness steps are indistinguishable from a continuous ramp here
                        val band = (sunlight(lat, lon, sun) * 4f).roundToInt()
                        dots[band].addOval(
                            androidx.compose.ui.geometry.Rect(
                                Offset(geo.left + (col + step / 2f) * geo.cell, geo.top + (row + step / 2f) * geo.cell), geo.cell * step * 0.34f,
                            )
                        )
                    }
                }
                // The day/night boundary: for each longitude, the latitude where the sun sets.
                val terminator = Path()
                val decl = sun.first.coerceIn(-89.0, 89.0).let { if (abs(it) < 0.5) 0.5 else it } * PI / 180
                // the true curve, even where it leaves the map; it is clipped at the map's edge
                // instead of being squashed along it
                val steps = 240
                for (i in 0..steps) {
                    val lon = -180.0 + 360.0 * i / steps
                    val lat = atan(-cos((lon - sun.second) * PI / 180) / tan(decl)) * 180 / PI
                    val p = geo.project(lat, lon)
                    if (i == 0) terminator.moveTo(p.x, p.y) else terminator.lineTo(p.x, p.y)
                }
                val mapRect = androidx.compose.ui.geometry.Rect(
                    geo.left, geo.top, geo.left + geo.cell * WorldMapData.COLS, geo.top + geo.cell * WorldMapData.ROWS,
                )
                val cities = info.worldCities.map { geo.project(it.lat, it.lon) }
                onDrawBehind {
                    dots.forEachIndexed { band, path ->
                        drawPath(path, lerpColor(Color(0xFF3A3A3E), Color(0xFFE6E6E6), band / 4f))
                    }
                    // The line fades out towards every edge of the map, so it never runs into the
                    // screen edge or ends abruptly: draw it in a layer, then mask with two fades.
                    val fade = androidx.compose.ui.graphics.Paint()
                    drawContext.canvas.saveLayer(mapRect, fade)
                    clipRect(mapRect.left, mapRect.top, mapRect.right, mapRect.bottom) {
                        drawPath(
                            terminator, Color.White.copy(alpha = 0.7f),
                            style = Stroke(geo.cell * 0.32f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                        )
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent,
                                startY = mapRect.top, endY = mapRect.bottom,
                            ),
                            topLeft = mapRect.topLeft, size = mapRect.size, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.Transparent, 0.1f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent,
                                startX = mapRect.left, endX = mapRect.right,
                            ),
                            topLeft = mapRect.topLeft, size = mapRect.size, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    }
                    drawContext.canvas.restore()
                    cities.forEach { p ->
                        drawCircle(Color.Black, geo.cell * 1.05f, p)
                        drawCircle(accent, geo.cell * 0.85f, p)
                    }
                }
            }
        )
        Canvas(Modifier.fillMaxSize()) {
            val geo = WorldGeometry(size.width, size.height)
            val p = geo.project(home.lat, home.lon)
            val pulse = (frame.value % 2400) / 2400f
            drawCircle(accent.copy(alpha = 0.45f * (1 - pulse)), geo.cell * (1f + 2.4f * pulse), p)
            drawCircle(Color.Black, geo.cell * 1.15f, p)
            drawCircle(accent, geo.cell * 0.95f, p)
        }
        // where you are: a small time with the location arrow, top left
        Row(Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.Locate, null, tint = Color.White, modifier = Modifier.size(with(LocalDensity.current) { (hPx * 0.06f).toDp() }))
            Text(
                " " + TimeText.time(t, info.h24),
                style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = pxToSp(hPx * 0.065f)),
            )
        }
        // and big, bottom left, under the place name
        Column(Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 12.dp)) {
            Text(
                home.name.ifBlank { TimeText.shortDateLine(t) },
                style = TextStyle(color = accent, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = pxToSp(hPx * 0.065f)),
            )
            Text(
                TimeText.time(t, info.h24),
                style = TextStyle(
                    color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.Medium,
                    fontSize = pxToSp(hPx * 0.25f), lineHeight = pxToSp(hPx * 0.26f),
                ),
            )
        }
    }
}

/** Where the dotted map sits: most of the screen, nudged right of the big time. */
private class WorldGeometry(width: Float, height: Float) {
    val cell = minOf(height * 0.84f / WorldMapData.ROWS, width * 0.86f / WorldMapData.COLS)
    private val mapW = cell * WorldMapData.COLS
    private val mapH = cell * WorldMapData.ROWS
    val left = (width - mapW) * 0.8f
    val top = (height - mapH) * 0.5f

    fun project(lat: Double, lon: Double) = Offset(
        left + ((lon + 180) / 360 * mapW).toFloat(),
        top + ((WorldMapData.LAT_TOP - lat) / (WorldMapData.LAT_TOP - WorldMapData.LAT_BOTTOM) * mapH).toFloat(),
    )
}

/** Approximate subsolar latitude/longitude (degrees) for an instant. */
internal fun subsolarPoint(epochMs: Long): Pair<Double, Double> {
    val days = epochMs / 86_400_000.0
    val dayOfYear = (days % 365.2422)
    val decl = -23.44 * cos(2 * PI / 365.2422 * (dayOfYear + 10))
    val utcHours = (epochMs % 86_400_000L) / 3_600_000.0
    val lon = -15.0 * (utcHours - 12.0)
    return decl to lon
}

/** 0 at night, 1 in full daylight, with a soft twilight band. */
internal fun sunlight(lat: Double, lon: Double, sun: Pair<Double, Double>): Float {
    val toRad = PI / 180
    val cosZ = sin(lat * toRad) * sin(sun.first * toRad) +
        cos(lat * toRad) * cos(sun.first * toRad) * cos((lon - sun.second) * toRad)
    return ((cosZ + 0.1) / 0.2).toFloat().coerceIn(0f, 1f)
}

internal fun lerpColor(a: Color, b: Color, f: Float) = Color(
    a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, 1f
)
