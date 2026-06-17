package io.github.ezoushen.blur.demo

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import io.github.ezoushen.blur.cmp.BlurOverlayPlatformContext
import io.github.ezoushen.blur.cmp.LocalBlurOverlayPlatformContext
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelAlert
import platform.UIKit.UIWindowScene
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController

private var dialogWindow: UIWindow? = null

actual val supportsWindowMode: Boolean = true

/**
 * Mirrors stforestkit's `IosDialogManager.ensureDialogWindow`: spin up a single
 * Alert-level [UIWindow], host a [ComposeUIViewController] that injects
 * [LocalBlurOverlayPlatformContext] with `contentWindow = window`, and render the
 * dialog inline. blur-cmp's injected-window path adds the backdrop to this window.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
actual fun presentBlurWindowDialog() {
    if (dialogWindow != null) return
    val scene = findActiveWindowScene() ?: return

    val window = UIWindow(windowScene = scene)
    window.windowLevel = UIWindowLevelAlert
    window.backgroundColor = UIColor.clearColor
    window.setOpaque(false)

    val containerVC = UIViewController(nibName = null, bundle = null)
    containerVC.view.backgroundColor = UIColor.clearColor
    containerVC.view.setOpaque(false)

    val composeVC = ComposeUIViewController(configure = { opaque = false }) {
        CompositionLocalProvider(
            LocalBlurOverlayPlatformContext provides BlurOverlayPlatformContext(contentWindow = window)
        ) {
            WindowModeDialogContent(onClose = { tearDownDialog() })
        }
    }
    composeVC.view.backgroundColor = UIColor.clearColor
    composeVC.view.setOpaque(false)
    composeVC.view.setAutoresizingMask(
        UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
    )

    containerVC.addChildViewController(composeVC)
    containerVC.view.addSubview(composeVC.view)
    composeVC.didMoveToParentViewController(containerVC)

    window.rootViewController = containerVC
    window.setHidden(false)
    dialogWindow = window
}

private fun tearDownDialog() {
    dialogWindow?.rootViewController = null
    dialogWindow?.setHidden(true)
    dialogWindow = null
}

@OptIn(ExperimentalForeignApi::class)
private fun findActiveWindowScene(): UIWindowScene? {
    for (scene in UIApplication.sharedApplication.connectedScenes) {
        val windowScene = scene as? UIWindowScene ?: continue
        if (windowScene.keyWindow != null) return windowScene
    }
    return null
}
