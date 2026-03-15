package io.github.ezoushen.blur.cmp

import androidx.compose.ui.graphics.Color

/**
 * Returns the tint as a Compose [Color], or null if no tint is set (tintColorValue == 0).
 */
val BlurOverlayConfig.tintColor: Color?
    get() {
        if (tintColorValue == 0L) return null
        val argb = tintColorValue.toInt()
        val alpha = ((argb ushr 24) and 0xFF) / 255f
        val red = ((argb ushr 16) and 0xFF) / 255f
        val green = ((argb ushr 8) and 0xFF) / 255f
        val blue = (argb and 0xFF) / 255f
        return Color(red = red, green = green, blue = blue, alpha = alpha)
    }

/**
 * Returns a copy of this config with the given [Color] as tint.
 * Pass null to clear the tint.
 */
fun BlurOverlayConfig.withTint(color: Color?): BlurOverlayConfig {
    if (color == null) return copy(tintColorValue = 0L)
    val a = (color.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (color.red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (color.green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (color.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    val packed = ((a shl 24) or (r shl 16) or (g shl 8) or b).toLong()
    return copy(tintColorValue = packed)
}
