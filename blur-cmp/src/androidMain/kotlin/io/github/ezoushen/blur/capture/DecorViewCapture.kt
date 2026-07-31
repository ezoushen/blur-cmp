package io.github.ezoushen.blur.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.RequiresApi
import io.github.ezoushen.blur.view.BlurView
import io.github.ezoushen.blur.view.VariableBlurView
import java.util.IdentityHashMap

internal data class BackdropCaptureSource(
    val view: View,
    val window: Window?,
)

/**
 * Captures view content by drawing the DecorView to a scaled bitmap.
 */
class DecorViewCapture : ContentCapture {

    private val sourceLocation = IntArray(2)
    private val sourceWindowLocation = IntArray(2)
    private val blurViewLocation = IntArray(2)
    private val surfaceLocation = IntArray(2)
    private val surfacePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val windowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val surfaceClipPath = Path()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isCapturing = false

    private val excludedViews = mutableListOf<View>()
    private val surfaceCapture = SurfaceCapture()
    private val surfaceBitmaps = IdentityHashMap<View, Bitmap>()
    private val pinnedSurfaceBitmaps = IdentityHashMap<Bitmap, Int>()
    private var viewHierarchyBitmap: Bitmap? = null
    private var sourceWindow: Window? = null
    private var captureSources: List<BackdropCaptureSource>? = null
    private var windowFront: Bitmap? = null
    private var windowBack: Bitmap? = null
    private var windowPending = false
    private var windowDeliveryPending = false
    private var windowGeneration = 0
    private var windowSourceRects = emptyList<Rect>()
    private var windowRetryCount = 0
    private var windowRetry: Runnable? = null

    private data class SurfaceLayer(
        val target: View,
        val bitmap: Bitmap,
        val alpha: Float,
        val aboveWindow: Boolean,
        val compositionOrder: Int,
    )

    private data class CapturePlane(
        val source: BackdropCaptureSource,
        val sourceRect: Rect,
        val surfaceLayers: List<SurfaceLayer>,
    )

    private data class TextureOverlay(
        val view: TextureView,
        val drawable: Drawable,
    )

    fun isCurrentlyCapturing(): Boolean = isCapturing

    fun setSourceWindow(window: Window?) {
        if (sourceWindow === window) return
        sourceWindow = window
        resetWindowCapture()
    }

    internal fun setCaptureSources(sources: List<BackdropCaptureSource>?) {
        val unchanged = captureSources?.sameSources(sources) ?: (sources == null)
        if (unchanged) return
        captureSources = sources?.toList()
        resetWindowCapture()
    }

    fun addExcludedView(view: View) {
        if (view !in excludedViews) {
            excludedViews.add(view)
        }
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
        if (blurView.width == 0 || blurView.height == 0) {
            return false
        }

        val dimmedViews = mutableListOf<Pair<View, Float>>()
        val textureOverlays = mutableListOf<TextureOverlay>()
        val activeSurfaceViews = mutableSetOf<View>()

        try {
            isCapturing = true

            for (view in excludedViews) {
                // Exclude via alpha, NOT visibility. Setting an excluded view INVISIBLE clears its
                // focus and tears down the IME input connection on every capture frame, so a focused
                // TextField inside the excluded content can never hold focus or receive keystrokes.
                // alpha=0 keeps it out of the captured bitmap while leaving focus untouched.
                if (view.alpha > 0f) {
                    dimmedViews.add(view to view.alpha)
                    view.alpha = 0f
                }
            }

            blurView.getLocationOnScreen(blurViewLocation)
            val scaleX = output.width.toFloat() / blurView.width
            val scaleY = output.height.toFloat() / blurView.height
            val sources = captureSources
                ?: listOf(BackdropCaptureSource(sourceView, sourceWindow))
            val canCaptureWindows = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                sources.all { it.window != null }
            output.eraseColor(android.graphics.Color.TRANSPARENT)
            var surfacesReady = true
            if (canCaptureWindows) {
                val planes = sources.map { source ->
                    source.view.getLocationOnScreen(sourceLocation)
                    val sourceRect = sourceRect(blurView, source.view)
                    val (surfaceLayers, ready) = captureSurfaceChildren(
                        blurView = blurView,
                        sourceView = source.view,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        includeTextureViews = true,
                        activeSurfaceViews = activeSurfaceViews,
                    )
                    surfacesReady = surfacesReady && ready
                    CapturePlane(
                        source = source,
                        sourceRect = sourceRect,
                        surfaceLayers = surfaceLayers.sortedBy { it.compositionOrder },
                    )
                }
                if (!surfacesReady) return false
                val captured = captureWindows(
                    blurView = blurView,
                    planes = planes,
                    width = output.width,
                    height = output.height,
                    scaleX = scaleX,
                    scaleY = scaleY,
                ) ?: return false
                Canvas(output).drawBitmap(
                    captured,
                    null,
                    Rect(0, 0, output.width, output.height),
                    windowPaint,
                )
            } else {
                val canvas = Canvas(output)
                for (source in sources) {
                    source.view.getLocationOnScreen(sourceLocation)
                    val offsetX = blurViewLocation[0] - sourceLocation[0]
                    val offsetY = blurViewLocation[1] - sourceLocation[1]
                    val (surfaceLayers, ready) = captureSurfaceChildren(
                        blurView = blurView,
                        sourceView = source.view,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        includeTextureViews = true,
                        activeSurfaceViews = activeSurfaceViews,
                    )
                    surfacesReady = surfacesReady && ready
                    drawHierarchyPlane(
                        canvas = canvas,
                        sourceView = source.view,
                        surfaceLayers = surfaceLayers.sortedBy { it.compositionOrder },
                        textureOverlays = textureOverlays,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        width = output.width,
                        height = output.height,
                    )
                }
            }
            return surfacesReady
        } catch (e: StopCaptureException) {
            return true
        } catch (e: Exception) {
            return false
        } finally {
            for ((view, drawable) in textureOverlays) {
                view.overlay.remove(drawable)
            }
            for ((view, originalAlpha) in dimmedViews) {
                view.alpha = originalAlpha
            }
            surfaceCapture.retainViews(activeSurfaceViews.toList())
            surfaceBitmaps.keys
                .filter { it !in activeSurfaceViews }
                .forEach { removed ->
                    surfaceBitmaps.remove(removed)?.let(::retireSurfaceBitmap)
                }
            isCapturing = false
        }
    }

    private fun captureSurfaceChildren(
        blurView: View,
        sourceView: View,
        scaleX: Float,
        scaleY: Float,
        includeTextureViews: Boolean,
        activeSurfaceViews: MutableSet<View>,
    ): Pair<List<SurfaceLayer>, Boolean> {
        val layers = mutableListOf<SurfaceLayer>()
        var surfacesReady = true
        val activeViews = SurfaceCapture.findSurfaceViews(sourceView)
        activeSurfaceViews += activeViews
        for (surfaceView in activeViews) {
            if (!includeTextureViews && surfaceView is TextureView) continue
            val aboveWindow = surfaceView is SurfaceView &&
                SurfaceCapture.isAboveWindow(surfaceView)
            if (surfaceView.width == 0 ||
                surfaceView.height == 0 ||
                surfaceView.isDescendantOf(blurView) ||
                surfaceView.effectiveAlpha() == 0f
            ) {
                continue
            }

            val width = (surfaceView.width * scaleX).toInt().coerceAtLeast(1)
            val height = (surfaceView.height * scaleY).toInt().coerceAtLeast(1)
            var surfaceBitmap = surfaceBitmaps[surfaceView]
            if (surfaceBitmap?.width != width ||
                surfaceBitmap.height != height ||
                pinnedSurfaceBitmaps.containsKey(surfaceBitmap)
            ) {
                surfaceBitmap?.let(::retireSurfaceBitmap)
                surfaceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                surfaceBitmaps[surfaceView] = surfaceBitmap
            }
            surfaceBitmap.eraseColor(android.graphics.Color.TRANSPARENT)
            val captured = surfaceCapture.capture(blurView, surfaceView, surfaceBitmap, 1f)
            if (!captured) {
                surfacesReady = false
                continue
            }
            layers += SurfaceLayer(
                target = surfaceView,
                bitmap = surfaceBitmap,
                alpha = surfaceView.effectiveAlpha(),
                aboveWindow = aboveWindow,
                compositionOrder = (surfaceView as? SurfaceView)?.compositionOrder()
                    ?: 0,
            )
        }
        return layers to surfacesReady
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun captureWindows(
        blurView: View,
        planes: List<CapturePlane>,
        width: Int,
        height: Int,
        scaleX: Float,
        scaleY: Float,
    ): Bitmap? {
        val sourceRects = planes.map { Rect(it.sourceRect) }
        val front = windowFront
        val hasCurrent = front != null && windowSourceRects == sourceRects

        if (windowDeliveryPending && hasCurrent) {
            windowDeliveryPending = false
            return front
        }
        windowDeliveryPending = false
        if (windowSourceRects != sourceRects) {
            resetWindowCapture()
            windowSourceRects = sourceRects
        }
        if (!windowPending) {
            requestWindowCopy(
                blurView = blurView,
                planes = planes,
                width = width,
                height = height,
                scaleX = scaleX,
                scaleY = scaleY,
            )
        }
        return windowFront?.takeIf {
            windowSourceRects == sourceRects
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestWindowCopy(
        blurView: View,
        planes: List<CapturePlane>,
        width: Int,
        height: Int,
        scaleX: Float,
        scaleY: Float,
    ) {
        val reusable = windowBack
        val destination = if (reusable != null &&
            reusable.width == width &&
            reusable.height == height
        ) {
            reusable
        } else {
            reusable?.recycle()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        windowBack = null
        destination.eraseColor(android.graphics.Color.TRANSPARENT)
        val planeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(destination)
        windowPending = true
        val generation = windowGeneration
        val retainedSurfaceBitmaps = retainSurfaceBitmaps(planes)
        var requestFinished = false

        fun finishRequest() {
            if (requestFinished) return
            requestFinished = true
            releaseSurfaceBitmaps(retainedSurfaceBitmaps)
        }

        fun fail() {
            windowPending = false
            planeBitmap.recycle()
            windowBack = destination
            finishRequest()
            scheduleWindowRetry(blurView)
        }

        fun copyPlane(index: Int) {
            if (generation != windowGeneration) {
                planeBitmap.recycle()
                destination.recycle()
                finishRequest()
                return
            }
            if (index == planes.size) {
                windowPending = false
                planeBitmap.recycle()
                cancelWindowRetry()
                windowBack = windowFront
                windowFront = destination
                windowDeliveryPending = true
                finishRequest()
                requestUpdate(blurView)
                return
            }

            val plane = planes[index]
            val window = plane.source.window ?: return fail()
            planeBitmap.eraseColor(android.graphics.Color.TRANSPARENT)
            try {
                PixelCopy.request(
                    window,
                    Rect(plane.sourceRect),
                    planeBitmap,
                    { result ->
                        if (generation != windowGeneration) {
                            planeBitmap.recycle()
                            destination.recycle()
                            finishRequest()
                            return@request
                        }
                        if (result != PixelCopy.SUCCESS) {
                            fail()
                            return@request
                        }
                        plane.surfaceLayers
                            .filterNot { it.aboveWindow }
                            .forEach { layer ->
                                drawSurfaceLayer(
                                    canvas = canvas,
                                    layer = layer,
                                    sourceView = plane.source.view,
                                    scaleX = scaleX,
                                    scaleY = scaleY,
                                )
                            }
                        canvas.drawBitmap(planeBitmap, 0f, 0f, null)
                        plane.surfaceLayers
                            .filter { it.aboveWindow }
                            .forEach { layer ->
                            drawSurfaceLayer(
                                canvas = canvas,
                                layer = layer,
                                sourceView = plane.source.view,
                                scaleX = scaleX,
                                scaleY = scaleY,
                            )
                        }
                        copyPlane(index + 1)
                    },
                    mainHandler,
                )
            } catch (_: Exception) {
                fail()
            }
        }

        copyPlane(0)
    }

    private fun retainSurfaceBitmaps(planes: List<CapturePlane>): List<Bitmap> {
        val retained = mutableListOf<Bitmap>()
        planes.forEach { plane ->
            plane.surfaceLayers.forEach { layer ->
                if (retained.none { it === layer.bitmap }) {
                    retained += layer.bitmap
                    pinnedSurfaceBitmaps[layer.bitmap] =
                        (pinnedSurfaceBitmaps[layer.bitmap] ?: 0) + 1
                }
            }
        }
        return retained
    }

    private fun releaseSurfaceBitmaps(bitmaps: List<Bitmap>) {
        bitmaps.forEach { bitmap ->
            val remaining = (pinnedSurfaceBitmaps[bitmap] ?: 1) - 1
            if (remaining > 0) {
                pinnedSurfaceBitmaps[bitmap] = remaining
            } else {
                pinnedSurfaceBitmaps.remove(bitmap)
                if (surfaceBitmaps.values.none { it === bitmap }) {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun retireSurfaceBitmap(bitmap: Bitmap) {
        if (!pinnedSurfaceBitmaps.containsKey(bitmap)) {
            bitmap.recycle()
        }
    }

    private fun sourceRect(blurView: View, sourceView: View): Rect {
        sourceView.getLocationOnScreen(sourceLocation)
        sourceView.getLocationInWindow(sourceWindowLocation)
        val left = blurViewLocation[0] - sourceLocation[0] + sourceWindowLocation[0]
        val top = blurViewLocation[1] - sourceLocation[1] + sourceWindowLocation[1]
        return Rect(left, top, left + blurView.width, top + blurView.height)
    }

    private fun scheduleWindowRetry(blurView: View) {
        if (windowRetry != null) return
        val delayMillis = (INITIAL_RETRY_DELAY_MILLIS shl windowRetryCount)
            .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        windowRetryCount = (windowRetryCount + 1).coerceAtMost(MAX_RETRY_EXPONENT)
        windowRetry = Runnable {
            windowRetry = null
            requestUpdate(blurView)
        }.also {
            mainHandler.postDelayed(it, delayMillis)
        }
    }

    private fun cancelWindowRetry() {
        windowRetry?.let(mainHandler::removeCallbacks)
        windowRetry = null
        windowRetryCount = 0
    }

    private fun requestUpdate(blurView: View) {
        when (blurView) {
            is BlurView -> blurView.requestCaptureDelivery()
            is VariableBlurView -> blurView.requestCaptureDelivery()
            else -> blurView.postInvalidate()
        }
    }

    private fun resetWindowCapture() {
        windowGeneration++
        windowPending = false
        windowDeliveryPending = false
        cancelWindowRetry()
        windowFront?.recycle()
        windowFront = null
        windowBack?.recycle()
        windowBack = null
        windowSourceRects = emptyList()
    }

    private fun drawHierarchyPlane(
        canvas: Canvas,
        sourceView: View,
        surfaceLayers: List<SurfaceLayer>,
        textureOverlays: MutableList<TextureOverlay>,
        offsetX: Int,
        offsetY: Int,
        scaleX: Float,
        scaleY: Float,
        width: Int,
        height: Int,
    ) {
        surfaceLayers
            .filter { it.target is TextureView }
            .forEach { layer ->
                val textureView = layer.target as TextureView
                val drawable = CapturedTextureDrawable(layer.bitmap).apply {
                    setBounds(0, 0, textureView.width, textureView.height)
                }
                textureOverlays += TextureOverlay(textureView, drawable)
                textureView.overlay.add(drawable)
            }

        val hierarchyBitmap = obtainViewHierarchyBitmap(width, height)
        hierarchyBitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        Canvas(hierarchyBitmap).run {
            val saveCount = save()
            scale(scaleX, scaleY)
            translate(-offsetX.toFloat(), -offsetY.toFloat())
            sourceView.background?.draw(this)
            sourceView.draw(this)
            restoreToCount(saveCount)
        }

        surfaceLayers
            .filter { it.target is SurfaceView && !it.aboveWindow }
            .forEach { drawSurfaceLayer(canvas, it, sourceView, scaleX, scaleY) }
        canvas.drawBitmap(hierarchyBitmap, 0f, 0f, null)
        surfaceLayers
            .filter { it.target is SurfaceView && it.aboveWindow }
            .forEach { drawSurfaceLayer(canvas, it, sourceView, scaleX, scaleY) }
    }

    private fun drawSurfaceLayer(
        canvas: Canvas,
        layer: SurfaceLayer,
        sourceView: View,
        scaleX: Float,
        scaleY: Float,
    ) {
        sourceView.getLocationOnScreen(sourceLocation)
        val offsetX = blurViewLocation[0] - sourceLocation[0]
        val offsetY = blurViewLocation[1] - sourceLocation[1]
        surfacePaint.alpha = (layer.alpha * 255).toInt().coerceIn(0, 255)
        val saveCount = canvas.save()
        canvas.scale(scaleX, scaleY)
        canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
        val destination = if (layer.aboveWindow) {
            layer.target.getLocationOnScreen(surfaceLocation)
            val left = (surfaceLocation[0] - sourceLocation[0]).toFloat()
            val top = (surfaceLocation[1] - sourceLocation[1]).toFloat()
            RectF(left, top, left + layer.target.width, top + layer.target.height)
        } else {
            clipToAncestors(canvas, layer.target, sourceView)
            canvas.concat(layer.target.matrixToAncestor(sourceView))
            layer.target.clipBounds?.let(canvas::clipRect)
            RectF(0f, 0f, layer.target.width.toFloat(), layer.target.height.toFloat())
        }
        canvas.drawBitmap(
            layer.bitmap,
            null,
            destination,
            surfacePaint,
        )
        canvas.restoreToCount(saveCount)
    }

    private fun clipToAncestors(canvas: Canvas, target: View, ancestor: View) {
        var parent = target.parent as? View
        while (parent != null) {
            if (parent is ViewGroup && (parent.clipChildren || parent.clipToPadding)) {
                val left = if (parent.clipToPadding) parent.paddingLeft.toFloat() else 0f
                val top = if (parent.clipToPadding) parent.paddingTop.toFloat() else 0f
                val right = if (parent.clipToPadding) {
                    (parent.width - parent.paddingRight).toFloat()
                } else {
                    parent.width.toFloat()
                }
                val bottom = if (parent.clipToPadding) {
                    (parent.height - parent.paddingBottom).toFloat()
                } else {
                    parent.height.toFloat()
                }
                surfaceClipPath.reset()
                surfaceClipPath.addRect(left, top, right, bottom, Path.Direction.CW)
                surfaceClipPath.transform(parent.matrixToAncestor(ancestor))
                canvas.clipPath(surfaceClipPath)
            }
            parent.clipBounds?.let { bounds ->
                surfaceClipPath.reset()
                surfaceClipPath.addRect(
                    bounds.left.toFloat(),
                    bounds.top.toFloat(),
                    bounds.right.toFloat(),
                    bounds.bottom.toFloat(),
                    Path.Direction.CW,
                )
                surfaceClipPath.transform(parent.matrixToAncestor(ancestor))
                canvas.clipPath(surfaceClipPath)
            }
            if (parent === ancestor) return
            parent = parent.parent as? View
        }
    }

    private fun View.matrixToAncestor(ancestor: View): Matrix {
        val result = Matrix()
        val step = Matrix()
        var current: View = this
        while (current !== ancestor) {
            val parent = current.parent as? View ?: return Matrix()
            step.reset()
            step.setTranslate(
                (current.left - parent.scrollX).toFloat(),
                (current.top - parent.scrollY).toFloat(),
            )
            if (!current.matrix.isIdentity) {
                step.preConcat(current.matrix)
            }
            result.setConcat(step, result)
            current = parent
        }
        return result
    }

    private fun obtainViewHierarchyBitmap(width: Int, height: Int): Bitmap {
        val current = viewHierarchyBitmap
        if (current != null && current.width == width && current.height == height) {
            return current
        }
        current?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            viewHierarchyBitmap = it
        }
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun View.effectiveAlpha(): Float {
        var alphaProduct = 1f
        var current: View? = this
        while (current != null) {
            if (current.visibility != View.VISIBLE) return 0f
            alphaProduct *= current.alpha
            current = current.parent as? View
        }
        return alphaProduct
    }

    private fun SurfaceView.compositionOrder(): Int {
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                return javaClass.getMethod("getCompositionOrder").invoke(this) as Int
            }
        }
        SurfaceCapture.registeredCompositionOrder(this)?.let { return it }
        return if (SurfaceCapture.isAboveWindow(this)) 1 else -2
    }

    private fun List<BackdropCaptureSource>.sameSources(
        other: List<BackdropCaptureSource>?,
    ): Boolean = other != null &&
        size == other.size &&
        indices.all { this[it].view === other[it].view && this[it].window === other[it].window }

    override fun release() {
        surfaceCapture.release()
        val bitmaps = surfaceBitmaps.values.toList()
        surfaceBitmaps.clear()
        bitmaps.forEach(::retireSurfaceBitmap)
        viewHierarchyBitmap?.recycle()
        viewHierarchyBitmap = null
        resetWindowCapture()
        sourceWindow = null
        captureSources = null
    }

    override fun isAvailable(): Boolean = true

    private class CapturedTextureDrawable(
        private val bitmap: Bitmap,
    ) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            canvas.drawBitmap(bitmap, null, bounds, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    class StopCaptureException : RuntimeException()

    companion object {
        private const val INITIAL_RETRY_DELAY_MILLIS = 16L
        private const val MAX_RETRY_DELAY_MILLIS = 512L
        private const val MAX_RETRY_EXPONENT = 5

        val STOP_EXCEPTION = StopCaptureException()
    }
}
