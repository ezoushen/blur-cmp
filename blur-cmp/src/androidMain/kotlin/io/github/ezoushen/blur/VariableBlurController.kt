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
import io.github.ezoushen.blur.capture.SurfaceTextureCapture
import io.github.ezoushen.blur.cmp.TintOrder
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
    private val capture: ContentCapture = DecorViewCapture()

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
    private var configDirty = false
    private var contentDirty = true  // first-frame guarantee
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
        this.contentDirty = true
        this.isInitialized = true
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
            configDirty = true
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
            if (this.config.tintOrder != config.tintOrder) {
                contentDirty = true
            } else if (config.tintOrder == TintOrder.PRE_BLUR &&
                (this.config.tintColor != config.tintColor ||
                 this.config.tintBlendModeOrdinal != config.tintBlendModeOrdinal)) {
                contentDirty = true
            } else {
                configDirty = true
            }
            this.config = config
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
        configDirty = true
        contentDirty = true
    }

    fun markContentDirty() {
        contentDirty = true
    }

    fun hasPendingDirty(): Boolean =
        configDirty || contentDirty

    /**
     * Checks if the controller is currently capturing.
     *
     * Use this in the blur view's draw() method to prevent infinite recursion.
     */
    fun isCapturing(): Boolean {
        return (capture as? DecorViewCapture)?.isCurrentlyCapturing() == true
    }

    fun addExcludedView(view: View) {
        (capture as? DecorViewCapture)?.addExcludedView(view)
        surfaceTextureCapture?.addExcludedView(view)
    }

    fun removeExcludedView(view: View) {
        (capture as? DecorViewCapture)?.removeExcludedView(view)
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
            BlurPipelineStrategy.AUTO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    algorithm.hasExternalOesSupport()) {
                    BlurPipelineStrategy.SURFACE_TEXTURE
                } else {
                    BlurPipelineStrategy.LEGACY
                }
            }
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
        if (dimensionsChanged) contentDirty = true

        if (!configDirty && !contentDirty) {
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

        // Promote configDirty to contentDirty if scaled dimensions changed
        if (captureBitmap != null && (captureBitmap?.width != scaledWidth || captureBitmap?.height != scaledHeight)) {
            contentDirty = true
        }

        if (!algorithm.prepare(context, scaledWidth, scaledHeight, scaledMaxRadius)) {
            return false
        }

        val strategy = resolveStrategy()
        val t0 = if (BlurPerfMonitor.enabled) System.nanoTime() else 0L
        val success = if (contentDirty) {
            when (strategy) {
                BlurPipelineStrategy.SURFACE_TEXTURE -> updateSurfaceTexture(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)
                else -> updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)
            }
        } else {
            // configDirty only: skip capture, re-blur cached bitmap
            val cached = captureBitmap
            if (cached != null) {
                blurredBitmap = algorithm.blur(cached, scaledMaxRadius)
                true
            } else {
                // No cached bitmap yet — fall back to full capture
                when (strategy) {
                    BlurPipelineStrategy.SURFACE_TEXTURE -> updateSurfaceTexture(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)
                    else -> updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)
                }
            }
        }
        if (BlurPerfMonitor.enabled) {
            val totalUs = (System.nanoTime() - t0) / 1000
            android.util.Log.i("BlurPerf", "VarCtrl dim=${scaledWidth}x${scaledHeight} strategy=$strategy pipeline=${totalUs}us")
            BlurPerfMonitor.report(0, totalUs, totalUs, strategy.name, "${scaledWidth}x${scaledHeight}")
        }

        if (!success) return false

        lastWidth = view.width
        lastHeight = view.height
        configDirty = false
        contentDirty = false

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

        val tc0 = if (BlurPerfMonitor.enabled) System.nanoTime() else 0L
        if (!capture.capture(view, source, captureOutput, effectiveDownsample)) {
            return false
        }
        if (config.tintOrder == TintOrder.PRE_BLUR) {
            applyTint(captureOutput)
        }
        val tb0 = if (BlurPerfMonitor.enabled) System.nanoTime() else 0L
        blurredBitmap = algorithm.blur(captureOutput, scaledMaxRadius)
        if (BlurPerfMonitor.enabled) {
            val captureUs = (tb0 - tc0) / 1000
            val blurUs = (System.nanoTime() - tb0) / 1000
            android.util.Log.i("BlurPerf", "  Legacy capture=${captureUs}us blur=${blurUs}us captureType=${capture::class.simpleName}")
        }
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

        val tc0 = if (BlurPerfMonitor.enabled) System.nanoTime() else 0L
        if (!stCapture.capture(view, source, scaledWidth, scaledHeight)) {
            return updateLegacy(view, source, scaledWidth, scaledHeight, scaledMaxRadius, effectiveDownsample)
        }

        val dummyBitmap = captureBitmap ?: run {
            captureBitmap = bitmapPool.acquire(scaledWidth, scaledHeight)
            captureBitmap
        } ?: return false

        if (config.tintOrder == TintOrder.PRE_BLUR) {
            applyTint(dummyBitmap)
        }

        val tb0 = if (BlurPerfMonitor.enabled) System.nanoTime() else 0L
        blurredBitmap = algorithm.blur(dummyBitmap, scaledMaxRadius)
        if (BlurPerfMonitor.enabled) {
            val captureUs = (tb0 - tc0) / 1000
            val blurUs = (System.nanoTime() - tb0) / 1000
            android.util.Log.i("BlurPerf", "  STCapture capture=${captureUs}us blur=${blurUs}us")
        }
        return true
    }

    /**
     * Applies tint with blend mode to the captured bitmap (pre-blur path).
     */
    private fun applyTint(bitmap: Bitmap) {
        val color = config.tintColor ?: return

        val canvas = Canvas(bitmap)
        tintPaint.color = color

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ordinal = config.tintBlendModeOrdinal
            tintPaint.blendMode = if (ordinal != null) {
                try { android.graphics.BlendMode.values()[ordinal] } catch (_: Exception) { null }
            } else null
        }

        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), tintPaint)
    }

    /**
     * Draws a tint rectangle on the canvas using the configured tint color and blend mode.
     */
    private fun drawTint(canvas: Canvas) {
        val color = config.tintColor ?: return
        tintPaint.color = color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ordinal = config.tintBlendModeOrdinal
            tintPaint.blendMode = if (ordinal != null) {
                try { android.graphics.BlendMode.values()[ordinal] } catch (_: Exception) { null }
            } else null
        }
        val view = blurView ?: return
        canvas.drawRect(0f, 0f, view.width.toFloat(), view.height.toFloat(), tintPaint)
    }

    /**
     * Draws the blurred content and optional post-blur tint to the canvas.
     *
     * Call this in the blur view's onDraw method.
     *
     * @param canvas Canvas to draw to
     */
    fun draw(canvas: Canvas) {
        val blurred = blurredBitmap ?: return
        val view = blurView ?: return

        srcRect.set(0, 0, blurred.width, blurred.height)
        dstRect.set(0, 0, view.width, view.height)
        canvas.drawBitmap(blurred, srcRect, dstRect, paint)

        if (config.tintOrder == TintOrder.POST_BLUR) {
            drawTint(canvas)
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
        configDirty = false
        contentDirty = true
        isInitialized = false
    }

    /**
     * Returns the name of the blur algorithm being used.
     */
    fun getAlgorithmName(): String = algorithm.getName()
}
