package io.github.ezoushen.blur

import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import io.github.ezoushen.blur.cmp.TintOrder

/**
 * Configuration for blur effects.
 *
 * @property radius The blur radius in pixels. Must be non-negative.
 *                  Higher values produce stronger blur. Internally mapped to
 *                  Dual Kawase iterations using logarithmic scaling.
 *                  Typical values: 0-25 for subtle blur, 25-100 for strong blur.
 * @property tintColor Optional tint color with alpha (e.g., 0x80FFFFFF for semi-transparent white).
 *                     Set to null for no tint.
 * @property tintBlendModeOrdinal Android BlendMode ordinal for tint. Only used when tintColor is set.
 * @property tintOrder Controls whether tint is applied before or after blur.
 * @property downsampleFactor Factor to reduce blur computation (higher = faster but lower quality).
 *                            The blur appearance is normalized to be consistent across different
 *                            downsample factors using 4x as the baseline.
 */
data class BlurConfig(
    @FloatRange(from = 0.0)
    val radius: Float = 16f,
    @ColorInt
    val tintColor: Int? = null,
    val tintBlendModeOrdinal: Int? = null,
    val tintOrder: TintOrder = TintOrder.POST_BLUR,
    @FloatRange(from = 1.0, to = 16.0)
    val downsampleFactor: Float = 4f,
    /** Pipeline strategy for blur capture. AUTO selects the best available. */
    val pipelineStrategy: BlurPipelineStrategy = BlurPipelineStrategy.AUTO
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
        val Light = BlurConfig(radius = 10f, tintColor = 0x40FFFFFF.toInt())

        /**
         * Medium blur preset - balanced blur effect.
         */
        val Medium = BlurConfig(radius = 20f, tintColor = 0x60FFFFFF.toInt())

        /**
         * Heavy blur preset - strong blur for privacy or dramatic effect.
         */
        val Heavy = BlurConfig(radius = 50f, tintColor = 0x80FFFFFF.toInt())
    }
}
