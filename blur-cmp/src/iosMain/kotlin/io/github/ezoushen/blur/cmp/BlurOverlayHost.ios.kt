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
import platform.UIKit.UIVisualEffectView
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelNormal
import platform.UIKit.UIWindowScene
import platform.objc.object_getClass

/**
 * iOS BlurOverlayHost using native CABackdropLayer.
 *
 * Two modes based on [LocalBlurOverlayPlatformContext]:
 *
 * **Default (no injected window):**
 *   CMP MetalView (renders background)
 *     -> blur overlay (CABackdropLayer on rootVC view)
 *     -> content via new UIWindow (ComposeUIViewController, opaque=false)
 *
 * **Injected window (contentWindow provided):**
 *   The caller owns a UIWindow above the app window.
 *   blur-cmp adds the blur container to that window's rootVC view at index 0
 *   and renders content inline in the current composition.
 *   No additional UIWindow is created.
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

    if (!state.isEnabled) {
        Box(modifier = modifier) {
            background()
            content()
        }
        return
    }

    val platformContext = LocalBlurOverlayPlatformContext.current
    val injectedWindow = platformContext.contentWindow

    if (injectedWindow != null) {
        InjectedWindowBlurOverlay(state, modifier, background, content, injectedWindow)
    } else {
        DefaultBlurOverlay(state, modifier, background, content)
    }
}

/**
 * Injected-window path: adds blur container to the provided window's root VC view.
 * Content renders inline in the caller's composition — same window, same context.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
private fun InjectedWindowBlurOverlay(
    state: BlurOverlayState,
    modifier: Modifier,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
    window: UIWindow,
) {
    val config = state.config
    val blurState = remember { IosBlurState() }

    DisposableEffect(Unit) {
        val rootView = window.rootViewController?.view
        if (rootView != null) {
            blurState.setupAsBackdrop(rootView, config)
        }
        onDispose {
            blurState.cleanupBackdrop()
        }
    }

    LaunchedEffect(config) {
        blurState.applyConfig(config)
    }

    LaunchedEffect(state.alpha) {
        blurState.applyAlpha(state.alpha)
    }

    Box(modifier = modifier) {
        background()
        content()
    }
}

/**
 * Default path: creates its own UIWindow for content (original behavior).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DefaultBlurOverlay(
    state: BlurOverlayState,
    modifier: Modifier,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val config = state.config
    val blurState = remember { IosBlurState() }

    val contentHolder = remember { ContentHolder() }
    contentHolder.content = content

    DisposableEffect(Unit) {
        val windowScene = findActiveWindowScene()
        val rootView = windowScene?.keyWindow?.rootViewController?.view

        if (rootView != null && windowScene != null) {
            blurState.setupInView(rootView, config)

            val contentWindow = UIWindow(windowScene = windowScene)
            contentWindow.windowLevel = UIWindowLevelNormal + 1.0
            contentWindow.backgroundColor = UIColor.clearColor
            contentWindow.setOpaque(false)

            val contentVC = ComposeUIViewController(
                configure = { opaque = false }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    contentHolder.content()
                }
            }
            contentVC.view.backgroundColor = UIColor.clearColor
            contentVC.view.setOpaque(false)

            contentWindow.rootViewController = contentVC
            contentWindow.makeKeyAndVisible()

            blurState.contentWindow = contentWindow
        }

        onDispose {
            blurState.cleanup()
        }
    }

    LaunchedEffect(config) {
        blurState.applyConfig(config)
    }

    LaunchedEffect(state.alpha) {
        blurState.applyAlpha(state.alpha)
    }

    Box(modifier = modifier) {
        background()
    }
}

private fun findActiveWindowScene(): UIWindowScene? {
    val scenes = UIApplication.sharedApplication.connectedScenes
    for (scene in scenes) {
        val windowScene = scene as? UIWindowScene ?: continue
        if (windowScene.keyWindow != null) return windowScene
    }
    return null
}

private class ContentHolder {
    var content: @Composable () -> Unit by mutableStateOf({})
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

    var contentWindow: UIWindow? = null

    fun applyAlpha(alpha: Float) {
        container?.setAlpha(alpha.toDouble())
    }

    /**
     * Injected-window mode: adds blur container at index 0 of the target view
     * (beneath the ComposeVC's Metal view).
     */
    fun setupAsBackdrop(targetView: UIView, initialConfig: BlurOverlayConfig) {
        val cont = UIView(frame = targetView.bounds)
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

        targetView.insertSubview(cont, atIndex = 0)
        container = cont

        applyConfig(initialConfig)
    }

    fun cleanupBackdrop() {
        maskCache.release()
        teardownPreBlend()
        container?.removeFromSuperview()
        container = null
        backdropLayer = null
        tintLayer = null
    }

    /**
     * Default mode: adds blur container as a top-level subview of the parent.
     */
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
        preBlendView.setFrame(cont.bounds)
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
        cleanupBackdrop()
        contentWindow?.rootViewController = null
        contentWindow?.setHidden(true)
        contentWindow = null
    }
}
