package io.github.ezoushen.blur.cmp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import androidx.compose.ui.unit.dp

/**
 * iOS implementation using Compose's Modifier.blur() (Skia-based).
 *
 * CMP renders via Skia on iOS, so Modifier.blur() uses Skia's ImageFilter
 * for GPU-accelerated blur. This works identically to the Android implementation.
 *
 * Future: replace with CABackdropLayer via UIKitView for true compositor-level
 * backdrop blur with native performance and blend mode support.
 */
@Composable
actual fun BlurOverlayHost(
    state: BlurOverlayState,
    modifier: Modifier,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val config = state.config

    Box(modifier = modifier) {
        if (state.isEnabled && config.radius > 0f) {
            val hasTint = config.tintColorValue != 0L
            val isNonNormalBlend = config.tintBlendMode != BlurBlendMode.Normal
            val blurRadius = config.radius.dp

            if (hasTint && isNonNormalBlend) {
                // COLOR DODGE ORDER: background → tint(dodge) → blur
                Box(modifier = Modifier.fillMaxSize().blur(blurRadius)) {
                    background()
                    val tintColor = config.tintColor
                    if (tintColor != null) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            drawRect(color = tintColor, blendMode = mapBlendMode(config.tintBlendMode))
                        }
                    }
                }
            } else {
                // NORMAL ORDER: blur background, then tint on top
                Box(modifier = Modifier.fillMaxSize().blur(blurRadius)) {
                    background()
                }
                if (hasTint) {
                    val tintColor = config.tintColor
                    if (tintColor != null) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            drawRect(color = tintColor, blendMode = ComposeBlendMode.SrcOver)
                        }
                    }
                }
            }
        } else {
            background()
        }

        content()
    }
}

private fun mapBlendMode(mode: BlurBlendMode): ComposeBlendMode = when (mode) {
    BlurBlendMode.Normal -> ComposeBlendMode.SrcOver
    BlurBlendMode.ColorDodge -> ComposeBlendMode.ColorDodge
    BlurBlendMode.ColorBurn -> ComposeBlendMode.ColorBurn
    BlurBlendMode.Multiply -> ComposeBlendMode.Multiply
    BlurBlendMode.Screen -> ComposeBlendMode.Screen
    BlurBlendMode.Overlay -> ComposeBlendMode.Overlay
    BlurBlendMode.SoftLight -> ComposeBlendMode.Softlight
    BlurBlendMode.HardLight -> ComposeBlendMode.Hardlight
    BlurBlendMode.Darken -> ComposeBlendMode.Darken
    BlurBlendMode.Lighten -> ComposeBlendMode.Lighten
    BlurBlendMode.Difference -> ComposeBlendMode.Difference
    BlurBlendMode.Exclusion -> ComposeBlendMode.Exclusion
}
