package io.github.dockside.ui.common

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.dockside.R
import io.github.dockside.data.ClockStyle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Fonts are static instances cut from the variable originals: some devices drop the weight
// axis for large glyphs and would draw the big clock digits in the thinnest weight.

/** Tall condensed digits for the Tall clock (OFL "Oswald"). */
val CondensedFamily = FontFamily(Font(R.font.oswald_400, FontWeight.Normal), Font(R.font.oswald_500, FontWeight.Medium))

/** Wide geometric numerals for the Lines and Gradient clocks (OFL "Unbounded", Latin only). */
val WideFamily = FontFamily(Font(R.font.unbounded_400, FontWeight.Normal), Font(R.font.unbounded_500, FontWeight.Medium))

/** Rounded sans used for most Dockside typography (OFL "Nunito"). */
val RoundedFamily = FontFamily(
    Font(R.font.nunito_300, FontWeight.Light),
    Font(R.font.nunito_400, FontWeight.Normal),
    Font(R.font.nunito_500, FontWeight.Medium),
    Font(R.font.nunito_600, FontWeight.SemiBold),
    Font(R.font.nunito_700, FontWeight.Bold),
    Font(R.font.nunito_800, FontWeight.ExtraBold),
    Font(R.font.nunito_900, FontWeight.Black),
)

/** Bubbly display face used by the Float clock (OFL "Fredoka"). */
val BubbleFamily = FontFamily(Font(R.font.fredoka_600, FontWeight.SemiBold), Font(R.font.fredoka_700, FontWeight.Bold))

object Palette {
    val Red = Color(0xFFFF4539)
    val Orange = Color(0xFFFF9F0A)
    val Yellow = Color(0xFFFFD60A)
    val Green = Color(0xFF32D158)
    val Mint = Color(0xFF66E3D9)
    val Teal = Color(0xFF41C6E0)
    val Cyan = Color(0xFF64D2FF)
    val Blue = Color(0xFF0A84FF)
    val Indigo = Color(0xFF5E5CE6)
    val Purple = Color(0xFFBF5AF2)
    val Pink = Color(0xFFFF375F)
    val Sand = Color(0xFFC8A97E)
    val White = Color(0xFFF2F2F7)
    val Gray = Color(0xFF8E8E93)
    val Card = Color(0xFF1C1C1E)
    val CardHigh = Color(0xFF2C2C2E)

    val accents = listOf(Red, Orange, Yellow, Green, Mint, Teal, Cyan, Blue, Indigo, Purple, Pink, Sand, White)

    /** Hour / minute colour pairs for the Float clock. */
    val floatPairs = listOf(
        Color(0xFF1E6FD9) to Color(0xFF5BA8F5),
        Color(0xFFFF6A3D) to Color(0xFFFFB13D),
        Color(0xFF3DA5FF) to Color(0xFF7CF0D2),
        Color(0xFFB45CFF) to Color(0xFFFF6AC1),
        Color(0xFF32D158) to Color(0xFFD9F55C),
        Color(0xFFFF4539) to Color(0xFFFF8FA3),
        Color(0xFF5E5CE6) to Color(0xFF64D2FF),
        Color(0xFFF2F2F7) to Color(0xFF8E8E93),
    )

    /** Glow gradients (core, mid, edge) for the Solar clock. */
    val solar = listOf(
        listOf(Color(0xFFFFF3B0), Color(0xFFFFA62B), Color(0xFFE2420E)),
        listOf(Color(0xFFD8F3FF), Color(0xFF4BA8FF), Color(0xFF2433C9)),
        listOf(Color(0xFFFFE0F4), Color(0xFFFF5FB8), Color(0xFF8A1BD6)),
        listOf(Color(0xFFE6FFD8), Color(0xFF5FE07A), Color(0xFF0B7F6A)),
        listOf(Color(0xFFFFE9D6), Color(0xFFFF7A59), Color(0xFFB0123A)),
        listOf(Color(0xFFF1E9FF), Color(0xFF9E8CFF), Color(0xFF3A2C9C)),
        listOf(Color(0xFFFFFFFF), Color(0xFFB8B8C4), Color(0xFF4A4A57)),
    )

    /** Background (dark → light) and digit colours for the Gradient clock. */
    data class Wash(val dark: Color, val light: Color, val digits: Color)

    val gradients = listOf(
        Wash(Color(0xFF4A3358), Color(0xFFB98BC9), Color(0xFFF0B7FF)),
        Wash(Color(0xFF173A5E), Color(0xFF6FB3E8), Color(0xFFC4E6FF)),
        Wash(Color(0xFF1F4A3A), Color(0xFF7CC9A0), Color(0xFFD2F7DF)),
        Wash(Color(0xFF5A2E1E), Color(0xFFE59A6B), Color(0xFFFFD9BE)),
        Wash(Color(0xFF5A1E2E), Color(0xFFE0708A), Color(0xFFFFC7D3)),
        Wash(Color(0xFF2B2B33), Color(0xFF9C9CAA), Color(0xFFF2F2F7)),
    )

    fun paletteSize(style: ClockStyle) = when (style) {
        ClockStyle.FLOAT -> floatPairs.size
        ClockStyle.SOLAR -> solar.size
        ClockStyle.WORLD -> 1
        ClockStyle.GRADIENT -> gradients.size
        else -> accents.size
    }

    /** A representative colour for a palette entry (used by the colour picker). */
    fun swatch(style: ClockStyle, index: Int): List<Color> = when (style) {
        ClockStyle.FLOAT -> floatPairs[index % floatPairs.size].toList()
        ClockStyle.SOLAR -> solar[index % solar.size].take(2).reversed()
        ClockStyle.GRADIENT -> gradients[index % gradients.size].let { listOf(it.dark, it.light) }
        else -> listOf(accents[index % accents.size])
    }
}

/** Current wall-clock time, refreshed on each boundary of [everyMs]. */
@Composable
fun rememberTicker(everyMs: Long = 1000L): State<Long> = produceState(System.currentTimeMillis(), everyMs) {
    while (true) {
        val now = System.currentTimeMillis()
        value = now
        delay(everyMs - now % everyMs)
    }
}

/**
 * False for pages that exist but aren't on screen (a pager keeps its neighbours composed so a
 * swipe can start instantly). Per-frame animations pause there instead of drawing unseen.
 */
val LocalLive = compositionLocalOf { true }

/** Which "super animations" are on, after the master switch. Off everywhere by default (tests). */
data class Motion(
    val screens: Boolean = false,
    val pages: Boolean = false,
    val backdrop: Boolean = false,
    val springs: Boolean = false,
    val floating: Boolean = false,
) {
    /** How far clock digits drift: the usual barely-there hover, or clearly afloat. */
    val floatRange get() = if (floating) 2.6f else 1f

    companion object {
        fun of(p: io.github.dockside.data.MotionPrefs) =
            if (!p.enabled) Motion() else Motion(p.screens, p.pages, p.backdrop, p.springs, p.floating)
    }
}

val LocalMotion = compositionLocalOf { Motion() }

/** [bouncy] when springy animations are on, otherwise a plain ease of about the same length. */
fun <T> springOr(springs: Boolean, bouncy: androidx.compose.animation.core.FiniteAnimationSpec<T>): androidx.compose.animation.core.FiniteAnimationSpec<T> =
    if (springs) bouncy else androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)

/**
 * How far a clock face has "assembled" itself, 0..1. It runs from 0 to 1 when the face comes
 * into view (hands sweep in from 12, digits rise into place) and back when it leaves.
 * Faces read it inside layer/draw blocks only, so the animation costs no recomposition.
 */
val LocalIntro = compositionLocalOf<State<Float>> { mutableFloatStateOf(1f) }

/**
 * A never-repeating-looking position inside ±[ax], ±[ay] (pixels) for burn-in protection:
 * two incommensurate sine waves per axis.
 */
fun driftOffset(timeMs: Long, ax: Float, ay: Float): androidx.compose.ui.geometry.Offset {
    val t = timeMs / 1000.0
    val x = 0.65 * kotlin.math.sin(t / 37.0) + 0.35 * kotlin.math.sin(t / 13.7 + 1.3)
    val y = 0.6 * kotlin.math.cos(t / 29.0) + 0.4 * kotlin.math.sin(t / 17.3 + 0.7)
    return androidx.compose.ui.geometry.Offset((x * ax).toFloat(), (y * ay).toFloat())
}

/**
 * Where digit [i] floats right now, as fractions of its own size (x, y) and a tilt in degrees.
 * Every digit has its own unrelated slow rhythms, so they drift independently around their
 * places rather than moving together or in a wave.
 */
fun digitFloat(timeMs: Long, i: Int): Triple<Float, Float, Float> {
    val t = timeMs / 1000f
    val k = i * 1.618f + 0.7f
    val x = 0.6f * kotlin.math.sin(t / (3.1f + 0.37f * i) + k) + 0.4f * kotlin.math.sin(t / (1.9f + 0.23f * i) + 2 * k)
    val y = 0.6f * kotlin.math.cos(t / (2.7f + 0.41f * i) + k) + 0.4f * kotlin.math.sin(t / (1.7f + 0.29f * i) + 3 * k)
    val r = kotlin.math.sin(t / (3.9f + 0.31f * i) + 4 * k)
    return Triple(x, y, r)
}

/** Staggered progress for item [i] of [n] driven by [p] (0..1), eased out. */
fun stagger(p: Float, i: Int, n: Int, spread: Float = 0.12f): Float {
    val total = 1f + spread * (n - 1).coerceAtLeast(0)
    val local = ((p * total) - i * spread).coerceIn(0f, 1f)
    return 1f - (1f - local) * (1f - local) * (1f - local)
}

/** Wall-clock time refreshed every frame, for smooth second hands; once a second off screen. */
@Composable
fun rememberFrameTime(): State<Long> {
    val live = LocalLive.current
    return produceState(System.currentTimeMillis(), live) {
        if (live) while (true) withFrameMillis { value = System.currentTimeMillis() }
        else while (true) {
            value = System.currentTimeMillis(); delay(1000)
        }
    }
}

fun Long.toLocal(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)

object TimeText {
    fun is24h(context: Context) = DateFormat.is24HourFormat(context)

    /** Hours without leading zero in 12h mode, as on a bedside clock. */
    fun hours(t: LocalDateTime, h24: Boolean): String =
        if (h24) "%02d".format(t.hour) else ((t.hour + 11) % 12 + 1).toString()

    fun minutes(t: LocalDateTime): String = "%02d".format(t.minute)

    fun time(t: LocalDateTime, h24: Boolean) = hours(t, h24) + ":" + minutes(t)

    fun shortTime(epoch: Long, context: Context): String {
        val t = epoch.toLocal()
        return if (is24h(context)) "%02d:%02d".format(t.hour, t.minute)
        else DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(t)
    }

    fun weekdayShort(t: LocalDateTime): String =
        t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).replace(".", "")

    fun weekdayLong(t: LocalDateTime): String =
        t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

    fun monthShort(t: LocalDateTime): String =
        t.month.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault())

    fun monthLong(t: LocalDateTime): String =
        t.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())

    fun dateLine(t: LocalDateTime): String =
        DateTimeFormatter.ofPattern(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Locale.getDefault()
        ).format(t)

    fun shortDateLine(t: LocalDateTime): String = weekdayShort(t).replaceFirstChar { it.titlecase() } + " " + t.dayOfMonth
}
