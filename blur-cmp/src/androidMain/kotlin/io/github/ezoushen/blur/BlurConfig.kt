package io.github.ezoushen.blur

import androidx.annotation.ColorInt
import androidx.annotation.FloatRange

/**
 * Configuration for blur effects.
 *
 * @property radius The blur radius in pixels. Must be non-negative.
 *                  Higher values produce stronger blur. Internally mapped to
 *                  Dual Kawase iterations using logarithmic scaling.
 *                  Typical values: 0-25 for subtle blur, 25-100 for strong blur.
 * @property overlayColor Optional overlay color with alpha (e.g., 0x80FFFFFF for semi-transparent white).
 *                        Set to null for no overlay.
 * @property downsampleFactor Factor to reduce blur computation (higher = faster but lower quality).
 *                            The blur appearance is normalized to be consistent across different
 *                            downsample factors using 4x as the baseline.
 */
data class BlurConfig(
    @FloatRange(from = 0.0)
    val radius: Float = 16f,
    @ColorInt
    val overlayColor: Int? = null,
    @FloatRange(from = 1.0, to = 16.0)
    val downsampleFactor: Float = 4f,
    /** Tint color applied BEFORE blur (for non-Normal blend modes). */
    @ColorInt
    val preBlurTintColor: Int? = null,
    /** Android BlendMode ordinal for pre-blur tint. Only used when preBlurTintColor is set. */
    val preBlurBlendModeOrdinal: Int? = null
) {
    init {
        require(radius >= 0f) { "Radius must be non-negative, was $radius" }
        require(downsampleFactor >= 1f) { "Downsample factor must be >= 1, was $downsampleFactor" }
    }

    companion object {
        /**
         * Default configuration with standard blur radius and no overlay.
         */
        val Default = BlurConfig()

        /**
         * Light blur preset - subtle frosted glass effect.
         */
        val Light = BlurConfig(radius = 10f, overlayColor = 0x40FFFFFF.toInt())

        /**
         * Medium blur preset - balanced blur effect.
         */
        val Medium = BlurConfig(radius = 20f, overlayColor = 0x60FFFFFF.toInt())

        /**
         * Heavy blur preset - strong blur for privacy or dramatic effect.
         */
        val Heavy = BlurConfig(radius = 50f, overlayColor = 0x80FFFFFF.toInt())
    }
}
