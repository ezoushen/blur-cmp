package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSUUID
import platform.Foundation.setValue
import platform.QuartzCore.CALayer
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCAGravityResizeAspectFill
import platform.UIKit.UIApplication
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIVisualEffectView
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UISceneActivationStateForegroundActive

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun BlurOverlayHost(
    state: BlurOverlayState,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val currentState by rememberUpdatedState(state)

    DisposableEffect(Unit) {
        val window = getKeyWindow() ?: run {
            return@DisposableEffect onDispose { }
        }

        val blurView = createNativeBlurView(currentState.config)
        blurView.tag = BLUR_VIEW_TAG
        blurView.setFrame(window.bounds)
        blurView.autoresizingMask =
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight

        window.insertSubview(blurView, atIndex = 0)

        onDispose {
            blurView.removeFromSuperview()
            (blurView as? RealTimeBlurContainerView)?.release()
        }
    }

    // React to config changes
    LaunchedEffect(Unit) {
        snapshotFlow { currentState.config }
            .distinctUntilChanged()
            .collectLatest { config ->
                val window = getKeyWindow() ?: return@collectLatest
                val blurView = findBlurView(window) ?: return@collectLatest
                updateNativeBlurView(blurView, config)
            }
    }

    // React to enabled state
    LaunchedEffect(Unit) {
        snapshotFlow { currentState.isEnabled }
            .distinctUntilChanged()
            .collectLatest { enabled ->
                val window = getKeyWindow() ?: return@collectLatest
                val blurView = findBlurView(window) ?: return@collectLatest
                blurView.setHidden(!enabled)
            }
    }

    content()
}

private const val BLUR_VIEW_TAG: Long = 0x426C7572L

// ---------------------------------------------------------------------------
// Native blur view using CABackdropLayer (continuous radius, variable blur,
// all 12 blend modes). Falls back to UIVisualEffectView if extraction fails.
// ---------------------------------------------------------------------------

/**
 * Container UIView that manages the CABackdropLayer + tint layer hierarchy.
 */
@OptIn(ExperimentalForeignApi::class)
private class RealTimeBlurContainerView(
    frame: kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>,
) : UIView(frame) {

    var backdropLayer: CALayer? = null
    var tintLayer: CALayer? = null
    var gradientMaskCache: IosGradientMaskCache? = null
    var isVariableBlur: Boolean = false
    var isFallback: Boolean = false

    fun release() {
        gradientMaskCache?.release()
        gradientMaskCache = null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createNativeBlurView(config: BlurOverlayConfig): UIView {
    val isVariableBlur = config.gradient != null

    val canUseBackdrop = if (isVariableBlur) {
        IosBackdropLayerProvider.isValidForVariableBlur
    } else {
        IosBackdropLayerProvider.isValidForUniformBlur
    }

    if (canUseBackdrop) {
        val backdrop = IosBackdropLayerProvider.createBackdropLayer()
        if (backdrop != null) {
            return createBackdropBlurView(config, backdrop, isVariableBlur)
        }
    }

    return createFallbackBlurView(config)
}

@OptIn(ExperimentalForeignApi::class)
private fun createBackdropBlurView(
    config: BlurOverlayConfig,
    backdrop: CALayer,
    isVariableBlur: Boolean,
): UIView {
    val container = RealTimeBlurContainerView(CGRectZero.readValue())
    container.backgroundColor = null
    container.isVariableBlur = isVariableBlur

    // Configure the backdrop layer via KVC
    backdrop.setValue(NSUUID().UUIDString, forKey = "groupName")
    backdrop.setValue(true, forKey = "allowsInPlaceFiltering")
    container.layer.addSublayer(backdrop)
    container.backdropLayer = backdrop

    // Create tint layer on top of the backdrop
    val tintLayer = CALayer()
    container.layer.addSublayer(tintLayer)
    container.tintLayer = tintLayer

    if (isVariableBlur) {
        container.gradientMaskCache = IosGradientMaskCache()
    }

    applyConfigToBackdropView(container, config)

    return container
}

@OptIn(ExperimentalForeignApi::class)
private fun createFallbackBlurView(config: BlurOverlayConfig): UIView {
    val container = RealTimeBlurContainerView(CGRectZero.readValue())
    container.isFallback = true

    val blurStyle = when {
        config.radius <= 5f -> UIBlurEffectStyle.UIBlurEffectStyleSystemUltraThinMaterial
        config.radius <= 15f -> UIBlurEffectStyle.UIBlurEffectStyleSystemThinMaterial
        config.radius <= 30f -> UIBlurEffectStyle.UIBlurEffectStyleSystemMaterial
        else -> UIBlurEffectStyle.UIBlurEffectStyleSystemThickMaterial
    }

    val blurEffect = UIBlurEffect.effectWithStyle(blurStyle)
    val effectView = UIVisualEffectView(effect = blurEffect)
    effectView.autoresizingMask =
        UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
    container.addSubview(effectView)

    if (config.tintColorValue != 0L) {
        val tintView = UIView(frame = CGRectZero.readValue())
        tintView.backgroundColor = uiColorFromPackedValue(config.tintColorValue)
        tintView.autoresizingMask =
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        effectView.contentView.addSubview(tintView)
    }

    return container
}

/**
 * Apply blur, tint, blend mode, and gradient to a backdrop-based blur view.
 */
@OptIn(ExperimentalForeignApi::class)
private fun applyConfigToBackdropView(
    container: RealTimeBlurContainerView,
    config: BlurOverlayConfig,
) {
    val backdrop = container.backdropLayer ?: return
    val tintLayer = container.tintLayer ?: return

    CATransaction.begin()
    CATransaction.setDisableActions(true)

    val bounds = container.bounds
    backdrop.frame = bounds
    tintLayer.frame = bounds

    val radius = config.radius.toDouble()
    if (config.gradient != null && container.isVariableBlur) {
        // Variable blur with gradient mask
        val maskCache = container.gradientMaskCache ?: IosGradientMaskCache().also {
            container.gradientMaskCache = it
        }
        val mask = maskCache.getOrCreate(config.gradient)
        if (mask != null) {
            val filter = IosBackdropLayerProvider.createVariableBlurFilter(radius, mask)
            if (filter != null) {
                backdrop.setValue(listOf(filter), forKey = "filters")
            }

            if (config.tintColorValue != 0L) {
                tintLayer.backgroundColor = uiColorFromPackedValue(config.tintColorValue)?.CGColor
                val tintMaskLayer = CALayer()
                tintMaskLayer.frame = bounds
                tintMaskLayer.contents = mask
                tintMaskLayer.contentsGravity = kCAGravityResizeAspectFill
                tintLayer.mask = tintMaskLayer
            } else {
                tintLayer.backgroundColor = null
                tintLayer.mask = null
            }
        }
    } else {
        // Uniform blur
        val filter = IosBackdropLayerProvider.createGaussianBlurFilter(radius)
        if (filter != null) {
            backdrop.setValue(listOf(filter), forKey = "filters")
        }

        if (config.tintColorValue != 0L) {
            tintLayer.backgroundColor = uiColorFromPackedValue(config.tintColorValue)?.CGColor
            tintLayer.mask = null
        } else {
            tintLayer.backgroundColor = null
            tintLayer.mask = null
        }
    }

    // Scale factor: lower = better performance for uniform blur
    val scale = if (config.gradient == null) {
        (1.0 / config.downsampleFactor).coerceIn(0.05, 1.0)
    } else {
        1.0
    }
    backdrop.setValue(scale, forKey = "scale")

    // Apply blend mode to tint layer via compositingFilter
    val filterName = IosBlendModeMapper.toCompositingFilterName(config.tintBlendMode)
    tintLayer.compositingFilter = filterName

    CATransaction.commit()
}

/**
 * Update an existing blur view with new configuration.
 */
@OptIn(ExperimentalForeignApi::class)
private fun updateNativeBlurView(blurView: UIView, config: BlurOverlayConfig) {
    val container = blurView as? RealTimeBlurContainerView

    if (container == null || container.isFallback) {
        // Fallback: replace entirely
        val window = blurView.superview as? UIWindow ?: return
        blurView.removeFromSuperview()
        container?.release()

        val newView = createNativeBlurView(config)
        newView.tag = BLUR_VIEW_TAG
        newView.setFrame(window.bounds)
        newView.autoresizingMask =
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        window.insertSubview(newView, atIndex = 0)
        return
    }

    // Check if blur mode changed (uniform <-> variable)
    val needsVariableBlur = config.gradient != null
    if (needsVariableBlur != container.isVariableBlur) {
        val window = blurView.superview as? UIWindow ?: return
        blurView.removeFromSuperview()
        container.release()

        val newView = createNativeBlurView(config)
        newView.tag = BLUR_VIEW_TAG
        newView.setFrame(window.bounds)
        newView.autoresizingMask =
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        window.insertSubview(newView, atIndex = 0)
        return
    }

    // Update in-place
    applyConfigToBackdropView(container, config)
}

private fun findBlurView(window: UIWindow): UIView? {
    val subviews = window.subviews
    for (i in 0 until subviews.count().toInt()) {
        @Suppress("UNCHECKED_CAST")
        val subview = subviews[i] as? UIView ?: continue
        if (subview.tag == BLUR_VIEW_TAG) return subview
    }
    return null
}

private fun uiColorFromPackedValue(packedValue: Long): UIColor? {
    if (packedValue == 0L) return null
    val argb = packedValue.toInt()
    val alpha = ((argb ushr 24) and 0xFF) / 255.0
    val red = ((argb ushr 16) and 0xFF) / 255.0
    val green = ((argb ushr 8) and 0xFF) / 255.0
    val blue = (argb and 0xFF) / 255.0
    return UIColor(red = red, green = green, blue = blue, alpha = alpha)
}

/**
 * Gets the key window using the modern UIWindowScene API (iOS 15+).
 * Falls back to the deprecated keyWindow property for compatibility.
 */
@Suppress("DEPRECATION")
private fun getKeyWindow(): UIWindow? {
    val scenes = UIApplication.sharedApplication.connectedScenes
    for (scene in scenes) {
        val windowScene = scene as? UIWindowScene ?: continue
        if (windowScene.activationState == UISceneActivationStateForegroundActive) {
            val windows = windowScene.windows
            for (window in windows) {
                val uiWindow = window as? UIWindow ?: continue
                if (uiWindow.isKeyWindow()) return uiWindow
            }
        }
    }
    return UIApplication.sharedApplication.keyWindow
}
