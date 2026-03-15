package io.github.ezoushen.blur.cmp

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease

/**
 * Manages gradient mask lifecycle for variable blur on iOS.
 * Caches the generated CGImage mask and regenerates when the gradient changes.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosGradientMaskCache {

    private var cachedMask: CGImageRef? = null
    private var cachedGradient: BlurGradientType? = null

    /**
     * Get or create a gradient mask for the given gradient type.
     * Returns the CGImageRef (owned by this cache — do not release externally).
     */
    fun getOrCreate(gradient: BlurGradientType): CGImageRef? {
        if (gradient == cachedGradient && cachedMask != null) {
            return cachedMask
        }

        // Release previous mask
        release()

        val mask = IosGradientMaskGenerator.generateMask(gradient)
        cachedMask = mask
        cachedGradient = gradient
        return mask
    }

    /**
     * Invalidate the cache, releasing any held CGImage.
     */
    fun invalidate() {
        release()
        cachedGradient = null
    }

    /**
     * Release all resources.
     */
    fun release() {
        cachedMask?.let { CGImageRelease(it) }
        cachedMask = null
    }
}
