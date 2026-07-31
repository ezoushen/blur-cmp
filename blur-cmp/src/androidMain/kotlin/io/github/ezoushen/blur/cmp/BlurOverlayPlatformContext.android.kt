package io.github.ezoushen.blur.cmp

import android.view.View
import android.view.Window

class AndroidBlurOverlayCaptureSource(
    val view: View,
    val window: Window,
)

actual class BlurOverlayPlatformContext(
    val captureSources: List<AndroidBlurOverlayCaptureSource> = emptyList(),
) {
    actual companion object {
        actual val Default = BlurOverlayPlatformContext()
    }
}
