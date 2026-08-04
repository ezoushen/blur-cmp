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
 * @property captureSources window sources ordered from back to front. An empty list captures the
 * hosting activity's decor view.
 * @property contentWindow window hosting the current overlay when it cannot be discovered from
 * [androidx.compose.ui.platform.LocalView], such as a caller-owned [android.app.Dialog].
 */
actual class BlurOverlayPlatformContext {
    val captureSources: List<AndroidBlurOverlayCaptureSource>
    val contentWindow: Window?

    constructor() : this(emptyList())

    constructor(captureSources: List<AndroidBlurOverlayCaptureSource> = emptyList()) {
        this.captureSources = captureSources
        contentWindow = null
    }

    constructor(
        captureSources: List<AndroidBlurOverlayCaptureSource> = emptyList(),
        contentWindow: Window,
    ) {
        this.captureSources = captureSources
        this.contentWindow = contentWindow
    }

    actual companion object {
        actual val Default = BlurOverlayPlatformContext()
    }
}
