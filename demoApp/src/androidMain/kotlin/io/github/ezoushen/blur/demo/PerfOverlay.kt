package io.github.ezoushen.blur.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ezoushen.blur.BlurPerfMonitor
import kotlinx.coroutines.delay

@Composable
fun PerfOverlay(modifier: Modifier = Modifier) {
    var tick by remember { mutableLongStateOf(0L) }

    // Poll the monitor every 200ms
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            tick = BlurPerfMonitor.frameCount
        }
    }

    // Read values (tick triggers recomposition)
    @Suppress("UNUSED_EXPRESSION")
    tick

    val totalMs = BlurPerfMonitor.lastTotalUs / 1000f
    val blurMs = BlurPerfMonitor.lastBlurUs / 1000f
    val strategy = BlurPerfMonitor.lastStrategy
    val dim = BlurPerfMonitor.lastDimension
    val frames = BlurPerfMonitor.frameCount

    val monoStyle = TextStyle(
        color = Color.Yellow,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
    )

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(6.dp)
    ) {
        BasicText("BLUR PERF", style = monoStyle.copy(color = Color.Cyan))
        BasicText("strategy: $strategy", style = monoStyle)
        BasicText("dim: $dim", style = monoStyle)
        BasicText("total: ${"%.1f".format(totalMs)} ms", style = monoStyle)
        BasicText("blur:  ${"%.1f".format(blurMs)} ms", style = monoStyle)
        BasicText("frames: $frames", style = monoStyle)
    }
}
