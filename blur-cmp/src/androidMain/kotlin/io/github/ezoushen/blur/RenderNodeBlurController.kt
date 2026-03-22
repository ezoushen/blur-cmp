package io.github.ezoushen.blur

import android.graphics.BlendMode as AndroidBlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/**
 * GPU-resident blur controller for API 31+ using RenderNode + RenderEffect.
 *
 * Replaces the software capture + OpenGL Kawase pipeline with:
 *   decorView.draw(RecordingCanvas) -> captureNode.setRenderEffect(blur) -> canvas.drawRenderNode
 *
 * Key advantages over [BlurController]:
 * - Zero CPU pixel access (no software canvas, no bitmap, no glReadPixels)
 * - No dirty flag side effects (RecordingCanvas path preserves PFLAG state correctly)
 * - No OpenGL context management
 * - Skia handles downsampling internally
 *
 * Falls back to [BlurController] when unavailable (API < 31).
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

    // Location arrays reused across frames
    private val sourceLocation = IntArray(2)
    private val blurViewLocation = IntArray(2)

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
        if (view !in excludedViews) {
            excludedViews.add(view)
        }
    }

    fun removeExcludedView(view: View) {
        excludedViews.remove(view)
    }

    /**
     * Updates the blur capture. Called from onPreDraw.
     *
     * Records the source view's display list into [captureNode] using a
     * RecordingCanvas. Each child with a valid display list emits a single
     * drawRenderNode pointer -- no software rasterization occurs.
     *
     * Then applies RenderEffect blur to the capture node.
     *
     * @return true if the blur was updated and the view should be invalidated
     */
    fun update(): Boolean {
        if (!isInitialized) return false

        val view = blurView ?: return false
        val source = sourceView ?: return false

        if (view.width == 0 || view.height == 0) return false
        if (!isDirty) return false

        // Calculate offsets
        source.getLocationOnScreen(sourceLocation)
        view.getLocationOnScreen(blurViewLocation)
        val offsetX = (blurViewLocation[0] - sourceLocation[0]).toFloat()
        val offsetY = (blurViewLocation[1] - sourceLocation[1]).toFloat()

        // Hide excluded views and self during capture
        val hiddenViews = mutableListOf<View>()
        try {
            isCurrentlyCapturing = true

            for (excluded in excludedViews) {
                if (excluded.visibility == View.VISIBLE) {
                    excluded.visibility = View.INVISIBLE
                    hiddenViews.add(excluded)
                }
            }

            // Record source view into capture RenderNode.
            // The RecordingCanvas records display list references (pointers to
            // existing RenderNodes), NOT software pixels. This is near-zero cost.
            captureNode.setPosition(0, 0, source.width, source.height)
            val canvas = captureNode.beginRecording(source.width, source.height)
            try {
                source.draw(canvas)
            } finally {
                captureNode.endRecording()
            }
        } finally {
            // Restore visibility
            for (hidden in hiddenViews) {
                hidden.visibility = View.VISIBLE
            }
            isCurrentlyCapturing = false
        }

        // Apply blur + tint as chained RenderEffect
        applyRenderEffect()

        // Set translation so the captured content aligns with the blur view's position
        captureNode.setTranslationX(-offsetX)
        captureNode.setTranslationY(-offsetY)

        // API 31 bug workaround: RenderNode doesn't refresh when only
        // translation changes. Re-applying RenderEffect forces a redraw.
        // Fixed on API 32+. (Haze #77, BlurView 3.2.0)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
            applyRenderEffect()
        }

        isDirty = false
        return true
    }

    private fun applyRenderEffect() {
        val radius = config.radius
        if (radius <= 0f) {
            captureNode.setRenderEffect(null)
            return
        }

        // Build blur effect
        val blurEffect = RenderEffect.createBlurEffect(
            radius, radius, Shader.TileMode.CLAMP
        )

        // Check for tint
        val hasTint = config.overlayColor != null || config.preBlurTintColor != null

        if (!hasTint) {
            captureNode.setRenderEffect(blurEffect)
            return
        }

        // Pre-blur tint (non-Normal blend modes): tint -> blur
        val preBlurColor = config.preBlurTintColor
        val preBlurBlendOrdinal = config.preBlurBlendModeOrdinal
        if (preBlurColor != null && preBlurBlendOrdinal != null) {
            val blendMode = AndroidBlendMode.values()[preBlurBlendOrdinal]
            val tintEffect = RenderEffect.createColorFilterEffect(
                BlendModeColorFilter(preBlurColor, blendMode)
            )
            // tint first, then blur: result = blur(tint(source))
            captureNode.setRenderEffect(
                RenderEffect.createChainEffect(blurEffect, tintEffect)
            )
            return
        }

        // Post-blur tint (Normal blend): blur -> tint
        val overlayColor = config.overlayColor
        if (overlayColor != null) {
            val tintEffect = RenderEffect.createColorFilterEffect(
                BlendModeColorFilter(overlayColor, AndroidBlendMode.SRC_OVER)
            )
            // blur first, then tint: result = tint(blur(source))
            captureNode.setRenderEffect(
                RenderEffect.createChainEffect(tintEffect, blurEffect)
            )
            return
        }

        captureNode.setRenderEffect(blurEffect)
    }

    /**
     * Draws the blurred content to the canvas.
     *
     * Calls canvas.drawRenderNode(captureNode) which records a reference
     * to the captured + blurred display list. HWUI applies the RenderEffect
     * on the RenderThread -- zero CPU pixel access.
     */
    fun draw(canvas: Canvas) {
        val view = blurView ?: return
        if (!captureNode.hasDisplayList()) return

        canvas.save()
        canvas.clipRect(0f, 0f, view.width.toFloat(), view.height.toFloat())
        canvas.drawRenderNode(captureNode)
        canvas.restore()
    }

    fun release() {
        captureNode.discardDisplayList()
        blurView = null
        sourceView = null
        isInitialized = false
        isDirty = true
        excludedViews.clear()
    }

    fun getAlgorithmName(): String = "RenderNode + RenderEffect"
}
