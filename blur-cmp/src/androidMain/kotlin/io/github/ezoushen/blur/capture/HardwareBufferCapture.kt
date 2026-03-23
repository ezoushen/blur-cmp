package io.github.ezoushen.blur.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/**
 * GPU-accelerated capture using RenderNode + HardwareRenderer for API 29+.
 *
 * Replaces software canvas capture with hardware-accelerated recording:
 * 1. sourceView.draw(RecordingCanvas) — records display list refs (~0ms vs 2-5ms)
 * 2. HardwareRenderer rasterizes to ImageReader surface at downsampled size
 * 3. HardwareBuffer → Hardware Bitmap → copy to mutable output bitmap
 *
 * The output is a mutable Bitmap compatible with the existing Kawase blur
 * pipeline (texImage2D → blur → glReadPixels). The optimization is in the
 * capture step: RecordingCanvas records pointers to existing display lists
 * instead of software-rendering every pixel.
 *
 * On API 31+, [RenderNodeBlurController] bypasses this entirely by using
 * RenderEffect for the blur step too. This class is the API 29-30 path.
 *
 * **Requirements:** API 29+ (Android 10) — RenderNode, HardwareRenderer,
 * ImageReader with HardwareBuffer usage, Bitmap.wrapHardwareBuffer()
 */
@RequiresApi(Build.VERSION_CODES.Q)
class HardwareBufferCapture : ContentCapture {

    private var imageReader: ImageReader? = null
    private var hardwareRenderer: HardwareRenderer? = null
    private var renderNode: RenderNode? = null

    private var lastWidth = 0
    private var lastHeight = 0

    @Volatile
    private var isCapturing = false

    private val excludedViews = mutableListOf<View>()

    fun isCurrentlyCapturing(): Boolean = isCapturing

    fun addExcludedView(view: View) {
        if (view !in excludedViews) excludedViews.add(view)
    }

    fun removeExcludedView(view: View) {
        excludedViews.remove(view)
    }

    override fun capture(
        blurView: View,
        sourceView: View,
        output: Bitmap,
        downsampleFactor: Float
    ): Boolean {
        val width = output.width
        val height = output.height

        if (width <= 0 || height <= 0) return false

        try {
            // Initialize or recreate resources if dimensions changed
            if (lastWidth != width || lastHeight != height) {
                releaseResources()
                if (!initializeResources(width, height)) {
                    return false
                }
                lastWidth = width
                lastHeight = height
            }

            val reader = imageReader ?: return false
            val renderer = hardwareRenderer ?: return false
            val node = renderNode ?: return false

            // Calculate the region to capture (blurView's position in sourceView)
            val blurViewLocation = IntArray(2)
            val sourceLocation = IntArray(2)
            blurView.getLocationOnScreen(blurViewLocation)
            sourceView.getLocationOnScreen(sourceLocation)

            val offsetX = blurViewLocation[0] - sourceLocation[0]
            val offsetY = blurViewLocation[1] - sourceLocation[1]

            // Hide blurView + excluded views during capture
            val hiddenViews = mutableListOf<View>()
            try {
                isCapturing = true

                if (blurView.visibility == View.VISIBLE) {
                    blurView.visibility = View.INVISIBLE
                    hiddenViews.add(blurView)
                }
                for (excluded in excludedViews) {
                    if (excluded.visibility == View.VISIBLE) {
                        excluded.visibility = View.INVISIBLE
                        hiddenViews.add(excluded)
                    }
                }

                // Record drawing commands to RenderNode via RecordingCanvas.
                // This records display list references (pointers to existing
                // RenderNodes), NOT software pixels. Near-zero CPU cost.
                val canvas = node.beginRecording(width, height)
                try {
                    val scaleX = width.toFloat() / blurView.width
                    val scaleY = height.toFloat() / blurView.height
                    canvas.scale(scaleX, scaleY)
                    canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
                    sourceView.draw(canvas)
                } finally {
                    node.endRecording()
                }
            } finally {
                for (hidden in hiddenViews) {
                    hidden.visibility = View.VISIBLE
                }
                isCapturing = false
            }

            // Rasterize on GPU via HardwareRenderer → ImageReader
            val perf = io.github.ezoushen.blur.BlurPerfMonitor.enabled
            val ts0 = if (perf) System.nanoTime() else 0L
            renderer.setContentRoot(node)
            val syncResult = renderer.createRenderRequest()
                .setWaitForPresent(true)
                .syncAndDraw()
            val ts1 = if (perf) System.nanoTime() else 0L

            if (syncResult != HardwareRenderer.SYNC_OK &&
                syncResult != HardwareRenderer.SYNC_REDRAW_REQUESTED) {
                return false
            }

            // Extract HardwareBuffer → Hardware Bitmap → copy to mutable output
            val image = reader.acquireLatestImage() ?: return false
            try {
                val hardwareBuffer = image.hardwareBuffer ?: return false
                try {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                        hardwareBuffer,
                        android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                    ) ?: return false

                    val tc0 = if (perf) System.nanoTime() else 0L
                    val mutableCopy = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        ?: return false
                    try {
                        val outputCanvas = Canvas(output)
                        outputCanvas.drawBitmap(mutableCopy, 0f, 0f, null)
                    } finally {
                        mutableCopy.recycle()
                    }
                    if (perf) {
                        android.util.Log.i("BlurPerf", "    HWCapture sync=${(ts1-ts0)/1000}us copy=${(System.nanoTime()-tc0)/1000}us")
                    }
                    return true
                } finally {
                    hardwareBuffer.close()
                }
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Captures view content and returns the raw Image with HardwareBuffer inside,
     * without copying to a mutable bitmap. This enables zero-copy import via EGLImage.
     *
     * The caller is responsible for closing the returned Image (and its HardwareBuffer).
     *
     * @return The captured Image, or null if capture failed
     */
    fun captureToHardwareBuffer(
        blurView: View,
        sourceView: View,
        width: Int,
        height: Int,
        downsampleFactor: Float
    ): android.media.Image? {
        if (width <= 0 || height <= 0) return null

        try {
            // Initialize or recreate resources if dimensions changed
            if (lastWidth != width || lastHeight != height) {
                releaseResources()
                if (!initializeResources(width, height)) {
                    return null
                }
                lastWidth = width
                lastHeight = height
            }

            val reader = imageReader ?: return null
            val renderer = hardwareRenderer ?: return null
            val node = renderNode ?: return null

            // Calculate the region to capture
            val blurViewLocation = IntArray(2)
            val sourceLocation = IntArray(2)
            blurView.getLocationOnScreen(blurViewLocation)
            sourceView.getLocationOnScreen(sourceLocation)

            val offsetX = blurViewLocation[0] - sourceLocation[0]
            val offsetY = blurViewLocation[1] - sourceLocation[1]

            // Hide blurView + excluded views during capture
            val hiddenViews = mutableListOf<View>()
            try {
                isCapturing = true

                if (blurView.visibility == View.VISIBLE) {
                    blurView.visibility = View.INVISIBLE
                    hiddenViews.add(blurView)
                }
                for (excluded in excludedViews) {
                    if (excluded.visibility == View.VISIBLE) {
                        excluded.visibility = View.INVISIBLE
                        hiddenViews.add(excluded)
                    }
                }

                val canvas = node.beginRecording(width, height)
                try {
                    val scaleX = width.toFloat() / blurView.width
                    val scaleY = height.toFloat() / blurView.height
                    canvas.scale(scaleX, scaleY)
                    canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
                    sourceView.draw(canvas)
                } finally {
                    node.endRecording()
                }
            } finally {
                for (hidden in hiddenViews) {
                    hidden.visibility = View.VISIBLE
                }
                isCapturing = false
            }

            // Rasterize on GPU
            renderer.setContentRoot(node)
            val syncResult = renderer.createRenderRequest()
                .setWaitForPresent(true)
                .syncAndDraw()

            if (syncResult != HardwareRenderer.SYNC_OK &&
                syncResult != HardwareRenderer.SYNC_REDRAW_REQUESTED) {
                return null
            }

            return reader.acquireLatestImage()
        } catch (e: Exception) {
            return null
        }
    }

    private fun initializeResources(width: Int, height: Int): Boolean {
        return try {
            imageReader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            )
            val reader = imageReader ?: return false
            renderNode = RenderNode("BlurCapture").apply {
                setPosition(0, 0, width, height)
            }
            hardwareRenderer = HardwareRenderer().apply {
                setSurface(reader.surface)
                setContentRoot(renderNode)
            }
            true
        } catch (e: Exception) {
            releaseResources()
            false
        }
    }

    private fun releaseResources() {
        hardwareRenderer?.destroy()
        hardwareRenderer = null
        renderNode?.discardDisplayList()
        renderNode = null
        imageReader?.close()
        imageReader = null
    }

    override fun release() {
        releaseResources()
        lastWidth = 0
        lastHeight = 0
        excludedViews.clear()
    }

    override fun isAvailable(): Boolean = Companion.isAvailable()

    companion object {
        fun isAvailable(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
