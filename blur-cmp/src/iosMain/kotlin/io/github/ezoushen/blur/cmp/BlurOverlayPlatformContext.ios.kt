package io.github.ezoushen.blur.cmp

import platform.UIKit.UIWindow

actual class BlurOverlayPlatformContext(
    val contentWindow: UIWindow? = null,
) {
    actual companion object {
        actual val Default = BlurOverlayPlatformContext()
    }
}
