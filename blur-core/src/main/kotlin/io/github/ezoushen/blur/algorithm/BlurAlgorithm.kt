package io.github.ezoushen.blur.algorithm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.FloatRange
import androidx.annotation.WorkerThread

/**
 * Strategy interface for blur algorithm implementations.
 *
 * Different implementations can be provided for different API levels
 * and device capabilities:
 * - RenderEffectBlur: API 31+ (best performance)
 * - RenderScriptBlur: API 17-30 (deprecated but functional)
 * - ToolkitBlur: All APIs (CPU-based with SIMD optimization)
 *
 * Usage:
 * ```
 * val algorithm = BlurAlgorithmFactory.create(context)
 * algorithm.prepare(context, width, height, radius)
 * val blurred = algorithm.blur(inputBitmap, radius)
 * // ... use blurred bitmap
 * algorithm.release()
 * ```
 */
interface BlurAlgorithm {

    /**
     * Prepares the algorithm for blurring with the given dimensions.
     *
     * Must be called before [blur]. Can be called again if dimensions change.
     *
     * @param context Application context
     * @param width Expected bitmap width
     * @param height Expected bitmap height
     * @param radius Initial blur radius
     * @return true if preparation succeeded, false otherwise
     */
    fun prepare(
        context: Context,
        width: Int,
        height: Int,
        @FloatRange(from = 0.0, to = 25.0) radius: Float
    ): Boolean

    /**
     * Applies blur to the input bitmap.
     *
     * @param input Source bitmap to blur
     * @param radius Blur radius (may be different from prepare radius)
     * @return Blurred bitmap (may be same instance as input if [canModifyBitmap] is true)
     */
    @WorkerThread
    fun blur(
        input: Bitmap,
        @FloatRange(from = 0.0, to = 25.0) radius: Float
    ): Bitmap

    /**
     * Releases all resources held by this algorithm.
     *
     * The algorithm should not be used after calling this method
     * unless [prepare] is called again.
     */
    fun release()

    /**
     * Returns true if this algorithm modifies the input bitmap in-place.
     *
     * If false, the algorithm creates a new bitmap for output.
     * Callers should be aware of this when managing bitmap lifecycle.
     */
    fun canModifyBitmap(): Boolean

    /**
     * Returns the bitmap config required by this algorithm.
     */
    fun getSupportedBitmapConfig(): Bitmap.Config

    /**
     * Renders the blurred bitmap to a canvas.
     *
     * Default implementation simply draws the bitmap.
     * Subclasses may override for custom rendering (e.g., with effects).
     *
     * @param canvas Target canvas
     * @param bitmap Blurred bitmap to draw
     */
    fun render(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    /**
     * Returns true if this algorithm is available on the current device.
     */
    fun isAvailable(): Boolean

    /**
     * Returns a human-readable name for this algorithm.
     */
    fun getName(): String
}

/**
 * A no-op blur algorithm used as a fallback when no real implementation is available.
 */
class NoOpBlurAlgorithm : BlurAlgorithm {

    override fun prepare(context: Context, width: Int, height: Int, radius: Float): Boolean = true

    override fun blur(input: Bitmap, radius: Float): Bitmap = input

    override fun release() {}

    override fun canModifyBitmap(): Boolean = true

    override fun getSupportedBitmapConfig(): Bitmap.Config = Bitmap.Config.ARGB_8888

    override fun isAvailable(): Boolean = true

    override fun getName(): String = "NoOp"
}
