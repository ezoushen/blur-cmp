package io.github.ezoushen.blur.algorithm

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.min

/**
 * CPU-based blur implementation using Stack Blur algorithm.
 *
 * Stack Blur is a fast approximation of Gaussian blur that achieves O(1) complexity
 * per pixel by using a weighted sliding window approach. It provides a good balance
 * between quality and performance for CPU-based blur.
 *
 * This implementation is a fallback when GPU-based blur is not available.
 *
 * Supported API: All (pure Kotlin implementation)
 * Max blur radius: 25
 */
class ToolkitBlur : BlurAlgorithm {

    private var outputBitmap: Bitmap? = null
    private var lastWidth = 0
    private var lastHeight = 0

    override fun prepare(context: Context, width: Int, height: Int, radius: Float): Boolean {
        if (width <= 0 || height <= 0) return false

        if (lastWidth != width || lastHeight != height) {
            outputBitmap?.recycle()
            outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            lastWidth = width
            lastHeight = height
        }

        return true
    }

    override fun blur(input: Bitmap, radius: Float): Bitmap {
        val output = outputBitmap ?: return input

        if (radius <= 0) {
            val pixels = IntArray(input.width * input.height)
            input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
            output.setPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            return output
        }

        return try {
            val clampedRadius = radius.toInt().coerceIn(1, 25)
            stackBlur(input, output, clampedRadius)
            output
        } catch (e: Exception) {
            input
        }
    }

    /**
     * Stack Blur algorithm implementation.
     * Based on the algorithm by Mario Klingemann.
     */
    private fun stackBlur(input: Bitmap, output: Bitmap, radius: Int) {
        val width = input.width
        val height = input.height
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)

        val wm = width - 1
        val hm = height - 1
        val wh = width * height
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        val a = IntArray(wh)

        var rsum: Int
        var gsum: Int
        var bsum: Int
        var asum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int

        val vmin = IntArray(maxOf(width, height))

        var divsum = (div + 1) shr 1
        divsum *= divsum

        val dv = IntArray(256 * divsum)
        for (i in 0 until 256 * divsum) {
            dv[i] = i / divsum
        }

        yi = 0
        yw = 0

        val stack = Array(div) { IntArray(4) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var aoutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int
        var ainsum: Int

        for (y in 0 until height) {
            asum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            aoutsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            ainsum = 0
            rinsum = 0
            ginsum = 0
            binsum = 0

            for (i in -radius..radius) {
                p = pixels[yi + min(wm, maxOf(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p shr 16) and 0xff
                sir[1] = (p shr 8) and 0xff
                sir[2] = p and 0xff
                sir[3] = (p shr 24) and 0xff

                rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                asum += sir[3] * rbs

                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                    ainsum += sir[3]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                    aoutsum += sir[3]
                }
            }
            stackpointer = radius

            for (x in 0 until width) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]
                a[yi] = dv[asum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                asum -= aoutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                aoutsum -= sir[3]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                p = pixels[yw + vmin[x]]

                sir[0] = (p shr 16) and 0xff
                sir[1] = (p shr 8) and 0xff
                sir[2] = p and 0xff
                sir[3] = (p shr 24) and 0xff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                ainsum += sir[3]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                asum += ainsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                aoutsum += sir[3]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                ainsum -= sir[3]

                yi++
            }
            yw += width
        }

        for (x in 0 until width) {
            asum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            aoutsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            ainsum = 0
            rinsum = 0
            ginsum = 0
            binsum = 0

            yp = -radius * width

            for (i in -radius..radius) {
                yi = maxOf(0, yp) + x

                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                sir[3] = a[yi]

                rbs = r1 - kotlin.math.abs(i)

                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                asum += a[yi] * rbs

                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                    ainsum += sir[3]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                    aoutsum += sir[3]
                }

                if (i < hm) {
                    yp += width
                }
            }

            yi = x
            stackpointer = radius

            for (y in 0 until height) {
                pixels[yi] = (dv[asum] shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                asum -= aoutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                aoutsum -= sir[3]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * width
                }
                p = x + vmin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]
                sir[3] = a[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                ainsum += sir[3]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                asum += ainsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                aoutsum += sir[3]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                ainsum -= sir[3]

                yi += width
            }
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    override fun release() {
        outputBitmap?.recycle()
        outputBitmap = null
        lastWidth = 0
        lastHeight = 0
    }

    override fun canModifyBitmap(): Boolean = false

    override fun getSupportedBitmapConfig(): Bitmap.Config = Bitmap.Config.ARGB_8888

    override fun isAvailable(): Boolean = true

    override fun getName(): String = "Stack Blur (CPU)"
}
