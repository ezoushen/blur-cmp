package io.github.ezoushen.blur.cmp

import android.graphics.PorterDuff
import android.os.Build

internal object AndroidBlendModeMapper {

    fun toPorterDuffMode(mode: BlurBlendMode): PorterDuff.Mode = when (mode) {
        BlurBlendMode.Normal -> PorterDuff.Mode.SRC_OVER
        BlurBlendMode.Multiply -> PorterDuff.Mode.MULTIPLY
        BlurBlendMode.Screen -> PorterDuff.Mode.SCREEN
        BlurBlendMode.Overlay -> PorterDuff.Mode.OVERLAY
        BlurBlendMode.Darken -> PorterDuff.Mode.DARKEN
        BlurBlendMode.Lighten -> PorterDuff.Mode.LIGHTEN
        BlurBlendMode.ColorDodge,
        BlurBlendMode.ColorBurn,
        BlurBlendMode.SoftLight,
        BlurBlendMode.HardLight,
        BlurBlendMode.Difference,
        BlurBlendMode.Exclusion -> PorterDuff.Mode.SRC_OVER
    }

    fun toAndroidBlendMode(mode: BlurBlendMode): android.graphics.BlendMode? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return when (mode) {
            BlurBlendMode.Normal -> android.graphics.BlendMode.SRC_OVER
            BlurBlendMode.ColorDodge -> android.graphics.BlendMode.COLOR_DODGE
            BlurBlendMode.ColorBurn -> android.graphics.BlendMode.COLOR_BURN
            BlurBlendMode.Multiply -> android.graphics.BlendMode.MULTIPLY
            BlurBlendMode.Screen -> android.graphics.BlendMode.SCREEN
            BlurBlendMode.Overlay -> android.graphics.BlendMode.OVERLAY
            BlurBlendMode.SoftLight -> android.graphics.BlendMode.SOFT_LIGHT
            BlurBlendMode.HardLight -> android.graphics.BlendMode.HARD_LIGHT
            BlurBlendMode.Darken -> android.graphics.BlendMode.DARKEN
            BlurBlendMode.Lighten -> android.graphics.BlendMode.LIGHTEN
            BlurBlendMode.Difference -> android.graphics.BlendMode.DIFFERENCE
            BlurBlendMode.Exclusion -> android.graphics.BlendMode.EXCLUSION
        }
    }
}
