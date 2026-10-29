package io.github.dockside.ui.clocks

import io.github.dockside.ui.common.LocalLive
import io.github.dockside.ui.common.easyFling
import io.github.dockside.ui.common.lockedVerticalPaging
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.sin
import kotlinx.coroutines.delay
import io.github.dockside.ui.common.LocalIntro
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dockside.R
import io.github.dockside.data.AppSettings
import io.github.dockside.data.ClockStyle
import io.github.dockside.ui.common.PageDots
import io.github.dockside.ui.common.Palette
import io.github.dockside.ui.common.RoundedFamily

fun clockName(style: ClockStyle) = when (style) {
    ClockStyle.DIGITAL -> R.string.clock_digital
    ClockStyle.ANALOG -> R.string.clock_analog
    ClockStyle.FLOAT -> R.string.clock_float
    ClockStyle.SOLAR -> R.string.clock_solar
    ClockStyle.WORLD -> R.string.clock_world
    ClockStyle.LINES -> R.string.clock_lines
    ClockStyle.GRADIENT -> R.string.clock_gradient
    ClockStyle.TALL -> R.string.clock_tall
}

@Composable
fun ClockView(
    settings: AppSettings,
    info: ClockInfo,
    editing: Boolean,
    onEdit: (Boolean) -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val styles = ClockStyle.entries
    val pager = rememberPagerState(initialPage = settings.clockStyle.ordinal) { styles.size }
    var picker by remember { mutableStateOf(false) }
    LaunchedEffect(pager.settledPage) {
        // save after the motion has finished, so the re-render doesn't land mid-animation
        delay(1200)
        onUpdate { it.copy(clockStyle = styles[pager.settledPage]) }
    }
    LaunchedEffect(editing) { if (!editing) picker = false }

    val scale by animateFloatAsState(if (editing) 0.8f else 1f, spring(dampingRatio = 0.8f, stiffness = 380f), label = "scale")
    val corner by animateDpAsState(if (editing) 36.dp else 0.dp, label = "corner")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pager,
            flingBehavior = easyFling(pager),
            userScrollEnabled = false,
            // neighbours are built ahead of time, so a swipe never waits for a heavy face
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().lockedVerticalPaging(pager)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(corner))
                .then(if (editing) Modifier.border(2.dp, Color(0xFF3A3A3C), RoundedCornerShape(corner)) else Modifier)
                .pointerInput(editing) {
                    detectTapGestures(onLongPress = { if (!editing) onEdit(true) })
                },
        ) { page ->
            val style = styles[page]
            val outerLive = LocalLive.current
            // A face assembles itself (0 → 1) as soon as it becomes the swipe's destination and
            // comes apart when it is left, so arriving faces always animate in.
            val intro = remember { Animatable(0f) }
            val arriving = page == pager.targetPage && outerLive
            LaunchedEffect(arriving) {
                if (arriving) intro.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
                else intro.animateTo(0f, tween(350))
            }
            CompositionLocalProvider(
                LocalLive provides (page == pager.currentPage && outerLive),
                LocalIntro provides intro.asState(),
            ) {
                // Clip each face to its page: glows (Solar) must not spill onto the next face.
                Box(Modifier.fillMaxSize().clipToBounds().background(Color.Black)) {
                    FullScreenClock(style, settings.colorIndex(style), info, Modifier.fillMaxSize())
                }
            }
        }
        PageDots(
            count = styles.size, current = pager.currentPage, pulse = pager.currentPage, alwaysVisible = editing,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
        )
        if (editing) {
            val style = styles[pager.currentPage]
            Text(
                stringResource(clockName(style)),
                style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(14.dp).clip(CircleShape).background(Color(0xFF3A3A3C))
                    .clickable { onEdit(false) }.padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Text(
                    stringResource(R.string.done),
                    style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp),
                )
            }
            if (Palette.paletteSize(style) > 1) {
                // the white circle that opens the colour choices
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(18.dp).size(44.dp).clip(CircleShape)
                        .background(Color.White).clickable { picker = !picker }
                        .padding(4.dp).clip(CircleShape)
                        .background(Brush.sweepGradient(Palette.accents + Palette.accents.first()))
                )
            }
            AnimatedVisibility(
                visible = picker,
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-36).dp),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Row(
                    Modifier.clip(CircleShape).background(Color(0xE61C1C1E)).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(Palette.paletteSize(style)) { i ->
                        val colors = Palette.swatch(style, i)
                        val selected = settings.colorIndex(style) == i
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(if (colors.size > 1) Brush.linearGradient(colors) else Brush.linearGradient(colors + colors))
                                .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                                .clickable { onUpdate { it.copy(clockColors = it.clockColors + (style to i)) } }
                        )
                    }
                }
            }
        }
    }
}
