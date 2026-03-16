package io.github.ezoushen.blur.cmp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLog
import platform.Foundation.NSStringFromClass
import platform.Foundation.setValue
import platform.QuartzCore.CALayer
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.UIVisualEffectView
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelNormal
import platform.objc.object_getClass

/**
 * iOS BlurOverlayHost using native CABackdropLayer.
 *
 * Architecture:
 *   CMP MetalView (renders background)
 *     ↓ captured by
 *   Blur overlay (CABackdropLayer on rootVC view)
 *     ↓ content on top via
 *   Content UIWindow (separate UIWindow with ComposeUIViewController)
 *
 * The blur overlay captures and blurs CMP's Metal-rendered background.
 * Content is rendered in a separate UIWindow above the blur so it stays sharp.
 * Touches pass through the content window's transparent areas to CMP underneath.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun BlurOverlayHost(
    state: BlurOverlayState,
    modifier: Modifier,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val config = state.config

    if (!state.isEnabled || config.radius <= 0f) {
        Box(modifier = modifier) {
            background()
            content()
        }
        return
    }

    val blurState = remember { IosBlurState() }

    // Store the content composable in a holder accessible by the content window
    val contentHolder = remember { ContentHolder() }
    contentHolder.content = content

    // Set up blur overlay and content window
    DisposableEffect(Unit) {
        platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
            val window = UIApplication.sharedApplication.keyWindow
            val rootView = window?.rootViewController?.view
            val windowScene = window?.windowScene

            if (rootView != null && windowScene != null) {
                // 1. Add blur overlay to rootVC view
                blurState.setupInView(rootView, config)

                // 2. Create content window above the blur
                val contentWindow = UIWindow(windowScene = windowScene)
                contentWindow.windowLevel = UIWindowLevelNormal + 1.0
                contentWindow.backgroundColor = UIColor.clearColor
                contentWindow.setOpaque(false)

                // Create a ComposeUIViewController for the content
                val contentVC = ComposeUIViewController(
                    configure = { opaque = false }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        contentHolder.content()
                    }
                }
                contentVC.view.backgroundColor = UIColor.clearColor
                contentVC.view.setOpaque(false)

                // Make the entire content window and its view hierarchy transparent
                contentWindow.rootViewController = contentVC
                contentWindow.makeKeyAndVisible()

                NSLog("[BlurOverlayHost] Content window created with opaque=false")

                blurState.contentWindow = contentWindow

                NSLog("[BlurOverlayHost] Setup complete: blur overlay + content window")
            }
        }

        onDispose {
            blurState.cleanup()
        }
    }

    LaunchedEffect(config) {
        blurState.applyConfig(config)
    }

    // Only render background in the main CMP tree.
    // Content is rendered in the separate content window above the blur.
    Box(modifier = modifier) {
        background()
    }
}

/**
 * Holds a reference to the content composable for cross-window rendering.
 */
private class ContentHolder {
    var content: @Composable () -> Unit by mutableStateOf({})
}

/**
 * Manages the native iOS blur layer hierarchy.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosBlurState {
    var container: UIView? = null
    var backdropLayer: CALayer? = null
    var tintLayer: CALayer? = null
    var preBlendBackdropView: UIView? = null
    var preBlendTintLayer: CALayer? = null
    var contentWindow: UIWindow? = null
    val maskCache = IosGradientMaskCache()
    var isBeforeBlurActive = false

    fun setupInView(parentView: UIView, initialConfig: BlurOverlayConfig) {
        val cont = UIView(frame = parentView.bounds)
        cont.backgroundColor = UIColor.clearColor
        cont.setOpaque(false)
        cont.setUserInteractionEnabled(false)
        cont.setAutoresizingMask(
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        )

        val backdropView = extractBackdropView()
        if (backdropView != null) {
            backdropView.setFrame(cont.bounds)
            backdropView.setAutoresizingMask(
                UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            )
            cont.addSubview(backdropView)
            backdropLayer = backdropView.layer

            val tint = CALayer()
            tint.setFrame(cont.layer.bounds)
            cont.layer.addSublayer(tint)
            tintLayer = tint
        }

        parentView.addSubview(cont)
        container = cont

        applyConfig(initialConfig)
    }

    private fun extractBackdropView(): UIView? {
        val effectView = UIVisualEffectView(
            effect = UIBlurEffect.effectWithStyle(UIBlurEffectStyle.UIBlurEffectStyleLight)
        )
        for (i in 0 until effectView.subviews.count().toInt()) {
            @Suppress("UNCHECKED_CAST")
            val sv = effectView.subviews[i] as? UIView ?: continue
            val cls = NSStringFromClass(object_getClass(sv)!!)
            if (cls.contains("BackdropView")) {
                sv.removeFromSuperview()
                return sv
            }
        }
        return null
    }

    fun applyConfig(config: BlurOverlayConfig) {
        val backdrop = backdropLayer ?: return

        CATransaction.begin()
        CATransaction.setDisableActions(true)

        val gradient = config.gradient
        if (gradient != null && IosBackdropLayerProvider.isValidForVariableBlur) {
            val mask = maskCache.getOrCreate(gradient)
            if (mask != null) {
                val filter = IosBackdropLayerProvider.createVariableBlurFilter(
                    radius = config.radius.toDouble(), maskImage = mask
                )
                if (filter != null) {
                    val arr = platform.Foundation.NSMutableArray()
                    arr.addObject(filter)
                    backdrop.setValue(arr, forKey = "filters")
                }
            }
        } else {
            val filter = IosBackdropLayerProvider.createGaussianBlurFilter(
                radius = config.radius.toDouble()
            )
            if (filter != null) {
                val arr = platform.Foundation.NSMutableArray()
                arr.addObject(filter)
                backdrop.setValue(arr, forKey = "filters")
            }
        }

        // Don't override scale — the extracted backdrop view has its own default
        applyTint(config)

        CATransaction.commit()
    }

    private fun applyTint(config: BlurOverlayConfig) {
        val hasTint = config.tintColorValue != 0L
        val isNonNormalBlend = config.tintBlendMode != BlurBlendMode.Normal

        if (hasTint && isNonNormalBlend) {
            if (!isBeforeBlurActive) setupPreBlend()
            val color = argbToUIColor(config.tintColorValue)
            val filterName = IosBlendModeMapper.toCompositingFilterName(config.tintBlendMode)
            preBlendTintLayer?.backgroundColor = color?.CGColor
            preBlendTintLayer?.compositingFilter = filterName
            tintLayer?.backgroundColor = null
            tintLayer?.compositingFilter = null
        } else if (hasTint) {
            teardownPreBlend()
            val color = argbToUIColor(config.tintColorValue)
            val filterName = IosBlendModeMapper.toCompositingFilterName(config.tintBlendMode)
            tintLayer?.backgroundColor = color?.CGColor
            tintLayer?.compositingFilter = filterName
        } else {
            teardownPreBlend()
            tintLayer?.backgroundColor = null
            tintLayer?.compositingFilter = null
        }
    }

    private fun setupPreBlend() {
        val cont = container ?: return
        val preBlendView = extractBackdropView() ?: return
        preBlendView.setFrame(cont.bounds)
        preBlendView.setAutoresizingMask(
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        )
        val zeroFilter = IosBackdropLayerProvider.createGaussianBlurFilter(radius = 0.0)
        if (zeroFilter != null) {
            val arr = platform.Foundation.NSMutableArray()
            arr.addObject(zeroFilter)
            preBlendView.layer.setValue(arr, forKey = "filters")
        }
        val tint = CALayer()
        tint.setFrame(cont.layer.bounds)
        preBlendView.layer.addSublayer(tint)
        cont.insertSubview(preBlendView, atIndex = 0)
        preBlendBackdropView = preBlendView
        preBlendTintLayer = tint
        isBeforeBlurActive = true
    }

    private fun teardownPreBlend() {
        preBlendBackdropView?.removeFromSuperview()
        preBlendBackdropView = null
        preBlendTintLayer = null
        isBeforeBlurActive = false
    }

    private fun argbToUIColor(argb: Long): UIColor? {
        if (argb == 0L) return null
        val v = argb.toInt()
        return UIColor(
            red = ((v ushr 16) and 0xFF) / 255.0,
            green = ((v ushr 8) and 0xFF) / 255.0,
            blue = (v and 0xFF) / 255.0,
            alpha = ((v ushr 24) and 0xFF) / 255.0,
        )
    }

    fun cleanup() {
        maskCache.release()
        teardownPreBlend()
        container?.removeFromSuperview()
        container = null
        backdropLayer = null
        tintLayer = null
        contentWindow?.setHidden(true)
        contentWindow = null
    }
}
