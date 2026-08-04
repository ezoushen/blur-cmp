package io.github.ezoushen.blur.cmp

import platform.UIKit.UIWindow

/**
 * iOS backdrop capture context.
 *
 * @param contentWindow caller-owned overlay window whose root hosts the backdrop. `null` uses the
 * active app window and the integrated host.
 */
actual class BlurOverlayPlatformContext(
    val contentWindow: UIWindow? = null,
) {
    actual companion object {
        actual val Default = BlurOverlayPlatformContext()
    }
}
