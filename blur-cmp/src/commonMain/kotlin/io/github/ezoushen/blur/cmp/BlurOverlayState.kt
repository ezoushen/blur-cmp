package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Mutable state holder for controlling a blur overlay at runtime.
 * Create via [rememberBlurOverlayState].
 */
@Stable
class BlurOverlayState internal constructor(initialConfig: BlurOverlayConfig) {
    var config: BlurOverlayConfig by mutableStateOf(initialConfig)

    var isEnabled: Boolean by mutableStateOf(true)

    var alpha: Float by mutableStateOf(1f)

    /**
     * Whether the platform renderer has produced a displayable blur frame.
     *
     * Callers that own a separate overlay window can keep that window transparent until this
     * becomes true, avoiding an uninitialized surface flash without guessing a frame count.
     */
    var isReady: Boolean by mutableStateOf(false)
        internal set

    /**
     * Whether the overlay's hosting layer intercepts touches. Matters on iOS integrated mode,
     * where the overlay is a native full-screen container (backdrop + nested content VC) above
     * the app's Compose surface: while it exists, UIKit routes every touch to it, so content
     * beneath is unreachable even when the overlay is only animating out. Set `false` to let
     * touches pass through to the surface beneath — typically for the overlay's exit window,
     * so the revealed screen is interactive the moment the dismissal starts rather than after
     * the native teardown. In-tree hosts (Android, iOS injected-window content) render inline
     * and don't intercept beyond their own hit-testable content, so this is a no-op there.
     */
    var isInteractionEnabled: Boolean by mutableStateOf(true)

    /** Convenience: update radius only. */
    fun setRadius(radius: Float) {
        config = config.copy(radius = radius)
    }

    /** Convenience: update tint only. */
    fun setTintColor(color: androidx.compose.ui.graphics.Color?) {
        config = config.withTint(color)
    }

    /** Convenience: update gradient only. */
    fun setGradient(gradient: BlurGradientType?) {
        config = config.copy(gradient = gradient)
    }

    /** Force a single blur update (useful when isLive = false). */
    var updateTrigger: Long by mutableStateOf(0L)
        private set

    fun requestUpdate() {
        updateTrigger++
    }
}

@Composable
fun rememberBlurOverlayState(
    initialConfig: BlurOverlayConfig = BlurOverlayConfig.Default,
): BlurOverlayState = remember { BlurOverlayState(initialConfig) }
