package io.github.ezoushen.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.View
import io.github.ezoushen.blur.algorithm.VariableOpenGLBlur
import io.github.ezoushen.blur.capture.ContentCapture
import io.github.ezoushen.blur.capture.DecorViewCapture
import io.github.ezoushen.blur.capture.HardwareBufferCapture
import io.github.ezoushen.blur.capture.SurfaceTextureCapture
import io.github.ezoushen.blur.util.BitmapPool

/**
 * Controller for variable blur operations with gradient-based blur radius.
 *
 * This controller manages the lifecycle of variable blur operations where
 * the blur radius varies across the view based on a [BlurGradient].
 *
 * Usage:
 * ```kotlin
 * val controller = VariableBlurController(context)
 * controller.setGradient(BlurGradient.verticalGradient(0f, 30f))
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
 *
 * @see BlurGradient
 * @see VariableOpenGLBlur
 */
class VariableBlurController(
    private val context: Context,
    private var config: BlurConfig = BlurConfig.Default
) {
    companion object {
        /** Baseline downsample factor for consistent blur appearance */
        private const val BASELINE_DOWNSAMPLE = 4f

        /** Radius at which the full configured downsample factor is reached. */
        private const val FULL_DOWNSAMPLE_RADIUS = 16f
    }

    private val bitmapPool = BitmapPool(maxPoolSize = 4)
    private val algorithm = VariableOpenGLBlur()
    // API 29+: RenderNode capture (RecordingCanvas) instead of software canvas
    private val capture: ContentCapture =
        if (HardwareBufferCapture.isAvailable()) HardwareBufferCapture()
        else DecorViewCapture()

    // SurfaceTexture capture for zero-copy API 26-28 path
    private var surfaceTextureCapture: SurfaceTextureCapture? = null
    private var resolvedStrategy: BlurPipelineStrategy? = null

    private var gradient: BlurGradient? = null

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

        // Set initial overlay color from config
        algorithm.setOverlayColor(config.overlayColor)
    }

    /**
     * Sets the blur gradient for variable blur effect.
     *
     * @param gradient The gradient that defines how blur radius varies across the view
     */
    fun setGradient(gradient: BlurGradient) {
        if (this.gradient != gradient) {
            this.gradient = gradient
            algorithm.setGradient(gradient)
            isDirty = true
        }
    }

    /**
     * Gets the current blur gradient.
     */
    fun getGradient(): BlurGradient? = gradient

    /**
     * Updates the blur configuration.
     *
     * @param config New configuration to apply
     */
    fun setConfig(config: BlurConfig) {
        if (this.config != config) {
            if (this.config.pipelineStrategy != config.pipelineStrategy) {
                resolvedStrategy = null
            }
            this.config = config
            algorithm.setOverlayColor(config.overlayColor)
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

    fun addExcludedView(view: View) {
        when (capture) {
            is HardwareBufferCapture -> capture.addExcludedView(view)
            is DecorViewCapture -> capture.addExcludedView(view)
        }
        surfaceTextureCapture?.addExcludedView(view)
    }

    fun removeExcludedView(view: View) {
        when (capture) {
            is HardwareBufferCapture -> capture.removeExcludedView(view)
            is DecorViewCapture -> capture.removeExcludedView(view)
        }
        surfaceTextureCapture?.removeExcludedView(view)
    }

    fun setOutputSurface(surface: android.view.Surface?, width: Int = 0, height: Int = 0) {
        algorithm.setSurface(surface, width, height)
    }

    fun hasOutputSurface(): Boolean =
        algorithm.hasOutputSurface()

    /**
     * Resolves the pipeline strategy based on config and device capabilities.
     */
    private fun resolveStrategy(): BlurPipelineStrategy {
        resolvedStrategy?.let { return it }

        val requested = config.pipelineStrategy
        val resolved = when (requested) {
            // AUTO: use LEGACY for Kawase pipeline. EGLImage/SurfaceTexture
            // are available as opt-in for testing.
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
        val currentGradient = gradient ?: return false

        if (view.width == 0 || view.height == 0) return false

        // Check if dimensions changed
        val dimensionsChanged = view.width != lastWidth || view.height != lastHeight

        if (!isDirty && !dimensionsChanged) {
            return false
        }

        // Scale downsample factor with radius for smooth transitions
        val maxRadius = currentGradient.maxRadius
        val effectiveDownsample = if (maxRadius <= 0f) {
            1f
        } else {
            val t = (maxRadius / FULL_DOWNSAMPLE_RADIUS).coerceIn(0f, 1f)
            1f + (config.downsampleFactor - 1f) * t
        }

        // Calculate scaled dimensions
        val scaledWidth = (view.width / effectiveDownsample).toInt().coerceAtLeast(1)
        val scaledHeight = (view.height / effectiveDownsample).toInt().coerceAtLeast(1)

        // Scale the gradient's max radius for downsample-independent appearance
        val scaledMaxRadius = currentGradient.maxRadius * (BASELINE_DOWNSAMPLE / effectiveDownsample)

        val t0 = System.nanoTime()
        if (!algorithm.prepare(context, scaledWidth, scaledHeight, scaledMaxRadius)) {
            return false
        }
        val t1 = System.nanoTime()

        val strategy = resolveStrategy()
        val success = when (strategy) {
            BlurPipelineStrategy.EGL_IMAGE -> updateEglImage(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)
            BlurPipelineStrategy.SURFACE_TEXTURE -> updateSurfaceTexture(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)
            else -> updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)
        }
        val t2 = System.nanoTime()
        val totalUs = (t2 - t0) / 1000
        android.util.Log.i("BlurPerf", "VarCtrl dim=${scaledWidth}x${scaledHeight} strategy=$strategy prepare=${(t1-t0)/1000}us pipeline=${(t2-t1)/1000}us total=${totalUs}us")
        BlurPerfMonitor.report(0, (t2 - t1) / 1000, totalUs, strategy.name, "${scaledWidth}x${scaledHeight}")

        if (!success) return false

        lastWidth = view.width
        lastHeight = view.height
        isDirty = false

        return true
    }

    /**
     * Legacy capture+blur path.
     */
    private fun updateLegacy(
        view: View, source: View,
        scaledWidth: Int, scaledHeight: Int,
        scaledMaxRadius: Float, effectiveDownsample: Float
    ): Boolean {
        if (captureBitmap == null ||
            captureBitmap?.width != scaledWidth ||
            captureBitmap?.height != scaledHeight
        ) {
            captureBitmap?.let { bitmapPool.release(it) }
            captureBitmap = bitmapPool.acquire(scaledWidth, scaledHeight)
        }

        val captureOutput = captureBitmap ?: return false
        captureOutput.eraseColor(Color.TRANSPARENT)

        val tc0 = System.nanoTime()
        if (!capture.capture(view, source, captureOutput, effectiveDownsample)) {
            return false
        }
        val tc1 = System.nanoTime()

        applyPreBlurTint(captureOutput)

        val tb0 = System.nanoTime()
        blurredBitmap = algorithm.blur(captureOutput, scaledMaxRadius)
        val tb1 = System.nanoTime()
        android.util.Log.i("BlurPerf", "  Legacy capture=${(tc1-tc0)/1000}us blur=${(tb1-tb0)/1000}us captureType=${capture::class.simpleName}")
        return true
    }

    /**
     * EGL_IMAGE path (API 29+): zero-copy HardwareBuffer -> EGLImage -> GL_TEXTURE_2D.
     */
    private fun updateEglImage(
        view: View, source: View,
        scaledWidth: Int, scaledHeight: Int,
        scaledMaxRadius: Float, effectiveDownsample: Float
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)

        val hwCapture = capture as? HardwareBufferCapture
            ?: return updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)

        val image = hwCapture.captureToHardwareBuffer(view, source, scaledWidth, scaledHeight, effectiveDownsample)
            ?: return updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)

        try {
            val hwBuffer = image.hardwareBuffer
                ?: return updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)
            try {
                if (!algorithm.setInputFromHardwareBuffer(hwBuffer)) {
                    return updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)
                }
            } finally {
                hwBuffer.close()
            }
        } finally {
            image.close()
        }

        val dummyBitmap = captureBitmap ?: run {
            captureBitmap = bitmapPool.acquire(scaledWidth, scaledHeight)
            captureBitmap ?: return false
        }
        blurredBitmap = algorithm.blur(dummyBitmap, scaledMaxRadius)
        return true
    }

    /**
     * SURFACE_TEXTURE path (API 26+): zero-copy Surface -> SurfaceTexture -> GL_TEXTURE_EXTERNAL_OES.
     */
    private fun updateSurfaceTexture(
        view: View, source: View,
        scaledWidth: Int, scaledHeight: Int,
        scaledMaxRadius: Float, effectiveDownsample: Float
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)

        var stCapture = surfaceTextureCapture
        if (stCapture == null) {
            stCapture = SurfaceTextureCapture()
            surfaceTextureCapture = stCapture
        }

        val externalTexId = algorithm.getExternalInputTextureId()
        if (externalTexId == 0) return updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)

        stCapture.init(externalTexId, scaledWidth, scaledHeight)

        if (!stCapture.capture(view, source, scaledWidth, scaledHeight)) {
            return updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)
        }

        val dummyBitmap = captureBitmap ?: run {
            captureBitmap = bitmapPool.acquire(scaledWidth, scaledHeight)
            captureBitmap ?: return false
        }
        blurredBitmap = algorithm.blur(dummyBitmap, scaledMaxRadius)
        return true
    }

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
                tintPaint.blendMode = null
            }
        }

        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), tintPaint)
    }

    /**
     * Draws the blurred content to the canvas.
     *
     * Call this in the blur view's onDraw method.
     * Note: Overlay color is now applied in the shader to follow the blur gradient.
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
        // Note: Overlay is applied in the shader to follow blur gradient
        srcRect.set(0, 0, blurred.width, blurred.height)
        dstRect.set(0, 0, view.width, view.height)
        canvas.drawBitmap(blurred, srcRect, dstRect, paint)
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
