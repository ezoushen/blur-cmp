package io.github.ezoushen.blur.cmp

/**
 * Defines how blur intensity varies across the overlay surface.
 * null gradient = uniform blur at the configured radius.
 *
 * Positions are expressed as normalized floats (0.0 = start, 1.0 = end of the
 * component axis), so [startX]/[startY] and similar are in the range 0.0–1.0.
 * This avoids a dependency on [androidx.compose.ui.geometry.Offset] in the data
 * model, sidestepping a Kotlin 2.0 KMP/JVM IR lowering bug with inline value
 * classes in sealed class hierarchies.
 */
sealed class BlurGradientType {

    /**
     * A stop in a multi-stop gradient.
     * @param position Normalized position along the gradient axis (0.0 to 1.0)
     * @param intensity Blur intensity at this position (0.0 = no blur, 1.0 = full radius)
     */
    data class Stop(
        val position: Float,
        val intensity: Float,
    )

    /**
     * Linear gradient: blur intensity varies along a line from (startX, startY)
     * to (endX, endY), expressed in normalized coordinates.
     * Without stops: linear interpolation from [startIntensity] to [endIntensity].
     * With stops: custom multi-stop interpolation.
     */
    data class Linear(
        val startX: Float = 0.5f,
        val startY: Float = 0f,
        val endX: Float = 0.5f,
        val endY: Float = 1f,
        val startIntensity: Float = 1f,
        val endIntensity: Float = 0f,
        val stops: List<Stop>? = null,
    ) : BlurGradientType()

    /**
     * Radial gradient: blur intensity varies from (centerX, centerY) outward.
     * Without stops: linear interpolation from [centerIntensity] to [edgeIntensity].
     * With stops: custom multi-stop interpolation.
     */
    data class Radial(
        val centerX: Float = 0.5f,
        val centerY: Float = 0.5f,
        val radius: Float = 1f,
        val centerIntensity: Float = 0f,
        val edgeIntensity: Float = 1f,
        val stops: List<Stop>? = null,
    ) : BlurGradientType()

    companion object {
        /** Top-to-bottom gradient: full blur at top, no blur at bottom. */
        fun verticalTopToBottom(
            startIntensity: Float = 1f,
            endIntensity: Float = 0f,
        ) = Linear(
            startX = 0.5f,
            startY = 0f,
            endX = 0.5f,
            endY = 1f,
            startIntensity = startIntensity,
            endIntensity = endIntensity,
        )

        /** Spotlight: clear center, blurred edges. */
        fun spotlight(
            centerX: Float = 0.5f,
            centerY: Float = 0.5f,
            radius: Float = 0.5f,
        ) = Radial(
            centerX = centerX,
            centerY = centerY,
            radius = radius,
            centerIntensity = 0f,
            edgeIntensity = 1f,
        )
    }
}
