package io.github.ezoushen.blur.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View

/**
 * Captures view content by drawing the DecorView to a scaled bitmap.
 *
 * This is the primary capture method for standard views. It works by:
 * 1. Getting the screen position of both the blur view and source view
 * 2. Scaling the canvas to match the downsampled bitmap size
 * 3. Translating to capture only the region behind the blur view
 * 4. Drawing the source view (typically DecorView) to the canvas
 *
 * Limitations:
 * - Cannot capture SurfaceView/TextureView content (rendered to separate surface)
 * - May have performance overhead on complex view hierarchies
 */
class DecorViewCapture : ContentCapture {

    private val sourceLocation = IntArray(2)
    private val blurViewLocation = IntArray(2)

    // Tracks if we're currently in a capture operation
    // Used to prevent infinite recursion when blur view is drawn
    @Volatile
    private var isCapturing = false

    // Views to hide during capture (e.g., content overlays that should not be blurred).
    // These are set to INVISIBLE before capture and restored after.
    private val excludedViews = mutableListOf<View>()

    /**
     * Returns true if a capture operation is currently in progress.
     *
     * This can be checked in the blur view's draw() method to avoid
     * infinite recursion.
     */
    fun isCurrentlyCapturing(): Boolean = isCapturing

    /**
     * Register a view to be hidden during capture.
     * This prevents the view's content from appearing in the captured bitmap,
     * avoiding glow artifacts when the view is also drawn sharp on top of the blur.
     */
    fun addExcludedView(view: View) {
        if (view !in excludedViews) {
            excludedViews.add(view)
        }
    }

    /**
     * Unregister a previously excluded view.
     */
    fun removeExcludedView(view: View) {
        excludedViews.remove(view)
    }

    override fun capture(
        blurView: View,
        sourceView: View,
        output: Bitmap,
        downsampleFactor: Float
    ): Boolean {
        if (blurView.width == 0 || blurView.height == 0) {
            return false
        }

        // Track which views we hide so we can restore them in finally
        val hiddenViews = mutableListOf<View>()

        try {
            isCapturing = true

            // Hide excluded views during capture to prevent their content
            // from appearing in the blurred bitmap (which causes glow artifacts)
            for (view in excludedViews) {
                if (view.visibility == View.VISIBLE) {
                    view.visibility = View.INVISIBLE
                    hiddenViews.add(view)
                }
            }

            // Get screen positions
            sourceView.getLocationOnScreen(sourceLocation)
            blurView.getLocationOnScreen(blurViewLocation)

            // Calculate offset from source view to blur view
            val offsetX = blurViewLocation[0] - sourceLocation[0]
            val offsetY = blurViewLocation[1] - sourceLocation[1]

            // Create canvas for the output bitmap
            val canvas = Canvas(output)

            // Calculate scale factor
            val scaleX = output.width.toFloat() / blurView.width
            val scaleY = output.height.toFloat() / blurView.height

            // Save canvas state
            val saveCount = canvas.save()

            try {
                // Scale down to bitmap size
                canvas.scale(scaleX, scaleY)

                // Translate to capture region behind blur view
                canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())

                // Draw the background if present
                sourceView.background?.draw(canvas)

                // Draw the view hierarchy
                sourceView.draw(canvas)
            } finally {
                // Restore canvas state
                canvas.restoreToCount(saveCount)
            }

            return true
        } catch (e: StopCaptureException) {
            // Expected when blur view tries to draw itself during capture
            return true
        } catch (e: Exception) {
            return false
        } finally {
            // Restore views that were hidden during capture
            for (view in hiddenViews) {
                view.visibility = View.VISIBLE
            }
            isCapturing = false
        }
    }

    override fun release() {
        // No resources to release
    }

    override fun isAvailable(): Boolean = true

    /**
     * Exception thrown to stop capture when blur view attempts to draw itself.
     *
     * This prevents infinite recursion:
     * BlurView.draw() -> DecorView.draw() -> BlurView.draw() (throws) -> caught
     */
    class StopCaptureException : RuntimeException()

    companion object {
        /**
         * Singleton exception instance to avoid allocation overhead.
         */
        val STOP_EXCEPTION = StopCaptureException()
    }
}
