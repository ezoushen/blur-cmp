package io.github.ezoushen.blur.cmp

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageRef
import platform.Foundation.NSClassFromString
import platform.Foundation.NSNumber
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSStringFromClass
import platform.Foundation.setValue
import platform.Foundation.valueForKey
import platform.QuartzCore.CALayer
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIView
import platform.UIKit.UIVisualEffectView
import platform.darwin.NSObject
import platform.objc.object_getClass

/**
 * Extracts CABackdropLayer and CAFilter capabilities from UIVisualEffectView internals.
 * Mirrors the Swift BackdropLayerProvider from BlurViewCore.swift, implemented entirely
 * in Kotlin/Native using KVC (Key-Value Coding) to avoid Swift interop requirements.
 *
 * This approach is based on Flutter's iOS PlatformView BackdropFilter strategy:
 * extract blur filter capabilities from UIVisualEffectView once, then reuse them
 * to create custom backdrop blur layers with continuous radius control.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosBackdropLayerProvider {

    private var preparedOnce = false
    private var backdropLayerClassName: String? = null
    private var gaussianBlurFilterTemplate: NSObject? = null
    private var filterClassName: String? = null
    private var indexOfBackdropView: Int = -1
    private var indexOfVisualEffectSubview: Int = -1

    /**
     * Extract internal structure from UIVisualEffectView (called once, cached).
     */
    fun prepareOnce() {
        if (preparedOnce) return
        preparedOnce = true

        val effectView = UIVisualEffectView(
            effect = UIBlurEffect.effectWithStyle(UIBlurEffectStyle.UIBlurEffectStyleLight)
        )

        val subviews = effectView.subviews
        for (i in 0 until subviews.count().toInt()) {
            @Suppress("UNCHECKED_CAST")
            val view = subviews[i] as? UIView ?: continue
            val objcClass = object_getClass(view) ?: continue
            val className = NSStringFromClass(objcClass)

            if (className.contains("BackdropView")) {
                indexOfBackdropView = i
                val layerObjcClass = object_getClass(view.layer)
                if (layerObjcClass != null) {
                    backdropLayerClassName = NSStringFromClass(layerObjcClass)
                }

                // Extract gaussian blur filter from the backdrop layer
                @Suppress("UNCHECKED_CAST")
                val filters = view.layer.valueForKey("filters") as? List<*>
                if (filters != null) {
                    for (filter in filters) {
                        val nsFilter = filter as? NSObject ?: continue
                        val name = nsFilter.valueForKey("name") as? String
                        if (name == "gaussianBlur") {
                            val radius = nsFilter.valueForKey("inputRadius")
                            if (radius is NSNumber) {
                                gaussianBlurFilterTemplate = nsFilter
                                val filterObjcClass = object_getClass(nsFilter)
                                if (filterObjcClass != null) {
                                    filterClassName = NSStringFromClass(filterObjcClass)
                                }
                                break
                            }
                        }
                    }
                }
            } else if (className.contains("Subview") && !className.contains("Content")) {
                indexOfVisualEffectSubview = i
            }
        }
    }

    /** Whether extraction was successful for uniform blur. */
    val isValidForUniformBlur: Boolean
        get() {
            prepareOnce()
            return indexOfBackdropView > -1 &&
                indexOfVisualEffectSubview > -1 &&
                gaussianBlurFilterTemplate != null
        }

    /** Whether extraction was successful for variable blur. */
    val isValidForVariableBlur: Boolean
        get() {
            prepareOnce()
            return backdropLayerClassName != null && filterClassName != null
        }

    /**
     * Create a new CABackdropLayer instance.
     * Returns null if extraction failed.
     */
    fun createBackdropLayer(): CALayer? {
        prepareOnce()
        val className = backdropLayerClassName ?: return null
        return try {
            val cls = NSClassFromString(className) ?: return null
            val selector = NSSelectorFromString("new")
            @Suppress("UNCHECKED_CAST")
            val result = (cls as NSObject).performSelector(selector)
            result as? CALayer
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Create a gaussian blur filter with the specified radius.
     * Copies the template filter extracted from UIVisualEffectView.
     */
    fun createGaussianBlurFilter(radius: Double): NSObject? {
        prepareOnce()
        val template = gaussianBlurFilterTemplate ?: return null

        return try {
            val copySelector = NSSelectorFromString("copy")
            val copied = template.performSelector(copySelector) as? NSObject ?: return null
            copied.setValue(radius, forKey = "inputRadius")
            copied.setValue(true, forKey = "inputNormalizeEdges")
            copied
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Create a variable blur filter with the specified radius and mask image.
     * Uses the CAFilter class extracted from UIVisualEffectView.
     */
    fun createVariableBlurFilter(radius: Double, maskImage: CGImageRef): NSObject? {
        prepareOnce()
        val className = filterClassName ?: return null

        return try {
            val cls = NSClassFromString(className) ?: return null
            val selector = NSSelectorFromString("filterWithType:")
            @Suppress("UNCHECKED_CAST")
            val result = (cls as NSObject).performSelector(selector, withObject = "variableBlur")
            val filter = result as? NSObject ?: return null
            filter.setValue(radius, forKey = "inputRadius")
            filter.setValue(maskImage, forKey = "inputMaskImage")
            filter.setValue(true, forKey = "inputNormalizeEdges")
            filter
        } catch (_: Exception) {
            null
        }
    }
}
