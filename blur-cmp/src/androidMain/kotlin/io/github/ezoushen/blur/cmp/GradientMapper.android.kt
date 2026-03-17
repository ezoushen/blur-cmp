package io.github.ezoushen.blur.cmp

import android.os.Build
import androidx.compose.ui.geometry.Offset
import io.github.ezoushen.blur.BlurConfig
import io.github.ezoushen.blur.BlurGradient

internal object AndroidGradientMapper {

    fun toBlurConfig(config: BlurOverlayConfig): BlurConfig {
        // Normal blend: tint applied AFTER blur via overlayColor (drawn on top of blurred content)
        // Non-Normal blend: tint applied BEFORE blur via preBlurTintColor (blended into captured
        // bitmap before blur algorithm runs, so the blend mode interacts with actual background pixels)
        val hasTint = config.tintColorValue != 0L
        val isNormal = config.tintBlendMode == BlurBlendMode.Normal

        val overlayArgb: Int? = if (hasTint && isNormal) config.tintColorValue.toInt() else null

        val preBlurTint: Int?
        val preBlurBlendOrdinal: Int?
        if (hasTint && !isNormal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preBlurTint = config.tintColorValue.toInt()
            preBlurBlendOrdinal = AndroidBlendModeMapper.toAndroidBlendMode(config.tintBlendMode)?.ordinal
        } else {
            preBlurTint = null
            preBlurBlendOrdinal = null
        }

        return BlurConfig(
            radius = config.radius,
            overlayColor = overlayArgb,
            downsampleFactor = config.downsampleFactor,
            preBlurTintColor = preBlurTint,
            preBlurBlendModeOrdinal = preBlurBlendOrdinal,
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
