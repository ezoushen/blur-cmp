package io.github.ezoushen.blur.demo

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * Presents an opaque full-screen [NativeChildScreen] modally from the **app
 * window's** root VC — exactly the path a coordinator uses to present a child
 * screen while a [io.github.ezoushen.blur.cmp.BlurOverlay] stays mounted underneath.
 *
 * Resolving the app (lowest `windowLevel`) window — not `keyWindow` — is what
 * makes this a faithful repro: the old hybrid overlay lives in a `.normal + 1`
 * window and also becomes `keyWindow`, so presenting from `keyWindow` would
 * accidentally present from the overlay's own window and mask the bug.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun presentNativeChildScreen() {
    val presenter = appWindowTopmostViewController() ?: return

    lateinit var childVC: UIViewController
    childVC = ComposeUIViewController {
        NativeChildScreen(onClose = {
            childVC.dismissModalViewControllerAnimated(true)
        })
    }
    childVC.setModalPresentationStyle(UIModalPresentationFullScreen)
    presenter.presentViewController(childVC, animated = true, completion = null)
}

@OptIn(ExperimentalForeignApi::class)
private fun appWindowTopmostViewController(): UIViewController? {
    var appWindow: UIWindow? = null
    for (scene in UIApplication.sharedApplication.connectedScenes) {
        val windowScene = scene as? UIWindowScene ?: continue
        for (w in windowScene.windows) {
            val window = w as? UIWindow ?: continue
            if (window.rootViewController == null) continue
            val best = appWindow
            if (best == null || window.windowLevel < best.windowLevel) {
                appWindow = window
            }
        }
    }
    var vc = appWindow?.rootViewController
    while (vc?.presentedViewController != null) {
        vc = vc.presentedViewController
    }
    return vc
}
