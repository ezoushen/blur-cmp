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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
import io.github.ezoushen.blur.cmp.BlurOverlay
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
    Transition("Transition"),
}

private val textWhite = TextStyle(color = Color.White, fontSize = 14.sp)
private val textWhiteBold = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
private val textTitle = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

private enum class BlurBlendModeOption(val label: String, val blendMode: BlurBlendMode) {
    Normal("Normal", BlurBlendMode.Normal),
    ColorDodge("ColorDodge", BlurBlendMode.ColorDodge),
    Multiply("Multiply", BlurBlendMode.Multiply),
    Screen("Screen", BlurBlendMode.Screen),
    Overlay("Overlay", BlurBlendMode.Overlay),
}

@Composable
fun BlurCmpDemoApp() {
    var mode by remember { mutableStateOf(DemoMode.Variable) }

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
        DemoMode.Transition -> TransitionDemo(
            selectedMode = mode,
            onModeChange = { mode = it },
        )
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
private fun UniformBlurDemo(
    selectedMode: DemoMode,
    onModeChange: (DemoMode) -> Unit,
) {
    var radius by remember { mutableStateOf(25f) }
    var tintAlpha by remember { mutableStateOf(0.15f) }
    var blendModeOption by remember { mutableStateOf(BlurBlendModeOption.ColorDodge) }
    var isEnabled by remember { mutableStateOf(true) }

    val state = rememberBlurOverlayState(
        initialConfig = BlurOverlayConfig(
            radius = radius,
            tintBlendMode = blendModeOption.blendMode,
        ).withTint(Color.White.copy(alpha = tintAlpha)),
    )

    state.config = BlurOverlayConfig(
        radius = radius,
        tintBlendMode = blendModeOption.blendMode,
    ).withTint(Color.White.copy(alpha = tintAlpha))
    state.isEnabled = isEnabled

    Box(modifier = Modifier.fillMaxSize()) {
        // Background — NOT managed by blur, placed independently
        AnimatedBackground(modifier = Modifier.fillMaxSize())

        // Blur overlay — blurs whatever is behind it
        BlurOverlay(state = state, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(48.dp))
                ModeChips(selected = selectedMode, onSelect = onModeChange)

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText("Uniform Blur", style = textTitle)
                        Spacer(Modifier.height(8.dp))
                        BasicText("Blend: ${blendModeOption.label}", style = textWhite)
                    }
                }

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
                        BlurBlendModeOption.entries.forEach { option ->
                            Chip(
                                label = option.label,
                                isSelected = blendModeOption == option,
                                onClick = { blendModeOption = option },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ToggleRow("Blur Enabled", isEnabled) { isEnabled = it }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

// ── Mode 2: Variable Blur ───────────────────────────────────────────────

private enum class GradientStyle(val label: String) {
    TopToBottom("Top→Bottom"),
    BottomToTop("Bottom→Top"),
    LeftToRight("Left→Right"),
    RightToLeft("Right→Left"),
    Spotlight("Spotlight"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariableBlurDemo(
    selectedMode: DemoMode,
    onModeChange: (DemoMode) -> Unit,
) {
    var radius by remember { mutableStateOf(30f) }
    var gradientStyle by remember { mutableStateOf(GradientStyle.TopToBottom) }
    var spotlightRadius by remember { mutableStateOf(0.4f) }

    val gradient = when (gradientStyle) {
        GradientStyle.TopToBottom -> BlurGradientType.Linear(
            startX = 0.5f, startY = 0f, endX = 0.5f, endY = 1f,
            startIntensity = 1f, endIntensity = 0f,
        )
        GradientStyle.BottomToTop -> BlurGradientType.Linear(
            startX = 0.5f, startY = 1f, endX = 0.5f, endY = 0f,
            startIntensity = 1f, endIntensity = 0f,
        )
        GradientStyle.LeftToRight -> BlurGradientType.Linear(
            startX = 0f, startY = 0.5f, endX = 1f, endY = 0.5f,
            startIntensity = 1f, endIntensity = 0f,
        )
        GradientStyle.RightToLeft -> BlurGradientType.Linear(
            startX = 1f, startY = 0.5f, endX = 0f, endY = 0.5f,
            startIntensity = 1f, endIntensity = 0f,
        )
        GradientStyle.Spotlight -> BlurGradientType.Radial(
            centerX = 0.5f, centerY = 0.4f,
            radius = spotlightRadius,
            centerIntensity = 0f, edgeIntensity = 1f,
        )
    }

    val state = rememberBlurOverlayState(
        initialConfig = BlurOverlayConfig(radius = radius, gradient = gradient),
    )

    state.config = BlurOverlayConfig(radius = radius, gradient = gradient)

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(modifier = Modifier.fillMaxSize())

        BlurOverlay(state = state, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(48.dp))
                ModeChips(selected = selectedMode, onSelect = onModeChange)

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText("Variable Blur", style = textTitle)
                        Spacer(Modifier.height(8.dp))
                        BasicText(gradientStyle.label, style = textWhite)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(vertical = 8.dp),
                ) {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GradientStyle.entries.forEach { style ->
                            Chip(
                                label = style.label,
                                isSelected = gradientStyle == style,
                                onClick = { gradientStyle = style },
                            )
                        }
                    }
                    LabeledSlider("Radius", radius, { radius = it }, 0f..80f)
                    if (gradientStyle == GradientStyle.Spotlight) {
                        LabeledSlider("Spotlight Radius", spotlightRadius, { spotlightRadius = it }, 0.1f..1f)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

// ── Mode 3: Color Dodge Showcase ────────────────────────────────────────

@Composable
private fun ColorDodgeDemo(
    selectedMode: DemoMode,
    onModeChange: (DemoMode) -> Unit,
) {
    var radius by remember { mutableStateOf(20f) }
    var tintAlpha by remember { mutableStateOf(0.2f) }
    var useDodge by remember { mutableStateOf(true) }

    val blendMode = if (useDodge) BlurBlendMode.ColorDodge else BlurBlendMode.Normal

    val state = rememberBlurOverlayState(
        initialConfig = BlurOverlayConfig(
            radius = radius,
            tintBlendMode = blendMode,
        ).withTint(Color.White.copy(alpha = tintAlpha)),
    )

    state.config = BlurOverlayConfig(
        radius = radius,
        tintBlendMode = blendMode,
    ).withTint(Color.White.copy(alpha = tintAlpha))

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(modifier = Modifier.fillMaxSize())

        BlurOverlay(state = state, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(48.dp))
                ModeChips(selected = selectedMode, onSelect = onModeChange)

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
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
}

// ── Mode 4: Transition Demo ────────────────────────────────────────────

@Composable
private fun TransitionDemo(
    selectedMode: DemoMode,
    onModeChange: (DemoMode) -> Unit,
) {
    var maxRadius by remember { mutableStateOf(30f) }
    var durationMs by remember { mutableStateOf(800f) }
    val animatedRadius = remember { Animatable(30f) }
    val scope = rememberCoroutineScope()
    var isBlurVisible by remember { mutableStateOf(true) }

    val state = rememberBlurOverlayState(
        initialConfig = BlurOverlayConfig(
            radius = animatedRadius.value,
            tintBlendMode = BlurBlendMode.ColorDodge,
        ).withTint(Color.White.copy(alpha = 0.15f)),
    )

    state.config = BlurOverlayConfig(
        radius = animatedRadius.value,
        tintBlendMode = BlurBlendMode.ColorDodge,
    ).withTint(Color.White.copy(alpha = 0.15f))

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(modifier = Modifier.fillMaxSize())

        BlurOverlay(state = state, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(48.dp))
                ModeChips(selected = selectedMode, onSelect = onModeChange)

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText("Blur Transition", style = textTitle)
                        Spacer(Modifier.height(8.dp))
                        BasicText(
                            "radius = ${animatedRadius.value.fmt()}",
                            style = textWhite,
                        )
                        Spacer(Modifier.height(24.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Chip(
                                label = "Blur In",
                                isSelected = isBlurVisible,
                                onClick = {
                                    isBlurVisible = true
                                    scope.launch {
                                        animatedRadius.animateTo(
                                            targetValue = maxRadius,
                                            animationSpec = tween(durationMs.toInt()),
                                        )
                                    }
                                },
                            )
                            Chip(
                                label = "Blur Out",
                                isSelected = !isBlurVisible,
                                onClick = {
                                    isBlurVisible = false
                                    scope.launch {
                                        animatedRadius.animateTo(
                                            targetValue = 0f,
                                            animationSpec = tween(durationMs.toInt()),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(vertical = 8.dp),
                ) {
                    LabeledSlider("Max Radius", maxRadius, { maxRadius = it }, 0f..80f)
                    LabeledSlider("Duration (ms)", durationMs, { durationMs = it }, 200f..3000f)
                    LabeledSlider("Radius", animatedRadius.value, { scope.launch { animatedRadius.snapTo(it) } }, 0f..80f)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
