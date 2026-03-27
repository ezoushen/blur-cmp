package io.github.ezoushen.blur.cmp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSStringFromClass
import platform.Foundation.setValue
import platform.QuartzCore.CALayer
import platform.QuartzCore.CATransaction
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
 * Architecture:
 *   Box {
 *     background()            — rendered by Compose (Metal)
 *     UIKitView(blur layer)   — native CABackdropLayer captures Metal content below
 *     content()               — rendered by Compose on top of blur
 *   }
 *
 * All content stays in the same composition tree, preserving CompositionLocals,
 * resource bundles, and DI context.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun BlurOverlayHost(
    state: BlurOverlayState,
    modifier: Modifier,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!state.isEnabled) {
        Box(modifier = modifier) {
            background()
            content()
        }
        return
    }

    val blurState = remember { IosBlurState() }

    LaunchedEffect(state.config) {
        blurState.applyConfig(state.config)
    }

    LaunchedEffect(state.alpha) {
        blurState.applyAlpha(state.alpha)
    }

    Box(modifier = modifier) {
        background()

        UIKitView(
            factory = {
                blurState.createContainer(state.config)
            },
            modifier = Modifier.fillMaxSize(),
            update = { _ ->
                blurState.applyConfig(state.config)
                blurState.applyAlpha(state.alpha)
            },
            properties = UIKitInteropProperties(
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
                isNativeAccessibilityEnabled = false,
            ),
        )

        content()
    }
}

/**
 * Manages the native iOS blur layer hierarchy.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosBlurState {
    private var container: UIView? = null
    private var backdropLayer: CALayer? = null
    private var tintLayer: CALayer? = null
    private var preBlendBackdropView: UIView? = null
    private var preBlendTintLayer: CALayer? = null
    private val maskCache = IosGradientMaskCache()
    private var isBeforeBlurActive = false

    fun createContainer(initialConfig: BlurOverlayConfig): UIView {
        val cont = UIView()
        cont.backgroundColor = UIColor.clearColor
        cont.setOpaque(false)
        cont.setUserInteractionEnabled(false)

        val backdropView = extractBackdropView()
        if (backdropView != null) {
            backdropView.setAutoresizingMask(
                UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            )
            cont.addSubview(backdropView)
            backdropLayer = backdropView.layer

            val tint = CALayer()
            cont.layer.addSublayer(tint)
            tintLayer = tint
        }

        container = cont
        applyConfig(initialConfig)
        return cont
    }

    fun applyAlpha(alpha: Float) {
        container?.setAlpha(alpha.toDouble())
    }

    private fun extractBackdropView(): UIView? {
        val effectView = UIVisualEffectView(
            effect = UIBlurEffect.effectWithStyle(UIBlurEffectStyle.UIBlurEffectStyleLight)
        )
        for (i in 0 until effectView.subviews.count()) {
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

        // Resize layers to match container
        val bounds = container?.layer?.bounds
        if (bounds != null) {
            backdrop.superview?.setFrame(bounds)
            tintLayer?.setFrame(bounds)
        }

        val gradient = config.gradient
        if (gradient != null && IosBackdropLayerProvider.isValidForVariableBlur) {
            val mask = maskCache.getOrCreate(gradient)
            if (mask != null) {
                val filter = IosBackdropLayerProvider.createVariableBlurFilter(
                    radius = config.radius.toDouble(), maskImage = mask
                )
                if (filter != null) {
                    setFilterOnLayer(backdrop, filter)
                }
            }
        } else {
            val filter = IosBackdropLayerProvider.createGaussianBlurFilter(
                radius = config.radius.toDouble()
            )
            if (filter != null) {
                setFilterOnLayer(backdrop, filter)
            }
        }

        applyTint(config)
        CATransaction.commit()
    }

    private fun setFilterOnLayer(layer: CALayer, filter: platform.darwin.NSObject) {
        val arr = platform.Foundation.NSMutableArray()
        arr.addObject(filter)
        layer.setValue(arr, forKey = "filters")
    }

    private fun applyTint(config: BlurOverlayConfig) {
        val hasTint = config.tintColorValue != 0L

        if (hasTint && config.tintOrder == TintOrder.PRE_BLUR) {
            if (!isBeforeBlurActive) setupPreBlend()
            val color = argbToUIColor(config.tintColorValue)
            val filterName = IosBlendModeMapper.toCompositingFilterName(config.tintBlendMode)
            preBlendTintLayer?.backgroundColor = color?.CGColor
            preBlendTintLayer?.compositingFilter = filterName
            tintLayer?.backgroundColor = null
            tintLayer?.compositingFilter = null
        } else if (hasTint && config.tintOrder == TintOrder.POST_BLUR) {
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
        preBlendView.setAutoresizingMask(
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        )
        val zeroFilter = IosBackdropLayerProvider.createGaussianBlurFilter(radius = 0.0)
        if (zeroFilter != null) {
            val arr = platform.Foundation.NSMutableArray(capacity = 1u)
            arr.addObject(zeroFilter)
            preBlendView.layer.setValue(arr, forKey = "filters")
        }
        val tint = CALayer()
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
}

private val CALayer.superview: UIView?
    @OptIn(ExperimentalForeignApi::class)
    get() = delegate as? UIView
