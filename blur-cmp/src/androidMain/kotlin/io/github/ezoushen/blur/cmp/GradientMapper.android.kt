package io.github.ezoushen.blur.cmp

import androidx.compose.ui.geometry.Offset
import io.github.ezoushen.blur.BlurConfig
import io.github.ezoushen.blur.BlurGradient

internal object AndroidGradientMapper {

    fun toBlurConfig(config: BlurOverlayConfig): BlurConfig {
        val hasTint = config.tintColorValue != 0L
        return BlurConfig(
            radius = config.radius,
            tintColor = if (hasTint) config.tintColorValue.toInt() else null,
            tintBlendModeOrdinal = if (hasTint && config.tintBlendMode != BlurBlendMode.Normal)
                AndroidBlendModeMapper.toAndroidBlendMode(config.tintBlendMode)?.ordinal
            else null,
            tintOrder = config.tintOrder,
            downsampleFactor = config.downsampleFactor,
        )
    }

    fun toBlurGradient(gradient: BlurGradientType, radius: Float): BlurGradient = when (gradient) {
        is BlurGradientType.Linear -> {
            val start = Offset(gradient.startX, gradient.startY)
            val end = Offset(gradient.endX, gradient.endY)
            if (gradient.stops != null) {
                BlurGradient.linearGradient(
                    radiusStops = gradient.stops.map { it.position to (it.intensity * radius) }.toTypedArray(),
                    start = start,
                    end = end,
                )
            } else {
                BlurGradient.linearGradient(
                    startRadius = gradient.startIntensity * radius,
                    endRadius = gradient.endIntensity * radius,
                    start = start,
                    end = end,
                )
            }
        }
        is BlurGradientType.Radial -> {
            val center = Offset(gradient.centerX, gradient.centerY)
            if (gradient.stops != null) {
                BlurGradient.radialGradient(
                    radiusStops = gradient.stops.map { it.position to (it.intensity * radius) }.toTypedArray(),
                    center = center,
                    radius = gradient.radius,
                )
            } else {
                BlurGradient.radialGradient(
                    centerRadius = gradient.centerIntensity * radius,
                    edgeRadius = gradient.edgeIntensity * radius,
                    center = center,
                    radius = gradient.radius,
                )
            }
        }
    }
}
