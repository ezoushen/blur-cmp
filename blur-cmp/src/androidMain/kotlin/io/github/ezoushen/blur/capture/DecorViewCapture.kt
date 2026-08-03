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
import android.graphics.Region
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
    val pixelCopyCoordinator: WindowPixelCopyCoordinator? = null,
)

/**
 * Captures view content by drawing the DecorView to a scaled bitmap.
 */
class DecorViewCapture : ContentCapture {

    private val sourceLocation = IntArray(2)
    private val sourceWindowLocation = IntArray(2)
    private val blurViewLocation = IntArray(2)
    private val surfaceLocation = IntArray(2)
    private val surfaceTransparentRegion = Region()
    private val surfacePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val windowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val surfaceClipPath = Path()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isCapturing = false

    private val excludedViews = mutableListOf<View>()
    private val surfaceCapture = SurfaceCapture()
    private val surfaceViewTrackers = IdentityHashMap<View, SurfaceViewPresenceTracker>()
    private val activeSurfaceSources = IdentityHashMap<View, Boolean>()
    private val surfaceBitmaps = IdentityHashMap<View, Bitmap>()
    private val pinnedSurfaceBitmaps = IdentityHashMap<Bitmap, Int>()
    private var viewHierarchyBitmap: Bitmap? = null
    private var sourceWindow: Window? = null
    private var captureSources: List<BackdropCaptureSource>? = null
    private var capturePrefix: BackdropCapturePrefix? = null
    private var windowFront: Bitmap? = null
    private var windowFrontLease: WindowPixelCopyLease? = null
    private var windowFrontPrefixFrame: WindowPrefixFrame? = null
    private var directWindowFrame: WindowCapturedBitmap? = null
    private var directCoordinatorDelivery = false
    private var windowBack: Bitmap? = null
    private var windowPlaneBack: Bitmap? = null
    private var windowPending = false
    private var windowDeliveryPending = false
    private var windowDeliveryRequestVersion = Long.MIN_VALUE
    private var windowGeneration = 0
    private var windowSourceRects = emptyList<Rect>()
    private var windowCapturePrefix: BackdropCapturePrefix? = null
    private var windowPrefixRect: Rect? = null
    private var windowPrefixOutputWidth = 0
    private var windowPrefixOutputHeight = 0
    private var windowRetryCount = 0
    private var windowRetry: Runnable? = null
    private var windowCopyRequest: WindowPixelCopyRequest? = null
    private var windowCopyEpoch: WindowPixelCopyCoordinator.Epoch? = null
    private var windowCoordinatorCleanup: (() -> Unit)? = null

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

    internal fun hasPendingWindowCopy(): Boolean = windowPending

    fun setSourceWindow(window: Window?) {
        if (sourceWindow === window && capturePrefix == null) return
        capturePrefix = null
        sourceWindow = window
        resetWindowCapture()
    }

    internal fun setCaptureSources(sources: List<BackdropCaptureSource>?) {
        val unchanged = captureSources?.sameSources(sources) ?: (sources == null)
        if (unchanged && capturePrefix == null) return
        capturePrefix = null
        captureSources = sources?.toList()
        resetWindowCapture()
    }

    internal fun setCapturePrefix(prefix: BackdropCapturePrefix?) {
        if (capturePrefix === prefix) return
        capturePrefix = prefix
        captureSources = null
        sourceWindow = null
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

    internal fun captureForBlur(
        blurView: View,
        sourceView: View,
        output: Bitmap,
        downsampleFactor: Float,
    ): Boolean {
        directWindowFrame?.close()
        directWindowFrame = null
        directCoordinatorDelivery = true
        return try {
            capture(blurView, sourceView, output, downsampleFactor)
        } finally {
            directCoordinatorDelivery = false
        }
    }

    internal fun capturePreparedPrefixForBlur(
        blurView: View,
        outputWidth: Int,
        outputHeight: Int,
    ): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val prefix = capturePrefix ?: return null
        if (blurView.width == 0 || blurView.height == 0) return false

        directWindowFrame?.close()
        directWindowFrame = null
        directCoordinatorDelivery = true
        return try {
            blurView.getLocationOnScreen(blurViewLocation)
            capturePreparedPrefix(
                blurView = blurView,
                prefix = prefix,
                output = null,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                requestVersion = captureRequestVersion(blurView),
            )
        } finally {
            directCoordinatorDelivery = false
        }
    }

    internal fun takeDirectWindowFrame(): WindowCapturedBitmap? =
        directWindowFrame.also { directWindowFrame = null }

    override fun capture(
        blurView: View,
        sourceView: View,
        output: Bitmap,
        downsampleFactor: Float
    ): Boolean {
        if (blurView.width == 0 || blurView.height == 0) {
            return false
        }

        blurView.getLocationOnScreen(blurViewLocation)
        val scaleX = output.width.toFloat() / blurView.width
        val scaleY = output.height.toFloat() / blurView.height
        val prefix = capturePrefix
        if (prefix != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return capturePreparedPrefix(
                blurView = blurView,
                prefix = prefix,
                output = output,
                outputWidth = output.width,
                outputHeight = output.height,
                requestVersion = captureRequestVersion(blurView),
            )
        }
        val sources = captureSources
            ?: listOf(BackdropCaptureSource(sourceView, sourceWindow))
        retainSurfaceSources(sources)
        val canCaptureWindows = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            sources.all { it.window != null }
        val sourceRects = if (canCaptureWindows) {
            sources.map { sourceRect(blurView, it.view) }
        } else {
            emptyList()
        }
        val requestVersion = captureRequestVersion(blurView)
        if (canCaptureWindows) {
            val frontLease = windowFrontLease
            val front = frontLease?.bitmap ?: windowFront
            if (windowDeliveryPending && front != null &&
                front.width == output.width && front.height == output.height &&
                windowSourceRects == sourceRects &&
                windowDeliveryRequestVersion == requestVersion
            ) {
                windowDeliveryPending = false
                if (directCoordinatorDelivery && frontLease != null) {
                    windowFrontLease = null
                    directWindowFrame = frontLease
                } else {
                    output.eraseColor(android.graphics.Color.TRANSPARENT)
                    Canvas(output).drawBitmap(
                        front,
                        null,
                        Rect(0, 0, output.width, output.height),
                        windowPaint,
                    )
                    if (windowFrontLease === frontLease) windowFrontLease = null
                    frontLease?.close()
                }
                return true
            }
            windowDeliveryPending = false
            windowFrontLease?.close()
            windowFrontLease = null
            if (windowSourceRects != sourceRects) {
                resetWindowCapture()
                windowSourceRects = sourceRects
            } else if (windowPending) {
                return false
            }
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

            output.eraseColor(android.graphics.Color.TRANSPARENT)
            var surfacesReady = true
            if (canCaptureWindows) {
                val planes = sources.mapIndexed { index, source ->
                    val (surfaceLayers, ready) = captureSurfaceChildren(
                        blurView = blurView,
                        sourceView = source.view,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        includeTextureViews = false,
                        activeSurfaceViews = activeSurfaceViews,
                    )
                    surfacesReady = surfacesReady && ready
                    CapturePlane(
                        source = source,
                        sourceRect = sourceRects[index],
                        surfaceLayers = surfaceLayers,
                    )
                }
                if (!surfacesReady) return false
                requestWindowCopy(
                    blurView = blurView,
                    planes = planes,
                    width = output.width,
                    height = output.height,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    requestVersion = requestVersion,
                )
                return false
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
                        surfaceLayers = surfaceLayers,
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
            surfaceCapture.retainViews(activeSurfaceViews)
            val bitmapIterator = surfaceBitmaps.entries.iterator()
            while (bitmapIterator.hasNext()) {
                val entry = bitmapIterator.next()
                if (entry.key !in activeSurfaceViews) {
                    val bitmap = entry.value
                    bitmapIterator.remove()
                    retireSurfaceBitmap(bitmap)
                }
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
        val activeViews = surfaceViewTrackers.getOrPut(sourceView) {
            SurfaceViewPresenceTracker().also { it.setSource(sourceView) }
        }.surfaceViews()
        for (surfaceView in activeViews) {
            if (!includeTextureViews && surfaceView is TextureView) continue
            val alpha = surfaceView.effectiveAlpha()
            if (surfaceView.width == 0 ||
                surfaceView.height == 0 ||
                !surfaceView.isDescendantOf(sourceView) ||
                surfaceView.isDescendantOf(blurView) ||
                alpha == 0f
            ) {
                continue
            }
            activeSurfaceViews += surfaceView
            val aboveWindow = surfaceView is SurfaceView &&
                SurfaceCapture.isAboveWindow(
                    surfaceView,
                    surfaceTransparentRegion,
                    surfaceLocation,
                )

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
                alpha = alpha,
                aboveWindow = aboveWindow,
                compositionOrder = (surfaceView as? SurfaceView)?.compositionOrder(aboveWindow)
                    ?: 0,
            )
        }
        layers.sortBy { it.compositionOrder }
        return layers to surfacesReady
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun capturePreparedPrefix(
        blurView: View,
        prefix: BackdropCapturePrefix,
        output: Bitmap?,
        outputWidth: Int,
        outputHeight: Int,
        requestVersion: Long,
    ): Boolean {
        val screenRect = Rect(
            blurViewLocation[0],
            blurViewLocation[1],
            blurViewLocation[0] + blurView.width,
            blurViewLocation[1] + blurView.height,
        )
        val front = windowFrontPrefixFrame
        if (windowDeliveryPending && front != null &&
            windowCapturePrefix === prefix &&
            windowPrefixRect == screenRect &&
            windowPrefixOutputWidth == outputWidth &&
            windowPrefixOutputHeight == outputHeight &&
            windowDeliveryRequestVersion == requestVersion
        ) {
            windowDeliveryPending = false
            windowFrontPrefixFrame = null
            if (directCoordinatorDelivery) {
                directWindowFrame = front
            } else {
                requireNotNull(output)
                output.eraseColor(android.graphics.Color.TRANSPARENT)
                Canvas(output).drawBitmap(
                    front.bitmap,
                    null,
                    Rect(0, 0, outputWidth, outputHeight),
                    windowPaint,
                )
                front.close()
            }
            return true
        }

        windowDeliveryPending = false
        windowFrontPrefixFrame?.close()
        windowFrontPrefixFrame = null
        directWindowFrame?.close()
        directWindowFrame = null
        val requestChanged = windowCapturePrefix !== prefix ||
            windowPrefixRect != screenRect ||
            windowPrefixOutputWidth != outputWidth ||
            windowPrefixOutputHeight != outputHeight
        if (requestChanged) {
            resetWindowCapture()
            windowCapturePrefix = prefix
            windowPrefixRect = screenRect
            windowPrefixOutputWidth = outputWidth
            windowPrefixOutputHeight = outputHeight
        } else if (windowPending) {
            return false
        }

        val coordinator = prefix.source.pixelCopyCoordinator
            ?: return false
        val epoch = runCatching { coordinator.beginEpoch() }.getOrNull()
            ?: return false
        val viewport = BackdropCaptureViewport(
            screenRect = screenRect,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
        )
        windowPending = true
        val generation = windowGeneration
        var requestFinished = false
        var activeRequest: WindowPixelCopyRequest? = null
        lateinit var coordinatorCleanup: () -> Unit
        windowCopyEpoch = epoch

        fun finishRequest() {
            if (requestFinished) return
            requestFinished = true
            activeRequest?.cancel()
            if (windowCopyRequest === activeRequest) windowCopyRequest = null
            activeRequest = null
            if (windowCopyEpoch === epoch) windowCopyEpoch = null
            epoch.close()
            if (windowCoordinatorCleanup === coordinatorCleanup) {
                windowCoordinatorCleanup = null
            }
        }

        coordinatorCleanup = {
            if (!requestFinished) finishRequest()
        }
        windowCoordinatorCleanup = coordinatorCleanup

        fun fail() {
            windowPending = false
            finishRequest()
            scheduleWindowRetry(blurView)
        }

        try {
            val request = epoch.requestPrefix(prefix, viewport) { result, frame ->
                val completedRequest = activeRequest
                if (windowCopyRequest === completedRequest) windowCopyRequest = null
                activeRequest = null
                if (generation != windowGeneration) {
                    frame?.close()
                    finishRequest()
                } else if (result != PixelCopy.SUCCESS || frame == null) {
                    frame?.close()
                    fail()
                } else {
                    windowPending = false
                    cancelWindowRetry()
                    windowFrontPrefixFrame?.close()
                    windowFrontPrefixFrame = frame
                    windowDeliveryRequestVersion = requestVersion
                    windowDeliveryPending = true
                    finishRequest()
                    requestUpdate(blurView)
                }
            }
            if (requestFinished) {
                request.cancel()
            } else {
                activeRequest = request
                windowCopyRequest = request
            }
        } catch (_: Exception) {
            fail()
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestWindowCopy(
        blurView: View,
        planes: List<CapturePlane>,
        width: Int,
        height: Int,
        scaleX: Float,
        scaleY: Float,
        requestVersion: Long,
    ) {
        val coordinator = planes.firstOrNull()?.source?.pixelCopyCoordinator
            ?.takeIf { candidate ->
                planes.all { it.source.pixelCopyCoordinator === candidate }
            }
        val epoch = runCatching { coordinator?.beginEpoch() }.getOrNull()
        // Window PixelCopy already includes TextureViews. Separately captured SurfaceViews need
        // the per-plane path below to preserve their below-window/above-window ordering.
        if (epoch != null && planes.all { it.surfaceLayers.isEmpty() }) {
            requestSharedWindowPrefix(
                blurView = blurView,
                planes = planes,
                width = width,
                height = height,
                requestVersion = requestVersion,
                epoch = epoch,
            )
            return
        }

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
        val planeBitmap = if (epoch == null) {
            val reusablePlane = windowPlaneBack
            if (reusablePlane != null &&
                reusablePlane.width == width &&
                reusablePlane.height == height
            ) {
                reusablePlane
            } else {
                reusablePlane?.recycle()
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }.also { windowPlaneBack = null }
        } else {
            null
        }
        val canvas = Canvas(destination)
        windowPending = true
        val generation = windowGeneration
        val retainedSurfaceBitmaps = retainSurfaceBitmaps(planes)
        var requestFinished = false
        var activeRequest: WindowPixelCopyRequest? = null
        lateinit var coordinatorCleanup: () -> Unit
        windowCopyEpoch = epoch

        fun finishRequest() {
            if (requestFinished) return
            requestFinished = true
            activeRequest?.cancel()
            if (windowCopyRequest === activeRequest) windowCopyRequest = null
            activeRequest = null
            if (windowCopyEpoch === epoch) windowCopyEpoch = null
            epoch?.close()
            if (windowCoordinatorCleanup === coordinatorCleanup) {
                windowCoordinatorCleanup = null
            }
            planeBitmap?.let { bitmap ->
                if (generation == windowGeneration) {
                    windowPlaneBack?.recycle()
                    windowPlaneBack = bitmap
                } else {
                    bitmap.recycle()
                }
            }
            releaseSurfaceBitmaps(retainedSurfaceBitmaps)
        }

        coordinatorCleanup = {
            if (!requestFinished) {
                activeRequest?.cancel()
                destination.recycle()
                finishRequest()
            }
        }
        if (epoch != null) windowCoordinatorCleanup = coordinatorCleanup

        fun fail() {
            windowPending = false
            windowBack = destination
            finishRequest()
            scheduleWindowRetry(blurView)
        }

        fun copyPlane(index: Int) {
            if (generation != windowGeneration) {
                destination.recycle()
                finishRequest()
                return
            }
            if (index == planes.size) {
                windowPending = false
                cancelWindowRetry()
                windowFrontLease?.close()
                windowFrontLease = null
                windowBack = windowFront
                windowFront = destination
                windowDeliveryRequestVersion = requestVersion
                windowDeliveryPending = true
                finishRequest()
                requestUpdate(blurView)
                return
            }

            val plane = planes[index]
            val window = plane.source.window ?: return fail()
            try {
                val onResult: (Int, Bitmap?) -> Unit = { result, copiedBitmap ->
                    val completedRequest = activeRequest
                    if (windowCopyRequest === completedRequest) windowCopyRequest = null
                    activeRequest = null
                    if (generation != windowGeneration) {
                        destination.recycle()
                        finishRequest()
                    } else if (result != PixelCopy.SUCCESS || copiedBitmap == null) {
                        fail()
                    } else {
                        for (layer in plane.surfaceLayers) {
                            if (!layer.aboveWindow) {
                                drawSurfaceLayer(
                                    canvas = canvas,
                                    layer = layer,
                                    sourceView = plane.source.view,
                                    scaleX = scaleX,
                                    scaleY = scaleY,
                                )
                            }
                        }
                        canvas.drawBitmap(copiedBitmap, 0f, 0f, null)
                        for (layer in plane.surfaceLayers) {
                            if (layer.aboveWindow) {
                                drawSurfaceLayer(
                                    canvas = canvas,
                                    layer = layer,
                                    sourceView = plane.source.view,
                                    scaleX = scaleX,
                                    scaleY = scaleY,
                                )
                            }
                        }
                        copyPlane(index + 1)
                    }
                }
                if (epoch != null) {
                    activeRequest = epoch.request(
                        window = window,
                        sourceRect = plane.sourceRect,
                        width = width,
                        height = height,
                        onResult = onResult,
                    ).also { windowCopyRequest = it }
                } else {
                    requireNotNull(planeBitmap)
                        .eraseColor(android.graphics.Color.TRANSPARENT)
                    PixelCopy.request(
                        window,
                        Rect(plane.sourceRect),
                        planeBitmap,
                        { result ->
                            onResult(
                                result,
                                planeBitmap.takeIf { result == PixelCopy.SUCCESS },
                            )
                        },
                        mainHandler,
                    )
                }
            } catch (_: Exception) {
                fail()
            }
        }

        copyPlane(0)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestSharedWindowPrefix(
        blurView: View,
        planes: List<CapturePlane>,
        width: Int,
        height: Int,
        requestVersion: Long,
        epoch: WindowPixelCopyCoordinator.Epoch,
    ) {
        windowPending = true
        val generation = windowGeneration
        var requestFinished = false
        var activeRequest: WindowPixelCopyRequest? = null
        lateinit var coordinatorCleanup: () -> Unit
        windowCopyEpoch = epoch

        fun finishRequest() {
            if (requestFinished) return
            requestFinished = true
            activeRequest?.cancel()
            if (windowCopyRequest === activeRequest) windowCopyRequest = null
            activeRequest = null
            if (windowCopyEpoch === epoch) windowCopyEpoch = null
            epoch.close()
            if (windowCoordinatorCleanup === coordinatorCleanup) {
                windowCoordinatorCleanup = null
            }
        }

        coordinatorCleanup = {
            if (!requestFinished) finishRequest()
        }
        windowCoordinatorCleanup = coordinatorCleanup

        fun fail() {
            windowPending = false
            finishRequest()
            scheduleWindowRetry(blurView)
        }

        try {
            val request = epoch.requestPrefix(
                planes = planes.map { plane ->
                    WindowPixelCopyPlane(
                        window = requireNotNull(plane.source.window),
                        sourceRect = plane.sourceRect,
                    )
                },
                width = width,
                height = height,
            ) { result, lease ->
                val completedRequest = activeRequest
                if (windowCopyRequest === completedRequest) windowCopyRequest = null
                activeRequest = null
                if (generation != windowGeneration) {
                    lease?.close()
                    finishRequest()
                } else if (result != PixelCopy.SUCCESS || lease == null) {
                    lease?.close()
                    fail()
                } else {
                    windowPending = false
                    cancelWindowRetry()
                    windowFrontLease?.close()
                    windowFrontLease = lease
                    windowDeliveryRequestVersion = requestVersion
                    windowDeliveryPending = true
                    finishRequest()
                    requestUpdate(blurView)
                }
            }
            if (requestFinished) {
                request.cancel()
            } else {
                activeRequest = request
                windowCopyRequest = request
            }
        } catch (_: Exception) {
            fail()
        }
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

    private fun captureRequestVersion(blurView: View): Long = when (blurView) {
        is BlurView -> blurView.captureRequestVersion()
        is VariableBlurView -> blurView.captureRequestVersion()
        else -> 0L
    }

    private fun resetWindowCapture() {
        windowGeneration++
        windowCoordinatorCleanup?.let { cleanup ->
            windowCoordinatorCleanup = null
            cleanup()
        }
        windowCopyRequest?.cancel()
        windowCopyRequest = null
        windowCopyEpoch?.close()
        windowCopyEpoch = null
        windowPending = false
        windowDeliveryPending = false
        windowDeliveryRequestVersion = Long.MIN_VALUE
        cancelWindowRetry()
        windowFrontLease?.close()
        windowFrontLease = null
        windowFrontPrefixFrame?.close()
        windowFrontPrefixFrame = null
        directWindowFrame?.close()
        directWindowFrame = null
        windowFront?.recycle()
        windowFront = null
        windowBack?.recycle()
        windowBack = null
        windowPlaneBack?.recycle()
        windowPlaneBack = null
        windowSourceRects = emptyList()
        windowCapturePrefix = null
        windowPrefixRect = null
        windowPrefixOutputWidth = 0
        windowPrefixOutputHeight = 0
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
        for (layer in surfaceLayers) {
            if (layer.target is TextureView) {
                val textureView = layer.target
                val drawable = CapturedTextureDrawable(layer.bitmap).apply {
                    setBounds(0, 0, textureView.width, textureView.height)
                }
                textureOverlays += TextureOverlay(textureView, drawable)
                textureView.overlay.add(drawable)
            }
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

        for (layer in surfaceLayers) {
            if (layer.target is SurfaceView && !layer.aboveWindow) {
                drawSurfaceLayer(canvas, layer, sourceView, scaleX, scaleY)
            }
        }
        canvas.drawBitmap(hierarchyBitmap, 0f, 0f, null)
        for (layer in surfaceLayers) {
            if (layer.target is SurfaceView && layer.aboveWindow) {
                drawSurfaceLayer(canvas, layer, sourceView, scaleX, scaleY)
            }
        }
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

    private fun SurfaceView.compositionOrder(aboveWindow: Boolean): Int {
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                return surfaceCompositionOrderMethod?.invoke(this) as Int
            }
        }
        SurfaceCapture.registeredCompositionOrder(this)?.let { return it }
        return if (aboveWindow) 1 else -2
    }

    private fun retainSurfaceSources(sources: List<BackdropCaptureSource>) {
        activeSurfaceSources.clear()
        sources.forEach { activeSurfaceSources[it.view] = true }
        val iterator = surfaceViewTrackers.entries.iterator()
        while (iterator.hasNext()) {
            val (view, tracker) = iterator.next()
            if (!activeSurfaceSources.containsKey(view)) {
                tracker.release()
                iterator.remove()
            }
        }
    }

    private fun List<BackdropCaptureSource>.sameSources(
        other: List<BackdropCaptureSource>?,
    ): Boolean = other != null &&
        size == other.size &&
        indices.all {
            this[it].view === other[it].view &&
                this[it].window === other[it].window &&
                this[it].pixelCopyCoordinator === other[it].pixelCopyCoordinator
        }

    override fun release() {
        surfaceCapture.release()
        surfaceViewTrackers.values.forEach(SurfaceViewPresenceTracker::release)
        surfaceViewTrackers.clear()
        activeSurfaceSources.clear()
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

        private val surfaceCompositionOrderMethod by lazy(LazyThreadSafetyMode.NONE) {
            runCatching {
                SurfaceView::class.java.getMethod("getCompositionOrder")
            }.getOrNull()
        }

        val STOP_EXCEPTION = StopCaptureException()
    }
}
