package com.virexdl.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.hypot

@Composable fun AudioVisualizer(session: Int, color: Color, playing: Boolean) {
    val context = LocalContext.current
    var bars by remember { mutableStateOf(List(32) { 0f }) }
    var available by remember { mutableStateOf(true) }
    DisposableEffect(session, playing) {
        var effect: Visualizer? = null
        if (session > 0 && playing && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                effect = Visualizer(session)
                effect!!.apply {
                    captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) = Unit
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                            if (fft == null) return
                            bars = List(32) { index ->
                                val bin = 2 + index * ((fft.size / 2 - 2) / 32).coerceAtLeast(1)
                                if (bin + 1 < fft.size) (hypot(fft[bin].toFloat(), fft[bin + 1].toFloat()) / 100f).coerceIn(0f, 1f) else 0f
                            }
                        }
                    }, Visualizer.getMaxCaptureRate().coerceAtMost(15000), false, true)
                    enabled = true
                }
                available = true
            }.onFailure { effect?.release(); effect = null; available = false }
        } else bars = List(32) { 0f }
        onDispose { effect?.release() }
    }
    if (available) Canvas(Modifier.fillMaxWidth().height(24.dp).testTag("audio-visualizer").padding(vertical = 3.dp)) {
        val step = size.width / bars.size
        bars.forEachIndexed { i, amplitude ->
            val height = (if (playing) amplitude else 0f) * size.height
            drawLine(color.copy(alpha = .55f), Offset((i + .5f) * step, size.height), Offset((i + .5f) * step, size.height - height.coerceAtLeast(2f)), strokeWidth = step * .45f, cap = StrokeCap.Round)
        }
    } else Text("Audio visualization is unavailable on this device.", color = color.copy(alpha = .6f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().heightIn(max = 24.dp).testTag("audio-visualizer"))
}
