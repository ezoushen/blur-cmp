package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A fullscreen blur overlay host. The blur layer renders beneath [content],
 * blurring everything behind it. [content] is drawn on top of the blur.
 *
 * This must be used at the top level of your screen/activity — it injects a
 * native platform view at the window level.
 *
 * @param state Controls blur configuration at runtime. Create via [rememberBlurOverlayState].
 * @param modifier Modifier applied to the content container (not the blur layer itself).
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
