package io.github.ezoushen.blur.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View

/**
 * Interface for capturing view content to a bitmap for blurring.
 *
 * Different implementations can capture content from different sources:
 * - DecorView (standard views)
 * - SurfaceTexture (video, maps)
 * - HardwareBuffer (zero-copy on API 31+)
 */
interface ContentCapture {

    /**
     * Captures the content behind the blur view into the output bitmap.
     *
     * @param blurView The blur view (used for position calculation)
     * @param sourceView The view to capture content from (usually DecorView)
     * @param output The bitmap to draw captured content into
     * @param downsampleFactor Factor to reduce capture size
     * @return true if capture succeeded, false otherwise
     */
    fun capture(
        blurView: View,
        sourceView: View,
        output: Bitmap,
        downsampleFactor: Float
    ): Boolean

    /**
     * Releases any resources held by this capture implementation.
     */
    fun release()

    /**
     * Returns true if this capture implementation is available on the current device.
     */
    fun isAvailable(): Boolean
}

/**
 * Configuration for content capture.
 */
data class CaptureConfig(
    /** Factor to reduce capture dimensions (higher = smaller = faster) */
    val downsampleFactor: Float = 4f,

    /** Whether to include the view's background in the capture */
    val includeBackground: Boolean = true,

    /** Color to use when erasing the bitmap before capture */
    val backgroundColor: Int = 0
)
