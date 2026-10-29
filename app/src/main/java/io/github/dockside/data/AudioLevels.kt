package io.github.dockside.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/**
 * Loudness of whatever is playing, split into a few frequency bands (0..1 each), for the bars in
 * the collapsed player. It uses the system visualizer on the output mix: nothing is recorded or
 * stored, but Android still requires the microphone permission for it.
 */
object AudioLevels {
    const val BANDS = 5

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Emits band levels ~20 times a second; completes without emitting if unavailable. */
    fun flow(context: Context): Flow<FloatArray> = callbackFlow {
        if (!hasPermission(context)) {
            close(); return@callbackFlow
        }
        val visualizer = runCatching {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) = Unit
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        if (fft != null) trySend(bands(fft))
                    }
                }, (Visualizer.getMaxCaptureRate() / 2).coerceAtMost(20_000), false, true)
                enabled = true
            }
        }.getOrNull()
        if (visualizer == null) {
            close(); return@callbackFlow
        }
        awaitClose { runCatching { visualizer.enabled = false; visualizer.release() } }
    }

    /** Log-spaced bands over the FFT bins, scaled to 0..1 on a decibel-like curve. */
    private fun bands(fft: ByteArray): FloatArray {
        val bins = fft.size / 2
        val out = FloatArray(BANDS)
        var start = 1
        for (b in 0 until BANDS) {
            val end = max(start + 1, (bins * Math.pow(2.0, (b + 1 - BANDS).toDouble() * 1.6)).toInt().coerceAtMost(bins))
            var peak = 0.0
            for (i in start until end) {
                val re = fft[2 * i].toDouble()
                val im = fft[2 * i + 1].toDouble()
                peak = max(peak, hypot(re, im))
            }
            out[b] = (ln(1 + peak) / ln(1 + 128.0)).toFloat().coerceIn(0f, 1f)
            start = end
        }
        return out
    }
}
