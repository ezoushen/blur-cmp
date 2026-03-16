package io.github.ezoushen.blur

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

/**
 * Defines how blur radius varies across a view.
 *
 * BlurGradient follows the same API conventions as Jetpack Compose's [Brush] class,
 * making it familiar for Compose developers. Instead of colors, it maps positions
 * to blur radii.
 *
 * Example usage:
 * ```kotlin
 * // Linear gradient from sharp (0) at top to blurred (25) at bottom
 * BlurGradient.verticalGradient(startRadius = 0f, endRadius = 25f)
 *
 * // Radial gradient with sharp center and blurred edges (spotlight effect)
 * BlurGradient.radialGradient(centerRadius = 0f, edgeRadius = 30f)
 *
 * // Custom stops for depth-of-field effect
 * BlurGradient.linearGradient(
 *     0.0f to 15f,   // background blur
 *     0.3f to 0f,    // sharp focus zone
 *     1.0f to 20f    // foreground blur
 * )
 * ```
 */
sealed class BlurGradient {

    /**
     * Returns the minimum blur radius in this gradient.
     */
    abstract val minRadius: Float

    /**
     * Returns the maximum blur radius in this gradient.
     */
    abstract val maxRadius: Float

    /**
     * Linear gradient blur where radius varies along a line from [start] to [end].
     *
     * @property startRadius Blur radius at the start position
     * @property endRadius Blur radius at the end position
     * @property start Start position in normalized coordinates (0-1), default is top-left
     * @property end End position in normalized coordinates (0-1), default is bottom-right
     */
    data class Linear(
        val startRadius: Float,
        val endRadius: Float,
        val start: Offset = Offset.Zero,
        val end: Offset = Offset(1f, 1f)
    ) : BlurGradient() {
        override val minRadius: Float get() = minOf(startRadius, endRadius)
        override val maxRadius: Float get() = maxOf(startRadius, endRadius)
    }

    /**
     * Linear gradient blur with explicit radius stops.
     *
     * @property stops List of (position, radius) pairs where position is 0-1
     * @property start Start position in normalized coordinates (0-1)
     * @property end End position in normalized coordinates (0-1)
     */
    data class LinearWithStops(
        val stops: List<Pair<Float, Float>>,
        val start: Offset = Offset.Zero,
        val end: Offset = Offset(1f, 1f)
    ) : BlurGradient() {
        override val minRadius: Float get() = stops.minOfOrNull { it.second } ?: 0f
        override val maxRadius: Float get() = stops.maxOfOrNull { it.second } ?: 0f

        init {
            require(stops.isNotEmpty()) { "Stops list cannot be empty" }
            require(stops.all { it.first in 0f..1f }) { "Stop positions must be in range 0-1" }
            require(stops.all { it.second >= 0f }) { "Blur radii must be non-negative" }
        }
    }

    /**
     * Radial gradient blur where radius varies from center to edge.
     *
     * @property centerRadius Blur radius at the center (0 = sharp focus point)
     * @property edgeRadius Blur radius at the outer edge
     * @property center Center position in normalized coordinates (0-1), default is view center
     * @property radius Size of the gradient as fraction of view size (0-1), default fills view
     */
    data class Radial(
        val centerRadius: Float,
        val edgeRadius: Float,
        val center: Offset = Offset(0.5f, 0.5f),
        val radius: Float = 1f
    ) : BlurGradient() {
        override val minRadius: Float get() = minOf(centerRadius, edgeRadius)
        override val maxRadius: Float get() = maxOf(centerRadius, edgeRadius)

        init {
            require(radius > 0f) { "Gradient radius must be positive" }
        }
    }

    /**
     * Radial gradient blur with explicit radius stops.
     *
     * @property stops List of (position, radius) pairs where position is 0-1 (0=center, 1=edge)
     * @property center Center position in normalized coordinates (0-1)
     * @property radius Size of the gradient as fraction of view size (0-1)
     */
    data class RadialWithStops(
        val stops: List<Pair<Float, Float>>,
        val center: Offset = Offset(0.5f, 0.5f),
        val radius: Float = 1f
    ) : BlurGradient() {
        override val minRadius: Float get() = stops.minOfOrNull { it.second } ?: 0f
        override val maxRadius: Float get() = stops.maxOfOrNull { it.second } ?: 0f

        init {
            require(stops.isNotEmpty()) { "Stops list cannot be empty" }
            require(radius > 0f) { "Gradient radius must be positive" }
        }
    }

    companion object {
        /**
         * Creates a linear gradient blur where radius varies from [startRadius] at [start]
         * to [endRadius] at [end].
         *
         * @param startRadius Blur radius at the start position (0 = sharp)
         * @param endRadius Blur radius at the end position
         * @param start Start position in normalized coordinates (default: top-left)
         * @param end End position in normalized coordinates (default: bottom-right)
         */
        fun linearGradient(
            startRadius: Float,
            endRadius: Float,
            start: Offset = Offset.Zero,
            end: Offset = Offset(1f, 1f)
        ): BlurGradient = Linear(startRadius, endRadius, start, end)

        /**
         * Creates a linear gradient blur with explicit radius stops.
         *
         * @param radiusStops Pairs of (position, radius) where position is 0-1
         * @param start Start position in normalized coordinates
         * @param end End position in normalized coordinates
         */
        fun linearGradient(
            vararg radiusStops: Pair<Float, Float>,
            start: Offset = Offset.Zero,
            end: Offset = Offset(1f, 1f)
        ): BlurGradient = LinearWithStops(radiusStops.toList(), start, end)

        /**
         * Creates a vertical gradient blur (top to bottom).
         *
         * @param startRadius Blur radius at the top
         * @param endRadius Blur radius at the bottom
         * @param startY Start Y position (0 = top, 1 = bottom), default 0
         * @param endY End Y position, default 1
         */
        fun verticalGradient(
            startRadius: Float,
            endRadius: Float,
            startY: Float = 0f,
            endY: Float = 1f
        ): BlurGradient = Linear(
            startRadius = startRadius,
            endRadius = endRadius,
            start = Offset(0.5f, startY),
            end = Offset(0.5f, endY)
        )

        /**
         * Creates a horizontal gradient blur (left to right).
         *
         * @param startRadius Blur radius at the left
         * @param endRadius Blur radius at the right
         * @param startX Start X position (0 = left, 1 = right), default 0
         * @param endX End X position, default 1
         */
        fun horizontalGradient(
            startRadius: Float,
            endRadius: Float,
            startX: Float = 0f,
            endX: Float = 1f
        ): BlurGradient = Linear(
            startRadius = startRadius,
            endRadius = endRadius,
            start = Offset(startX, 0.5f),
            end = Offset(endX, 0.5f)
        )

        /**
         * Creates a linear gradient blur at a specified angle.
         *
         * @param startRadius Blur radius at the start
         * @param endRadius Blur radius at the end
         * @param angleDegrees Angle in degrees (0 = left-to-right, 90 = top-to-bottom)
         */
        fun angledGradient(
            startRadius: Float,
            endRadius: Float,
            angleDegrees: Float
        ): BlurGradient {
            val angleRad = Math.toRadians(angleDegrees.toDouble())
            val dx = cos(angleRad).toFloat()
            val dy = sin(angleRad).toFloat()

            // Calculate start and end points based on angle
            // Center at 0.5, 0.5 and extend to edges
            val start = Offset(0.5f - dx * 0.5f, 0.5f - dy * 0.5f)
            val end = Offset(0.5f + dx * 0.5f, 0.5f + dy * 0.5f)

            return Linear(startRadius, endRadius, start, end)
        }

        /**
         * Creates a radial gradient blur where radius varies from [centerRadius] at
         * the center to [edgeRadius] at the outer edge.
         *
         * @param centerRadius Blur radius at the center (0 = sharp focus point)
         * @param edgeRadius Blur radius at the outer edge
         * @param center Center position in normalized coordinates (default: view center)
         * @param radius Size of gradient as fraction of view (default: fills view)
         */
        fun radialGradient(
            centerRadius: Float,
            edgeRadius: Float,
            center: Offset = Offset(0.5f, 0.5f),
            radius: Float = 1f
        ): BlurGradient = Radial(centerRadius, edgeRadius, center, radius)

        /**
         * Creates a radial gradient blur with explicit radius stops.
         *
         * @param radiusStops Pairs of (position, radius) where position 0=center, 1=edge
         * @param center Center position in normalized coordinates
         * @param radius Size of gradient as fraction of view
         */
        fun radialGradient(
            vararg radiusStops: Pair<Float, Float>,
            center: Offset = Offset(0.5f, 0.5f),
            radius: Float = 1f
        ): BlurGradient = RadialWithStops(radiusStops.toList(), center, radius)

    }
}
