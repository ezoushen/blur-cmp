package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A composable that blurs its [background] and renders [content] sharply on top.
 *
 * Architecture per platform:
 * - **iOS**: Compose([background]) → UIKitView(CABackdropLayer) → Compose([content])
 *   The CABackdropLayer captures the Metal-rendered background and applies real-time blur.
 * - **Android**: Compose([background] + RenderEffect blur) → Compose([content])
 *   GPU-accelerated blur via graphicsLayer RenderEffect (API 31+).
 *
 * @param state Controls blur configuration at runtime. Create via [rememberBlurOverlayState].
 * @param modifier Modifier applied to the outer container.
 * @param background Composable to blur (e.g., animated scene, image, video).
 * @param content Composable drawn sharp on top of the blurred background (e.g., controls, text).
 */
@Composable
expect fun BlurOverlayHost(
    state: BlurOverlayState,
    modifier: Modifier,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
)

/**
 * Overload with default [Modifier] for convenience.
 */
@Composable
fun BlurOverlayHost(
    state: BlurOverlayState,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
) = BlurOverlayHost(state = state, modifier = Modifier, background = background, content = content)
