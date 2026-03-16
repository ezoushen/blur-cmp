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

/**
 * Renders [background] with a variable blur gradient.
 *
 * Technique: composites two layers — the sharp background and the fully blurred
 * background — using a gradient alpha mask to interpolate between them.
 *
 * Where gradient intensity = 1.0 → fully blurred (radius)
 * Where gradient intensity = 0.0 → fully sharp (no blur)
 */
@Composable
internal fun VariableBlurLayer(
    blurRadius: Dp,
    gradient: BlurGradientType,
    modifier: Modifier = Modifier,
    background: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        // Layer 0: Sharp background (always visible as base)
        background()

        // Layer 1: Fully blurred background, masked by gradient
        // The gradient mask controls where blur is visible:
        // - White (alpha=1) areas → blurred content shows through
        // - Black (alpha=0) areas → sharp content shows through
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Use offscreen compositing so the DstIn mask works correctly
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    // Draw the blurred content
                    drawContent()
                    // Mask it with the gradient (DstIn: keep dst where src alpha > 0)
                    val brush = createGradientBrush(gradient, size)
                    drawRect(brush = brush, blendMode = BlendMode.DstIn)
                }
        ) {
            Box(modifier = Modifier.fillMaxSize().blur(blurRadius)) {
                background()
            }
        }
    }
}

/**
 * Creates a [Brush] that maps [BlurGradientType] intensity to alpha.
 * intensity=1.0 → Color.White (opaque, shows blur)
 * intensity=0.0 → Color.Transparent (transparent, shows sharp)
 */
private fun createGradientBrush(
    gradient: BlurGradientType,
    size: androidx.compose.ui.geometry.Size,
): Brush = when (gradient) {
    is BlurGradientType.Linear -> {
        val startOffset = Offset(gradient.startX * size.width, gradient.startY * size.height)
        val endOffset = Offset(gradient.endX * size.width, gradient.endY * size.height)

        if (gradient.stops != null) {
            val colors = gradient.stops.map { Color.White.copy(alpha = it.intensity) }
            val positions = gradient.stops.map { it.position }
            Brush.linearGradient(
                colorStops = positions.zip(colors).toTypedArray(),
                start = startOffset,
                end = endOffset,
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = gradient.startIntensity),
                    Color.White.copy(alpha = gradient.endIntensity),
                ),
                start = startOffset,
                end = endOffset,
            )
        }
    }

    is BlurGradientType.Radial -> {
        val center = Offset(gradient.centerX * size.width, gradient.centerY * size.height)
        // Radius is normalized (0-1), scale to the larger dimension
        val radiusPx = gradient.radius * maxOf(size.width, size.height)

        if (gradient.stops != null) {
            val colors = gradient.stops.map { Color.White.copy(alpha = it.intensity) }
            val positions = gradient.stops.map { it.position }
            Brush.radialGradient(
                colorStops = positions.zip(colors).toTypedArray(),
                center = center,
                radius = radiusPx,
            )
        } else {
            Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = gradient.centerIntensity),
                    Color.White.copy(alpha = gradient.edgeIntensity),
                ),
                center = center,
                radius = radiusPx,
            )
        }
    }
}
