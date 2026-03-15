package io.github.ezoushen.blur.cmp

/**
 * Configuration for a blur overlay.
 *
 * @param radius Blur radius in logical pixels. 0 = no blur. No upper limit.
 * @param tintColorValue Packed color value for the tint (0 = no tint).
 *   Prefer using [withTint] extension to set a Compose [androidx.compose.ui.graphics.Color].
 * @param tintBlendMode How the tint color composites over blurred content.
 * @param downsampleFactor Performance tuning: higher = faster but lower quality (1.0-16.0).
 *   Only affects Android. iOS uses compositor-level scale.
 * @param gradient Optional variable blur gradient. null = uniform blur.
 * @param isLive Whether the blur updates every frame (true) or is static (false).
 */
data class BlurOverlayConfig(
    val radius: Float = 16f,
    val tintColorValue: Long = 0L,
    val tintBlendMode: BlurBlendMode = BlurBlendMode.Normal,
    val downsampleFactor: Float = 4f,
    val gradient: BlurGradientType? = null,
    val isLive: Boolean = true,
) {
    init {
        require(radius >= 0f) { "radius must be >= 0, was $radius" }
        require(downsampleFactor >= 1f) { "downsampleFactor must be >= 1, was $downsampleFactor" }
    }

    companion object {
        val Default = BlurOverlayConfig()

        val Light = BlurOverlayConfig(
            radius = 10f,
            tintColorValue = packColor(1f, 1f, 1f, 0.25f),
        )

        val Dark = BlurOverlayConfig(
            radius = 20f,
            tintColorValue = packColor(0f, 0f, 0f, 0.4f),
        )

        val Heavy = BlurOverlayConfig(
            radius = 50f,
            tintColorValue = packColor(1f, 1f, 1f, 0.5f),
        )

        /**
         * Packs RGBA float components into a Long matching Compose Color's internal format.
         * Compose Color uses sRGB color space with component order: alpha, red, green, blue
         * packed as 16-bit floats in a ULong. For default sRGB space, the packing is:
         *   bits 48-63: alpha, bits 32-47: red, bits 16-31: green, bits 0-15: blue
         * colorSpace id=0 occupies bits 48 of the ULong (upper 6 bits of the Long sign area).
         *
         * For simplicity, encode as ARGB 8-bit (0xAARRGGBB) in the lower 32 bits.
         * The [tintColor] extension property converts this back to a Compose Color.
         */
        internal fun packColor(red: Float, green: Float, blue: Float, alpha: Float): Long {
            val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
            val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
            return ((a shl 24) or (r shl 16) or (g shl 8) or b).toLong()
        }
    }
}
