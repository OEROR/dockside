package io.github.dockside.ui.widgets

import io.github.dockside.ui.common.easyFling
import io.github.dockside.ui.common.LocalMotion
import io.github.dockside.ui.common.springOr
import io.github.dockside.ui.common.lockedVerticalPaging
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dockside.R
import io.github.dockside.data.AppSettings
import io.github.dockside.data.StackConfig
import io.github.dockside.data.WidgetKind
import io.github.dockside.standby.StandbyData
import io.github.dockside.ui.common.Glyphs
import io.github.dockside.ui.common.PageDots
import io.github.dockside.ui.common.Palette
import io.github.dockside.ui.common.RoundedFamily
import io.github.dockside.ui.common.jiggle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class StackSide { LEFT, RIGHT }

private fun AppSettings.stack(side: StackSide) = if (side == StackSide.LEFT) left else right
private fun AppSettings.withStack(side: StackSide, s: StackConfig) =
    if (side == StackSide.LEFT) copy(left = s) else copy(right = s)

/** Widgets that are shown even if not in the stack while Widget Suggestions is on. */
private fun effectiveWidgets(stack: StackConfig, data: StandbyData, suggest: Boolean): List<WidgetKind> {
    val base = stack.widgets
    if (!stack.suggestions || !suggest) return base
    // Paused music keeps its widget (the pause button must not make it vanish); it goes once the
    // player's session ends.
    return if (data.media != null && WidgetKind.NOW_PLAYING !in base) listOf(WidgetKind.NOW_PLAYING) + base else base
}

@Composable
fun WidgetsView(
    settings: AppSettings,
    data: StandbyData,
    editing: StackSide?,
    onEdit: (StackSide?) -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onOpen: (Intent) -> Unit,
) {
    // A suggested widget appears in one stack only: the right one, unless it has suggestions off.
    val suggestRight = settings.right.suggestions
    val leftState = rememberStackPager(settings.left, data, !suggestRight)
    val rightState = rememberStackPager(settings.right, data, suggestRight)
    val scale by animateFloatAsState(if (editing == null) 1f else 0.94f, springOr(LocalMotion.current.springs, spring(stiffness = 300f)), label = "dim")

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (editing == null) 1f else 0f },
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            StackSide.entries.forEach { side ->
                SmartStack(
                    stack = settings.stack(side), data = data, settings = settings,
                    pager = if (side == StackSide.LEFT) leftState else rightState,
                    suggest = (side == StackSide.RIGHT) == suggestRight,
                    editing = false,
                    onLongPress = { onEdit(side) },
                    onIndex = { i -> onUpdate { it.withStack(side, it.stack(side).copy(index = i)) } },
                    onOpen = onOpen,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        AnimatedVisibility(
            visible = editing != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
        ) {
            val side = editing ?: StackSide.LEFT
            StackEditor(side = side, settings = settings, data = data, onDone = { onEdit(null) }, onUpdate = onUpdate)
        }
    }
}

@Composable
private fun rememberStackPager(stack: StackConfig, data: StandbyData, suggest: Boolean): PagerState {
    val count = effectiveWidgets(stack, data, suggest).size
    return rememberPagerState(initialPage = stack.index.coerceIn(0, maxOf(0, count - 1))) { count }
}

@Composable
private fun SmartStack(
    stack: StackConfig,
    data: StandbyData,
    settings: AppSettings,
    pager: PagerState,
    suggest: Boolean,
    editing: Boolean,
    onLongPress: () -> Unit,
    onIndex: (Int) -> Unit,
    onOpen: (Intent) -> Unit,
    modifier: Modifier = Modifier,
    onRemove: ((WidgetKind) -> Unit)? = null,
) {
    val widgets = effectiveWidgets(stack, data, suggest)
    val scope = rememberCoroutineScope()
    var lastTouch by remember { mutableLongStateOf(0L) }
    var arrowFor by remember { mutableStateOf<WidgetKind?>(null) }
    val latestData by rememberUpdatedState(data)

    LaunchedEffect(pager.settledPage) {
        if (!editing) onIndex(pager.settledPage)
        lastTouch = System.currentTimeMillis()
    }
    // When a suggested widget comes or goes, the indices shift: keep the widget that was on
    // screen, except that music which just started is brought forward.
    var shown by remember { mutableStateOf(widgets) }
    LaunchedEffect(widgets) {
        val before = shown
        shown = widgets
        if (before == widgets) return@LaunchedEffect
        val arrived = WidgetKind.NOW_PLAYING in widgets && WidgetKind.NOW_PLAYING !in before
        val keep = if (arrived) WidgetKind.NOW_PLAYING else before.getOrNull(pager.currentPage)
        val target = keep?.let { widgets.indexOf(it) } ?: -1
        if (target >= 0 && target != pager.currentPage) pager.scrollToPage(target)
    }
    // Smart Rotate: surface the most relevant widget when the user isn't interacting.
    LaunchedEffect(stack.smartRotate, widgets, editing) {
        if (!stack.smartRotate || editing) return@LaunchedEffect
        while (true) {
            delay(20_000)
            val now = System.currentTimeMillis()
            if (now - lastTouch < 3 * 60_000 || widgets.size < 2) continue
            val scores = widgets.map { relevance(it, latestData, now) }
            val best = scores.indices.maxBy { scores[it] }
            val current = pager.currentPage.coerceIn(0, widgets.size - 1)
            if (best != current && scores[best] > scores[current] + 0.15f) pager.animateScrollToPage(best)
        }
    }
    LaunchedEffect(arrowFor) {
        if (arrowFor != null) {
            delay(3500); arrowFor = null
        }
    }

    Box(modifier) {
        if (widgets.isEmpty()) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)).background(Palette.Card)
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyphs.Plus, null, tint = Palette.Gray, modifier = Modifier.size(48.dp)) }
            return@Box
        }
        VerticalPager(
            state = pager,
            flingBehavior = easyFling(pager),
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize().lockedVerticalPaging(pager).clip(RoundedCornerShape(32.dp))
                .pointerInput(widgets) {
                    detectTapGestures(
                        onLongPress = { onLongPress() },
                        onTap = {
                            lastTouch = System.currentTimeMillis()
                            val kind = widgets.getOrNull(pager.currentPage)
                            arrowFor = if (kind != null && widgetIntent(kind, latestData) != null && arrowFor == null) kind else null
                        },
                    )
                },
            key = { widgets.getOrNull(it)?.name ?: it },
        ) { page ->
            val kind = widgets[page]
            Box(Modifier.fillMaxSize().jiggle(editing, page)) {
                WidgetContent(kind, data, settings)
            }
        }
        // the "open in app" arrow shown after a tap on a non-interactive widget
        AnimatedVisibility(
            visible = arrowFor != null, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
        ) {
            RoundButton(Glyphs.ArrowOut, 44.dp, background = Color(0xFF3A3A3C)) {
                arrowFor?.let { k -> widgetIntent(k, data)?.let(onOpen) }
                arrowFor = null
            }
        }
        val current = widgets.getOrNull(pager.currentPage)
        if (editing && onRemove != null && current != null && current in stack.widgets) {
            Box(
                Modifier.align(Alignment.TopStart).offset((-14).dp, (-14).dp).size(36.dp).clip(CircleShape)
                    .background(Color(0xFF48484A)).clickable { onRemove(current) },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyphs.Minus, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
        PageDots(
            count = widgets.size, current = pager.currentPage, pulse = pager.currentPage,
            alwaysVisible = editing,
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = 12.dp),
            dotSize = 6.dp,
        )
        LaunchedEffect(widgets.size) {
            if (pager.currentPage >= widgets.size && widgets.isNotEmpty()) scope.launch { pager.scrollToPage(widgets.size - 1) }
        }
    }
}

@Composable
private fun StackEditor(
    side: StackSide,
    settings: AppSettings,
    data: StandbyData,
    onDone: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val stack = settings.stack(side)
    // Editing shows only the widgets actually in the stack (no suggestions), so it pages separately.
    val pager = rememberPagerState(initialPage = stack.index.coerceIn(0, maxOf(0, stack.widgets.size - 1))) { stack.widgets.size }
    var gallery by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // same footprint as in the normal two-stack layout, slightly reduced
        val stackWidth = (maxWidth - 58.dp) / 2 * 0.9f
        val stackHeight = (maxHeight - 28.dp) * 0.9f
        SmartStack(
            stack = stack, data = data, settings = settings, pager = pager, suggest = false, editing = true,
            onLongPress = {}, onIndex = {}, onOpen = {},
            onRemove = { kind ->
                onUpdate { it.withStack(side, it.stack(side).copy(widgets = it.stack(side).widgets - kind)) }
            },
            modifier = Modifier.align(Alignment.Center).size(stackWidth, stackHeight),
        )
        // + (add widget)
        RoundButton(
            Glyphs.Plus, 52.dp, background = Color(0xFF3A3A3C),
            modifier = Modifier.align(Alignment.TopStart).padding(18.dp),
        ) { gallery = true }
        // Done
        Box(
            Modifier.align(Alignment.TopEnd).padding(18.dp).clip(CircleShape).background(Color(0xFF3A3A3C))
                .clickable(onClick = onDone).padding(horizontal = 22.dp, vertical = 12.dp)
        ) { Text(stringResource(R.string.done), style = labelStyle(18f, FontWeight.ExtraBold)) }
        // Smart Rotate and Widget Suggestions toggles
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ToggleChip(Glyphs.Rotate, R.string.smart_rotate, stack.smartRotate) {
                onUpdate { it.withStack(side, it.stack(side).copy(smartRotate = !it.stack(side).smartRotate)) }
            }
            ToggleChip(Glyphs.Sparkle, R.string.widget_suggestions, stack.suggestions) {
                onUpdate { it.withStack(side, it.stack(side).copy(suggestions = !it.stack(side).suggestions)) }
            }
        }
        AnimatedVisibility(
            gallery, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
        ) {
            WidgetGallery(
                data = data, settings = settings,
                onPick = { kind ->
                    gallery = false
                    val at = (pager.currentPage + 1).coerceAtMost(stack.widgets.size)
                    onUpdate {
                        val w = it.stack(side).widgets.toMutableList().apply { remove(kind); add(at.coerceAtMost(size), kind) }
                        it.withStack(side, it.stack(side).copy(widgets = w))
                    }
                    scope.launch { delay(120); pager.animateScrollToPage(at) }
                },
                onClose = { gallery = false },
            )
        }
    }
}

@Composable
private fun ToggleChip(icon: ImageVector, label: Int, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(110.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(if (on) Color.White else Color(0xFF3A3A3C)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(label), style = labelStyle(13f, FontWeight.Bold), textAlign = TextAlign.Center)
    }
}

@Composable
private fun WidgetGallery(data: StandbyData, settings: AppSettings, onPick: (WidgetKind) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xF0101012)).padding(vertical = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.add_widget), style = labelStyle(24f, FontWeight.Black), modifier = Modifier.weight(1f))
            RoundButton(Glyphs.Close, 44.dp, background = Color(0xFF3A3A3C), onClick = onClose)
        }
        Spacer(Modifier.weight(1f))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(WidgetKind.entries) { kind ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(kind) }) {
                    Box(
                        Modifier.height(170.dp).aspectRatio(1f).clip(RoundedCornerShape(26.dp)).background(Color.Black)
                            .border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(26.dp))
                    ) {
                        // live preview; touches are handled by the parent
                        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onPick(kind) } }) {
                            WidgetContent(kind, data, settings)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(widgetTitle(kind)), style = labelStyle(15f, FontWeight.Bold))
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

internal fun labelStyle(size: Float, weight: FontWeight, color: Color = Color.White) =
    TextStyle(color = color, fontFamily = RoundedFamily, fontWeight = weight, fontSize = size.sp)

@Composable
fun RoundButton(icon: ImageVector, size: androidx.compose.ui.unit.Dp, background: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier) { CircleButton(icon, size, Color.White, background, onClick) }
}
