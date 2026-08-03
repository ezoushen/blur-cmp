package io.github.ezoushen.blur.capture

import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import io.github.ezoushen.blur.view.BlurView
import io.github.ezoushen.blur.view.VariableBlurView
import java.util.concurrent.atomic.AtomicBoolean

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
    private var textureId = 0

    private var lastWidth = 0
    private var lastHeight = 0
    private var queuedRequestVersion = Long.MIN_VALUE
    private val producerFrameAvailable = AtomicBoolean(false)
    private val firstFramePending = AtomicBoolean(false)

    @Volatile
    private var isCapturing = false

    private val excludedViews = mutableListOf<View>()
    private val dimmedViews = ArrayList<View>()
    private var dimmedAlphas = FloatArray(4)
    private val blurViewLocation = IntArray(2)
    private val sourceLocation = IntArray(2)

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
     * @param width The requested capture width
     * @param height The requested capture height
     */
    fun init(glTextureId: Int, width: Int, height: Int) {
        if (textureId == glTextureId && surfaceTexture != null) {
            // Radius animation changes processing dimensions every frame. Keep the
            // producer stable so its queued frame can complete overlay readiness.
            return
        }

        release()

        val st = SurfaceTexture(glTextureId)
        st.setOnFrameAvailableListener(
            { available ->
                if (surfaceTexture === available) {
                    producerFrameAvailable.set(true)
                    firstFramePending.set(false)
                }
            },
            Handler(Looper.getMainLooper()),
        )
        st.setDefaultBufferSize(width, height)
        surfaceTexture = st
        surface = Surface(st)

        textureId = glTextureId
        lastWidth = width
        lastHeight = height
    }

    /**
     * Captures the source view content to the SurfaceTexture.
     *
     * After this call, [SurfaceTexture.updateTexImage] must be called on the GL thread
     * to make the content available as a GL texture.
     *
     * @param blurView The blur view excluded from its own capture without changing visibility
     * @param sourceView The view to capture
     * @param width The capture width
     * @param height The capture height
     * @return true if capture succeeded
     */
    fun capture(blurView: View, sourceView: View, width: Int, height: Int): Boolean {
        val surf = surface ?: return false
        val st = surfaceTexture ?: return false

        if (!surf.isValid) return false
        if (blurView.width == 0 || blurView.height == 0) return false

        if (width <= 0 || height <= 0 || lastWidth <= 0 || lastHeight <= 0) return false

        val requestVersion = captureRequestVersion(blurView)
        if (producerFrameAvailable.compareAndSet(true, false)) {
            val frameReady = try {
                st.updateTexImage()
                queuedRequestVersion == requestVersion
            } catch (_: Exception) {
                return false
            }
            queuedRequestVersion = Long.MIN_VALUE
            if (frameReady) return true
        }
        if (firstFramePending.get()) {
            blurView.postInvalidateOnAnimation()
            return false
        }

        try {
            isCapturing = true

            dimForCapture(blurView)
            for (excluded in excludedViews) {
                // Exclude via alpha, NOT visibility. Setting an excluded view INVISIBLE clears its
                // focus and tears down the IME input connection on every capture frame, so a focused
                // TextField inside the excluded content can never hold focus or receive keystrokes
                // (the keyboard flickers in and out). alpha=0 keeps it out of the captured frame
                // while leaving focus and the input connection untouched.
                if (excluded.alpha > 0f) {
                    dimForCapture(excluded)
                }
            }

            // Calculate the region to capture
            blurView.getLocationOnScreen(blurViewLocation)
            sourceView.getLocationOnScreen(sourceLocation)

            val offsetX = blurViewLocation[0] - sourceLocation[0]
            val offsetY = blurViewLocation[1] - sourceLocation[1]

            // Lock hardware canvas for GPU-accelerated drawing
            val canvas = surf.lockHardwareCanvas()
            try {
                canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)

                // Keep the producer buffer stable while the blur radius changes the
                // processing resolution. Resizing a SurfaceTexture with an older frame
                // queued makes that frame sample with the next size's geometry.
                val scaleX = lastWidth.toFloat() / blurView.width
                val scaleY = lastHeight.toFloat() / blurView.height
                canvas.scale(scaleX, scaleY)
                canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
                sourceView.draw(canvas)
            } finally {
                queuedRequestVersion = requestVersion
                firstFramePending.set(true)
                surf.unlockCanvasAndPost(canvas)
            }

            // Frame delivery is asynchronous. Latch this exact capture on the next update
            // instead of consuming a previously queued frame and clearing the dirty request.
            blurView.postInvalidateOnAnimation()
            return false
        } catch (e: Exception) {
            firstFramePending.set(false)
            queuedRequestVersion = Long.MIN_VALUE
            return false
        } finally {
            for (index in dimmedViews.indices) {
                dimmedViews[index].alpha = dimmedAlphas[index]
            }
            dimmedViews.clear()
            isCapturing = false
        }
    }

    private fun dimForCapture(view: View) {
        val alpha = view.alpha
        if (alpha <= 0f) return
        val index = dimmedViews.size
        if (index == dimmedAlphas.size) {
            dimmedAlphas = dimmedAlphas.copyOf(dimmedAlphas.size * 2)
        }
        dimmedViews.add(view)
        dimmedAlphas[index] = alpha
        view.alpha = 0f
    }

    private fun captureRequestVersion(blurView: View): Long = when (blurView) {
        is BlurView -> blurView.captureRequestVersion()
        is VariableBlurView -> blurView.captureRequestVersion()
        else -> 0L
    }

    internal fun isFirstFramePending(): Boolean = firstFramePending.get()

    /**
     * Releases all resources.
     */
    fun release() {
        surface?.release()
        surface = null
        val st = surfaceTexture
        surfaceTexture = null
        st?.setOnFrameAvailableListener(null)
        st?.release()
        textureId = 0
        lastWidth = 0
        lastHeight = 0
        queuedRequestVersion = Long.MIN_VALUE
        producerFrameAvailable.set(false)
        firstFramePending.set(false)
    }

    companion object {
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
        fun isAvailable(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
