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
 * Blend order determines how tint color is composited relative to blur.
 *
 * - AFTER_BLUR: Normal blend mode — simple alpha tint on top of blurred content.
 * - BEFORE_BLUR: Non-Normal blend modes — tint is composited against raw backdrop
 *   first, then the combined result is blurred.
 */
private enum class BlendOrder { AFTER_BLUR, BEFORE_BLUR }

/**
 * Container UIView that manages the two-backdrop-layer hierarchy for correct
 * blend mode compositing.
 *
 * **afterBlur** (Normal blend mode):
 * ```
 * view.layer
 *   ├─ mainBackdropLayer (gaussianBlur filter, captures & blurs background)
 *   └─ afterBlurTintLayer (solid backgroundColor, no compositingFilter)
 * ```
 *
 * **beforeBlur** (ColorDodge and all non-Normal blend modes):
 * ```
 * view.layer
 *   ├─ preBlendBackdropLayer (gaussianBlur with radius=0, captures raw background)
 *   │   └─ preBlendTintLayer (backgroundColor + compositingFilter)
 *   └─ mainBackdropLayer (captures the combined result above and applies blur)
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
private class RealTimeBlurContainerView(
    frame: kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>,
) : UIView(frame) {

    /** Main blur backdrop layer (present in both blend orders). */
    var mainBackdropLayer: CALayer? = null

    /** Pre-blend backdrop layer (beforeBlur only): captures raw background. */
    var preBlendBackdropLayer: CALayer? = null

    /** Tint layer that is a sublayer of preBlendBackdropLayer (beforeBlur only). */
    var preBlendTintLayer: CALayer? = null

    /** Tint layer that is a sibling of mainBackdropLayer (afterBlur only). */
    var afterBlurTintLayer: CALayer? = null

    var gradientMaskCache: IosGradientMaskCache? = null
    var isVariableBlur: Boolean = false
    var isFallback: Boolean = false
    var currentBlendOrder: BlendOrder = BlendOrder.AFTER_BLUR

    fun release() {
        gradientMaskCache?.release()
        gradientMaskCache = null
    }
}

/**
 * Determine the blend order for the given config.
 * Use BEFORE_BLUR when there is a tint color AND a non-Normal blend mode.
 */
private fun resolveBlendOrder(config: BlurOverlayConfig): BlendOrder {
    return if (config.tintColorValue != 0L && config.tintBlendMode != BlurBlendMode.Normal) {
        BlendOrder.BEFORE_BLUR
    } else {
        BlendOrder.AFTER_BLUR
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
        return createBackdropBlurView(config, isVariableBlur)
            ?: createFallbackBlurView(config)
    }

    return createFallbackBlurView(config)
}

@OptIn(ExperimentalForeignApi::class)
private fun createBackdropBlurView(
    config: BlurOverlayConfig,
    isVariableBlur: Boolean,
): UIView? {
    val container = RealTimeBlurContainerView(CGRectZero.readValue())
    container.backgroundColor = null
    container.isVariableBlur = isVariableBlur

    if (isVariableBlur) {
        container.gradientMaskCache = IosGradientMaskCache()
    }

    val blendOrder = resolveBlendOrder(config)
    if (!buildLayerHierarchy(container, blendOrder)) return null

    applyConfigToBackdropView(container, config)

    return container
}

/**
 * Builds the correct layer hierarchy for the given blend order.
 * Returns false if backdrop layer creation fails.
 */
@OptIn(ExperimentalForeignApi::class)
private fun buildLayerHierarchy(
    container: RealTimeBlurContainerView,
    blendOrder: BlendOrder,
): Boolean {
    container.currentBlendOrder = blendOrder

    when (blendOrder) {
        BlendOrder.AFTER_BLUR -> {
            // Single backdrop + sibling tint
            val backdrop = IosBackdropLayerProvider.createBackdropLayer() ?: return false
            backdrop.setValue(NSUUID().UUIDString, forKey = "groupName")
            backdrop.setValue(true, forKey = "allowsInPlaceFiltering")
            container.layer.addSublayer(backdrop)
            container.mainBackdropLayer = backdrop

            val tintLayer = CALayer()
            container.layer.addSublayer(tintLayer)
            container.afterBlurTintLayer = tintLayer
        }

        BlendOrder.BEFORE_BLUR -> {
            // Pre-blend backdrop captures raw background (radius=0)
            val preBlend = IosBackdropLayerProvider.createBackdropLayer() ?: return false
            preBlend.setValue(NSUUID().UUIDString, forKey = "groupName")
            preBlend.setValue(true, forKey = "allowsInPlaceFiltering")
            preBlend.setValue(1.0, forKey = "scale") // full resolution for sharp blend
            container.layer.addSublayer(preBlend)
            container.preBlendBackdropLayer = preBlend

            // Tint layer is a SUBLAYER of the pre-blend backdrop
            val tintLayer = CALayer()
            preBlend.addSublayer(tintLayer)
            container.preBlendTintLayer = tintLayer

            // Main backdrop captures the pre-blended result and applies blur
            val mainBackdrop = IosBackdropLayerProvider.createBackdropLayer() ?: return false
            mainBackdrop.setValue(NSUUID().UUIDString, forKey = "groupName")
            mainBackdrop.setValue(true, forKey = "allowsInPlaceFiltering")
            container.layer.addSublayer(mainBackdrop)
            container.mainBackdropLayer = mainBackdrop
        }
    }

    return true
}

/**
 * Tears down all backdrop and tint layers from the container.
 */
@OptIn(ExperimentalForeignApi::class)
private fun teardownLayerHierarchy(container: RealTimeBlurContainerView) {
    container.preBlendTintLayer?.removeFromSuperlayer()
    container.preBlendTintLayer = null

    container.preBlendBackdropLayer?.removeFromSuperlayer()
    container.preBlendBackdropLayer = null

    container.afterBlurTintLayer?.removeFromSuperlayer()
    container.afterBlurTintLayer = null

    container.mainBackdropLayer?.removeFromSuperlayer()
    container.mainBackdropLayer = null
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
    val desiredOrder = resolveBlendOrder(config)

    // If blend order changed, tear down and rebuild the layer hierarchy
    if (desiredOrder != container.currentBlendOrder) {
        teardownLayerHierarchy(container)
        if (!buildLayerHierarchy(container, desiredOrder)) return
    }

    CATransaction.begin()
    CATransaction.setDisableActions(true)

    val bounds = container.bounds
    val radius = config.radius.toDouble()

    when (container.currentBlendOrder) {
        BlendOrder.AFTER_BLUR -> applyAfterBlurConfig(container, config, bounds, radius)
        BlendOrder.BEFORE_BLUR -> applyBeforeBlurConfig(container, config, bounds, radius)
    }

    CATransaction.commit()
}

/**
 * afterBlur: single backdrop with blur, sibling tint layer (no compositingFilter).
 */
@OptIn(ExperimentalForeignApi::class)
private fun applyAfterBlurConfig(
    container: RealTimeBlurContainerView,
    config: BlurOverlayConfig,
    bounds: kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>,
    radius: Double,
) {
    val backdrop = container.mainBackdropLayer ?: return
    val tintLayer = container.afterBlurTintLayer ?: return

    backdrop.frame = bounds
    tintLayer.frame = bounds

    // Apply blur filter to main backdrop
    applyBlurFilter(container, backdrop, config, radius)

    // Scale factor: lower = better performance for uniform blur
    val scale = if (config.gradient == null) {
        (1.0 / config.downsampleFactor).coerceIn(0.05, 1.0)
    } else {
        1.0
    }
    backdrop.setValue(scale, forKey = "scale")

    // Tint (no compositingFilter for Normal blend mode)
    if (config.tintColorValue != 0L) {
        tintLayer.backgroundColor = uiColorFromPackedValue(config.tintColorValue)?.CGColor
    } else {
        tintLayer.backgroundColor = null
    }
    tintLayer.compositingFilter = null

    // Apply gradient mask to tint if variable blur
    if (config.gradient != null && container.isVariableBlur) {
        val maskCache = container.gradientMaskCache ?: IosGradientMaskCache().also {
            container.gradientMaskCache = it
        }
        val mask = maskCache.getOrCreate(config.gradient)
        if (mask != null && config.tintColorValue != 0L) {
            val tintMaskLayer = CALayer()
            tintMaskLayer.frame = bounds
            tintMaskLayer.contents = mask
            tintMaskLayer.contentsGravity = kCAGravityResizeAspectFill
            tintLayer.mask = tintMaskLayer
        } else {
            tintLayer.mask = null
        }
    } else {
        tintLayer.mask = null
    }
}

/**
 * beforeBlur: preBlendBackdrop (radius=0) with tintLayer sublayer (compositingFilter),
 * then mainBackdrop blurs the combined result.
 */
@OptIn(ExperimentalForeignApi::class)
private fun applyBeforeBlurConfig(
    container: RealTimeBlurContainerView,
    config: BlurOverlayConfig,
    bounds: kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>,
    radius: Double,
) {
    val preBlend = container.preBlendBackdropLayer ?: return
    val tintLayer = container.preBlendTintLayer ?: return
    val mainBackdrop = container.mainBackdropLayer ?: return

    preBlend.frame = bounds
    tintLayer.frame = bounds
    mainBackdrop.frame = bounds

    // Pre-blend backdrop: gaussianBlur with radius=0 (needed so the backdrop layer works)
    val zeroBlurFilter = IosBackdropLayerProvider.createGaussianBlurFilter(0.0)
    if (zeroBlurFilter != null) {
        preBlend.setValue(listOf(zeroBlurFilter), forKey = "filters")
    }
    // Full resolution for crisp blend modes
    preBlend.setValue(1.0, forKey = "scale")

    // Tint layer with compositingFilter blends against the pre-blend backdrop's content
    if (config.tintColorValue != 0L) {
        tintLayer.backgroundColor = uiColorFromPackedValue(config.tintColorValue)?.CGColor
    } else {
        tintLayer.backgroundColor = null
    }
    val filterName = IosBlendModeMapper.toCompositingFilterName(config.tintBlendMode)
    tintLayer.compositingFilter = filterName

    // Apply gradient mask to tint if variable blur
    if (config.gradient != null && container.isVariableBlur) {
        val maskCache = container.gradientMaskCache ?: IosGradientMaskCache().also {
            container.gradientMaskCache = it
        }
        val mask = maskCache.getOrCreate(config.gradient)
        if (mask != null && config.tintColorValue != 0L) {
            val tintMaskLayer = CALayer()
            tintMaskLayer.frame = bounds
            tintMaskLayer.contents = mask
            tintMaskLayer.contentsGravity = kCAGravityResizeAspectFill
            tintLayer.mask = tintMaskLayer
        } else {
            tintLayer.mask = null
        }
    } else {
        tintLayer.mask = null
    }

    // Main backdrop: captures the pre-blended result and applies the actual blur
    applyBlurFilter(container, mainBackdrop, config, radius)

    // Scale factor for the main blur backdrop
    val scale = if (config.gradient == null) {
        (1.0 / config.downsampleFactor).coerceIn(0.05, 1.0)
    } else {
        1.0
    }
    mainBackdrop.setValue(scale, forKey = "scale")
}

/**
 * Applies the appropriate blur filter (uniform or variable) to the given backdrop layer.
 */
@OptIn(ExperimentalForeignApi::class)
private fun applyBlurFilter(
    container: RealTimeBlurContainerView,
    backdrop: CALayer,
    config: BlurOverlayConfig,
    radius: Double,
) {
    if (config.gradient != null && container.isVariableBlur) {
        val maskCache = container.gradientMaskCache ?: IosGradientMaskCache().also {
            container.gradientMaskCache = it
        }
        val mask = maskCache.getOrCreate(config.gradient)
        if (mask != null) {
            val filter = IosBackdropLayerProvider.createVariableBlurFilter(radius, mask)
            if (filter != null) {
                backdrop.setValue(listOf(filter), forKey = "filters")
            }
        }
    } else {
        val filter = IosBackdropLayerProvider.createGaussianBlurFilter(radius)
        if (filter != null) {
            backdrop.setValue(listOf(filter), forKey = "filters")
        }
    }
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

    // Update in-place (blend order change is handled inside applyConfigToBackdropView)
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
