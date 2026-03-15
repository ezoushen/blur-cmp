package io.github.ezoushen.blur.cmp

/**
 * Blend modes for tint color compositing over the blurred content.
 *
 * All 12 modes are supported on both platforms:
 * - Android API 29+: full native support via android.graphics.BlendMode
 * - Android API 23-28: Normal, Multiply, Screen, Overlay, Darken, Lighten
 *   natively; others gracefully degrade to Normal
 * - iOS 15+: full native support via CALayer compositingFilter
 */
enum class BlurBlendMode {
    Normal,
    ColorDodge,
    ColorBurn,
    Multiply,
    Screen,
    Overlay,
    SoftLight,
    HardLight,
    Darken,
    Lighten,
    Difference,
    Exclusion,
}
