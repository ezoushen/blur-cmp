package io.github.ezoushen.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.View
import io.github.ezoushen.blur.algorithm.BlurAlgorithm
import io.github.ezoushen.blur.algorithm.BlurAlgorithmFactory
import io.github.ezoushen.blur.algorithm.OpenGLBlur
import io.github.ezoushen.blur.capture.ContentCapture
import io.github.ezoushen.blur.capture.DecorViewCapture
import io.github.ezoushen.blur.capture.HardwareBufferCapture
import io.github.ezoushen.blur.capture.SurfaceTextureCapture
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

        /** Radius at which the full configured downsample factor is reached.
         *  Below this, downsample ramps linearly from 1.0 to avoid pixelation. */
        private const val FULL_DOWNSAMPLE_RADIUS = 16f
    }

    private val bitmapPool = BitmapPool(maxPoolSize = 4)
    private val algorithm: BlurAlgorithm = BlurAlgorithmFactory.create(context)
    // API 29+: RenderNode capture (RecordingCanvas, ~0ms) instead of software canvas (2-5ms)
    private val capture: ContentCapture =
        if (HardwareBufferCapture.isAvailable()) HardwareBufferCapture()
        else DecorViewCapture()

    // SurfaceTexture capture for zero-copy API 26-28 path
    private var surfaceTextureCapture: SurfaceTextureCapture? = null
    private var resolvedStrategy: BlurPipelineStrategy? = null

    private var captureBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null

    private var blurView: View? = null
    private var sourceView: View? = null

    private var lastWidth = 0
    private var lastHeight = 0
    private var isDirty = true
    private var isInitialized = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
            // Reset resolved strategy if pipeline strategy changed
            if (this.config.pipelineStrategy != config.pipelineStrategy) {
                resolvedStrategy = null
            }
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
        return when (capture) {
            is HardwareBufferCapture -> capture.isCurrentlyCapturing()
            is DecorViewCapture -> capture.isCurrentlyCapturing()
            else -> false
        }
    }

    /**
     * Register a view to exclude from capture. The view is hidden during capture
     * to prevent its content from appearing in the blurred bitmap.
     */
    fun addExcludedView(view: View) {
        when (capture) {
            is HardwareBufferCapture -> capture.addExcludedView(view)
            is DecorViewCapture -> capture.addExcludedView(view)
        }
        surfaceTextureCapture?.addExcludedView(view)
    }

    /**
     * Unregister a previously excluded view.
     */
    fun removeExcludedView(view: View) {
        when (capture) {
            is HardwareBufferCapture -> capture.removeExcludedView(view)
            is DecorViewCapture -> capture.removeExcludedView(view)
        }
        surfaceTextureCapture?.removeExcludedView(view)
    }

    fun setOutputSurface(surface: android.view.Surface?, width: Int = 0, height: Int = 0) {
        (algorithm as? OpenGLBlur)?.setSurface(surface, width, height)
    }

    fun hasOutputSurface(): Boolean =
        (algorithm as? OpenGLBlur)?.hasOutputSurface() == true

    /**
     * Resolves the pipeline strategy based on config and device capabilities.
     * Caches the result to avoid repeated GL extension queries.
     */
    private fun resolveStrategy(): BlurPipelineStrategy {
        resolvedStrategy?.let { return it }

        val requested = config.pipelineStrategy
        val resolved = when (requested) {
            // AUTO: use LEGACY for Kawase pipeline. RecordingCanvas capture +
            // TextureView output is already fast. EGLImage/SurfaceTexture are
            // available as opt-in for testing but per-frame EGLImage overhead
            // exceeds the crossing cost at downsampled resolutions.
            BlurPipelineStrategy.AUTO -> BlurPipelineStrategy.LEGACY
            else -> requested
        }
        resolvedStrategy = resolved
        return resolved
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

        // Scale downsample factor with radius: full resolution at radius=0,
        // ramping to the configured factor as blur increases. This avoids
        // visible pixelation jumps during radius animations and ensures
        // radius=0 produces a sharp (1:1) pass-through.
        val effectiveDownsample = if (config.radius <= 0f) {
            1f
        } else {
            val t = (config.radius / FULL_DOWNSAMPLE_RADIUS).coerceIn(0f, 1f)
            1f + (config.downsampleFactor - 1f) * t
        }

        // Calculate scaled dimensions
        val scaledWidth = (view.width / effectiveDownsample).toInt().coerceAtLeast(1)
        val scaledHeight = (view.height / effectiveDownsample).toInt().coerceAtLeast(1)

        // Scale radius to maintain consistent blur appearance across different downsample factors
        val scaledRadius = config.radius * (BASELINE_DOWNSAMPLE / effectiveDownsample)

        // Prepare blur algorithm (needed for GL extension queries in resolveStrategy)
        if (!algorithm.prepare(context, scaledWidth, scaledHeight, scaledRadius)) {
            return false
        }

        val strategy = resolveStrategy()

        // Dispatch to the appropriate capture+blur path
        val success = when (strategy) {
            BlurPipelineStrategy.EGL_IMAGE -> updateEglImage(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)
            BlurPipelineStrategy.SURFACE_TEXTURE -> updateSurfaceTexture(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)
            else -> updateLegacy(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)
        }

        if (!success) return false

        lastWidth = view.width
        lastHeight = view.height
        isDirty = false

        return true
    }

    /**
     * Legacy capture+blur path: bitmap capture -> texImage2D (2 CPU-GPU crossings).
     */
    private fun updateLegacy(
        view: View, source: View,
        scaledWidth: Int, scaledHeight: Int,
        scaledRadius: Float, effectiveDownsample: Float
    ): Boolean {
        // Get or create capture bitmap
        if (captureBitmap == null ||
            captureBitmap?.width != scaledWidth ||
            captureBitmap?.height != scaledHeight
        ) {
            captureBitmap?.let { bitmapPool.release(it) }
            captureBitmap = bitmapPool.acquire(scaledWidth, scaledHeight)
        }

        val captureOutput = captureBitmap ?: return false
        captureOutput.eraseColor(Color.TRANSPARENT)

        if (!capture.capture(view, source, captureOutput, effectiveDownsample)) {
            return false
        }

        applyPreBlurTint(captureOutput)

        blurredBitmap = algorithm.blur(captureOutput, scaledRadius)
        return true
    }

    /**
     * EGL_IMAGE path (API 29+): HardwareBuffer -> EGLImage -> GL_TEXTURE_2D. Zero CPU-GPU crossings.
     */
    private fun updateEglImage(
        view: View, source: View,
        scaledWidth: Int, scaledHeight: Int,
        scaledRadius: Float, effectiveDownsample: Float
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return updateLegacy(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)

        val hwCapture = capture as? HardwareBufferCapture
            ?: return updateLegacy(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)
        val glBlur = algorithm as? OpenGLBlur
            ?: return updateLegacy(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)

        val image = hwCapture.captureToHardwareBuffer(view, source, scaledWidth, scaledHeight, effectiveDownsample)
            ?: return updateLegacy(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)

        try {
            val hwBuffer = image.hardwareBuffer
                ?: return updateLegacy(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)

            try {
                if (!glBlur.setInputFromHardwareBuffer(hwBuffer)) {
                    return updateLegacy(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)
                }
            } finally {
                hwBuffer.close()
            }
        } finally {
            image.close()
        }

        // Blur with zero-copy input (inputTexture is already set)
        // Pass a dummy bitmap — the blur method will use inputTexture instead of texImage2D
        val dummyBitmap = captureBitmap ?: run {
            captureBitmap = bitmapPool.acquire(scaledWidth, scaledHeight)
            captureBitmap ?: return false
        }
        blurredBitmap = algorithm.blur(dummyBitmap, scaledRadius)
        return true
    }

    /**
     * SURFACE_TEXTURE path (API 26+): Surface.lockHardwareCanvas -> SurfaceTexture -> GL_TEXTURE_EXTERNAL_OES.
     * Zero CPU-GPU crossings.
     */
    private fun updateSurfaceTexture(
        view: View, source: View,
        scaledWidth: Int, scaledHeight: Int,
        scaledRadius: Float, effectiveDownsample: Float
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return updateLegacy(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)

        val glBlur = algorithm as? OpenGLBlur
            ?: return updateLegacy(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)

        // Initialize SurfaceTextureCapture if needed
        var stCapture = surfaceTextureCapture
        if (stCapture == null) {
            stCapture = SurfaceTextureCapture()
            surfaceTextureCapture = stCapture
        }

        // Get or create the external OES texture
        val externalTexId = glBlur.getExternalInputTextureId()
        if (externalTexId == 0) return updateLegacy(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)

        // Initialize SurfaceTexture with the GL texture
        stCapture.init(externalTexId, scaledWidth, scaledHeight)

        // Forward excluded views
        // Note: excluded views are managed separately per capture instance

        if (!stCapture.capture(view, source, scaledWidth, scaledHeight)) {
            return updateLegacy(view, source, scaledWidth, scaledHeight, scaledRadius, effectiveDownsample)
        }

        // Blur with external OES input
        val dummyBitmap = captureBitmap ?: run {
            captureBitmap = bitmapPool.acquire(scaledWidth, scaledHeight)
            captureBitmap ?: return false
        }
        blurredBitmap = algorithm.blur(dummyBitmap, scaledRadius)
        return true
    }

    /**
     * Applies pre-blur tint with blend mode to the captured bitmap.
     * This is used for non-Normal blend modes where the tint should be
     * part of the blurred content (capture → tint → blur → render).
     */
    private fun applyPreBlurTint(bitmap: Bitmap) {
        val tintColor = config.preBlurTintColor ?: return
        val blendOrdinal = config.preBlurBlendModeOrdinal ?: return

        val canvas = Canvas(bitmap)
        tintPaint.color = tintColor

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val blendMode = android.graphics.BlendMode.values()[blendOrdinal]
                tintPaint.blendMode = blendMode
            } catch (_: Exception) {
                // Fallback: just draw with SRC_OVER
                tintPaint.blendMode = null
            }
        }

        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), tintPaint)
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

        surfaceTextureCapture?.release()
        surfaceTextureCapture = null
        resolvedStrategy = null

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
