package io.github.ezoushen.blur.cmp

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLog
import platform.Foundation.NSStringFromClass
import platform.Foundation.setValue
import platform.Foundation.NSUUID
import platform.QuartzCore.CALayer
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIVisualEffectView
import platform.objc.object_getClass

/**
 * iOS BlurOverlayHost using native CABackdropLayer.
 *
 * Strategy: Extract _UIVisualEffectBackdropView from a temporary UIVisualEffectView
 * (the backdrop view contains a pre-configured UICABackdropLayer), then customize
 * its blur filter with our desired radius. This works because UIKit's own backdrop
 * view is fully initialized by the system, unlike manually instantiated CABackdropLayer.
 *
 * The blur view is added to rootViewController.view so CABackdropLayer can capture
 * CMP's Metal-rendered content below it.
 *
 * Touch interaction passes through the blur overlay (userInteractionEnabled=false),
 * so CMP controls underneath remain interactive.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
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

    // Add blur overlay to the rootVC view hierarchy
    DisposableEffect(Unit) {
        platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
            val window = UIApplication.sharedApplication.keyWindow
            val rootView = window?.rootViewController?.view
            if (rootView != null) {
                blurState.setupInView(rootView, config)
            }
        }

        onDispose {
            blurState.cleanup()
        }
    }

    LaunchedEffect(config) {
        blurState.applyConfig(config)
    }

    // Background renders into CMP's MetalView → captured by CABackdropLayer.
    // Content renders on top in the same MetalView.
    // Since CMP's MetalView is BELOW the blur overlay in the view hierarchy,
    // the blur effect covers the entire CMP content.
    // Content controls remain interactive because userInteractionEnabled=false
    // on the blur overlay passes touches through to CMP.
    //
    // Visual ordering (top to bottom):
    //   blur overlay (transparent, shows blurred content)
    //   CMP MetalView (renders background + content)
    //
    // The user sees blurred background with content text/buttons also blurred.
    // For a production version, content should be rendered in a separate layer
    // above the blur (e.g., via a second UIWindow or ComposeUIViewController child).
    // For now, we render everything through CMP and accept the full-screen blur.
    Box(modifier = modifier) {
        background()
        content()
    }
}

/**
 * Manages the native iOS blur layer hierarchy.
 * Uses extracted _UIVisualEffectBackdropView for reliable CABackdropLayer behavior.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosBlurState {
    var container: UIView? = null
    var backdropLayer: CALayer? = null
    var tintLayer: CALayer? = null
    var preBlendBackdropView: UIView? = null
    var preBlendTintLayer: CALayer? = null
    val maskCache = IosGradientMaskCache()
    var isBeforeBlurActive = false

    fun setupInView(parentView: UIView, initialConfig: BlurOverlayConfig) {
        // Create transparent container
        val cont = UIView(frame = parentView.bounds)
        cont.backgroundColor = UIColor.clearColor
        cont.setOpaque(false)
        cont.setUserInteractionEnabled(false)
        cont.setAutoresizingMask(
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        )

        // Extract backdrop view from UIVisualEffectView
        val backdropView = extractBackdropView()
        if (backdropView != null) {
            backdropView.setFrame(cont.bounds)
            backdropView.setAutoresizingMask(
                UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            )
            cont.addSubview(backdropView)
            backdropLayer = backdropView.layer

            val layerCls = NSStringFromClass(object_getClass(backdropView.layer)!!)
            NSLog("[IosBlurState] backdrop layer class: $layerCls")

            // Add tint layer on top
            val tint = CALayer()
            tint.setFrame(cont.layer.bounds)
            cont.layer.addSublayer(tint)
            tintLayer = tint
        } else {
            NSLog("[IosBlurState] Failed to extract backdrop view!")
        }

        parentView.addSubview(cont)
        container = cont

        applyConfig(initialConfig)
    }

    /**
     * Extract _UIVisualEffectBackdropView from a temporary UIVisualEffectView.
     * This gives us a fully initialized backdrop view with a working UICABackdropLayer.
     */
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

        // Apply blur filter
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

        // Don't override scale — the extracted backdrop view has its own default (0.25)
        // Setting scale here was causing the backdrop to stop rendering.

        // Apply tint
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
        // Extract another backdrop view for pre-blend
        val preBlendView = extractBackdropView() ?: return
        preBlendView.setFrame(cont.bounds)
        preBlendView.setAutoresizingMask(
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        )

        // Apply zero-radius blur (pass-through)
        val zeroFilter = IosBackdropLayerProvider.createGaussianBlurFilter(radius = 0.0)
        if (zeroFilter != null) {
            val arr = platform.Foundation.NSMutableArray()
            arr.addObject(zeroFilter)
            preBlendView.layer.setValue(arr, forKey = "filters")
        }
        preBlendView.layer.setValue(1.0, forKey = "scale")

        // Tint sublayer on the pre-blend backdrop
        val tint = CALayer()
        tint.setFrame(cont.layer.bounds)
        preBlendView.layer.addSublayer(tint)

        // Insert below the main blur
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
    }
}
