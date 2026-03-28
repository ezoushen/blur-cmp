package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.staticCompositionLocalOf

expect class BlurOverlayPlatformContext {
    companion object {
        val Default: BlurOverlayPlatformContext
    }
}

val LocalBlurOverlayPlatformContext = staticCompositionLocalOf {
    BlurOverlayPlatformContext.Default
}
