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
