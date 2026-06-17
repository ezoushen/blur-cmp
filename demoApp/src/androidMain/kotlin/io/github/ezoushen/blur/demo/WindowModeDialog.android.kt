package io.github.ezoushen.blur.demo

/**
 * Window (separated) mode is an iOS-specific hosting path
 * ([io.github.ezoushen.blur.cmp.LocalBlurOverlayPlatformContext]). No-op on Android.
 */
actual fun presentBlurWindowDialog() {
    // no-op on Android
}

/** Window mode is iOS-only; hide the demo entry point on Android. */
actual val supportsWindowMode: Boolean = false
