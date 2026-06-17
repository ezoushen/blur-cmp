package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager

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

/**
 * Receiver scope for [BlurOverlay] content.
 *
 * On Android the overlay's content is hosted in a nested compose view — a separate focus/input
 * root from the host window — so it does not share the host's focus/IME lifecycle. When the overlay
 * is dismissed by removing it from composition, a field inside the content that still holds focus
 * has its input session torn down only after the root is already detached, which can leave the soft
 * keyboard on screen without an input connection.
 *
 * [dismiss] closes that gap: it releases focus from the hosted content *first* — while the content
 * is still composed and attached — so Compose runs the normal focus/input teardown (hiding the
 * keyboard) before the caller tears the overlay down. Route any user-initiated dismissal (scrim
 * tap, close button) through it. Content that never takes focus can ignore this scope entirely;
 * clearing focus on a subtree that holds none is a no-op.
 */
@Stable
interface BlurOverlayScope {
    /**
     * Releases focus from the hosted content, then invokes [andThen] (typically the caller's
     * dismissal — popping the overlay, finishing, etc.).
     */
    fun dismiss(andThen: () -> Unit)
}

private class BlurOverlayScopeImpl(
    private val focusManager: FocusManager,
) : BlurOverlayScope {
    override fun dismiss(andThen: () -> Unit) {
        // Clear focus while the content is still composed/attached so Compose's own focus-out →
        // input-session teardown runs through its standard path (which hides the IME). This is
        // generic focus management — no reference to the input-method service, no assumption about
        // what the content is.
        focusManager.clearFocus(force = true)
        andThen()
    }
}

/**
 * Backdrop blur overlay.
 *
 * When [onDismissRequest] is `null` the overlay renders inline in the
 * current Compose tree — the caller is responsible for hosting it in a
 * surface that gives the backdrop a stable region to blur (typically the
 * activity / view-controller's root). When [onDismissRequest] is non-null
 * the overlay is hosted in a [BackdropBlurDialog] automatically: on
 * Android that promotes it into a transparent edge-to-edge `Dialog`
 * Window — which is required for the backdrop blur to extend under status
 * + navigation bars and to avoid the cold-mount black frame produced by
 * adding a fresh overlay window underneath; on iOS the wrapper is a
 * passthrough because the native modal presentation chain already
 * supplies an equivalent surface.
 *
 * Pass [onDismissRequest] whenever the overlay represents a stand-alone
 * presentation (menu, sheet, fullscreen blur backdrop); omit it when the
 * overlay is composed inline as one element of a larger compose tree.
 *
 * [content] runs with a [BlurOverlayScope] receiver; call [BlurOverlayScope.dismiss] for
 * user-initiated dismissals so a focused field's keyboard is torn down cleanly (see
 * [BlurOverlayScope]). Content that never takes focus can ignore the receiver.
 */
@Composable
fun BlurOverlay(
    state: BlurOverlayState,
    modifier: Modifier = Modifier,
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable BlurOverlayScope.() -> Unit,
) {
    // Build the scope from the focus manager of the composition that actually hosts [content]
    // (the nested compose root on Android), so dismiss() clears focus in the right root.
    val hosted: @Composable () -> Unit = {
        val focusManager = LocalFocusManager.current
        val scope = remember(focusManager) { BlurOverlayScopeImpl(focusManager) }
        scope.content()
    }
    if (onDismissRequest != null) {
        BackdropBlurDialog(onDismissRequest = onDismissRequest) {
            BlurOverlayHost(
                state = state,
                modifier = modifier,
                background = EmptyBackground,
                content = hosted,
            )
        }
    } else {
        BlurOverlayHost(
            state = state,
            modifier = modifier,
            background = EmptyBackground,
            content = hosted,
        )
    }
}
