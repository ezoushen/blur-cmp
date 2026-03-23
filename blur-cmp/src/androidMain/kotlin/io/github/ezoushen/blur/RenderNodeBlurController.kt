package io.github.ezoushen.blur

import android.graphics.Bitmap
import android.graphics.BlendMode as AndroidBlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/**
 * GPU-resident blur controller for API 31+ using RenderNode + RenderEffect.
 *
 * Pipeline:
 *   1. decorView.draw(RecordingCanvas) — records display list refs (~0ms vs 2-5ms software)
 *   2. HardwareRenderer renders captureNode → ImageReader → HardwareBuffer
 *   3. Bitmap.wrapHardwareBuffer — zero-copy GPU-resident bitmap
 *   4. canvas.drawBitmap in BlurView.onDraw — HWUI draws without re-upload
 *
 * The HardwareRenderer step is needed to break the circular RenderNode reference:
 * captureNode → decorView tree → BlurView → drawRenderNode(captureNode) → cycle.
 * By rasterizing captureNode to a HardwareBuffer, we sever the graph.
 *
 * Compared to [BlurController]:
 * - Capture: RecordingCanvas (GPU draw ops) vs software Canvas (CPU rasterization)
 * - Blur: RenderEffect on GPU RenderThread vs OpenGL Kawase
 * - Output: Hardware Bitmap (GPU-resident) vs software Bitmap + re-upload
 * - Dirty flags: No side effects (no PFLAG_DIRTY_MASK clearing)
 */
@RequiresApi(Build.VERSION_CODES.S)
class RenderNodeBlurController {

    private var config: BlurConfig = BlurConfig.Default

    private var blurView: View? = null
    private var sourceView: View? = null
    private var isInitialized = false
    private var isDirty = true

    @Volatile
    private var isCurrentlyCapturing = false

    private val captureNode = RenderNode("BlurCapture")
    private val excludedViews = mutableListOf<View>()

    // HardwareRenderer pipeline to rasterize the blurred captureNode
    private var imageReader: ImageReader? = null
    private var hardwareRenderer: HardwareRenderer? = null
    private var outputBitmap: Bitmap? = null

    private var lastWidth = 0
    private var lastHeight = 0

    private val sourceLocation = IntArray(2)
    private val blurViewLocation = IntArray(2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    fun init(blurView: View, sourceView: View) {
        this.blurView = blurView
        this.sourceView = sourceView
        this.isDirty = true
        this.isInitialized = true
    }

    fun setConfig(config: BlurConfig) {
        if (this.config != config) {
            this.config = config
            isDirty = true
        }
    }

    fun getConfig(): BlurConfig = config

    fun invalidate() {
        isDirty = true
    }

    fun isCapturing(): Boolean = isCurrentlyCapturing

    fun addExcludedView(view: View) {
        if (view !in excludedViews) excludedViews.add(view)
    }

    fun removeExcludedView(view: View) {
        excludedViews.remove(view)
    }

    fun update(): Boolean {
        if (!isInitialized) return false

        val view = blurView ?: return false
        val source = sourceView ?: return false

        if (view.width == 0 || view.height == 0) return false
        if (!isDirty) return false

        val perf = BlurPerfMonitor.enabled
        val t0 = if (perf) System.nanoTime() else 0L

        // Calculate offsets
        source.getLocationOnScreen(sourceLocation)
        view.getLocationOnScreen(blurViewLocation)
        val offsetX = (blurViewLocation[0] - sourceLocation[0]).toFloat()
        val offsetY = (blurViewLocation[1] - sourceLocation[1]).toFloat()

        // Ensure HardwareRenderer pipeline is sized correctly
        if (view.width != lastWidth || view.height != lastHeight) {
            releaseRendererResources()
            if (!initRendererResources(view.width, view.height)) return false
            lastWidth = view.width
            lastHeight = view.height
        }

        val reader = imageReader ?: return false
        val renderer = hardwareRenderer ?: return false

        // Hide BlurView + excluded views during capture to prevent
        // them from appearing in the blurred output.
        val hiddenViews = mutableListOf<View>()
        try {
            isCurrentlyCapturing = true

            if (view.visibility == View.VISIBLE) {
                view.visibility = View.INVISIBLE
                hiddenViews.add(view)
            }
            for (excluded in excludedViews) {
                if (excluded.visibility == View.VISIBLE) {
                    excluded.visibility = View.INVISIBLE
                    hiddenViews.add(excluded)
                }
            }

            // Record source view into captureNode using RecordingCanvas.
            // This records display list references (pointers), not pixels.
            captureNode.setPosition(0, 0, view.width, view.height)
            val canvas = captureNode.beginRecording(view.width, view.height)
            try {
                canvas.translate(-offsetX, -offsetY)
                source.draw(canvas)
            } finally {
                captureNode.endRecording()
            }
        } finally {
            for (hidden in hiddenViews) {
                hidden.visibility = View.VISIBLE
            }
            isCurrentlyCapturing = false
        }

        // Apply blur + tint as RenderEffect
        applyRenderEffect()

        // API 31 bug: RenderNode doesn't refresh on property-only changes.
        // Re-apply RenderEffect to force redraw. (Haze #77)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
            applyRenderEffect()
        }

        // Rasterize the blurred captureNode via HardwareRenderer → ImageReader.
        // This severs the RenderNode graph (no circular reference in BlurView's
        // display list) while keeping everything on the GPU.
        renderer.setContentRoot(captureNode)
        val syncResult = renderer.createRenderRequest()
            .setWaitForPresent(true)
            .syncAndDraw()

        if (syncResult != HardwareRenderer.SYNC_OK &&
            syncResult != HardwareRenderer.SYNC_REDRAW_REQUESTED
        ) {
            return false
        }

        val image = reader.acquireLatestImage() ?: return false
        try {
            val hwBuffer = image.hardwareBuffer ?: return false
            try {
                outputBitmap?.recycle()
                outputBitmap = Bitmap.wrapHardwareBuffer(
                    hwBuffer, ColorSpace.get(ColorSpace.Named.SRGB)
                )
            } finally {
                hwBuffer.close()
            }
        } finally {
            image.close()
        }

        isDirty = false
        if (perf) {
            val totalUs = (System.nanoTime() - t0) / 1000
            BlurPerfMonitor.report(totalUs, 0, totalUs, "RENDER_EFFECT", "${view.width}x${view.height}")
        }
        return true
    }

    private fun applyRenderEffect() {
        val radius = config.radius
        if (radius <= 0f) {
            captureNode.setRenderEffect(null)
            return
        }

        // Scale radius to match Kawase pipeline visual output.
        // The Kawase path blurs a downsampled image (default 4x), so a radius of R
        // on a 1/4 resolution image produces visual blur ≈ R * downsampleFactor
        // at full resolution. RenderEffect operates at full resolution, so we must
        // multiply by the same factor for visual parity.
        // Also matches iOS CAFilter gaussianBlur which uses the same Kawase-calibrated
        // radius values from BlurOverlayConfig.
        val effectiveRadius = radius * config.downsampleFactor

        val blurEffect = RenderEffect.createBlurEffect(
            effectiveRadius, effectiveRadius, Shader.TileMode.CLAMP
        )

        val hasTint = config.overlayColor != null || config.preBlurTintColor != null
        if (!hasTint) {
            captureNode.setRenderEffect(blurEffect)
            return
        }

        // Pre-blur tint (non-Normal blend): tint → blur
        val preBlurColor = config.preBlurTintColor
        val preBlurBlendOrdinal = config.preBlurBlendModeOrdinal
        if (preBlurColor != null && preBlurBlendOrdinal != null) {
            val blendMode = AndroidBlendMode.values()[preBlurBlendOrdinal]
            val tintEffect = RenderEffect.createColorFilterEffect(
                BlendModeColorFilter(preBlurColor, blendMode)
            )
            captureNode.setRenderEffect(
                RenderEffect.createChainEffect(blurEffect, tintEffect)
            )
            return
        }

        // Post-blur tint (Normal blend): blur → tint
        val overlayColor = config.overlayColor
        if (overlayColor != null) {
            val tintEffect = RenderEffect.createColorFilterEffect(
                BlendModeColorFilter(overlayColor, AndroidBlendMode.SRC_OVER)
            )
            captureNode.setRenderEffect(
                RenderEffect.createChainEffect(tintEffect, blurEffect)
            )
            return
        }

        captureNode.setRenderEffect(blurEffect)
    }

    fun draw(canvas: Canvas) {
        val view = blurView ?: return
        val bitmap = outputBitmap ?: return

        srcRect.set(0, 0, bitmap.width, bitmap.height)
        dstRect.set(0, 0, view.width, view.height)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }

    fun release() {
        releaseRendererResources()
        captureNode.discardDisplayList()
        outputBitmap?.recycle()
        outputBitmap = null
        blurView = null
        sourceView = null
        isInitialized = false
        isDirty = true
        lastWidth = 0
        lastHeight = 0
        excludedViews.clear()
    }

    private fun initRendererResources(width: Int, height: Int): Boolean {
        return try {
            val reader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            )
            imageReader = reader

            hardwareRenderer = HardwareRenderer().apply {
                setSurface(reader.surface)
            }
            true
        } catch (e: Exception) {
            releaseRendererResources()
            false
        }
    }

    private fun releaseRendererResources() {
        hardwareRenderer?.destroy()
        hardwareRenderer = null
        imageReader?.close()
        imageReader = null
    }

    fun getAlgorithmName(): String = "RenderNode + RenderEffect"
}
