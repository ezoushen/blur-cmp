package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A blur overlay host. The blur layer renders beneath [content] inside a [Box],
 * blurring everything behind it in the view hierarchy. [content] is drawn on top
 * of the blur.
 *
 * The native blur view is embedded inline in the Compose tree via [AndroidView] /
 * [UIKitView], so content behind this composable in the z-order will be blurred.
 *
 * @param state Controls blur configuration at runtime. Create via [rememberBlurOverlayState].
 * @param modifier Modifier applied to the outer [Box] container.
 * @param content Compose UI drawn on top of the blurred background.
 */
@Composable
expect fun BlurOverlayHost(
    state: BlurOverlayState,
    modifier: Modifier,
    content: @Composable () -> Unit,
)

/**
 * Overload with default [Modifier] for convenience.
 */
@Composable
fun BlurOverlayHost(
    state: BlurOverlayState,
    content: @Composable () -> Unit,
) = BlurOverlayHost(state = state, modifier = Modifier, content = content)
