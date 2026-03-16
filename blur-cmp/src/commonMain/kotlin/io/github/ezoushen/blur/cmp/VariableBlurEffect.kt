package io.github.ezoushen.blur.cmp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Number of discrete blur levels for variable blur approximation.
 * More levels = smoother transitions but more rendering cost.
 * 4 levels provides a good balance: sharp, light, medium, heavy blur.
 */
private const val BLUR_LEVELS = 4

/**
 * Renders [background] with a variable blur gradient using multi-level compositing.
 *
 * Instead of blending sharp + blurred (which causes glow artifacts), this renders
 * multiple blur levels and uses gradient masks to select which level is visible
 * at each position. Each pixel shows exactly one blur level — no additive overlay.
 *
 * Levels (for radius = 40dp):
 *   Level 0: blur(0dp)  — sharp
 *   Level 1: blur(13dp) — light blur
 *   Level 2: blur(26dp) — medium blur
 *   Level 3: blur(40dp) — full blur
 *
 * The gradient intensity maps to these levels:
 *   intensity 0.0    → Level 0 (sharp)
 *   intensity 0.33   → Level 1
 *   intensity 0.66   → Level 2
 *   intensity 1.0    → Level 3 (full blur)
 */
@Composable
internal fun VariableBlurLayer(
    blurRadius: Dp,
    gradient: BlurGradientType,
    modifier: Modifier = Modifier,
    background: @Composable () -> Unit,
) {
    // Calculate blur radius for each level
    val maxRadiusPx = blurRadius
    val levelRadii = (0 until BLUR_LEVELS).map { level ->
        (maxRadiusPx * level.toFloat() / (BLUR_LEVELS - 1).toFloat())
    }

    Box(modifier = modifier) {
        // Render each blur level from heaviest to lightest.
        // Each level is masked to show only where its intensity band applies.
        // Heaviest blur is drawn first (bottom), lightest on top.
        for (levelIndex in (BLUR_LEVELS - 1) downTo 0) {
            val levelRadius = levelRadii[levelIndex]

            // This level is visible where gradient intensity falls within its band.
            // Level 0: intensity [0.0, 0.25)
            // Level 1: intensity [0.25, 0.5)
            // Level 2: intensity [0.5, 0.75)
            // Level 3: intensity [0.75, 1.0]
            val bandStart = levelIndex.toFloat() / BLUR_LEVELS
            val bandEnd = (levelIndex + 1).toFloat() / BLUR_LEVELS

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // Mask: opaque where this level should be visible
                        val brush = createBandMask(gradient, size, bandStart, bandEnd)
                        drawRect(brush = brush, blendMode = BlendMode.DstIn)
                    }
            ) {
                if (levelRadius > 0.dp) {
                    Box(modifier = Modifier.fillMaxSize().blur(levelRadius)) {
                        background()
                    }
                } else {
                    background()
                }
            }
        }
    }
}

/**
 * Creates a brush that is opaque (white) where the gradient intensity falls within
 * [bandStart, bandEnd], and transparent (clear) elsewhere.
 *
 * For smooth transitions, we use a soft ramp instead of a hard cutoff.
 */
private fun createBandMask(
    gradient: BlurGradientType,
    size: androidx.compose.ui.geometry.Size,
    bandStart: Float,
    bandEnd: Float,
): Brush {
    // For the lowest band (bandStart=0), we want to show this level where
    // gradient intensity is BELOW bandEnd.
    // For the highest band (bandEnd=1), show where intensity is ABOVE bandStart.
    // For middle bands, show where intensity is between bandStart and bandEnd.
    //
    // Simplification: each level "wins" where it's the closest level to the
    // gradient's intensity value. So we create a mask where:
    //   alpha = 1 where gradient_intensity is in [bandStart, bandEnd]
    //   alpha = 0 elsewhere

    return when (gradient) {
        is BlurGradientType.Linear -> {
            val startOffset = Offset(gradient.startX * size.width, gradient.startY * size.height)
            val endOffset = Offset(gradient.endX * size.width, gradient.endY * size.height)

            val stops = if (gradient.stops != null) {
                gradient.stops.map { it.position to it.intensity }
            } else {
                listOf(0f to gradient.startIntensity, 1f to gradient.endIntensity)
            }

            // Convert intensity stops to alpha stops for this band
            val colorStops = stops.map { (pos, intensity) ->
                val alpha = if (intensity >= bandStart && intensity < bandEnd) 1f
                else if (bandEnd >= 1f && intensity >= bandStart) 1f // Include 1.0 in top band
                else 0f
                pos to Color.White.copy(alpha = alpha)
            }.toTypedArray()

            // Add interpolation points at band boundaries for smoother transitions
            Brush.linearGradient(
                colorStops = colorStops,
                start = startOffset,
                end = endOffset,
            )
        }

        is BlurGradientType.Radial -> {
            val center = Offset(gradient.centerX * size.width, gradient.centerY * size.height)
            val radiusPx = gradient.radius * maxOf(size.width, size.height)

            val stops = if (gradient.stops != null) {
                gradient.stops.map { it.position to it.intensity }
            } else {
                listOf(0f to gradient.centerIntensity, 1f to gradient.edgeIntensity)
            }

            val colorStops = stops.map { (pos, intensity) ->
                val alpha = if (intensity >= bandStart && intensity < bandEnd) 1f
                else if (bandEnd >= 1f && intensity >= bandStart) 1f
                else 0f
                pos to Color.White.copy(alpha = alpha)
            }.toTypedArray()

            Brush.radialGradient(
                colorStops = colorStops,
                center = center,
                radius = radiusPx,
            )
        }
    }
}
