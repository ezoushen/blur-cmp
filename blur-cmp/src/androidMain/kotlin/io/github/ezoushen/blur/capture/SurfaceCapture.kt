package io.github.ezoushen.blur.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import io.github.ezoushen.blur.view.BlurView
import io.github.ezoushen.blur.view.VariableBlurView
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Captures content from SurfaceView and TextureView.
 *
 * Standard View.draw() cannot capture SurfaceView/TextureView content because they
 * render to a separate surface layer. This capture implementation handles these
 * special cases.
 *
 * **Supported View Types:**
 * - [TextureView]: Uses [TextureView.getBitmap] (API 14+)
 * - [SurfaceView]: Uses [PixelCopy] (API 24+)
 *
 * **Limitations:**
 * - SurfaceView capture requires API 24+ for reliable results
 * - Some DRM-protected content cannot be captured
 */
class SurfaceCapture : ContentCapture {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val surfaceFrames = IdentityHashMap<SurfaceView, SurfaceFrame>()
    private val textureFrames = IdentityHashMap<TextureView, RetryFrame>()

    private open class RetryFrame {
        var active = true
        var retryCount = 0
        var retryRunnable: Runnable? = null
        var blurView = WeakReference<View>(null)
    }

    private class SurfaceFrame : RetryFrame() {
        var front: Bitmap? = null
        var back: Bitmap? = null
        var pending = false
        var deliveryPending = false
        var generation = 0
        var deliveredRequestVersion = Long.MIN_VALUE
        var deliveryRequestVersion = Long.MIN_VALUE
        var holderCallback: SurfaceHolder.Callback? = null
    }

    override fun capture(
        blurView: View,
        sourceView: View,
        output: Bitmap,
        downsampleFactor: Float
    ): Boolean {
        return when (sourceView) {
            is TextureView -> captureTextureView(
                blurView,
                sourceView,
                output,
                downsampleFactor,
            )
            is SurfaceView -> captureSurfaceView(blurView, sourceView, output)
            else -> false // Use DecorViewCapture for regular views
        }
    }

    /**
     * Captures content from a TextureView.
     *
     * getBitmap leaves the destination unchanged until the producer posts a frame,
     * so its generation ID is the readiness signal on the API 24-25 fallback.
     */
    private fun captureTextureView(
        blurView: View,
        textureView: TextureView,
        output: Bitmap,
        @Suppress("UNUSED_PARAMETER") downsampleFactor: Float
    ): Boolean {
        val frame = textureFrames.getOrPut(textureView, ::RetryFrame)
        frame.blurView = WeakReference(blurView)
        if (!textureView.isAvailable) {
            scheduleRetry(blurView, frame)
            return false
        }
        if (frame.retryRunnable != null) return false
        val generation = output.generationId
        return try {
            textureView.getBitmap(output)
            if (output.generationId != generation) {
                cancelRetry(frame)
                true
            } else {
                scheduleRetry(blurView, frame)
                false
            }
        } catch (_: IllegalStateException) {
            scheduleRetry(blurView, frame)
            false
        }
    }

    /**
     * Captures content from a SurfaceView.
     *
     * SurfaceView capture is more complex as content is rendered to a separate surface.
     * Uses PixelCopy on API 24+ for reliable capture.
     */
    private fun captureSurfaceView(
        blurView: View,
        surfaceView: SurfaceView,
        output: Bitmap,
    ): Boolean {
        val frame = surfaceFrame(surfaceView)
        frame.blurView = WeakReference(blurView)
        val current = frame.front
        val hasCurrent = current != null &&
            current.width == output.width &&
            current.height == output.height
        if (hasCurrent) {
            Canvas(output).drawBitmap(current, 0f, 0f, null)
        }

        val holder = surfaceView.holder ?: return false
        val surface = holder.surface ?: return false

        if (!surface.isValid) {
            scheduleRetry(blurView, frame)
            return hasCurrent
        }

        return captureSurfaceWithPixelCopy(blurView, surface, output, frame, hasCurrent)
    }

    /**
     * Uses PixelCopy API (API 24+) for reliable surface capture.
     */
    private fun captureSurfaceWithPixelCopy(
        blurView: View,
        surface: Surface,
        output: Bitmap,
        frame: SurfaceFrame,
        hasCurrent: Boolean,
    ): Boolean {
        if (frame.deliveryPending && hasCurrent) {
            frame.deliveryPending = false
            frame.deliveredRequestVersion = frame.deliveryRequestVersion
            return true
        }
        frame.deliveryPending = false

        val requestVersion = captureRequestVersion(blurView)
        val shouldCapture = isLive(blurView) ||
            !hasCurrent ||
            requestVersion != frame.deliveredRequestVersion
        if (!frame.pending && shouldCapture) {
            val requestGeneration = frame.generation
            val reusable = frame.back
            val destination = if (reusable != null &&
                reusable.width == output.width &&
                reusable.height == output.height
            ) {
                reusable
            } else {
                reusable?.recycle()
                Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888)
            }
            frame.back = null
            frame.pending = true
            try {
                android.view.PixelCopy.request(
                    surface,
                    destination,
                    { result ->
                        if (!frame.active || requestGeneration != frame.generation) {
                            destination.recycle()
                            return@request
                        }
                        frame.pending = false
                        if (result == android.view.PixelCopy.SUCCESS) {
                            cancelRetry(frame)
                            frame.back = frame.front
                            frame.front = destination
                            frame.deliveryRequestVersion = requestVersion
                            frame.deliveryPending = true
                            requestUpdate(blurView)
                        } else {
                            frame.back = destination
                            scheduleRetry(blurView, frame)
                        }
                    },
                    mainHandler,
                )
            } catch (e: Exception) {
                frame.pending = false
                frame.back = destination
                scheduleRetry(blurView, frame)
            }
        }
        return hasCurrent && (isLive(blurView) ||
            requestVersion == frame.deliveredRequestVersion)
    }

    private fun scheduleRetry(blurView: View, frame: RetryFrame) {
        if (!frame.active || frame.retryRunnable != null) {
            return
        }
        val delayMillis = (INITIAL_RETRY_DELAY_MILLIS shl frame.retryCount)
            .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        frame.retryCount = (frame.retryCount + 1).coerceAtMost(MAX_RETRY_EXPONENT)
        val retry = Runnable {
            frame.retryRunnable = null
            if (frame.active) requestUpdate(blurView)
        }
        frame.retryRunnable = retry
        mainHandler.postDelayed(retry, delayMillis)
    }

    private fun cancelRetry(frame: RetryFrame) {
        frame.retryRunnable?.let(mainHandler::removeCallbacks)
        frame.retryRunnable = null
        frame.retryCount = 0
    }

    private fun requestUpdate(blurView: View) {
        when (blurView) {
            is BlurView -> blurView.requestCaptureDelivery()
            is VariableBlurView -> blurView.requestCaptureDelivery()
            else -> blurView.postInvalidate()
        }
    }

    private fun captureRequestVersion(blurView: View): Long = when (blurView) {
        is BlurView -> blurView.captureRequestVersion()
        is VariableBlurView -> blurView.captureRequestVersion()
        else -> 0L
    }

    private fun isLive(blurView: View): Boolean = when (blurView) {
        is BlurView -> blurView.isLive()
        is VariableBlurView -> blurView.isLive()
        else -> false
    }

    private fun surfaceFrame(surfaceView: SurfaceView): SurfaceFrame {
        return surfaceFrames.getOrPut(surfaceView) {
            val frame = SurfaceFrame()
            val callback = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    invalidateGeneration(frame)
                    frame.blurView.get()?.let(::requestUpdate)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    invalidateGeneration(frame)
                    frame.blurView.get()?.let(::requestUpdate)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    invalidateGeneration(frame)
                }
            }
            frame.holderCallback = callback
            surfaceView.holder.addCallback(callback)
            frame
        }
    }

    private fun invalidateGeneration(frame: SurfaceFrame) {
        frame.generation++
        frame.pending = false
        frame.deliveryPending = false
        frame.deliveredRequestVersion = Long.MIN_VALUE
        frame.deliveryRequestVersion = Long.MIN_VALUE
        cancelRetry(frame)
    }

    fun retainViews(activeViews: Collection<View>) {
        val activeSet = activeViews.toSet()
        val iterator = surfaceFrames.entries.iterator()
        while (iterator.hasNext()) {
            val (view, frame) = iterator.next()
            if (view !in activeSet) {
                frame.active = false
                cancelRetry(frame)
                frame.holderCallback?.let(view.holder::removeCallback)
                frame.front?.recycle()
                if (!frame.pending) frame.back?.recycle()
                iterator.remove()
            }
        }
        val textureIterator = textureFrames.entries.iterator()
        while (textureIterator.hasNext()) {
            val (view, frame) = textureIterator.next()
            if (view !in activeSet) {
                frame.active = false
                cancelRetry(frame)
                textureIterator.remove()
            }
        }
    }

    override fun release() {
        surfaceFrames.forEach { (view, frame) ->
            frame.active = false
            cancelRetry(frame)
            frame.holderCallback?.let(view.holder::removeCallback)
            frame.front?.recycle()
            if (!frame.pending) frame.back?.recycle()
        }
        surfaceFrames.clear()
        textureFrames.values.forEach { frame ->
            frame.active = false
            cancelRetry(frame)
        }
        textureFrames.clear()
    }

    override fun isAvailable(): Boolean = true

    /**
     * Checks if a view requires special surface capture.
     */
    companion object {
        private const val INITIAL_RETRY_DELAY_MILLIS = 16L
        private const val MAX_RETRY_DELAY_MILLIS = 512L
        private const val MAX_RETRY_EXPONENT = 5
        private val registeredCompositionOrders = WeakHashMap<SurfaceView, Int>()

        internal fun registerCompositionOrder(surfaceView: SurfaceView, order: Int) {
            registeredCompositionOrders[surfaceView] = order
        }

        internal fun registeredCompositionOrder(surfaceView: SurfaceView): Int? {
            return registeredCompositionOrders[surfaceView]
        }

        /**
         * Returns true if the view requires SurfaceCapture instead of standard capture.
         */
        fun requiresSurfaceCapture(view: View): Boolean {
            return view is SurfaceView || view is TextureView
        }

        internal fun isAboveWindow(surfaceView: SurfaceView): Boolean {
            val originallyWillNotDraw = surfaceView.willNotDraw()
            surfaceView.setWillNotDraw(true)
            val transparentRegion = Region()
            return try {
                surfaceView.gatherTransparentRegion(transparentRegion)
                val location = IntArray(2)
                surfaceView.getLocationInWindow(location)
                !transparentRegion.contains(
                    location[0] + surfaceView.width / 2,
                    location[1] + surfaceView.height / 2,
                )
            } finally {
                surfaceView.setWillNotDraw(originallyWillNotDraw)
            }
        }

        /**
         * Finds any SurfaceView or TextureView children that need special handling.
         */
        fun findSurfaceViews(root: View): List<View> {
            val result = mutableListOf<View>()
            findSurfaceViewsRecursive(root, result)
            return result
        }

        private fun findSurfaceViewsRecursive(view: View, result: MutableList<View>) {
            if (view is SurfaceView || view is TextureView) {
                result.add(view)
            }
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    findSurfaceViewsRecursive(view.getChildAt(i), result)
                }
            }
        }
    }
}
