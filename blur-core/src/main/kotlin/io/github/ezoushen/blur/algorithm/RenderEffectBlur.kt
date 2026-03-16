package io.github.ezoushen.blur.algorithm

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap

/**
 * Blur algorithm implementation for API 31+.
 *
 * Note: While RenderEffect is available for View-level blur on API 31+,
 * for capturing and blurring bitmaps (which is needed for blur-behind effect),
 * RenderScript is still the most reliable approach. RenderScript continues
 * to work on API 31+ despite being deprecated.
 *
 * The "RenderEffect" name is kept for API clarity but internally uses
 * RenderScript for actual bitmap blur operations.
 *
 * Supported API: 31+ (Android 12)
 * Max blur radius: 25
 */
@RequiresApi(Build.VERSION_CODES.S)
class RenderEffectBlur : BlurAlgorithm {

    private var renderScript: RenderScript? = null
    private var blurScript: ScriptIntrinsicBlur? = null
    private var inputAllocation: Allocation? = null
    private var outputAllocation: Allocation? = null
    private var outputBitmap: Bitmap? = null

    private var lastWidth = 0
    private var lastHeight = 0
    private var lastRadius = 0f

    @Suppress("DEPRECATION")
    override fun prepare(context: Context, width: Int, height: Int, radius: Float): Boolean {
        if (width <= 0 || height <= 0) return false

        try {
            // Initialize RenderScript if needed
            if (renderScript == null) {
                renderScript = RenderScript.create(context)
                blurScript = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))
            }

            // Recreate allocations if dimensions changed
            if (lastWidth != width || lastHeight != height) {
                releaseAllocations()

                outputBitmap = createBitmap(width, height)

                outputAllocation = Allocation.createFromBitmap(
                    renderScript,
                    outputBitmap,
                    Allocation.MipmapControl.MIPMAP_NONE,
                    Allocation.USAGE_SCRIPT
                )

                lastWidth = width
                lastHeight = height
            }

            // Update radius if changed
            val clampedRadius = radius.coerceIn(0.1f, 25f)
            if (lastRadius != clampedRadius) {
                blurScript?.setRadius(clampedRadius)
                lastRadius = clampedRadius
            }

            return true
        } catch (e: Exception) {
            release()
            return false
        }
    }

    @Suppress("DEPRECATION")
    override fun blur(input: Bitmap, radius: Float): Bitmap {
        val rs = renderScript ?: return input
        val script = blurScript ?: return input
        val output = outputBitmap ?: return input
        val outAlloc = outputAllocation ?: return input

        if (radius <= 0) {
            // No blur needed, just copy
            val pixels = IntArray(input.width * input.height)
            input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
            output.setPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            return output
        }

        try {
            // Update radius if needed
            val clampedRadius = radius.coerceIn(0.1f, 25f)
            if (lastRadius != clampedRadius) {
                script.setRadius(clampedRadius)
                lastRadius = clampedRadius
            }

            // Create input allocation from input bitmap
            inputAllocation?.destroy()
            inputAllocation = Allocation.createFromBitmap(
                rs, input,
                Allocation.MipmapControl.MIPMAP_NONE,
                Allocation.USAGE_SCRIPT
            )

            // Execute blur
            script.setInput(inputAllocation)
            script.forEach(outAlloc)

            // Copy result to output bitmap
            outAlloc.copyTo(output)

            return output
        } catch (e: Exception) {
            return input
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseAllocations() {
        inputAllocation?.destroy()
        inputAllocation = null

        outputAllocation?.destroy()
        outputAllocation = null

        outputBitmap?.recycle()
        outputBitmap = null
    }

    @Suppress("DEPRECATION")
    override fun release() {
        releaseAllocations()

        blurScript?.destroy()
        blurScript = null

        renderScript?.destroy()
        renderScript = null

        lastWidth = 0
        lastHeight = 0
        lastRadius = 0f
    }

    override fun canModifyBitmap(): Boolean = false

    override fun getSupportedBitmapConfig(): Bitmap.Config = Bitmap.Config.ARGB_8888

    override fun isAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    override fun getName(): String = "RenderEffect"
}
