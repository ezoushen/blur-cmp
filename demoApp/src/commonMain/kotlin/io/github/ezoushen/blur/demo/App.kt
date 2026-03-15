package io.github.ezoushen.blur.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ezoushen.blur.cmp.BlurBlendMode
import io.github.ezoushen.blur.cmp.BlurGradientType
import io.github.ezoushen.blur.cmp.BlurOverlayConfig
import io.github.ezoushen.blur.cmp.BlurOverlayHost
import io.github.ezoushen.blur.cmp.rememberBlurOverlayState
import io.github.ezoushen.blur.cmp.withTint

/** Simple float formatting without String.format (not available in commonMain). */
private fun Float.fmt(): String {
    val int = (this * 10).toInt()
    return "${int / 10}.${int % 10}"
}

private enum class DemoMode(val label: String) {
    Uniform("Uniform"),
    Variable("Variable"),
    ColorDodge("ColorDodge"),
}

private val textWhite = TextStyle(color = Color.White, fontSize = 14.sp)
private val textWhiteBold = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
private val textTitle = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

@Composable
fun BlurCmpDemoApp() {
    var mode by remember { mutableStateOf(DemoMode.Uniform) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (mode) {
            DemoMode.Uniform -> UniformBlurDemo(
                selectedMode = mode,
                onModeChange = { mode = it },
            )
            DemoMode.Variable -> VariableBlurDemo(
                selectedMode = mode,
                onModeChange = { mode = it },
            )
            DemoMode.ColorDodge -> ColorDodgeDemo(
                selectedMode = mode,
                onModeChange = { mode = it },
            )
        }
    }
}

// ── Mode Chips ──────────────────────────────────────────────────────────

@Composable
private fun ModeChips(
    selected: DemoMode,
    onSelect: (DemoMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DemoMode.entries.forEach { mode ->
            Chip(
                label = mode.label,
                isSelected = mode == selected,
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun Chip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val bg = if (isSelected) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f)
    val style = if (isSelected) textWhiteBold else textWhite
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        BasicText(text = label, style = style)
    }
}

// ── Custom Slider ───────────────────────────────────────────────────────

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(text = label, style = textWhite)
            BasicText(text = value.fmt(), style = textWhite)
        }
        Spacer(Modifier.height(4.dp))
        SliderTrack(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

@Composable
private fun SliderTrack(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .pointerInput(valueRange) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + frac * (valueRange.endInclusive - valueRange.start))
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + frac * (valueRange.endInclusive - valueRange.start))
                    },
                )
            },
    ) {
        // Filled portion
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(32.dp)
                .background(Color.White.copy(alpha = 0.25f)),
        )
        // Thumb
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = (fraction * 100).dp.coerceAtMost(280.dp)) // approximate
        )
    }
}

// ── Toggle Row ──────────────────────────────────────────────────────────

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onToggle(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = label, style = textWhite)
        val indicator = if (checked) "[ON]" else "[OFF]"
        BasicText(text = indicator, style = textWhiteBold)
    }
}

// ── Mode 1: Uniform Blur ────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UniformBlurDemo(selectedMode: DemoMode, onModeChange: (DemoMode) -> Unit) {
    var radius by remember { mutableStateOf(25f) }
    var tintAlpha by remember { mutableStateOf(0.15f) }
    var blendMode by remember { mutableStateOf(BlurBlendMode.ColorDodge) }
    var enabled by remember { mutableStateOf(true) }

    val state = rememberBlurOverlayState(
        initialConfig = BlurOverlayConfig(
            radius = radius,
            tintBlendMode = blendMode,
        ).withTint(Color.White.copy(alpha = tintAlpha))
    )

    // Keep state in sync with slider changes
    state.config = BlurOverlayConfig(
        radius = radius,
        tintBlendMode = blendMode,
    ).withTint(Color.White.copy(alpha = tintAlpha))
    state.isEnabled = enabled

    BlurOverlayHost(state = state) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(48.dp))
            ModeChips(selected = selectedMode, onSelect = onModeChange)

            // Content area
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedBackground()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText("Uniform Blur", style = textTitle)
                    Spacer(Modifier.height(8.dp))
                    BasicText("Blend: ${blendMode.name}", style = textWhite)
                }
            }

            // Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                LabeledSlider("Radius", radius, { radius = it }, 0f..80f)
                LabeledSlider("Tint Alpha", tintAlpha, { tintAlpha = it }, 0f..1f)

                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        BlurBlendMode.Normal,
                        BlurBlendMode.ColorDodge,
                        BlurBlendMode.Multiply,
                        BlurBlendMode.Screen,
                        BlurBlendMode.Overlay,
                    ).forEach { mode ->
                        Chip(
                            label = mode.name,
                            isSelected = blendMode == mode,
                            onClick = { blendMode = mode },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("Enabled", enabled) { enabled = it }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Mode 2: Variable Blur ───────────────────────────────────────────────

private enum class VariableStyle(val label: String) {
    Vertical("Vertical"),
    Spotlight("Spotlight"),
}

@Composable
private fun VariableBlurDemo(selectedMode: DemoMode, onModeChange: (DemoMode) -> Unit) {
    var style by remember { mutableStateOf(VariableStyle.Vertical) }
    var radius by remember { mutableStateOf(30f) }
    var startIntensity by remember { mutableStateOf(1f) }
    var endIntensity by remember { mutableStateOf(0f) }

    val gradient = when (style) {
        VariableStyle.Vertical -> BlurGradientType.verticalTopToBottom(
            startIntensity = startIntensity,
            endIntensity = endIntensity,
        )
        VariableStyle.Spotlight -> BlurGradientType.spotlight(
            centerX = 0.5f,
            centerY = 0.5f,
            radius = 0.4f,
        )
    }

    val state = rememberBlurOverlayState(
        initialConfig = BlurOverlayConfig(radius = radius, gradient = gradient)
    )

    state.config = BlurOverlayConfig(radius = radius, gradient = gradient)

    BlurOverlayHost(state = state) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(48.dp))
            ModeChips(selected = selectedMode, onSelect = onModeChange)

            // Content area
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedBackground()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText("Variable Blur", style = textTitle)
                    Spacer(Modifier.height(8.dp))
                    BasicText("Style: ${style.label}", style = textWhite)
                }
            }

            // Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VariableStyle.entries.forEach { s ->
                        Chip(label = s.label, isSelected = style == s, onClick = { style = s })
                    }
                }
                LabeledSlider("Radius", radius, { radius = it }, 0f..80f)
                if (style == VariableStyle.Vertical) {
                    LabeledSlider("Start Intensity", startIntensity, { startIntensity = it }, 0f..1f)
                    LabeledSlider("End Intensity", endIntensity, { endIntensity = it }, 0f..1f)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Mode 3: Color Dodge Showcase ────────────────────────────────────────

@Composable
private fun ColorDodgeDemo(selectedMode: DemoMode, onModeChange: (DemoMode) -> Unit) {
    var radius by remember { mutableStateOf(20f) }
    var tintAlpha by remember { mutableStateOf(0.2f) }
    var useDodge by remember { mutableStateOf(true) }

    val blendMode = if (useDodge) BlurBlendMode.ColorDodge else BlurBlendMode.Normal

    val state = rememberBlurOverlayState(
        initialConfig = BlurOverlayConfig(
            radius = radius,
            tintBlendMode = blendMode,
        ).withTint(Color.White.copy(alpha = tintAlpha))
    )

    state.config = BlurOverlayConfig(
        radius = radius,
        tintBlendMode = blendMode,
    ).withTint(Color.White.copy(alpha = tintAlpha))

    BlurOverlayHost(state = state) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(48.dp))
            ModeChips(selected = selectedMode, onSelect = onModeChange)

            // Content area
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedBackground()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    BasicText("Color Dodge", style = textTitle)
                    Spacer(Modifier.height(12.dp))
                    BasicText(
                        "Brightens the background to reflect the tint color. " +
                            "Lighter areas become brighter.",
                        style = textWhite,
                    )
                    Spacer(Modifier.height(24.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!useDodge) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f))
                                .clickable { useDodge = false }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            BasicText("NORMAL", style = if (!useDodge) textWhiteBold else textWhite)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (useDodge) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f))
                                .clickable { useDodge = true }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            BasicText("DODGE", style = if (useDodge) textWhiteBold else textWhite)
                        }
                    }
                }
            }

            // Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(vertical = 8.dp),
            ) {
                LabeledSlider("Radius", radius, { radius = it }, 0f..80f)
                LabeledSlider("Tint Alpha", tintAlpha, { tintAlpha = it }, 0f..1f)
                ToggleRow("Normal <-> ColorDodge", useDodge) { useDodge = it }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
