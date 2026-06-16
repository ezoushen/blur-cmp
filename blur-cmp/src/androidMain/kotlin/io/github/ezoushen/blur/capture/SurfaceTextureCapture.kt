package io.github.ezoushen.blur.capture

import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.View
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * Zero-copy capture using Surface.lockHardwareCanvas() + SurfaceTexture (API 26+).
 *
 * This capture pipeline avoids CPU-GPU crossings by:
 * 1. Drawing the source view to a Surface backed by a SurfaceTexture
 * 2. The SurfaceTexture wraps a GL_TEXTURE_EXTERNAL_OES texture
 * 3. The blur shader samples directly from this texture
 *
 * The GL texture ID must be created by the OpenGL blur engine and passed
 * to [init] before capture. After [capture], call [SurfaceTexture.updateTexImage]
 * to make the content available to the shader.
 *
 * **Requirements:** API 26+ (lockHardwareCanvas), GL_OES_EGL_image_external
 */
@RequiresApi(Build.VERSION_CODES.O)
class SurfaceTextureCapture {

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

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

    /**
     * Initializes the SurfaceTexture capture with the given GL texture ID.
     *
     * @param glTextureId A GL_TEXTURE_EXTERNAL_OES texture ID created by the blur engine
     * @param width The capture width (downsampled)
     * @param height The capture height (downsampled)
     */
    fun init(glTextureId: Int, width: Int, height: Int) {
        if (lastWidth == width && lastHeight == height && surfaceTexture != null) return

        release()

        val st = SurfaceTexture(glTextureId)
        st.setDefaultBufferSize(width, height)
        surfaceTexture = st
        surface = Surface(st)

        lastWidth = width
        lastHeight = height
    }

    /**
     * Captures the source view content to the SurfaceTexture.
     *
     * After this call, [SurfaceTexture.updateTexImage] must be called on the GL thread
     * to make the content available as a GL texture.
     *
     * @param blurView The blur view (hidden during capture)
     * @param sourceView The view to capture
     * @param width The capture width
     * @param height The capture height
     * @return true if capture succeeded
     */
    fun capture(blurView: View, sourceView: View, width: Int, height: Int): Boolean {
        val surf = surface ?: return false

        if (!surf.isValid) return false
        if (blurView.width == 0 || blurView.height == 0) return false

        // Reinitialize if dimensions changed
        if (width != lastWidth || height != lastHeight) {
            surfaceTexture?.setDefaultBufferSize(width, height)
            lastWidth = width
            lastHeight = height
        }

        val hiddenViews = mutableListOf<View>()
        val dimmedViews = mutableListOf<Pair<View, Float>>()

        try {
            isCapturing = true

            // Hide blur view and excluded views
            if (blurView.visibility == View.VISIBLE) {
                blurView.visibility = View.INVISIBLE
                hiddenViews.add(blurView)
            }
            for (excluded in excludedViews) {
                // Exclude via alpha, NOT visibility. Setting an excluded view INVISIBLE clears its
                // focus and tears down the IME input connection on every capture frame, so a focused
                // TextField inside the excluded content can never hold focus or receive keystrokes
                // (the keyboard flickers in and out). alpha=0 keeps it out of the captured frame
                // while leaving focus and the input connection untouched.
                if (excluded.alpha > 0f) {
                    dimmedViews.add(excluded to excluded.alpha)
                    excluded.alpha = 0f
                }
            }

            // Calculate the region to capture
            val blurViewLocation = IntArray(2)
            val sourceLocation = IntArray(2)
            blurView.getLocationOnScreen(blurViewLocation)
            sourceView.getLocationOnScreen(sourceLocation)

            val offsetX = blurViewLocation[0] - sourceLocation[0]
            val offsetY = blurViewLocation[1] - sourceLocation[1]

            // Lock hardware canvas for GPU-accelerated drawing
            val canvas = surf.lockHardwareCanvas()
            try {
                canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)

                val scaleX = width.toFloat() / blurView.width
                val scaleY = height.toFloat() / blurView.height
                canvas.scale(scaleX, scaleY)
                canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
                sourceView.draw(canvas)
            } finally {
                surf.unlockCanvasAndPost(canvas)
            }

            // Update the SurfaceTexture to make the content available as GL texture.
            // This must be called on the GL thread (which is the main thread in our pipeline).
            surfaceTexture?.updateTexImage()

            return true
        } catch (e: Exception) {
            return false
        } finally {
            for (hidden in hiddenViews) {
                hidden.visibility = View.VISIBLE
            }
            for ((dimmed, originalAlpha) in dimmedViews) {
                dimmed.alpha = originalAlpha
            }
            isCapturing = false
        }
    }

    /**
     * Releases all resources.
     */
    fun release() {
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        lastWidth = 0
        lastHeight = 0
    }

    companion object {
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
        fun isAvailable(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
