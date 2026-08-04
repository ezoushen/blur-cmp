package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.staticCompositionLocalOf

/** Platform-owned context for capturing backdrops hosted outside the current composition. */
expect class BlurOverlayPlatformContext {
    companion object {
        val Default: BlurOverlayPlatformContext
    }
}

/** Supplies platform capture context to [BlurOverlay] and [BlurOverlayHost]. */
val LocalBlurOverlayPlatformContext = staticCompositionLocalOf {
    BlurOverlayPlatformContext.Default
}
