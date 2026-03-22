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

/**
 * A composable that blurs whatever is behind it and renders [content] sharply on top.
 *
 * Unlike [BlurOverlayHost], this does not manage its own background. It acts as a
 * true backdrop blur — place it on top of any content in the view hierarchy, and it
 * blurs whatever happens to be behind it.
 *
 * Architecture per platform:
 * - **Android**: DecorView capture blurs everything drawn behind the BlurView's screen position.
 * - **iOS**: CABackdropLayer captures live window content below it at the GPU compositor level.
 *
 * Usage:
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     MyScene()  // this gets blurred
 *
 *     BlurOverlay(state = blurState) {
 *         Controls()  // this stays sharp on top
 *     }
 * }
 * ```
 *
 * @param state Controls blur configuration at runtime. Create via [rememberBlurOverlayState].
 * @param modifier Modifier applied to the blur overlay container.
 * @param content Composable drawn sharp on top of the blur effect.
 */
/**
 * Sentinel for detecting backdrop-blur mode (BlurOverlay) vs explicit-background mode
 * (BlurOverlayHost). On Android, RenderEffect can only blur the composable's own content,
 * not what's behind it, so backdrop-blur must use the DecorView capture pipeline.
 */
internal val EmptyBackground: @Composable () -> Unit = {}

@Composable
fun BlurOverlay(
    state: BlurOverlayState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = BlurOverlayHost(
    state = state,
    modifier = modifier,
    background = EmptyBackground,
    content = content,
)
