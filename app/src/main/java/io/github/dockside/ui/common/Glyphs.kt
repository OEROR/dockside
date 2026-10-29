package io.github.dockside.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small original icon set, drawn on a 24×24 grid. */
object Glyphs {
    private fun stroke(name: String, width: Float = 2.2f, block: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
            )
        }.build()

    private fun fill(name: String, block: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White), pathBuilder = block)
        }.build()

    val Plus = stroke("plus", 2.6f) { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Minus = stroke("minus", 2.8f) { moveTo(6f, 12f); lineTo(18f, 12f) }
    val Check = stroke("check", 2.6f) { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
    val Close = stroke("close", 2.4f) { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val ArrowOut = stroke("arrow", 2.4f) {
        moveTo(8f, 16f); lineTo(16f, 8f); moveTo(9f, 8f); lineTo(16f, 8f); lineTo(16f, 15f)
    }
    val Eye = stroke("eye", 2f) {
        moveTo(2.5f, 12f); curveTo(5f, 7f, 8.5f, 5f, 12f, 5f); curveTo(15.5f, 5f, 19f, 7f, 21.5f, 12f)
        curveTo(19f, 17f, 15.5f, 19f, 12f, 19f); curveTo(8.5f, 19f, 5f, 17f, 2.5f, 12f); close()
        moveTo(12f, 9f); arcTo(3f, 3f, 0f, true, true, 11.99f, 9f)
    }
    val EyeOff = stroke("eye_off", 2f) {
        moveTo(2.5f, 12f); curveTo(5f, 7f, 8.5f, 5f, 12f, 5f); curveTo(15.5f, 5f, 19f, 7f, 21.5f, 12f)
        curveTo(19f, 17f, 15.5f, 19f, 12f, 19f); curveTo(8.5f, 19f, 5f, 17f, 2.5f, 12f); close()
        moveTo(4f, 4f); lineTo(20f, 20f)
    }
    val Rotate = stroke("rotate", 2.1f) {
        moveTo(19f, 9f); arcTo(7.5f, 7.5f, 0f, false, false, 5f, 10f)
        moveTo(5f, 15f); arcTo(7.5f, 7.5f, 0f, false, false, 19f, 14f)
        moveTo(19f, 4.5f); lineTo(19f, 9f); lineTo(14.5f, 9f)
        moveTo(5f, 19.5f); lineTo(5f, 15f); lineTo(9.5f, 15f)
    }
    val Sparkle = fill("sparkle") {
        moveTo(11f, 2f); curveTo(11.8f, 7f, 13f, 8.2f, 18f, 9f); curveTo(13f, 9.8f, 11.8f, 11f, 11f, 16f)
        curveTo(10.2f, 11f, 9f, 9.8f, 4f, 9f); curveTo(9f, 8.2f, 10.2f, 7f, 11f, 2f); close()
        moveTo(18f, 14f); curveTo(18.4f, 16.4f, 18.9f, 17f, 21f, 17.5f); curveTo(18.9f, 18f, 18.4f, 18.6f, 18f, 21f)
        curveTo(17.6f, 18.6f, 17.1f, 18f, 15f, 17.5f); curveTo(17.1f, 17f, 17.6f, 16.4f, 18f, 14f); close()
    }
    val Play = fill("play") { moveTo(7f, 4.5f); lineTo(19.5f, 12f); lineTo(7f, 19.5f); close() }
    val Pause = fill("pause") {
        moveTo(6f, 4.5f); lineTo(10f, 4.5f); lineTo(10f, 19.5f); lineTo(6f, 19.5f); close()
        moveTo(14f, 4.5f); lineTo(18f, 4.5f); lineTo(18f, 19.5f); lineTo(14f, 19.5f); close()
    }
    val Next = fill("next") {
        moveTo(2.5f, 6f); lineTo(11f, 12f); lineTo(2.5f, 18f); close()
        moveTo(11.5f, 6f); lineTo(20f, 12f); lineTo(11.5f, 18f); close()
    }
    val Previous = fill("previous") {
        moveTo(21.5f, 6f); lineTo(13f, 12f); lineTo(21.5f, 18f); close()
        moveTo(12.5f, 6f); lineTo(4f, 12f); lineTo(12.5f, 18f); close()
    }
    val Bolt = fill("bolt") {
        moveTo(13.5f, 2f); lineTo(5f, 13.5f); lineTo(11f, 13.5f); lineTo(10f, 22f); lineTo(19f, 10f)
        lineTo(13f, 10f); close()
    }
    val Alarm = stroke("alarm", 2f) {
        moveTo(12f, 6f); arcTo(7f, 7f, 0f, true, true, 11.99f, 6f)
        moveTo(12f, 9f); lineTo(12f, 13f); lineTo(14.5f, 14.5f)
        moveTo(3.5f, 6.5f); lineTo(6.5f, 3.5f); moveTo(20.5f, 6.5f); lineTo(17.5f, 3.5f)
    }
    val Bell = fill("bell") {
        moveTo(12f, 2.5f); curveTo(8f, 2.5f, 6f, 5.5f, 6f, 9.5f); lineTo(6f, 14f); lineTo(4f, 17f); lineTo(20f, 17f)
        lineTo(18f, 14f); lineTo(18f, 9.5f); curveTo(18f, 5.5f, 16f, 2.5f, 12f, 2.5f); close()
        moveTo(9.5f, 18.5f); lineTo(14.5f, 18.5f); curveTo(14.5f, 20f, 13.4f, 21.5f, 12f, 21.5f)
        curveTo(10.6f, 21.5f, 9.5f, 20f, 9.5f, 18.5f); close()
    }
    /** "You are here" arrow, used next to the weather location. */
    val Locate = fill("locate") {
        moveTo(20.5f, 3.5f); lineTo(3.5f, 10.8f); lineTo(11.2f, 12.8f); lineTo(13.2f, 20.5f); close()
    }
    val Pin = fill("pin") {
        moveTo(12f, 2f); curveTo(8f, 2f, 5f, 5f, 5f, 9f); curveTo(5f, 14f, 12f, 22f, 12f, 22f)
        curveTo(12f, 22f, 19f, 14f, 19f, 9f); curveTo(19f, 5f, 16f, 2f, 12f, 2f); close()
    }
    val Music = fill("music") {
        moveTo(9f, 4f); lineTo(20f, 2f); lineTo(20f, 16f); curveTo(20f, 17.7f, 18.4f, 19f, 16.5f, 19f)
        curveTo(14.6f, 19f, 13.5f, 17.9f, 13.5f, 16.5f); curveTo(13.5f, 15f, 15f, 14f, 16.5f, 14f)
        curveTo(17.1f, 14f, 17.6f, 14.1f, 18f, 14.3f); lineTo(18f, 6.5f); lineTo(11f, 7.8f); lineTo(11f, 18f)
        curveTo(11f, 19.7f, 9.4f, 21f, 7.5f, 21f); curveTo(5.6f, 21f, 4.5f, 19.9f, 4.5f, 18.5f)
        curveTo(4.5f, 17f, 6f, 16f, 7.5f, 16f); curveTo(8.1f, 16f, 8.6f, 16.1f, 9f, 16.3f); close()
    }
}
