package io.github.ezoushen.blur.compose

import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ezoushen.blur.BlurConfig
import io.github.ezoushen.blur.BlurGradient
import io.github.ezoushen.blur.view.VariableBlurView
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * A composable that provides a variable blur effect where the blur radius
 * varies across the surface based on a gradient.
 *
 * This composable uses the View-based [VariableBlurView] internally via [AndroidView]
 * to provide true blur-behind functionality with per-pixel variable blur radius.
 *
 * **API Design:** Follows Jetpack Compose's [Brush] API conventions for gradients,
 * making it familiar for Compose developers.
 *
 * **Works on all supported API levels (23+).**
 *
 * Usage:
 * ```kotlin
 * // Radial gradient: sharp center, blurred edges (spotlight effect)
 * VariableBlurSurface(
 *     modifier = Modifier.fillMaxWidth().height(200.dp),
 *     gradient = BlurGradient.radialGradient(centerRadius = 0f, edgeRadius = 30f)
 * ) {
 *     Text("Content on blur surface")
 * }
 *
 * // Vertical gradient: sharp at top, blurred at bottom
 * VariableBlurSurface(
 *     modifier = Modifier.fillMaxWidth().height(200.dp),
 *     gradient = BlurGradient.verticalGradient(startRadius = 0f, endRadius = 25f)
 * ) {
 *     Text("Content on blur surface")
 * }
 * ```
 *
 * @param modifier Modifier to be applied to the surface.
 * @param gradient The blur gradient that defines how blur radius varies.
 * @param config The blur configuration to apply (overlayColor, downsampleFactor).
 * @param isLive Whether the blur updates in real-time. Set to false to save energy
 *               when the background is static. Default is true.
 * @param content The content to display on top of the blur effect.
 * @see BlurGradient
 * @see BlurConfig
 * @see VariableBlurView
 */
@Composable
fun VariableBlurSurface(
    modifier: Modifier = Modifier,
    gradient: BlurGradient,
    config: BlurConfig = BlurConfig.Default,
    isLive: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val blurView = remember { VariableBlurView(context) }

    DisposableEffect(gradient, config, isLive) {
        blurView.setBlurGradient(gradient)
        blurView.setBlurConfig(config)
        blurView.setIsLive(isLive)
        onDispose { }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { blurView },
            modifier = Modifier.matchParentSize(),
            update = { view ->
                view.setBlurGradient(gradient)
                view.setBlurConfig(config)
                view.setIsLive(isLive)
            }
        )

        content()
    }
}

/**
 * A composable that provides a variable blur effect with specified parameters.
 *
 * This is a convenience overload that creates the [BlurGradient] and [BlurConfig]
 * from individual parameters.
 *
 * @param modifier Modifier to be applied to the surface.
 * @param gradient The blur gradient that defines how blur radius varies.
 * @param overlayColor Optional overlay color with alpha.
 * @param downsampleFactor Factor to downsample the captured content before blurring (1-16).
 * @param isLive Whether the blur updates in real-time. Set to false to save energy
 *               when the background is static. Default is true.
 * @param content The content to display on top of the blur effect.
 */
@Composable
fun VariableBlurSurface(
    modifier: Modifier = Modifier,
    gradient: BlurGradient,
    overlayColor: ComposeColor? = null,
    downsampleFactor: Float = 4f,
    isLive: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val config = BlurConfig(
        radius = gradient.maxRadius,
        tintColor = overlayColor?.let {
            val alpha = (it.alpha * 255).toInt()
            val red = (it.red * 255).toInt()
            val green = (it.green * 255).toInt()
            val blue = (it.blue * 255).toInt()
            Color.argb(alpha, red, green, blue)
        },
        downsampleFactor = downsampleFactor.coerceIn(1f, 16f)
    )

    VariableBlurSurface(
        modifier = modifier,
        gradient = gradient,
        config = config,
        isLive = isLive,
        content = content
    )
}

/**
 * A composable that provides a vertical gradient blur effect.
 *
 * Convenience function for common vertical gradient blur use case.
 *
 * @param modifier Modifier to be applied to the surface.
 * @param startRadius Blur radius at the top of the surface.
 * @param endRadius Blur radius at the bottom of the surface.
 * @param overlayColor Optional overlay color with alpha.
 * @param downsampleFactor Factor to downsample the captured content before blurring (1-16).
 * @param isLive Whether the blur updates in real-time. Set to false to save energy
 *               when the background is static. Default is true.
 * @param content The content to display on top of the blur effect.
 */
@Composable
fun VerticalBlurSurface(
    modifier: Modifier = Modifier,
    startRadius: Float = 0f,
    endRadius: Float = 25f,
    overlayColor: ComposeColor? = null,
    downsampleFactor: Float = 4f,
    isLive: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    VariableBlurSurface(
        modifier = modifier,
        gradient = BlurGradient.verticalGradient(startRadius, endRadius),
        overlayColor = overlayColor,
        downsampleFactor = downsampleFactor,
        isLive = isLive,
        content = content
    )
}

/**
 * A composable that provides a radial gradient blur effect (spotlight effect).
 *
 * Convenience function for common radial gradient blur use case.
 * Creates a sharp center with blurred edges.
 *
 * @param modifier Modifier to be applied to the surface.
 * @param centerRadius Blur radius at the center (0 = sharp focus point).
 * @param edgeRadius Blur radius at the edges.
 * @param overlayColor Optional overlay color with alpha.
 * @param downsampleFactor Factor to downsample the captured content before blurring (1-16).
 * @param isLive Whether the blur updates in real-time. Set to false to save energy
 *               when the background is static. Default is true.
 * @param content The content to display on top of the blur effect.
 */
@Composable
fun RadialBlurSurface(
    modifier: Modifier = Modifier,
    centerRadius: Float = 0f,
    edgeRadius: Float = 30f,
    overlayColor: ComposeColor? = null,
    downsampleFactor: Float = 4f,
    isLive: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    VariableBlurSurface(
        modifier = modifier,
        gradient = BlurGradient.radialGradient(centerRadius, edgeRadius),
        overlayColor = overlayColor,
        downsampleFactor = downsampleFactor,
        isLive = isLive,
        content = content
    )
}
