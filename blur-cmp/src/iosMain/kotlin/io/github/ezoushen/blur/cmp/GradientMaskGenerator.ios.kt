package io.github.ezoushen.blur.cmp

import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CVariable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextClearRect
import platform.CoreGraphics.CGContextDrawLinearGradient
import platform.CoreGraphics.CGContextDrawRadialGradient
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGFloatVar
import platform.CoreGraphics.CGGradientCreateWithColorComponents
import platform.CoreGraphics.CGGradientDrawingOptions
import platform.CoreGraphics.CGGradientRef
import platform.CoreGraphics.CGGradientRelease
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGGradientDrawsAfterEndLocation
import platform.CoreGraphics.kCGGradientDrawsBeforeStartLocation

/**
 * Generates gradient mask CGImages for variable blur.
 * The mask alpha channel controls blur intensity at each pixel.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosGradientMaskGenerator {

    private const val MASK_RESOLUTION = 256

    // kCGImageAlphaPremultipliedLast = 1
    private const val BITMAP_INFO_PREMULTIPLIED_LAST: UInt = 1u

    /**
     * Generate a gradient mask image from a [BlurGradientType].
     * Returns a CGImageRef (caller is responsible for releasing it), or null on failure.
     */
    fun generateMask(gradient: BlurGradientType): CGImageRef? {
        return when (gradient) {
            is BlurGradientType.Linear -> createLinearMask(gradient)
            is BlurGradientType.Radial -> createRadialMask(gradient)
        }
    }

    private fun createLinearMask(gradient: BlurGradientType.Linear): CGImageRef? {
        val width = MASK_RESOLUTION
        val height = MASK_RESOLUTION

        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        val context = CGBitmapContextCreate(
            data = null,
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = (width * 4).toULong(),
            space = colorSpace,
            bitmapInfo = BITMAP_INFO_PREMULTIPLIED_LAST,
        )
        if (context == null) {
            CGColorSpaceRelease(colorSpace)
            return null
        }

        CGContextClearRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))

        val stops = buildLinearStops(gradient)
        val cgGradient = createCGGradient(colorSpace, stops)
        if (cgGradient == null) {
            CGContextRelease(context)
            CGColorSpaceRelease(colorSpace)
            return null
        }

        // Convert normalized coordinates to pixel coordinates.
        // CoreGraphics has Y-up, so invert Y.
        val start = CGPointMake(
            gradient.startX.toDouble() * width,
            (1.0 - gradient.startY.toDouble()) * height,
        )
        val end = CGPointMake(
            gradient.endX.toDouble() * width,
            (1.0 - gradient.endY.toDouble()) * height,
        )

        val options: CGGradientDrawingOptions =
            kCGGradientDrawsBeforeStartLocation or kCGGradientDrawsAfterEndLocation
        CGContextDrawLinearGradient(context, cgGradient, start, end, options)

        val image = CGBitmapContextCreateImage(context)

        CGGradientRelease(cgGradient)
        CGContextRelease(context)
        CGColorSpaceRelease(colorSpace)

        return image
    }

    private fun createRadialMask(gradient: BlurGradientType.Radial): CGImageRef? {
        val width = MASK_RESOLUTION
        val height = MASK_RESOLUTION

        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        val context = CGBitmapContextCreate(
            data = null,
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = (width * 4).toULong(),
            space = colorSpace,
            bitmapInfo = BITMAP_INFO_PREMULTIPLIED_LAST,
        )
        if (context == null) {
            CGColorSpaceRelease(colorSpace)
            return null
        }

        CGContextClearRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))

        val stops = buildRadialStops(gradient)
        val cgGradient = createCGGradient(colorSpace, stops)
        if (cgGradient == null) {
            CGContextRelease(context)
            CGColorSpaceRelease(colorSpace)
            return null
        }

        val center = CGPointMake(
            gradient.centerX.toDouble() * width,
            (1.0 - gradient.centerY.toDouble()) * height,
        )
        val maxDimension = maxOf(width, height).toDouble()
        val startRadius = 0.0
        val endRadius = (gradient.radius.toDouble() * maxDimension).coerceAtLeast(0.001)

        val options: CGGradientDrawingOptions =
            kCGGradientDrawsBeforeStartLocation or kCGGradientDrawsAfterEndLocation
        CGContextDrawRadialGradient(
            context, cgGradient,
            center, startRadius,
            center, endRadius,
            options,
        )

        val image = CGBitmapContextCreateImage(context)

        CGGradientRelease(cgGradient)
        CGContextRelease(context)
        CGColorSpaceRelease(colorSpace)

        return image
    }

    private fun buildLinearStops(gradient: BlurGradientType.Linear): List<Pair<Double, Double>> {
        if (gradient.stops != null && gradient.stops.size >= 2) {
            return gradient.stops
                .map {
                    it.position.toDouble().coerceIn(0.0, 1.0) to
                        it.intensity.toDouble().coerceIn(0.0, 1.0)
                }
                .sortedBy { it.first }
        }
        return listOf(
            0.0 to gradient.startIntensity.toDouble().coerceIn(0.0, 1.0),
            1.0 to gradient.endIntensity.toDouble().coerceIn(0.0, 1.0),
        )
    }

    private fun buildRadialStops(gradient: BlurGradientType.Radial): List<Pair<Double, Double>> {
        if (gradient.stops != null && gradient.stops.size >= 2) {
            return gradient.stops
                .map {
                    it.position.toDouble().coerceIn(0.0, 1.0) to
                        it.intensity.toDouble().coerceIn(0.0, 1.0)
                }
                .sortedBy { it.first }
        }
        return listOf(
            0.0 to gradient.centerIntensity.toDouble().coerceIn(0.0, 1.0),
            1.0 to gradient.edgeIntensity.toDouble().coerceIn(0.0, 1.0),
        )
    }

    /**
     * Create a CGGradient from stops. Each stop maps to black with varying alpha
     * (alpha controls blur intensity in the mask).
     */
    private fun createCGGradient(
        colorSpace: CGColorSpaceRef,
        stops: List<Pair<Double, Double>>,
    ): CGGradientRef? {
        return memScoped {
            val count = stops.size
            val locationsPtr: CArrayPointer<CGFloatVar> = allocArray(count)
            // RGBA components: black (0,0,0) with alpha = intensity
            val componentsPtr: CArrayPointer<CGFloatVar> = allocArray(count * 4)

            for (i in 0 until count) {
                locationsPtr[i] = stops[i].first
                val baseIdx = i * 4
                componentsPtr[baseIdx] = 0.0       // R
                componentsPtr[baseIdx + 1] = 0.0   // G
                componentsPtr[baseIdx + 2] = 0.0   // B
                componentsPtr[baseIdx + 3] = stops[i].second // A (intensity)
            }

            CGGradientCreateWithColorComponents(
                colorSpace,
                componentsPtr,
                locationsPtr,
                count.toULong(),
            )
        }
    }
}
