package io.github.ezoushen.blur.cmp

/**
 * Maps [BlurBlendMode] to CIFilter blend mode names used with CALayer.compositingFilter.
 * All 12 blend modes are supported on iOS via Core Animation's compositing filter mechanism.
 */
internal object IosBlendModeMapper {

    /**
     * Returns the CIFilter compositing filter name for the given blend mode,
     * or null for [BlurBlendMode.Normal] (standard alpha compositing, no filter needed).
     */
    fun toCompositingFilterName(mode: BlurBlendMode): String? = when (mode) {
        BlurBlendMode.Normal -> null
        BlurBlendMode.ColorDodge -> "colorDodgeBlendMode"
        BlurBlendMode.ColorBurn -> "colorBurnBlendMode"
        BlurBlendMode.Multiply -> "multiplyBlendMode"
        BlurBlendMode.Screen -> "screenBlendMode"
        BlurBlendMode.Overlay -> "overlayBlendMode"
        BlurBlendMode.SoftLight -> "softLightBlendMode"
        BlurBlendMode.HardLight -> "hardLightBlendMode"
        BlurBlendMode.Darken -> "darkenBlendMode"
        BlurBlendMode.Lighten -> "lightenBlendMode"
        BlurBlendMode.Difference -> "differenceBlendMode"
        BlurBlendMode.Exclusion -> "exclusionBlendMode"
    }

    /** All blend modes are supported on iOS via CALayer compositingFilter. */
    fun isSupported(mode: BlurBlendMode): Boolean = true
}
