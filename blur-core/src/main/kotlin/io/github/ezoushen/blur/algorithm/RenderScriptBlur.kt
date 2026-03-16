@file:Suppress("DEPRECATION")

package io.github.ezoushen.blur.algorithm

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur

/**
 * Blur algorithm implementation using Android's RenderScript.
 *
 * **Note:** RenderScript is deprecated as of API 31. Hardware acceleration
 * is no longer guaranteed, and it may fall back to CPU execution.
 * Use [RenderEffectBlur] on API 31+ for best performance.
 *
 * Supported API: 21+ (minSdk)
 * Max blur radius: 25
 */
class RenderScriptBlur : BlurAlgorithm {

    private var renderScript: RenderScript? = null
    private var blurScript: ScriptIntrinsicBlur? = null
    private var inputAllocation: Allocation? = null
    private var outputAllocation: Allocation? = null

    private var lastWidth = 0
    private var lastHeight = 0
    private var lastRadius = 0f

    override fun prepare(context: Context, width: Int, height: Int, radius: Float): Boolean {
        try {
            if (renderScript == null) {
                renderScript = RenderScript.create(context.applicationContext)
                blurScript = ScriptIntrinsicBlur.create(
                    renderScript,
                    Element.U8_4(renderScript)
                )
            }

            // Clamp radius to valid range
            val clampedRadius = radius.coerceIn(0f, 25f)
            if (clampedRadius != lastRadius) {
                blurScript?.setRadius(clampedRadius)
                lastRadius = clampedRadius
            }

            return true
        } catch (e: Exception) {
            release()
            return false
        }
    }

    override fun blur(input: Bitmap, radius: Float): Bitmap {
        val rs = renderScript ?: return input
        val script = blurScript ?: return input

        try {
            // Update radius if changed
            val clampedRadius = radius.coerceIn(0.1f, 25f)
            if (clampedRadius != lastRadius) {
                script.setRadius(clampedRadius)
                lastRadius = clampedRadius
            }

            // Recreate allocations if dimensions changed
            val needsNewAllocation = inputAllocation == null ||
                lastWidth != input.width ||
                lastHeight != input.height

            if (needsNewAllocation) {
                inputAllocation?.destroy()
                outputAllocation?.destroy()

                inputAllocation = Allocation.createFromBitmap(
                    rs, input,
                    Allocation.MipmapControl.MIPMAP_NONE,
                    Allocation.USAGE_SCRIPT
                )
                outputAllocation = Allocation.createTyped(rs, inputAllocation?.type)

                lastWidth = input.width
                lastHeight = input.height
            }

            // Copy input to allocation
            inputAllocation?.copyFrom(input)

            // Execute blur
            script.setInput(inputAllocation)
            script.forEach(outputAllocation)

            // Copy result back to bitmap
            outputAllocation?.copyTo(input)

            return input
        } catch (e: Exception) {
            // RenderScript can fail on some devices
            return input
        }
    }

    override fun release() {
        inputAllocation?.destroy()
        inputAllocation = null

        outputAllocation?.destroy()
        outputAllocation = null

        blurScript?.destroy()
        blurScript = null

        renderScript?.destroy()
        renderScript = null

        lastWidth = 0
        lastHeight = 0
        lastRadius = 0f
    }

    override fun canModifyBitmap(): Boolean = true

    override fun getSupportedBitmapConfig(): Bitmap.Config = Bitmap.Config.ARGB_8888

    override fun isAvailable(): Boolean = true

    override fun getName(): String = "RenderScript"
}
