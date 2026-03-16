package io.github.ezoushen.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.View
import io.github.ezoushen.blur.algorithm.BlurAlgorithm
import io.github.ezoushen.blur.algorithm.BlurAlgorithmFactory
import io.github.ezoushen.blur.capture.ContentCapture
import io.github.ezoushen.blur.capture.DecorViewCapture
import io.github.ezoushen.blur.util.BitmapPool

/**
 * Orchestrates the blur pipeline: capture -> blur -> render.
 *
 * This controller manages the lifecycle of blur operations and provides
 * caching and dirty tracking to avoid unnecessary work.
 *
 * Usage:
 * ```
 * val controller = BlurController(context)
 * controller.init(blurView, decorView)
 *
 * // In onPreDraw:
 * if (controller.update()) {
 *     blurView.invalidate()
 * }
 *
 * // In onDraw:
 * controller.draw(canvas)
 *
 * // When done:
 * controller.release()
 * ```
 */
class BlurController(
    private val context: Context,
    private var config: BlurConfig = BlurConfig.Default
) {
    companion object {
        /** Baseline downsample factor for consistent blur appearance */
        private const val BASELINE_DOWNSAMPLE = 4f
    }

    private val bitmapPool = BitmapPool(maxPoolSize = 4)
    private val algorithm: BlurAlgorithm = BlurAlgorithmFactory.create(context)
    private val capture: ContentCapture = DecorViewCapture()

    private var captureBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null

    private var blurView: View? = null
    private var sourceView: View? = null

    private var lastWidth = 0
    private var lastHeight = 0
    private var isDirty = true
    private var isInitialized = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    /**
     * Initializes the controller with the blur view and source view.
     *
     * @param blurView The view that will display the blur effect
     * @param sourceView The view to capture content from (usually DecorView)
     */
    fun init(blurView: View, sourceView: View) {
        this.blurView = blurView
        this.sourceView = sourceView
        this.isDirty = true
        this.isInitialized = true
    }

    /**
     * Updates the blur configuration.
     *
     * @param config New configuration to apply
     */
    fun setConfig(config: BlurConfig) {
        if (this.config != config) {
            this.config = config
            isDirty = true
        }
    }

    /**
     * Returns the current blur configuration.
     */
    fun getConfig(): BlurConfig = config

    /**
     * Marks the blur as needing update.
     *
     * Call this when the content behind the blur view has changed.
     */
    fun invalidate() {
        isDirty = true
    }

    /**
     * Checks if the controller is currently capturing.
     *
     * Use this in the blur view's draw() method to prevent infinite recursion.
     */
    fun isCapturing(): Boolean {
        return (capture as? DecorViewCapture)?.isCurrentlyCapturing() == true
    }

    /**
     * Register a view to exclude from capture. The view is hidden during capture
     * to prevent its content from appearing in the blurred bitmap.
     */
    fun addExcludedView(view: View) {
        (capture as? DecorViewCapture)?.addExcludedView(view)
    }

    /**
     * Unregister a previously excluded view.
     */
    fun removeExcludedView(view: View) {
        (capture as? DecorViewCapture)?.removeExcludedView(view)
    }

    /**
     * Updates the blur if needed.
     *
     * Call this in onPreDraw or before drawing.
     *
     * @return true if the blur was updated and the view should be invalidated
     */
    fun update(): Boolean {
        if (!isInitialized) return false

        val view = blurView ?: return false
        val source = sourceView ?: return false

        if (view.width == 0 || view.height == 0) return false

        // Check if dimensions changed
        val dimensionsChanged = view.width != lastWidth || view.height != lastHeight

        if (!isDirty && !dimensionsChanged) {
            return false
        }

        // Calculate scaled dimensions
        val scaledWidth = (view.width / config.downsampleFactor).toInt().coerceAtLeast(1)
        val scaledHeight = (view.height / config.downsampleFactor).toInt().coerceAtLeast(1)

        // Get or create capture bitmap
        if (captureBitmap == null ||
            captureBitmap?.width != scaledWidth ||
            captureBitmap?.height != scaledHeight
        ) {
            captureBitmap?.let { bitmapPool.release(it) }
            captureBitmap = bitmapPool.acquire(scaledWidth, scaledHeight)
        }

        val captureOutput = captureBitmap ?: return false

        // Clear the bitmap
        captureOutput.eraseColor(Color.TRANSPARENT)

        // Capture content
        if (!capture.capture(view, source, captureOutput, config.downsampleFactor)) {
            return false
        }

        // Scale radius to maintain consistent blur appearance across different downsample factors
        // Using 4x as baseline: at 8x downsample, halve the radius; at 2x, double it
        val scaledRadius = config.radius * (BASELINE_DOWNSAMPLE / config.downsampleFactor)

        // Prepare blur algorithm
        if (!algorithm.prepare(context, scaledWidth, scaledHeight, scaledRadius)) {
            return false
        }

        // Apply blur
        blurredBitmap = algorithm.blur(captureOutput, scaledRadius)

        lastWidth = view.width
        lastHeight = view.height
        isDirty = false

        return true
    }

    /**
     * Draws the blurred content and overlay to the canvas.
     *
     * Call this in the blur view's onDraw method.
     *
     * @param canvas Canvas to draw to
     */
    fun draw(canvas: Canvas) {
        val blurred = blurredBitmap
        val view = blurView

        if (blurred == null || view == null) {
            return
        }

        // Draw blurred bitmap scaled to view size
        srcRect.set(0, 0, blurred.width, blurred.height)
        dstRect.set(0, 0, view.width, view.height)
        canvas.drawBitmap(blurred, srcRect, dstRect, paint)

        // Draw overlay color (alpha is already included in the color)
        val overlayColor = config.overlayColor
        if (overlayColor != null) {
            canvas.drawColor(overlayColor)
        }
    }

    /**
     * Releases all resources held by this controller.
     *
     * The controller can be reused after calling init() again.
     */
    fun release() {
        algorithm.release()
        capture.release()

        captureBitmap?.let { bitmapPool.release(it) }
        captureBitmap = null

        blurredBitmap = null

        bitmapPool.clear()

        blurView = null
        sourceView = null

        lastWidth = 0
        lastHeight = 0
        isDirty = true
        isInitialized = false
    }

    /**
     * Returns the name of the blur algorithm being used.
     */
    fun getAlgorithmName(): String = algorithm.getName()
}
