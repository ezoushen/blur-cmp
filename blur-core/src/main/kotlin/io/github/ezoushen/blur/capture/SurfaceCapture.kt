package io.github.ezoushen.blur.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Captures content from SurfaceView and TextureView.
 *
 * Standard View.draw() cannot capture SurfaceView/TextureView content because they
 * render to a separate surface layer. This capture implementation handles these
 * special cases.
 *
 * **Supported View Types:**
 * - [TextureView]: Uses [TextureView.getBitmap] (API 14+)
 * - [SurfaceView]: Uses [PixelCopy] (API 24+) or lockCanvas fallback
 *
 * **Limitations:**
 * - SurfaceView capture requires API 24+ for reliable results
 * - Some DRM-protected content cannot be captured
 */
class SurfaceCapture : ContentCapture {

    override fun capture(
        blurView: View,
        sourceView: View,
        output: Bitmap,
        downsampleFactor: Float
    ): Boolean {
        return when (sourceView) {
            is TextureView -> captureTextureView(sourceView, output, downsampleFactor)
            is SurfaceView -> captureSurfaceView(sourceView, output, downsampleFactor)
            else -> false // Use DecorViewCapture for regular views
        }
    }

    /**
     * Captures content from a TextureView.
     *
     * TextureView provides direct access to its content via getBitmap().
     */
    private fun captureTextureView(
        textureView: TextureView,
        output: Bitmap,
        downsampleFactor: Float
    ): Boolean {
        if (!textureView.isAvailable) return false

        return try {
            // Get the TextureView's bitmap
            val sourceBitmap = textureView.getBitmap(output.width, output.height)
                ?: return false

            // Copy to output (already scaled)
            val canvas = Canvas(output)
            canvas.drawBitmap(sourceBitmap, 0f, 0f, null)

            // Recycle if getBitmap created a new bitmap
            if (sourceBitmap != output) {
                sourceBitmap.recycle()
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Captures content from a SurfaceView.
     *
     * SurfaceView capture is more complex as content is rendered to a separate surface.
     * Uses PixelCopy on API 24+ for reliable capture.
     */
    private fun captureSurfaceView(
        surfaceView: SurfaceView,
        output: Bitmap,
        downsampleFactor: Float
    ): Boolean {
        val holder = surfaceView.holder ?: return false
        val surface = holder.surface ?: return false

        if (!surface.isValid) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            captureSurfaceWithPixelCopy(surface, output, surfaceView.width, surfaceView.height)
        } else {
            // Fallback for older APIs - may not work reliably
            captureSurfaceWithLockCanvas(surfaceView, output)
        }
    }

    /**
     * Uses PixelCopy API (API 24+) for reliable surface capture.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun captureSurfaceWithPixelCopy(
        surface: Surface,
        output: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int
    ): Boolean {
        return try {
            // Create a temporary full-size bitmap for PixelCopy
            val tempBitmap = Bitmap.createBitmap(
                sourceWidth,
                sourceHeight,
                Bitmap.Config.ARGB_8888
            )

            // Use synchronous PixelCopy
            val latch = java.util.concurrent.CountDownLatch(1)
            var copyResult = android.view.PixelCopy.ERROR_UNKNOWN

            android.view.PixelCopy.request(
                surface,
                tempBitmap,
                { result ->
                    copyResult = result
                    latch.countDown()
                },
                android.os.Handler(android.os.Looper.getMainLooper())
            )

            // Wait for copy to complete (with timeout)
            val completed = latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)

            if (completed && copyResult == android.view.PixelCopy.SUCCESS) {
                // Scale down to output bitmap
                val canvas = Canvas(output)
                val scaleX = output.width.toFloat() / tempBitmap.width
                val scaleY = output.height.toFloat() / tempBitmap.height
                canvas.scale(scaleX, scaleY)
                canvas.drawBitmap(tempBitmap, 0f, 0f, null)
                tempBitmap.recycle()
                true
            } else {
                tempBitmap.recycle()
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fallback capture using lockCanvas (unreliable for SurfaceView).
     */
    @Suppress("DEPRECATION")
    private fun captureSurfaceWithLockCanvas(
        surfaceView: SurfaceView,
        output: Bitmap
    ): Boolean {
        // This approach typically doesn't work well for SurfaceView
        // as the content is rendered on a different thread
        return try {
            val canvas = Canvas(output)
            val scaleX = output.width.toFloat() / surfaceView.width
            val scaleY = output.height.toFloat() / surfaceView.height
            canvas.scale(scaleX, scaleY)
            surfaceView.draw(canvas)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun release() {
        // No persistent resources to release
    }

    override fun isAvailable(): Boolean = true

    /**
     * Checks if a view requires special surface capture.
     */
    companion object {
        /**
         * Returns true if the view requires SurfaceCapture instead of standard capture.
         */
        fun requiresSurfaceCapture(view: View): Boolean {
            return view is SurfaceView || view is TextureView
        }

        /**
         * Finds any SurfaceView or TextureView children that need special handling.
         */
        fun findSurfaceViews(root: View): List<View> {
            val result = mutableListOf<View>()
            findSurfaceViewsRecursive(root, result)
            return result
        }

        private fun findSurfaceViewsRecursive(view: View, result: MutableList<View>) {
            if (view is SurfaceView || view is TextureView) {
                result.add(view)
            }
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    findSurfaceViewsRecursive(view.getChildAt(i), result)
                }
            }
        }
    }
}
