package io.github.ezoushen.blur.capture

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.graphics.SurfaceTexture
import android.os.Debug
import android.os.Handler
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowPixelCopySurfacePrefixTest {

    @Test
    fun stackedSurfacePrefixesSharePhysicalCaptureAndWaitForFirstSurfaceFrame() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val windowCopier = RecordingWindowPixelCopier()
            val surfaceCopier = RecordingSurfacePixelCopier()
            val composer = CountingPrefixComposer()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = windowCopier,
                surfacePixelCopier = surfaceCopier,
                epochProvider = { 1L },
                prefixComposer = composer,
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val layers = List(32) { surfaceLayer(context) }
            val prefixes = sharedPrefixes(
                layers.map { layer ->
                    BackdropCaptureSource(layer.root, createWindow(context), coordinator)
                },
            )
            val outputSizes = List(layers.size) { index ->
                (8 + index) to (6 + index)
            }
            val requestedPrefixSizes = adversarialPrefixSizes(layers.size)
            val epochs = mutableListOf<WindowPixelCopyCoordinator.Epoch>()
            var callbackCount = 0

            try {
                requestedPrefixSizes.forEachIndexed { requestIndex, prefixSize ->
                    val (outputWidth, outputHeight) = outputSizes[requestIndex]
                    coordinator.beginEpoch().also { epoch ->
                        epochs += epoch
                        epoch.requestPrefix(
                            prefixes[prefixSize - 1],
                            BackdropCaptureViewport(
                                screenRect = Rect(0, 0, 8, 8),
                                outputWidth = outputWidth,
                                outputHeight = outputHeight,
                            ),
                        ) { result, frame ->
                            assertEquals(PixelCopy.SUCCESS, result)
                            requireNotNull(frame).let { capturedFrame ->
                                assertEquals(outputWidth, capturedFrame.bitmap.width)
                                assertEquals(outputHeight, capturedFrame.bitmap.height)
                                capturedFrame.close()
                            }
                            callbackCount++
                        }
                    }
                }

                batchScheduler.flush()
                assertEquals(layers.size, windowCopier.requests.size)
                assertEquals(layers.size, surfaceCopier.requests.size)
                windowCopier.completeAll(PixelCopy.SUCCESS)
                assertEquals(
                    0,
                    callbackCount,
                    "A prefix must not publish a window-only placeholder",
                )

                val locationReadsBeforeSurfaceCompletion =
                    layers.sumOf(SurfaceLayerFixture::screenReadCount)
                surfaceCopier.completeAll(PixelCopy.SUCCESS)
                val surfaceCompletionLocationReads =
                    layers.sumOf(SurfaceLayerFixture::screenReadCount) -
                        locationReadsBeforeSurfaceCompletion
                windowCopier.completeAll(PixelCopy.SUCCESS)

                assertEquals(layers.size, windowCopier.requests.size)
                assertEquals(layers.size, surfaceCopier.requests.size)
                assertEquals(layers.size, callbackCount)
                assertTrue(
                    composer.composeCallCount <= layers.size * 2,
                    "Surface and prefix composition must remain linear",
                )
                assertTrue(
                    composer.bitmapDrawCount <= layers.size * 3,
                    "Window, surface, and canonical-prefix draws must remain linear; " +
                        "draws=${composer.bitmapDrawCount}",
                )
                assertEquals(
                    layers.size * 2,
                    surfaceCompletionLocationReads,
                    "Each SurfaceView plane must be positioned exactly once; " +
                        "locationReads=$surfaceCompletionLocationReads",
                )
            } finally {
                epochs.forEach { it.close() }
                coordinator.close()
                layers.forEach(SurfaceLayerFixture::close)
            }
        }
    }

    @Test
    fun cancelledSurfacePrefixRetrySharesPendingPhysicalCaptures() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val windowCopier = RecordingWindowPixelCopier()
            val surfaceCopier = RecordingSurfacePixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val allocatedBitmaps = mutableListOf<Bitmap>()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = windowCopier,
                surfacePixelCopier = surfaceCopier,
                epochProvider = { 1L },
                bitmapFactory = { width, height ->
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        allocatedBitmaps += it
                    }
                },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val layers = List(4) { surfaceLayer(context) }
            val prefixes = sharedPrefixes(
                layers.map { layer ->
                    BackdropCaptureSource(layer.root, createWindow(context), coordinator)
                },
            )
            val viewport = BackdropCaptureViewport(Rect(0, 0, 8, 8), 8, 8)
            val epoch = coordinator.beginEpoch()
            var cancelledCallbacks = 0
            var retryCallbacks = 0

            try {
                val first = epoch.requestPrefix(prefixes.last(), viewport) { _, frame ->
                    frame?.close()
                    cancelledCallbacks++
                }
                batchScheduler.flush()
                assertEquals(layers.size, windowCopier.requests.size)
                assertEquals(layers.size, surfaceCopier.requests.size)

                first.cancel()
                epoch.requestPrefix(prefixes.last(), viewport) { result, frame ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    frame?.close()
                    retryCallbacks++
                }
                batchScheduler.flush()

                assertEquals(layers.size, windowCopier.requests.size)
                assertEquals(
                    layers.size,
                    surfaceCopier.requests.size,
                    "A same-epoch retry must reuse in-flight Surface PixelCopies",
                )

                windowCopier.completeAll(PixelCopy.SUCCESS)
                surfaceCopier.completeAll(PixelCopy.SUCCESS)
                assertEquals(0, cancelledCallbacks)
                assertEquals(1, retryCallbacks)
            } finally {
                epoch.close()
                coordinator.close()
                layers.forEach(SurfaceLayerFixture::close)
            }

            assertTrue(allocatedBitmaps.all(Bitmap::isRecycled))
        }
    }

    @Test
    fun cancelledSurfacePrefixSkipsUnusedFullFrameComposition() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val windowCopier = RecordingWindowPixelCopier()
            val surfaceCopier = RecordingSurfacePixelCopier()
            val composer = CountingPrefixComposer()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val allocatedBitmaps = mutableListOf<Bitmap>()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = windowCopier,
                surfacePixelCopier = surfaceCopier,
                epochProvider = { 1L },
                bitmapFactory = { width, height ->
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        allocatedBitmaps += it
                    }
                },
                prefixComposer = composer,
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val layers = List(4) { surfaceLayer(context) }
            val prefixes = sharedPrefixes(
                layers.map { layer ->
                    BackdropCaptureSource(layer.root, createWindow(context), coordinator)
                },
            )
            val epoch = coordinator.beginEpoch()
            var callbackCount = 0

            try {
                val request = epoch.requestPrefix(
                    prefixes.last(),
                    BackdropCaptureViewport(Rect(0, 0, 8, 8), 8, 8),
                ) { _, frame ->
                    frame?.close()
                    callbackCount++
                }
                batchScheduler.flush()
                val allocationsBeforeCompletion = allocatedBitmaps.size

                request.cancel()
                windowCopier.completeAll(PixelCopy.SUCCESS)
                surfaceCopier.completeAll(PixelCopy.SUCCESS)

                assertEquals(0, callbackCount)
                assertEquals(0, composer.composeCallCount)
                assertEquals(
                    allocationsBeforeCompletion,
                    allocatedBitmaps.size,
                    "A canceled prefix must not allocate full-frame composition outputs",
                )
            } finally {
                epoch.close()
                coordinator.close()
                layers.forEach(SurfaceLayerFixture::close)
            }

            assertTrue(allocatedBitmaps.all(Bitmap::isRecycled))
        }
    }

    @Test
    fun cancelledCompletedSurfacePrefixRetrySharesPhysicalCaptures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var coordinator: WindowPixelCopyCoordinator? = null
        var epoch: WindowPixelCopyCoordinator.Epoch? = null
        var layers = emptyList<SurfaceLayerFixture>()
        lateinit var windowCopier: RecordingWindowPixelCopier
        lateinit var surfaceCopier: RecordingSurfacePixelCopier
        var retryCallbacks = 0

        try {
            onMain {
                val context = ApplicationProvider.getApplicationContext<Context>()
                windowCopier = RecordingWindowPixelCopier()
                surfaceCopier = RecordingSurfacePixelCopier()
                val batchScheduler = ManualPreparedPrefixBatchScheduler()
                val stableCoordinator = WindowPixelCopyCoordinator(
                    pixelCopier = windowCopier,
                    surfacePixelCopier = surfaceCopier,
                    epochProvider = { 1L },
                    preparedPrefixBatchPoster = batchScheduler::post,
                )
                coordinator = stableCoordinator
                layers = List(4) { surfaceLayer(context) }
                val prefixes = sharedPrefixes(
                    layers.map { layer ->
                        BackdropCaptureSource(
                            layer.root,
                            createWindow(context),
                            stableCoordinator,
                        )
                    },
                )
                val viewport = BackdropCaptureViewport(Rect(0, 0, 8, 8), 8, 8)
                val stableEpoch = stableCoordinator.beginEpoch()
                epoch = stableEpoch

                val first = stableEpoch.requestPrefix(prefixes.last(), viewport) { _, frame ->
                    frame?.close()
                }
                batchScheduler.flush()
                first.cancel()
                windowCopier.completeAll(PixelCopy.SUCCESS)
                surfaceCopier.completeAll(PixelCopy.SUCCESS)

                stableEpoch.requestPrefix(prefixes.last(), viewport) { result, frame ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    requireNotNull(frame).close()
                    retryCallbacks++
                }
                batchScheduler.flush()

                assertEquals(layers.size, windowCopier.requests.size)
                assertEquals(
                    layers.size,
                    surfaceCopier.requests.size,
                    "A completed same-epoch retry must reuse Surface PixelCopies",
                )
            }

            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(1, retryCallbacks)
                assertEquals(layers.size, windowCopier.requests.size)
                assertEquals(layers.size, surfaceCopier.requests.size)
            }
        } finally {
            onMain {
                epoch?.close()
                coordinator?.close()
                layers.forEach(SurfaceLayerFixture::close)
            }
        }
    }

    @Test
    fun surfacePrefixPreservesBelowWindowAndAboveWindowOrder() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val below = surfaceView(context, aboveWindow = false)
            val above = surfaceView(context, aboveWindow = true)
            val root = FrameLayout(context).apply {
                addView(below.view)
                addView(above.view)
                layout(0, 0, 3, 1)
                below.view.layout(0, 0, 3, 1)
                above.view.layout(0, 0, 3, 1)
            }
            SurfaceCapture.registerCompositionOrder(below.view, -2)
            SurfaceCapture.registerCompositionOrder(above.view, 1)
            val window = createWindow(context)
            val windowCopier = RecordingWindowPixelCopier(
                IdentityHashMap<Window, IntArray>().apply {
                    put(window, intArrayOf(Color.TRANSPARENT, Color.GREEN, Color.TRANSPARENT))
                },
            )
            val surfaceCopier = RecordingSurfacePixelCopier(
                IdentityHashMap<Surface, IntArray>().apply {
                    put(below.surface, intArrayOf(Color.RED, Color.RED, Color.RED))
                    put(above.surface, intArrayOf(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        Color.BLUE,
                    ))
                },
            )
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = windowCopier,
                surfacePixelCopier = surfaceCopier,
                epochProvider = { 2L },
                prefixComposer = CountingPrefixComposer(),
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val prefix = BackdropCapturePrefix(
                parent = null,
                source = BackdropCaptureSource(root, window, coordinator),
            )
            val epoch = coordinator.beginEpoch()
            var frame: WindowPrefixFrame? = null

            try {
                epoch.requestPrefix(
                    prefix,
                    BackdropCaptureViewport(Rect(0, 0, 3, 1), 3, 1),
                ) { result, capturedFrame ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    frame = requireNotNull(capturedFrame)
                }

                batchScheduler.flush()
                windowCopier.completeAll(PixelCopy.SUCCESS)
                assertEquals(null, frame)
                surfaceCopier.completeAll(PixelCopy.SUCCESS)
                windowCopier.completeAll(PixelCopy.SUCCESS)

                assertEquals(Color.RED, frame?.bitmap?.getPixel(0, 0))
                assertEquals(Color.GREEN, frame?.bitmap?.getPixel(1, 0))
                assertEquals(Color.BLUE, frame?.bitmap?.getPixel(2, 0))
            } finally {
                frame?.close()
                epoch.close()
                coordinator.close()
                below.close()
                above.close()
            }
        }
    }

    @Test
    fun closingPendingSurfacePrefixRecyclesCompletedAndLateCopiesWithoutDelivery() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val completedSurface = surfaceView(context, aboveWindow = true)
            val pendingSurface = surfaceView(context, aboveWindow = true)
            val root = FrameLayout(context).apply {
                addView(completedSurface.view)
                addView(pendingSurface.view)
                layout(0, 0, 8, 8)
                completedSurface.view.layout(0, 0, 8, 8)
                pendingSurface.view.layout(0, 0, 8, 8)
            }
            SurfaceCapture.registerCompositionOrder(completedSurface.view, 1)
            SurfaceCapture.registerCompositionOrder(pendingSurface.view, 2)
            val windowCopier = RecordingWindowPixelCopier()
            val surfaceCopier = RecordingSurfacePixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = windowCopier,
                surfacePixelCopier = surfaceCopier,
                epochProvider = { 3L },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val prefix = BackdropCapturePrefix(
                parent = null,
                source = BackdropCaptureSource(root, createWindow(context), coordinator),
            )
            val epoch = coordinator.beginEpoch()
            var callbackCount = 0

            try {
                epoch.requestPrefix(
                    prefix,
                    BackdropCaptureViewport(Rect(0, 0, 8, 8), 8, 8),
                ) { _, frame ->
                    frame?.close()
                    callbackCount++
                }

                batchScheduler.flush()
                assertEquals(2, surfaceCopier.requests.size)
                val completedDestination = surfaceCopier.requests[0].destination
                val pendingDestination = surfaceCopier.requests[1].destination
                windowCopier.completeAll(PixelCopy.SUCCESS)
                surfaceCopier.completeNext(PixelCopy.SUCCESS)

                epoch.close()
                coordinator.close()

                assertTrue(completedDestination.isRecycled)
                assertFalse(
                    pendingDestination.isRecycled,
                    "An in-flight PixelCopy destination must remain valid until its callback",
                )
                surfaceCopier.completeNext(PixelCopy.SUCCESS)
                assertTrue(pendingDestination.isRecycled)
                assertEquals(0, callbackCount)
            } finally {
                epoch.close()
                coordinator.close()
                completedSurface.close()
                pendingSurface.close()
            }
        }
    }

    @Test
    fun closingCoordinatorFromFirstSharedSurfaceSubscriberSkipsLaterDelivery() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val texture = SurfaceTexture(0)
            val sharedSurface = Surface(texture)
            val firstSurface = surfaceView(context, sharedSurface, aboveWindow = true)
            val secondSurface = surfaceView(context, sharedSurface, aboveWindow = true)
            val firstRoot = FrameLayout(context).apply {
                addView(firstSurface)
                layout(0, 0, 8, 8)
                firstSurface.layout(0, 0, 8, 8)
            }
            val secondRoot = FrameLayout(context).apply {
                addView(secondSurface)
                layout(0, 0, 8, 8)
                secondSurface.layout(0, 0, 8, 8)
            }
            SurfaceCapture.registerCompositionOrder(firstSurface, 1)
            SurfaceCapture.registerCompositionOrder(secondSurface, 1)
            val windowCopier = RecordingWindowPixelCopier()
            val surfaceCopier = RecordingSurfacePixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val allocatedBitmaps = mutableListOf<Bitmap>()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = windowCopier,
                surfacePixelCopier = surfaceCopier,
                epochProvider = { 4L },
                bitmapFactory = { width, height ->
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        allocatedBitmaps += it
                    }
                },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val prefixes = sharedPrefixes(
                listOf(firstRoot, secondRoot).map { root ->
                    BackdropCaptureSource(root, createWindow(context), coordinator)
                },
            )
            val epoch = coordinator.beginEpoch()
            var firstCallbackCount = 0
            var laterCallbackCount = 0

            try {
                epoch.requestPrefix(
                    prefixes[0],
                    BackdropCaptureViewport(Rect(0, 0, 8, 8), 8, 8),
                ) { result, frame ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    requireNotNull(frame).let {
                        assertFalse(it.bitmap.isRecycled)
                        coordinator.close()
                        it.close()
                        it.close()
                    }
                    firstCallbackCount++
                }
                epoch.requestPrefix(
                    prefixes[1],
                    BackdropCaptureViewport(Rect(0, 0, 8, 8), 8, 8),
                ) { _, frame ->
                    frame?.close()
                    laterCallbackCount++
                }

                batchScheduler.flush()
                assertEquals(1, surfaceCopier.requests.size)
                windowCopier.completeAll(PixelCopy.SUCCESS)
                surfaceCopier.completeAll(PixelCopy.SUCCESS)

                assertEquals(1, firstCallbackCount)
                assertEquals(0, laterCallbackCount)
                assertTrue(allocatedBitmaps.all { it.isRecycled })
            } finally {
                epoch.close()
                coordinator.close()
                sharedSurface.release()
                texture.release()
            }
        }
    }

    @Test
    fun pruningTransientPrefixRootReleasesSurfaceViewTracker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var coordinator: WindowPixelCopyCoordinator? = null
        var surface: SurfaceFixture? = null

        try {
            onMain {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val stableSurface = surfaceView(context, aboveWindow = true)
                surface = stableSurface
                val root = FrameLayout(context).apply {
                    addView(stableSurface.view)
                    layout(0, 0, 8, 8)
                    stableSurface.view.layout(0, 0, 8, 8)
                }
                val windowCopier = RecordingWindowPixelCopier()
                val surfaceCopier = RecordingSurfacePixelCopier()
                val batchScheduler = ManualPreparedPrefixBatchScheduler()
                var frameEpoch = 5L
                val stableCoordinator = WindowPixelCopyCoordinator(
                    pixelCopier = windowCopier,
                    surfacePixelCopier = surfaceCopier,
                    epochProvider = { frameEpoch },
                    preparedPrefixBatchPoster = batchScheduler::post,
                )
                coordinator = stableCoordinator
                val prefix = BackdropCapturePrefix(
                    parent = null,
                    source = BackdropCaptureSource(
                        root,
                        createWindow(context),
                        stableCoordinator,
                    ),
                )

                stableCoordinator.beginEpoch().use { epoch ->
                    epoch.requestPrefix(
                        prefix,
                        BackdropCaptureViewport(Rect(0, 0, 8, 8), 8, 8),
                    ) { result, frame ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        requireNotNull(frame).close()
                    }
                    batchScheduler.flush()
                    windowCopier.completeAll(PixelCopy.SUCCESS)
                    surfaceCopier.completeAll(PixelCopy.SUCCESS)
                }

                assertEquals(1, stableCoordinator.surfaceViewTrackerCount())
                frameEpoch++
                stableCoordinator.beginEpoch().close()
                assertEquals(
                    0,
                    stableCoordinator.surfaceEntryCount(),
                    "A previous-epoch Surface PixelCopy must leave the capture cache",
                )
            }

            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(
                    0,
                    requireNotNull(coordinator).surfaceViewTrackerCount(),
                    "A pruned prefix root must not retain its source or layout listener",
                )
            }
        } finally {
            onMain {
                coordinator?.close()
                surface?.close()
            }
        }
    }

    @Test
    fun adjacentLiveEpochsReuseStableSurfaceViewTracker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var coordinator: WindowPixelCopyCoordinator? = null
        var surface: SurfaceFixture? = null

        try {
            onMain {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val stableSurface = surfaceView(context, aboveWindow = true)
                surface = stableSurface
                val root = FrameLayout(context).apply {
                    addView(stableSurface.view)
                    layout(0, 0, 8, 8)
                    stableSurface.view.layout(0, 0, 8, 8)
                }
                val windowCopier = RecordingWindowPixelCopier()
                val surfaceCopier = RecordingSurfacePixelCopier()
                val batchScheduler = ManualPreparedPrefixBatchScheduler()
                var frameEpoch = 20L
                val stableCoordinator = WindowPixelCopyCoordinator(
                    pixelCopier = windowCopier,
                    surfacePixelCopier = surfaceCopier,
                    epochProvider = { frameEpoch },
                    preparedPrefixBatchPoster = batchScheduler::post,
                )
                coordinator = stableCoordinator
                val prefix = BackdropCapturePrefix(
                    parent = null,
                    source = BackdropCaptureSource(
                        root,
                        createWindow(context),
                        stableCoordinator,
                    ),
                )
                val viewport = BackdropCaptureViewport(Rect(0, 0, 8, 8), 8, 8)

                stableCoordinator.beginEpoch().use { epoch ->
                    epoch.requestPrefix(prefix, viewport) { result, frame ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        requireNotNull(frame).close()
                    }
                    batchScheduler.flush()
                    windowCopier.completeAll(PixelCopy.SUCCESS)
                    surfaceCopier.completeAll(PixelCopy.SUCCESS)
                }
                val firstTracker = requireNotNull(
                    stableCoordinator.surfaceViewTracker(root),
                )

                frameEpoch++
                stableCoordinator.beginEpoch().use { epoch ->
                    epoch.requestPrefix(prefix, viewport) { result, frame ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        requireNotNull(frame).close()
                    }
                    batchScheduler.flush()
                    assertSame(
                        firstTracker,
                        stableCoordinator.surfaceViewTracker(root),
                        "Stable live capture must retain its cached hierarchy tracker",
                    )
                    windowCopier.completeAll(PixelCopy.SUCCESS)
                    surfaceCopier.completeAll(PixelCopy.SUCCESS)
                }

                frameEpoch++
                stableCoordinator.beginEpoch().close()
            }

            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(
                    0,
                    requireNotNull(coordinator).surfaceViewTrackerCount(),
                    "An unreacquired tracker must still release after the capture turn",
                )
            }
        } finally {
            onMain {
                coordinator?.close()
                surface?.close()
            }
        }
    }

    @Test
    fun stableSurfaceWavesStayWithinAllocationBudget() {
        onMain {
            val warmupWaves = 4
            val measuredWaves = 32
            val context = ApplicationProvider.getApplicationContext<Context>()
            val belowWindow = surfaceView(context, aboveWindow = false)
            val aboveWindow = surfaceView(context, aboveWindow = true)
            val root = FrameLayout(context).apply {
                addView(belowWindow.view)
                addView(aboveWindow.view)
                layout(0, 0, 8, 8)
                belowWindow.view.layout(0, 0, 8, 8)
                aboveWindow.view.layout(0, 0, 8, 8)
            }
            SurfaceCapture.registerCompositionOrder(belowWindow.view, -2)
            SurfaceCapture.registerCompositionOrder(aboveWindow.view, 1)
            val windowCopier = RecordingWindowPixelCopier()
            val surfaceCopier = RecordingSurfacePixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            var frameEpoch = 0L
            var callbackCount = 0
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = windowCopier,
                surfacePixelCopier = surfaceCopier,
                epochProvider = { frameEpoch },
                prefixComposer = WindowPrefixComposer { _, _, _ -> },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val prefix = BackdropCapturePrefix(
                parent = null,
                source = BackdropCaptureSource(root, createWindow(context), coordinator),
            )
            val viewport = BackdropCaptureViewport(Rect(0, 0, 8, 8), 8, 8)
            val onResult: (Int, WindowPrefixFrame?) -> Unit = { result, frame ->
                check(result == PixelCopy.SUCCESS)
                requireNotNull(frame).close()
                callbackCount++
            }

            fun captureWave() {
                frameEpoch++
                coordinator.beginEpoch().use { epoch ->
                    epoch.requestPrefix(prefix, viewport, onResult)
                    batchScheduler.flush()
                    check(windowCopier.requests.size == 1)
                    check(surfaceCopier.requests.size == 2)
                    windowCopier.completeAll(PixelCopy.SUCCESS)
                    surfaceCopier.completeAll(PixelCopy.SUCCESS)
                }
                windowCopier.requests.clear()
                surfaceCopier.requests.clear()
            }

            try {
                repeat(warmupWaves) { captureWave() }

                val allocations = measuredThreadAllocations {
                    repeat(measuredWaves) { captureWave() }
                }

                // Epoch/request/subscriber objects remain necessary per-wave work. The old
                // renderer scratch and two-list surface preparation measured 215-216
                // allocations per wave on the connected API 33/35 emulators.
                val maximumAllocations = measuredWaves * 200
                assertTrue(
                    allocations <= maximumAllocations,
                    "Stable SurfaceView allocations exceeded the per-wave budget; " +
                        "allocations=$allocations, budget=$maximumAllocations",
                )
                assertEquals(warmupWaves + measuredWaves, callbackCount)
            } finally {
                coordinator.close()
                belowWindow.close()
                aboveWindow.close()
            }
        }
    }

    @Test
    fun visibleInvalidSurfaceNeverPublishesWindowOnlyPrefix() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val surface = surfaceView(context, aboveWindow = true)
            val root = FrameLayout(context).apply {
                addView(surface.view)
                layout(0, 0, 8, 8)
                surface.view.layout(0, 0, 8, 8)
            }
            surface.surface.release()
            val windowCopier = RecordingWindowPixelCopier()
            val surfaceCopier = RecordingSurfacePixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = windowCopier,
                surfacePixelCopier = surfaceCopier,
                epochProvider = { 6L },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val prefix = BackdropCapturePrefix(
                parent = null,
                source = BackdropCaptureSource(root, createWindow(context), coordinator),
            )
            val epoch = coordinator.beginEpoch()
            var successfulFrameCount = 0

            try {
                epoch.requestPrefix(
                    prefix,
                    BackdropCaptureViewport(Rect(0, 0, 8, 8), 8, 8),
                ) { result, frame ->
                    if (result == PixelCopy.SUCCESS && frame != null) successfulFrameCount++
                    frame?.close()
                }
                batchScheduler.flush()
                windowCopier.completeAll(PixelCopy.SUCCESS)

                assertEquals(
                    0,
                    successfulFrameCount,
                    "A visible SurfaceView without a valid surface must remain not-ready",
                )
            } finally {
                epoch.close()
                coordinator.close()
                surface.close()
            }
        }
    }

    @Test
    fun surfaceFailuresCoalesceIdlePoolTrim() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val layerCount = 16
        var coordinator: WindowPixelCopyCoordinator? = null
        var layers = emptyList<SurfaceLayerFixture>()
        lateinit var entryInspections: InspectionCountingLinkedHashMap<Any, Any>
        var callbackCount = 0

        try {
            onMain {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val windowCopier = RecordingWindowPixelCopier()
                val surfaceCopier = RecordingSurfacePixelCopier()
                val batchScheduler = ManualPreparedPrefixBatchScheduler()
                val stableCoordinator = WindowPixelCopyCoordinator(
                    pixelCopier = windowCopier,
                    surfacePixelCopier = surfaceCopier,
                    epochProvider = { 7L },
                    preparedPrefixBatchPoster = batchScheduler::post,
                )
                coordinator = stableCoordinator
                entryInspections = stableCoordinator.installRawEntryInspectionCounter()
                layers = List(layerCount) { surfaceLayer(context) }
                val prefixes = sharedPrefixes(
                    layers.map { layer ->
                        BackdropCaptureSource(
                            layer.root,
                            createWindow(context),
                            stableCoordinator,
                        )
                    },
                )
                val epochs = List(layerCount) { stableCoordinator.beginEpoch() }
                val viewport = BackdropCaptureViewport(Rect(0, 0, 8, 8), 8, 8)

                epochs.forEachIndexed { index, epoch ->
                    epoch.requestPrefix(prefixes[index], viewport) { result, frame ->
                        assertEquals(PixelCopy.ERROR_SOURCE_NO_DATA, result)
                        assertEquals(null, frame)
                        callbackCount++
                        epoch.close()
                    }
                }
                batchScheduler.flush()
                assertEquals(layerCount, windowCopier.requests.size)
                assertEquals(layerCount, surfaceCopier.requests.size)
                windowCopier.completeAll(PixelCopy.SUCCESS)

                entryInspections.inspectionCount = 0
                surfaceCopier.completeAll(PixelCopy.ERROR_SOURCE_NO_DATA)
            }

            instrumentation.waitForIdleSync()
            onMain {
                assertEquals(layerCount, callbackCount)
                assertTrue(
                    entryInspections.inspectionCount <= layerCount * 2,
                    "Surface failure cleanup must do at most the epoch-release pass and " +
                        "one coalesced pool trim; inspections=" +
                        entryInspections.inspectionCount,
                )
            }
        } finally {
            onMain {
                coordinator?.close()
                layers.forEach(SurfaceLayerFixture::close)
            }
        }
    }

    private fun sharedPrefixes(
        sources: List<BackdropCaptureSource>,
    ): List<BackdropCapturePrefix> {
        var parent: BackdropCapturePrefix? = null
        return sources.map { source ->
            BackdropCapturePrefix(parent, source).also { parent = it }
        }
    }

    private fun adversarialPrefixSizes(depth: Int): List<Int> = buildList(depth) {
        var shallow = 1
        var deep = depth
        while (shallow <= deep) {
            add(deep--)
            if (shallow <= deep) add(shallow++)
        }
    }

    private fun surfaceLayer(context: Context): SurfaceLayerFixture {
        val surface = surfaceView(context, aboveWindow = true)
        val root = CountingLocationFrameLayout(context).apply {
            addView(surface.view)
            layout(0, 0, 8, 8)
            surface.view.layout(0, 0, 8, 8)
        }
        SurfaceCapture.registerCompositionOrder(surface.view, 1)
        return SurfaceLayerFixture(root, surface)
    }

    private fun surfaceView(context: Context, aboveWindow: Boolean): SurfaceFixture {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        return SurfaceFixture(
            surfaceView(context, surface, aboveWindow),
            surface,
            texture,
        )
    }

    private fun surfaceView(
        context: Context,
        surface: Surface,
        aboveWindow: Boolean,
    ): CountingLocationSurfaceView {
        val holder = FakeSurfaceHolder(surface)
        return CountingLocationSurfaceView(context, holder, aboveWindow)
    }

    private class CountingLocationFrameLayout(context: Context) : FrameLayout(context) {
        var screenReadCount = 0

        override fun getLocationOnScreen(outLocation: IntArray) {
            screenReadCount++
            outLocation[0] = 0
            outLocation[1] = 0
        }
    }

    private class CountingLocationSurfaceView(
        context: Context,
        private val holder: SurfaceHolder,
        private val aboveWindow: Boolean,
    ) : SurfaceView(context) {
        var screenReadCount = 0

        override fun getLocationOnScreen(outLocation: IntArray) {
            screenReadCount++
            outLocation[0] = 0
            outLocation[1] = 0
        }

        override fun getHolder(): SurfaceHolder = holder

        override fun gatherTransparentRegion(region: Region?): Boolean {
            if (!aboveWindow) region?.set(0, 0, width, height)
            return !aboveWindow
        }
    }

    private fun WindowPixelCopyCoordinator.installRawEntryInspectionCounter():
        InspectionCountingLinkedHashMap<Any, Any> {
        val counter = InspectionCountingLinkedHashMap<Any, Any>()
        javaClass.getDeclaredField("entries").apply { isAccessible = true }.set(this, counter)
        return counter
    }

    private fun WindowPixelCopyCoordinator.surfaceViewTrackerCount(): Int =
        (javaClass.getDeclaredField("surfaceViewTrackers").apply { isAccessible = true }
            .get(this) as Map<*, *>).size

    private fun WindowPixelCopyCoordinator.surfaceEntryCount(): Int =
        (javaClass.getDeclaredField("surfaceEntries").apply { isAccessible = true }
            .get(this) as Map<*, *>).size

    private fun WindowPixelCopyCoordinator.surfaceViewTracker(sourceView: View): Any? =
        (javaClass.getDeclaredField("surfaceViewTrackers").apply { isAccessible = true }
            .get(this) as Map<*, *>)[sourceView]

    private fun createWindow(context: Context): Window =
        requireNotNull(Dialog(context, android.R.style.Theme_Material_Light).window)

    private fun <T> onMain(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val result = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result.set(runCatching(block))
        }
        return result.get().getOrThrow()
    }

    @Suppress("DEPRECATION")
    private fun measuredThreadAllocations(block: () -> Unit): Int {
        Debug.startAllocCounting()
        return try {
            Debug.resetThreadAllocCount()
            block()
            Debug.getThreadAllocCount()
        } finally {
            Debug.stopAllocCounting()
        }
    }

    private class ManualPreparedPrefixBatchScheduler {
        private val tasks = ArrayDeque<Runnable>()

        fun post(task: Runnable) {
            tasks.addLast(task)
        }

        fun flush() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class RecordingWindowPixelCopier(
        private val pixels: IdentityHashMap<Window, IntArray> = IdentityHashMap(),
    ) : WindowPixelCopier {
        val requests = mutableListOf<Request>()

        override fun request(
            window: Window,
            sourceRect: Rect,
            destination: Bitmap,
            onResult: (Int) -> Unit,
        ) {
            requests += Request(window, destination, onResult)
        }

        fun completeAll(result: Int) {
            while (true) {
                val request = requests.firstOrNull { !it.completed } ?: return
                request.completed = true
                request.destination.eraseColor(Color.TRANSPARENT)
                pixels[request.window]?.let { sourcePixels ->
                    request.destination.setPixels(
                        sourcePixels,
                        0,
                        request.destination.width,
                        0,
                        0,
                        request.destination.width,
                        request.destination.height,
                    )
                }
                request.onResult(result)
            }
        }

        data class Request(
            val window: Window,
            val destination: Bitmap,
            val onResult: (Int) -> Unit,
            var completed: Boolean = false,
        )
    }

    private class RecordingSurfacePixelCopier(
        private val pixels: IdentityHashMap<Surface, IntArray> = IdentityHashMap(),
    ) : SurfacePixelCopier {
        val requests = mutableListOf<Request>()

        override fun request(
            surface: Surface,
            destination: Bitmap,
            onResult: (Int) -> Unit,
            handler: Handler,
        ) {
            requests += Request(surface, destination, onResult)
        }

        fun completeAll(result: Int) {
            while (true) {
                val request = requests.firstOrNull { !it.completed } ?: return
                complete(request, result)
            }
        }

        fun completeNext(result: Int) {
            complete(requests.first { !it.completed }, result)
        }

        private fun complete(request: Request, result: Int) {
            request.completed = true
            request.destination.eraseColor(Color.TRANSPARENT)
            pixels[request.surface]?.let { sourcePixels ->
                request.destination.setPixels(
                    sourcePixels,
                    0,
                    request.destination.width,
                    0,
                    0,
                    request.destination.width,
                    request.destination.height,
                )
            }
            request.onResult(result)
        }

        data class Request(
            val surface: Surface,
            val destination: Bitmap,
            val onResult: (Int) -> Unit,
            var completed: Boolean = false,
        )
    }

    private class CountingPrefixComposer : WindowPrefixComposer {
        var composeCallCount = 0
        var bitmapDrawCount = 0

        override fun compose(
            destination: Bitmap,
            prefix: Bitmap?,
            additions: List<Bitmap>,
        ) {
            composeCallCount++
            bitmapDrawCount += additions.size + if (prefix == null) 0 else 1
            destination.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(destination)
            prefix?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            additions.forEach { canvas.drawBitmap(it, 0f, 0f, null) }
        }
    }

    private class InspectionCountingLinkedHashMap<K, V> : LinkedHashMap<K, V>() {
        var inspectionCount = 0

        override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
            get() {
                val delegate = super.entries
                return object : MutableSet<MutableMap.MutableEntry<K, V>> by delegate {
                    override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
                        val iterator = delegate.iterator()
                        return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                            override fun hasNext(): Boolean = iterator.hasNext()

                            override fun next(): MutableMap.MutableEntry<K, V> {
                                inspectionCount++
                                return iterator.next()
                            }

                            override fun remove() = iterator.remove()
                        }
                    }
                }
            }

        override val values: MutableCollection<V>
            get() {
                val delegate = super.values
                return object : MutableCollection<V> by delegate {
                    override fun iterator(): MutableIterator<V> {
                        val iterator = delegate.iterator()
                        return object : MutableIterator<V> {
                            override fun hasNext(): Boolean = iterator.hasNext()

                            override fun next(): V {
                                inspectionCount++
                                return iterator.next()
                            }

                            override fun remove() = iterator.remove()
                        }
                    }
                }
            }
    }

    private class SurfaceLayerFixture(
        val root: CountingLocationFrameLayout,
        private val surface: SurfaceFixture,
    ) : AutoCloseable {
        val screenReadCount: Int
            get() = root.screenReadCount + surface.view.screenReadCount

        override fun close() = surface.close()
    }

    private class SurfaceFixture(
        val view: CountingLocationSurfaceView,
        val surface: Surface,
        private val texture: SurfaceTexture,
    ) : AutoCloseable {
        override fun close() {
            surface.release()
            texture.release()
        }
    }

    private class FakeSurfaceHolder(
        private val backingSurface: Surface,
    ) : SurfaceHolder {
        private val callbacks = mutableListOf<SurfaceHolder.Callback>()

        override fun addCallback(callback: SurfaceHolder.Callback) {
            callbacks += callback
        }

        override fun removeCallback(callback: SurfaceHolder.Callback) {
            callbacks -= callback
        }

        override fun getSurface(): Surface = backingSurface
        override fun getSurfaceFrame(): Rect = Rect()
        override fun isCreating(): Boolean = false
        override fun setType(type: Int) = Unit
        override fun setFixedSize(width: Int, height: Int) = Unit
        override fun setSizeFromLayout() = Unit
        override fun setFormat(format: Int) = Unit
        override fun setKeepScreenOn(screenOn: Boolean) = Unit
        override fun lockCanvas(): Canvas = error("unused")
        override fun lockCanvas(dirty: Rect?): Canvas = error("unused")
        override fun unlockCanvasAndPost(canvas: Canvas) = Unit
    }
}
