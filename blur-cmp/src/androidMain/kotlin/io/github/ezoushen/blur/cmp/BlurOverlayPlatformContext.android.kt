package io.github.ezoushen.blur.cmp

import android.view.View
import android.view.Window

/** One Android view hierarchy and its owning window to include in backdrop capture. */
class AndroidBlurOverlayCaptureSource(
    val view: View,
    val window: Window,
)

/**
 * Android backdrop capture context.
 *
 * @param captureSources window sources ordered from back to front. An empty list captures the
 * hosting activity's decor view.
 */
actual class BlurOverlayPlatformContext(
    val captureSources: List<AndroidBlurOverlayCaptureSource> = emptyList(),
) {
    actual companion object {
        actual val Default = BlurOverlayPlatformContext()
    }
}
