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
 * High-performance capture implementation using HardwareBuffer for API 31+.
 *
 * This implementation provides zero-copy GPU-accelerated capture by:
 * 1. Rendering view content to a RenderNode
 * 2. Using HardwareRenderer to render to an ImageReader surface
 * 3. Extracting the HardwareBuffer directly without CPU copies
 *
 * **Performance Benefits:**
 * - Zero-copy between GPU and CPU
 * - Direct hardware buffer access
 * - Optimal for RenderEffect blur pipeline
 *
 * **Requirements:** API 31+ (Android 12)
 */
@RequiresApi(Build.VERSION_CODES.S)
class HardwareBufferCapture : ContentCapture {

    private var imageReader: ImageReader? = null
    private var hardwareRenderer: HardwareRenderer? = null
    private var renderNode: RenderNode? = null

    private var lastWidth = 0
    private var lastHeight = 0

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

            // Record drawing commands to RenderNode
            val canvas = node.beginRecording(width, height)
            try {
                // Scale for downsampling
                val scaleX = width.toFloat() / blurView.width
                val scaleY = height.toFloat() / blurView.height
                canvas.scale(scaleX, scaleY)

                // Translate to capture the correct region
                canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())

                // Draw the source view
                sourceView.draw(canvas)
            } finally {
                node.endRecording()
            }

            // Render to the ImageReader surface
            renderer.setContentRoot(node)
            val syncResult = renderer.createRenderRequest()
                .setWaitForPresent(true)
                .syncAndDraw()

            if (syncResult != HardwareRenderer.SYNC_OK &&
                syncResult != HardwareRenderer.SYNC_REDRAW_REQUESTED) {
                return false
            }

            // Acquire the rendered image
            val image = reader.acquireLatestImage() ?: return false

            try {
                val hardwareBuffer = image.hardwareBuffer ?: return false

                try {
                    // Create a hardware bitmap from the buffer
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                        hardwareBuffer,
                        android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                    ) ?: return false

                    // Copy to the output bitmap (which may be mutable)
                    val outputCanvas = Canvas(output)
                    outputCanvas.drawBitmap(hardwareBitmap, 0f, 0f, null)

                    // Don't recycle hardware bitmap - it shares the buffer
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

    private fun initializeResources(width: Int, height: Int): Boolean {
        return try {
            // Create ImageReader with HardwareBuffer usage flags
            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2, // Max images
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            )

            val reader = imageReader ?: return false

            // Create RenderNode
            renderNode = RenderNode("BlurCapture").apply {
                setPosition(0, 0, width, height)
            }

            // Create HardwareRenderer
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
    }

    override fun isAvailable(): Boolean = Companion.isAvailable()

    companion object {
        /**
         * Checks if HardwareBuffer capture is available on this device.
         */
        fun isAvailable(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }
    }
}
