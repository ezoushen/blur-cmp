package io.github.ezoushen.blur.compose

import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ezoushen.blur.BlurConfig
import io.github.ezoushen.blur.view.BlurView
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * A composable that provides a real-time blur effect on the content behind it,
 * similar to iOS's UIVisualEffectView.
 *
 * This composable uses the View-based [BlurView] internally via [AndroidView] to
 * provide true blur-behind functionality. Unlike [RenderEffect]-based approaches
 * which only blur the view's own content, this actually captures and blurs
 * what's behind it in the view hierarchy.
 *
 * **Works on all supported API levels (23+).**
 *
 * Usage:
 * ```kotlin
 * BlurSurface(
 *     modifier = Modifier.fillMaxWidth().height(100.dp),
 *     config = BlurConfig(radius = 16f, overlayColor = 0x80FFFFFF.toInt())
 * ) {
 *     Text("Content on blur surface")
 * }
 * ```
 *
 * @param modifier Modifier to be applied to the surface.
 * @param config The blur configuration to apply.
 * @param isLive Whether the blur updates in real-time. Set to false to save energy
 *               when the background is static. Default is true.
 * @param content The content to display on top of the blur effect.
 * @see BlurConfig
 * @see BlurView
 */
@Composable
fun BlurSurface(
    modifier: Modifier = Modifier,
    config: BlurConfig = BlurConfig.Default,
    isLive: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val blurView = remember { BlurView(context) }

    DisposableEffect(config, isLive) {
        blurView.setBlurConfig(config)
        blurView.setIsLive(isLive)
        onDispose { }
    }

    Box(modifier = modifier) {
        // AndroidView drawn first (behind) - matchParentSize doesn't affect Box sizing
        AndroidView(
            factory = { blurView },
            modifier = Modifier.matchParentSize(),
            update = { view ->
                view.setBlurConfig(config)
                view.setIsLive(isLive)
            }
        )

        content()
    }
}

/**
 * A composable that provides a blurred background effect with specified parameters.
 *
 * This composable uses the View-based [BlurView] internally via [AndroidView] to
 * provide true blur-behind functionality on all supported API levels (23+).
 *
 * @param modifier Modifier to be applied to the surface.
 * @param radius The blur radius in pixels.
 * @param overlayColor Optional overlay color with alpha.
 * @param downsampleFactor Factor to downsample the captured content before blurring (1-16).
 *                         Higher values improve performance but reduce quality. Default is 4.
 * @param isLive Whether the blur updates in real-time. Set to false to save energy
 *               when the background is static. Default is true.
 * @param content The content to display on top of the blur effect.
 */
@Composable
fun BlurSurface(
    modifier: Modifier = Modifier,
    radius: Float = 16f,
    overlayColor: ComposeColor? = null,
    downsampleFactor: Float = 4f,
    isLive: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val config = BlurConfig(
        radius = radius,
        overlayColor = overlayColor?.let {
            val alpha = (it.alpha * 255).toInt()
            val red = (it.red * 255).toInt()
            val green = (it.green * 255).toInt()
            val blue = (it.blue * 255).toInt()
            Color.argb(alpha, red, green, blue)
        },
        downsampleFactor = downsampleFactor.coerceIn(1f, 16f)
    )

    BlurSurface(
        modifier = modifier,
        config = config,
        isLive = isLive,
        content = content
    )
}
