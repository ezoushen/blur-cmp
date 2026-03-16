package io.github.ezoushen.blur.cmp

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageRef
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
import platform.darwin.NSObjectMeta
import platform.objc.object_getClass

/**
 * Extracts CABackdropLayer and CAFilter capabilities from UIVisualEffectView internals.
 * Mirrors the Swift BackdropLayerProvider from BlurViewCore.swift.
 *
 * Uses NSObjectMeta for correct class method dispatch in Kotlin/Native,
 * matching Swift's metatype-based approach (layerClass.init()).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosBackdropLayerProvider {

    private var preparedOnce = false

    // Store actual class references (like Swift's AnyClass?) instead of string names
    private var backdropLayerClass: NSObjectMeta? = null
    private var filterClass: NSObjectMeta? = null
    private var gaussianBlurFilterTemplate: NSObject? = null
    private var indexOfBackdropView: Int = -1
    private var indexOfVisualEffectSubview: Int = -1

    /**
     * Extract internal structure from UIVisualEffectView (called once, cached).
     * Matches Swift BackdropLayerProvider.prepareOnce() logic exactly.
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

            platform.Foundation.NSLog("[BackdropLayerProvider] subview[$i] class: $className")

            if (className.hasSuffix("BackdropView")) {
                indexOfBackdropView = i
                // Store the layer's class as NSObjectMeta for direct .new() calls
                val layerObjcClass = object_getClass(view.layer)
                if (layerObjcClass != null) {
                    backdropLayerClass = layerObjcClass as? NSObjectMeta
                    platform.Foundation.NSLog("[BackdropLayerProvider] Found backdrop layer class: ${NSStringFromClass(layerObjcClass)}")
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
                                    filterClass = filterObjcClass as? NSObjectMeta
                                }
                                platform.Foundation.NSLog("[BackdropLayerProvider] Found gaussian blur filter, class: ${NSStringFromClass(object_getClass(nsFilter)!!)}")
                                break
                            }
                        }
                    }
                }
            } else if (className.hasSuffix("Subview") && !className.contains("Content")) {
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
            return backdropLayerClass != null && filterClass != null
        }

    /**
     * Create a new CABackdropLayer instance.
     * Uses NSObjectMeta.new() for correct class method dispatch,
     * matching Swift's `layerClass.init()` pattern.
     */
    fun createBackdropLayer(): CALayer? {
        prepareOnce()
        val cls = backdropLayerClass ?: return null
        return try {
            // NSObjectMeta.new() correctly dispatches +[CABackdropLayer new]
            // K/N handles ARC naming convention for "new" family methods
            cls.new() as? CALayer
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Create a gaussian blur filter with the specified radius.
     * Uses NSObject.copy() which is correctly annotated @ReturnsRetained in K/N.
     */
    fun createGaussianBlurFilter(radius: Double): NSObject? {
        prepareOnce()
        val template = gaussianBlurFilterTemplate ?: return null

        return try {
            // NSObject.copy() has correct @ReturnsRetained annotation in K/N,
            // unlike performSelector("copy") which would double-retain
            val copied = template.copy() as? NSObject ?: return null
            copied.setValue(radius, forKey = "inputRadius")
            copied.setValue(true, forKey = "inputNormalizeEdges")
            copied
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Create a variable blur filter with the specified radius and mask image.
     * Dispatches +[CAFilter filterWithType:] as a class method.
     * filterWithType: returns +0 (autoreleased), so performSelector handling is correct.
     */
    @Suppress("UNCHECKED_CAST")
    fun createVariableBlurFilter(radius: Double, maskImage: CGImageRef): NSObject? {
        prepareOnce()
        val cls = filterClass ?: return null

        return try {
            val selector = NSSelectorFromString("filterWithType:")
            val result = (cls as NSObject).performSelector(selector, withObject = "variableBlur")
            val filter = result as? NSObject
            if (filter == null) {
                platform.Foundation.NSLog("[BackdropLayerProvider] filterWithType: returned null")
                return null
            }
            platform.Foundation.NSLog("[BackdropLayerProvider] Created variableBlur filter: ${NSStringFromClass(object_getClass(filter)!!)}")

            filter.setValue(radius, forKey = "inputRadius")
            platform.Foundation.NSLog("[BackdropLayerProvider] Set inputRadius=$radius")

            // CGImageRef needs to be bridged to an ObjC object for KVC.
            // In Swift, CGImage toll-free bridges. In K/N, we need to use
            // CFBridgingRetain to convert the CF type to a retained NSObject reference.
            val maskAsId = kotlinx.cinterop.interpretObjCPointerOrNull<platform.darwin.NSObject>(
                maskImage.rawValue
            )
            if (maskAsId != null) {
                filter.setValue(maskAsId, forKey = "inputMaskImage")
                platform.Foundation.NSLog("[BackdropLayerProvider] Set inputMaskImage via interpretObjCPointer")
            } else {
                platform.Foundation.NSLog("[BackdropLayerProvider] Failed to bridge CGImageRef to NSObject")
            }

            filter.setValue(true, forKey = "inputNormalizeEdges")
            platform.Foundation.NSLog("[BackdropLayerProvider] Variable blur filter created successfully")
            filter
        } catch (e: Exception) {
            platform.Foundation.NSLog("[BackdropLayerProvider] Exception: ${e.message}")
            null
        }
    }

    /** Reset preparation state (for testing). */
    fun resetPreparation() {
        preparedOnce = false
        indexOfBackdropView = -1
        indexOfVisualEffectSubview = -1
        gaussianBlurFilterTemplate = null
        filterClass = null
        backdropLayerClass = null
    }
}

// Extension to match Swift's hasSuffix
private fun String.hasSuffix(suffix: String): Boolean = endsWith(suffix)
