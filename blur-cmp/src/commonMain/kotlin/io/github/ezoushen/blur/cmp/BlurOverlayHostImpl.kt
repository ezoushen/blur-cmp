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
 * Shared implementation for BlurOverlayHost on all platforms.
 * Uses Compose's Modifier.blur() (Skia-based) for uniform blur,
 * and [VariableBlurLayer] for gradient-based variable blur.
 */
@Composable
internal fun BlurOverlayHostCommon(
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

            if (config.gradient != null) {
                // VARIABLE BLUR: gradient-masked two-layer compositing
                if (hasTint && isNonNormalBlend) {
                    // Color dodge order: background → tint(dodge) → variable blur
                    VariableBlurLayer(
                        blurRadius = blurRadius,
                        gradient = config.gradient,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        background()
                        val tintColor = config.tintColor
                        if (tintColor != null) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                drawRect(
                                    color = tintColor,
                                    blendMode = mapBlendMode(config.tintBlendMode),
                                )
                            }
                        }
                    }
                } else {
                    VariableBlurLayer(
                        blurRadius = blurRadius,
                        gradient = config.gradient,
                        modifier = Modifier.fillMaxSize(),
                        background = background,
                    )
                    // Normal tint on top
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
                // UNIFORM BLUR
                if (hasTint && isNonNormalBlend) {
                    // Color dodge order: background → tint(dodge) → blur
                    Box(modifier = Modifier.fillMaxSize().blur(blurRadius)) {
                        background()
                        val tintColor = config.tintColor
                        if (tintColor != null) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                drawRect(
                                    color = tintColor,
                                    blendMode = mapBlendMode(config.tintBlendMode),
                                )
                            }
                        }
                    }
                } else {
                    // Normal order: blur background, then tint on top
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
            }
        } else {
            // Disabled or radius=0: show unblurred background
            background()
        }

        // Controls on top (always sharp)
        content()
    }
}

internal fun mapBlendMode(mode: BlurBlendMode): ComposeBlendMode = when (mode) {
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
