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

    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            tick = BlurPerfMonitor.frameCount
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    tick

    val m = BlurPerfMonitor
    val monoStyle = TextStyle(
        color = Color.Yellow,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 12.sp,
    )

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(4.dp)
    ) {
        BasicText("BLUR PERF", style = monoStyle.copy(color = Color.Cyan, fontSize = 11.sp))
        BasicText("${m.lastStrategy} ${m.lastDimension}", style = monoStyle)
        BasicText("total: ${"%.1f".format(m.lastTotalUs/1000f)}ms", style = monoStyle.copy(color = Color.Green))
        BasicText("captr: ${"%.1f".format(m.lastCaptureUs/1000f)}ms  [GPU>CPU]", style = monoStyle)
        BasicText("─blur────", style = monoStyle.copy(color = Color.Gray))
        BasicText("uplod: ${"%.1f".format(m.lastUploadUs/1000f)}ms  [CPU>GPU]", style = monoStyle)
        BasicText("pyrmd: ${"%.1f".format(m.lastPyramidUs/1000f)}ms  [GPU]", style = monoStyle)
        BasicText("comps: ${"%.1f".format(m.lastCompositeUs/1000f)}ms  [GPU]", style = monoStyle)
        BasicText("swap:  ${"%.1f".format(m.lastSwapUs/1000f)}ms  [GPU]", style = monoStyle)
        BasicText("fr: ${m.frameCount}", style = monoStyle.copy(color = Color.Gray))
    }
}
