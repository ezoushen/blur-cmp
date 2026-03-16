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
 *
 * Thread safety: must only be called from the main thread (UIKit requirement).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosBackdropLayerProvider {

    private var preparedOnce = false

    private var backdropLayerClass: NSObjectMeta? = null
    private var filterClass: NSObjectMeta? = null
    private var gaussianBlurFilterTemplate: NSObject? = null
    private var indexOfBackdropView: Int = -1
    private var indexOfVisualEffectSubview: Int = -1

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

            if (className.hasSuffix("BackdropView")) {
                indexOfBackdropView = i
                val layerObjcClass = object_getClass(view.layer)
                if (layerObjcClass != null) {
                    backdropLayerClass = layerObjcClass as? NSObjectMeta
                }

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

    val isValidForUniformBlur: Boolean
        get() {
            prepareOnce()
            return indexOfBackdropView > -1 &&
                indexOfVisualEffectSubview > -1 &&
                gaussianBlurFilterTemplate != null
        }

    val isValidForVariableBlur: Boolean
        get() {
            prepareOnce()
            return backdropLayerClass != null && filterClass != null
        }

    fun createBackdropLayer(): CALayer? {
        prepareOnce()
        val cls = backdropLayerClass ?: return null
        return try {
            cls.new() as? CALayer
        } catch (_: Exception) {
            null
        }
    }

    fun createGaussianBlurFilter(radius: Double): NSObject? {
        prepareOnce()
        val template = gaussianBlurFilterTemplate ?: return null

        return try {
            val copied = template.copy() as? NSObject ?: return null
            copied.setValue(radius, forKey = "inputRadius")
            copied.setValue(true, forKey = "inputNormalizeEdges")
            copied
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun createVariableBlurFilter(radius: Double, maskImage: CGImageRef): NSObject? {
        prepareOnce()
        val cls = filterClass ?: return null

        return try {
            val selector = NSSelectorFromString("filterWithType:")
            val result = (cls as NSObject).performSelector(selector, withObject = "variableBlur")
            val filter = result as? NSObject ?: return null

            filter.setValue(radius, forKey = "inputRadius")

            // CGImageRef -> NSObject bridging for KVC (Swift auto-bridges, K/N needs explicit)
            val maskAsId = kotlinx.cinterop.interpretObjCPointerOrNull<platform.darwin.NSObject>(
                maskImage.rawValue
            )
            if (maskAsId != null) {
                filter.setValue(maskAsId, forKey = "inputMaskImage")
            }

            filter.setValue(true, forKey = "inputNormalizeEdges")
            filter
        } catch (_: Exception) {
            null
        }
    }

    fun resetPreparation() {
        preparedOnce = false
        indexOfBackdropView = -1
        indexOfVisualEffectSubview = -1
        gaussianBlurFilterTemplate = null
        filterClass = null
        backdropLayerClass = null
    }
}

private fun String.hasSuffix(suffix: String): Boolean = endsWith(suffix)
