package io.github.dockside.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dockside.R
import io.github.dockside.data.AppSettings
import io.github.dockside.data.BluetoothBatteries
import io.github.dockside.data.DeviceBattery
import io.github.dockside.data.DeviceKind
import io.github.dockside.standby.StandbyData
import io.github.dockside.ui.clocks.WorldCities
import io.github.dockside.ui.clocks.drawHand
import io.github.dockside.ui.common.CondensedFamily
import io.github.dockside.ui.common.Palette
import io.github.dockside.ui.common.RoundedFamily
import io.github.dockside.ui.common.TimeText
import io.github.dockside.ui.common.WideFamily
import io.github.dockside.ui.common.rememberFrameTime
import io.github.dockside.ui.common.rememberTicker
import io.github.dockside.ui.common.toLocal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun ray(angle: Float, radius: Float) = Offset(sin(angle) * radius, -cos(angle) * radius)

/** Distance from the centre to the edge of a square of half-side [half] along [angle]. */
private fun squareEdge(angle: Float, half: Float): Float {
    val dx = abs(sin(angle)).coerceAtLeast(1e-4f)
    val dy = abs(cos(angle)).coerceAtLeast(1e-4f)
    return min(half / dx, half / dy)
}

// ------------------------------------------------------------- square analog clock

/** An analog clock on a rounded-square dial whose minute marks follow the square's edges. */
@Composable
internal fun SquareClockWidget() {
    val frame = rememberFrameTime()
    val measurer = rememberTextMeasurer(cacheSize = 8)
    Box(Modifier.fillMaxSize().padding(8.dp)) {
        Spacer(
            Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                val s = size.minDimension
                val c = Offset(size.width / 2f, size.height / 2f)
                val half = s / 2f
                val inner = half * 0.9f
                val minor = Path()
                val major = Path()
                for (i in 0 until 60) {
                    val a = i / 60f * 2 * PI.toFloat()
                    val isMajor = i % 5 == 0
                    // pull the corner marks in a little so they follow the rounded corners
                    val d = min(squareEdge(a, inner), inner * 1.22f)
                    val from = c + ray(a, d - if (isMajor) s * 0.075f else s * 0.035f)
                    val to = c + ray(a, d)
                    (if (isMajor) major else minor).apply { moveTo(from.x, from.y); lineTo(to.x, to.y) }
                }
                val numeral = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = (s * 0.12f).toSp())
                val numerals = listOf(12, 3, 6, 9).map { n ->
                    val layout = measurer.measure(n.toString(), numeral)
                    val p = c + ray(n / 12f * 2 * PI.toFloat(), inner * 0.66f)
                    layout to Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f)
                }
                onDrawBehind {
                    drawRoundRect(Palette.Card, Offset(c.x - half, c.y - half), Size(s, s), CornerRadius(s * 0.2f))
                    drawPath(minor, Color.White.copy(alpha = 0.35f), style = Stroke(s * 0.008f, cap = StrokeCap.Round))
                    drawPath(major, Color.White, style = Stroke(s * 0.014f, cap = StrokeCap.Round))
                    numerals.forEach { (layout, at) -> drawText(layout, topLeft = at) }
                }
            }
        )
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            val c = center
            val inner = s / 2f * 0.9f
            val now = frame.value
            val t = now.toLocal()
            val sec = t.second + (now % 1000) / 1000f
            val min = t.minute + sec / 60f
            val hour = t.hour % 12 + min / 60f
            drawHand(hour / 12f, inner * 0.52f, s * 0.035f, Color.White, c)
            drawHand(min / 60f, inner * 0.82f, s * 0.028f, Color.White, c)
            val sa = sec / 60f * 2 * PI.toFloat()
            drawLine(Palette.Orange, c - ray(sa, inner * 0.14f), c + ray(sa, inner * 0.9f), strokeWidth = s * 0.008f, cap = StrokeCap.Round)
            drawCircle(Palette.Orange, s * 0.022f, c)
            drawCircle(Palette.Card, s * 0.009f, c)
        }
    }
}

// ------------------------------------------------------------- city time

/**
 * Digital time for the first world-clock city. The frame of minute marks fills up with the
 * seconds of the current minute.
 */
@Composable
internal fun CityTimeWidget(settings: AppSettings, data: StandbyData) {
    val city = settings.worldCities.firstNotNullOfOrNull { WorldCities.byZone(it) }
    if (city == null) {
        Placeholder(R.string.no_city); return
    }
    val now by rememberTicker(1000)
    val zone = ZoneId.of(city.zone)
    val local = now.toLocal(zone)
    val offset = (zone.rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds -
        ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds) / 3600
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        val m = metrics()
        Canvas(Modifier.size(m.dp(1f))) {
            val s = size.minDimension
            val half = s / 2f * 0.94f
            for (i in 0 until 60) {
                val a = i / 60f * 2 * PI.toFloat()
                val d = min(squareEdge(a, half), half * 1.2f)
                val lit = i <= local.second
                drawLine(
                    if (lit) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.22f),
                    center + ray(a, d - s * 0.05f), center + ray(a, d),
                    strokeWidth = s * 0.012f, cap = StrokeCap.Round,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                cityCode(city.name), style = style(m.unit(0.08f), Palette.Gray, FontWeight.ExtraBold),
            )
            Text(
                TimeText.time(local, data.h24),
                style = TextStyle(
                    color = Color.White, fontFamily = CondensedFamily, fontWeight = FontWeight.Medium,
                    fontSize = m.unit(0.36f), lineHeight = m.unit(0.4f),
                ),
                maxLines = 1,
            )
            Text(stringResource(R.string.hours_offset, offset), style = style(m.unit(0.07f), Palette.Gray))
        }
    }
}

/** "Los Angeles" → "LA", "Tokyo" → "TOK". */
private fun cityCode(name: String): String {
    val words = name.split(' ', '-').filter { it.isNotBlank() }
    return (if (words.size > 1) words.joinToString("") { it.take(1) } else name.take(3)).uppercase()
}

// ------------------------------------------------------------- batteries of nearby devices

/** The phone plus connected Bluetooth accessories, each as a ring around its own pictogram. */
@Composable
internal fun DevicesWidget(data: StandbyData) {
    val context = LocalContext.current
    val accessories by produceState(emptyList<DeviceBattery>()) {
        value = BluetoothBatteries.read(context)
        // refresh right away on connect / disconnect / battery report, and every half minute
        launch { BluetoothBatteries.changes.collect { delay(800); value = BluetoothBatteries.read(context) } }
        while (true) {
            delay(30_000)
            value = BluetoothBatteries.read(context)
        }
    }
    val phone = Ring(data.battery.level, data.battery.charging, null)
    val rings = (listOf(phone) + accessories.map { Ring(it.level, false, it.kind) }).take(4)
    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val m = metrics()
        val cell = m.dp(0.44f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            for (row in 0 until 2) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (col in 0 until 2) {
                        BatteryRing(rings.getOrNull(row * 2 + col), cell)
                    }
                }
            }
        }
    }
}

private data class Ring(val level: Int, val charging: Boolean, val kind: DeviceKind?)

@Composable
private fun BatteryRing(ring: Ring?, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.1f
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(Palette.CardHigh, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (ring != null) {
                if (ring.level >= 0) {
                    val color = if (ring.level <= 20 && !ring.charging) Palette.Red else Palette.Green
                    drawArc(color, -90f, 360f * ring.level / 100f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                drawDevice(ring.kind, this.size.minDimension * 0.34f)
                if (ring.charging) drawBolt(Offset(center.x, stroke * 0.5f), stroke * 1.6f)
            }
        }
    }
}

/** Pictograms drawn for this project: phone (null), headphones, earbuds, speaker, watch, other. */
private fun DrawScope.drawDevice(kind: DeviceKind?, s: Float) {
    val c = center
    val white = Color.White
    val hole = Color.Black
    fun rr(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color = white) =
        drawRoundRect(color, c + Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(r * s))
    when (kind) {
        null -> {
            rr(-0.3f, -0.5f, 0.6f, 1f, 0.14f)
            rr(-0.245f, -0.445f, 0.49f, 0.89f, 0.1f, hole)
            rr(-0.08f, -0.42f, 0.16f, 0.05f, 0.025f) // earpiece island
        }
        DeviceKind.HEADPHONES -> {
            // headband
            drawArc(white, 185f, 170f, false, c + Offset(-0.42f * s, -0.46f * s), Size(0.84f * s, 0.84f * s), style = Stroke(0.09f * s, cap = StrokeCap.Round))
            // cushions (dark) and cups (white)
            rr(-0.5f, -0.04f, 0.26f, 0.48f, 0.12f)
            rr(0.24f, -0.04f, 0.26f, 0.48f, 0.12f)
            rr(-0.4f, 0.02f, 0.07f, 0.36f, 0.035f, hole)
            rr(0.33f, 0.02f, 0.07f, 0.36f, 0.035f, hole)
        }
        DeviceKind.EARBUDS -> {
            for (side in listOf(-1f, 1f)) {
                val x = side * 0.2f
                drawCircle(white, 0.17f * s, c + Offset(x * s, -0.22f * s))
                drawCircle(hole, 0.06f * s, c + Offset((x - side * 0.05f) * s, -0.24f * s))
                rr(x - 0.055f + side * 0.03f, -0.12f, 0.11f, 0.5f, 0.055f)
            }
        }
        DeviceKind.SPEAKER -> {
            rr(-0.32f, -0.5f, 0.64f, 1f, 0.14f)
            drawCircle(hole, 0.2f * s, c + Offset(0f, 0.16f * s))
            drawCircle(white, 0.07f * s, c + Offset(0f, 0.16f * s))
            drawCircle(hole, 0.08f * s, c + Offset(0f, -0.28f * s))
        }
        DeviceKind.WATCH -> {
            rr(-0.19f, -0.56f, 0.38f, 1.12f, 0.1f, white.copy(alpha = 0.55f))
            rr(-0.31f, -0.36f, 0.62f, 0.72f, 0.18f)
            rr(-0.25f, -0.3f, 0.5f, 0.6f, 0.13f, hole)
            rr(0.31f, -0.08f, 0.05f, 0.16f, 0.025f) // crown
        }
        DeviceKind.OTHER -> {
            drawCircle(white, 0.36f * s, c, style = Stroke(0.09f * s))
            drawCircle(white, 0.12f * s, c)
        }
    }
}

private fun DrawScope.drawBolt(c: Offset, h: Float) {
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x + h * 0.1f, c.y - h * 0.5f); lineTo(c.x - h * 0.25f, c.y + h * 0.06f); lineTo(c.x, c.y + h * 0.06f)
        lineTo(c.x - h * 0.1f, c.y + h * 0.5f); lineTo(c.x + h * 0.25f, c.y - h * 0.06f); lineTo(c.x, c.y - h * 0.06f); close()
    }
    drawCircle(Color.Black, h * 0.42f, c)
    drawPath(p, Color.White)
}

/** Every pictogram on its ring, for the screenshot tests. */
@Composable
internal fun DeviceIconsPreview() {
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        listOf(Ring(76, true, null), Ring(90, false, DeviceKind.HEADPHONES), Ring(60, false, DeviceKind.EARBUDS),
            Ring(35, false, DeviceKind.SPEAKER), Ring(15, false, DeviceKind.WATCH), Ring(-1, false, DeviceKind.OTHER))
            .forEach { BatteryRing(it, 150.dp) }
    }
}
