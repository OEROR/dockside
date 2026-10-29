package io.github.dockside.ui.photos

import android.net.Uri
import io.github.dockside.data.BatteryInfo
import io.github.dockside.data.PhotoDates
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.produceState
import io.github.dockside.ui.common.easyFling
import io.github.dockside.ui.common.lockedVerticalPaging
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dockside.R
import io.github.dockside.data.Album
import io.github.dockside.data.AppSettings
import io.github.dockside.data.BitmapLoader
import io.github.dockside.ui.common.Glyphs
import io.github.dockside.ui.common.PageDots
import io.github.dockside.ui.common.Palette
import io.github.dockside.ui.common.RoundedFamily
import io.github.dockside.ui.common.TimeText
import io.github.dockside.ui.common.jiggle
import io.github.dockside.ui.common.rememberTicker
import io.github.dockside.ui.common.toLocal
import kotlinx.coroutines.delay

private const val SLIDE_MS = 12_000

@Composable
fun PhotosView(
    settings: AppSettings,
    albums: List<Album>,
    h24: Boolean,
    battery: BatteryInfo,
    editing: Boolean,
    onEdit: (Boolean) -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val shown = if (editing) albums else albums.filter { it.id !in settings.hiddenAlbums }
    if (shown.isEmpty()) {
        EmptyPhotos(h24, battery); return
    }
    val start = shown.indexOfFirst { it.id == settings.photoAlbum }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { shown.size }
    LaunchedEffect(pager.settledPage, shown) {
        delay(1200)
        shown.getOrNull(pager.settledPage)?.let { a -> onUpdate { it.copy(photoAlbum = a.id) } }
    }
    val scale by animateFloatAsState(if (editing) 0.78f else 1f, spring(dampingRatio = 0.8f, stiffness = 380f), label = "scale")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pager,
            flingBehavior = easyFling(pager),
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize().lockedVerticalPaging(pager).pointerInput(editing) {
                detectTapGestures(onLongPress = { if (!editing) onEdit(true) })
            },
        ) { page ->
            val album = shown[page]
            val hidden = album.id in settings.hiddenAlbums
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (editing && hidden) 0.45f else 1f }
                    .jiggle(editing, page)
                    .clip(RoundedCornerShape(if (editing) 36.dp else 0.dp))
            ) {
                var showing by remember(album.id) { mutableStateOf<Uri?>(null) }
                Slideshow(album, active = page == pager.currentPage, onShowing = { showing = it })
                if (!editing) TimeOverlay(h24, battery, showing)
                AlbumLabel(album.name, pulse = pager.settledPage, always = editing)
            }
            if (editing) {
                // eye toggles whether this collection appears in the rotation
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(48.dp).clip(CircleShape)
                        .background(Color(0xFF3A3A3C))
                        .clickable {
                            onUpdate {
                                it.copy(hiddenAlbums = if (hidden) it.hiddenAlbums - album.id else it.hiddenAlbums + album.id)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (hidden) Glyphs.EyeOff else Glyphs.Eye, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            }
        }
        PageDots(
            count = shown.size, current = pager.currentPage, pulse = pager.currentPage, alwaysVisible = editing,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
        )
        if (editing) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(14.dp).clip(CircleShape).background(Color(0xFF3A3A3C))
                    .clickable { onEdit(false) }.padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Text(stringResource(R.string.done), style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp))
            }
        }
    }
}

@Composable
private fun Slideshow(album: Album, active: Boolean, onShowing: (Uri) -> Unit) {
    val context = LocalContext.current
    var index by remember(album.id) { mutableIntStateOf(0) }
    LaunchedEffect(album.id, active) {
        while (active) {
            delay(SLIDE_MS.toLong()); index = (index + 1) % album.uris.size
        }
    }
    LaunchedEffect(album.id, index) { onShowing(album.uris[index % album.uris.size]) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxDim = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }.coerceAtMost(2048)
        val bitmap by produceState<ImageBitmap?>(null, album.id, index) {
            value = BitmapLoader.load(context, album.uris[index % album.uris.size], maxDim)
            // pre-load the next one so the cross-fade is seamless
            BitmapLoader.load(context, album.uris[(index + 1) % album.uris.size], maxDim)
        }
        Crossfade(bitmap, animationSpec = tween(1400), label = "slide") { bmp ->
            if (bmp != null) {
                // slow Ken Burns drift
                val zoom = remember(bmp) { Animatable(1f) }
                LaunchedEffect(bmp) { zoom.animateTo(1.08f, tween(SLIDE_MS + 2000, easing = LinearEasing)) }
                Image(
                    bmp, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                )
            } else Box(Modifier.fillMaxSize().background(Palette.Card))
        }
    }
}

private val shadow = Shadow(Color(0x66000000), blurRadius = 18f)

/**
 * Time in the top-right corner (with a charge ring while charging) and, bottom-right, the date
 * the photo on screen was taken.
 */
@Composable
private fun TimeOverlay(h24: Boolean, battery: BatteryInfo, photo: Uri?) {
    val context = LocalContext.current
    val now by rememberTicker()
    val t = now.toLocal()
    val taken by produceState<Long?>(null, photo) { value = photo?.let { PhotoDates.takenAt(context, it) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = with(LocalDensity.current) { maxHeight.toSp() }
        val hDp = maxHeight
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x44000000), 0.35f to Color.Transparent)))
        Row(
            Modifier.align(Alignment.TopEnd).padding(end = 40.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                TimeText.time(t, h24),
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.92f), fontFamily = RoundedFamily, fontWeight = FontWeight.SemiBold,
                    fontSize = h * 0.22f, lineHeight = h * 0.24f, shadow = shadow,
                ),
            )
            if (battery.charging) ChargeRing(battery.level, hDp * 0.15f)
        }
        val dateText = taken?.let { photoDate(it.toLocal()) } ?: TimeText.dateLine(t)
        Text(
            dateText.uppercase(),
            style = TextStyle(
                color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold,
                fontSize = h * 0.045f, letterSpacing = (h * 0.045f) * 0.12f, shadow = shadow,
            ),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 44.dp, bottom = 20.dp),
        )
    }
}

private fun photoDate(t: java.time.LocalDateTime): String =
    java.time.format.DateTimeFormatter.ofPattern(
        android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "dMMMyyyy"),
        java.util.Locale.getDefault(),
    ).format(t).replace(".", "")

@Composable
private fun ChargeRing(level: Int, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.padding(start = 14.dp).size(size), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.09f
            val inset = stroke / 2
            val arc = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            drawCircle(Color(0x66000000), this.size.minDimension / 2f)
            drawArc(Color.White.copy(alpha = 0.3f), 0f, 360f, false, androidx.compose.ui.geometry.Offset(inset, inset), arc,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawArc(Palette.Green, -90f, 360f * level / 100f, false, androidx.compose.ui.geometry.Offset(inset, inset), arc,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text(
            level.toString(),
            style = TextStyle(color = Palette.Green, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold,
                fontSize = with(LocalDensity.current) { (size * 0.36f).toSp() }),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.AlbumLabel(name: String, pulse: Any?, always: Boolean) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(pulse, always) {
        visible = true
        if (!always) {
            delay(2500); visible = false
        }
    }
    AnimatedVisibility(visible, Modifier.align(Alignment.BottomStart).padding(start = 36.dp, bottom = 22.dp), enter = fadeIn(), exit = fadeOut(tween(800))) {
        Text(name, style = TextStyle(color = Color.White, fontFamily = RoundedFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, shadow = shadow))
    }
}

@Composable
private fun EmptyPhotos(h24: Boolean, battery: BatteryInfo) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2735), Color(0xFF090A0F))))) {
        TimeOverlay(h24, battery, null)
        Text(
            stringResource(R.string.needs_photos),
            style = TextStyle(color = Palette.Gray, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp),
            modifier = Modifier.align(Alignment.BottomStart).padding(36.dp),
        )
    }
}
