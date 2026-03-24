package io.github.ezoushen.blur.compose

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import io.github.ezoushen.blur.BlurConfig

/**
 * **DEPRECATED: Use [BlurSurface] instead for true blur-behind effect.**
 *
 * This modifier uses [RenderEffect.createBlurEffect] which only blurs the composable's
 * OWN content (children), NOT the background behind it. For actual iOS-like blur-behind
 * effect, use [BlurSurface] which uses the View-based [io.github.ezoushen.blur.view.BlurView]
 * implementation via AndroidView.
 *
 * This modifier is kept for cases where you want to blur the content INSIDE the composable,
 * but its name is misleading for blur-behind use cases.
 *
 * **Note:** On API 31+, this uses RenderEffect for hardware-accelerated blur of content.
 * On older APIs, only an overlay is shown as fallback (no blur effect).
 *
 * @param config The blur configuration to apply.
 * @return A modifier that applies blur to the content inside.
 * @see BlurSurface for true blur-behind effect
 */
@Deprecated(
    message = "This modifier blurs the composable's content, NOT the background. Use BlurSurface for true blur-behind effect.",
    replaceWith = ReplaceWith("BlurSurface(config = config) { content }", "io.github.ezoushen.blur.compose.BlurSurface")
)
@Stable
fun Modifier.blurBehind(
    config: BlurConfig = BlurConfig.Default
): Modifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.graphicsLayer {
            val blurRadiusPx = config.radius
            if (blurRadiusPx > 0) {
                renderEffect = RenderEffect.createBlurEffect(
                    blurRadiusPx,
                    blurRadiusPx,
                    Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
            }
        }.drawWithContent {
            drawContent()
            // Draw tint color (alpha is included in the color)
            config.tintColor?.let { color ->
                drawRect(color = Color(color))
            }
        }
    } else {
        // Fallback for older APIs - just show tint without blur
        this.drawWithContent {
            drawContent()
            config.tintColor?.let { color ->
                drawRect(color = Color(color))
            }
        }
    }
}

/**
 * **DEPRECATED: Use [BlurSurface] instead for true blur-behind effect.**
 *
 * This modifier uses [RenderEffect.createBlurEffect] which only blurs the composable's
 * OWN content (children), NOT the background behind it.
 *
 * @param radius The blur radius in pixels (0-25).
 * @param tintColor Optional tint color with alpha.
 * @return A modifier that applies blur to the content inside.
 * @see BlurSurface for true blur-behind effect
 */
@Deprecated(
    message = "This modifier blurs the composable's content, NOT the background. Use BlurSurface for true blur-behind effect.",
    replaceWith = ReplaceWith("BlurSurface(radius = radius, tintColor = tintColor) { content }", "io.github.ezoushen.blur.compose.BlurSurface")
)
@Stable
fun Modifier.blurBehind(
    radius: Float = 16f,
    tintColor: Color? = null
): Modifier {
    val config = BlurConfig(
        radius = radius,
        tintColor = tintColor?.let {
            val alpha = (it.alpha * 255).toInt()
            val red = (it.red * 255).toInt()
            val green = (it.green * 255).toInt()
            val blue = (it.blue * 255).toInt()
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    )
    @Suppress("DEPRECATION")
    return blurBehind(config)
}

/**
 * Applies a blur effect that blurs this composable's content (not the background).
 *
 * This is useful for blurring images, text, or other content inside the composable.
 * Use this when you want to blur a view's own content.
 *
 * For blurring what's behind the composable (like iOS UIVisualEffectView),
 * use [BlurSurface] instead.
 *
 * **Note:** Only available on API 31+. On older APIs, no effect is applied.
 *
 * @param radius The blur radius in pixels.
 * @return A modifier that blurs the content.
 * @see BlurSurface for blur-behind effect
 */
@Stable
fun Modifier.blurContent(radius: Float): Modifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radius > 0) {
        this.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(
                radius,
                radius,
                Shader.TileMode.CLAMP
            ).asComposeRenderEffect()
        }
    } else {
        this
    }
}

/**
 * **DEPRECATED: Use [blurContent] instead - the name is more accurate.**
 *
 * Applies a blur effect that blurs this composable's content (not the background).
 *
 * @param radius The blur radius in pixels.
 * @return A modifier that blurs the content.
 */
@Deprecated(
    message = "Use blurContent for clarity - it blurs the content, not the background.",
    replaceWith = ReplaceWith("blurContent(radius)")
)
@Stable
fun Modifier.blur(radius: Float): Modifier = blurContent(radius)
