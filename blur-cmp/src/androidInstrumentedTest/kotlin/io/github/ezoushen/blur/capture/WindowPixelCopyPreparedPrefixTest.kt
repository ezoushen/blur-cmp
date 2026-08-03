package io.github.ezoushen.blur.capture

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowPixelCopyPreparedPrefixTest {

    @Test
    fun sixtyFourAdversarialPrefixesMaterializeEachSourceOnce() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val window = createWindow(context)
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            var composedPlaneCount = 0
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { 1L },
                prefixComposer = WindowPrefixComposer { _, _, additions ->
                    composedPlaneCount += additions.size
                },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val sourceViews = List(64) { index ->
                CountingLocationView(context).apply {
                    layout(0, 0, 32, 32)
                    screenX = index
                }
            }
            val prefixes = sharedPrefixes(
                sourceViews.map { view ->
                    BackdropCaptureSource(view, window, coordinator)
                },
            )
            val viewport = BackdropCaptureViewport(
                screenRect = Rect(0, 0, 32, 32),
                outputWidth = 8,
                outputHeight = 8,
            )
            val epochs = mutableListOf<WindowPixelCopyCoordinator.Epoch>()
            var callbackCount = 0

            adversarialPrefixSizes(64).forEach { prefixSize ->
                coordinator.beginEpoch().also { epoch ->
                    epochs += epoch
                    epoch.requestPrefix(
                        prefix = prefixes[prefixSize - 1],
                        viewport = viewport,
                    ) { result, frame ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        requireNotNull(frame).close()
                        callbackCount++
                    }
                }
            }

            batchScheduler.flush()
            assertEquals(64, copier.requests.size)
            assertEquals(64, sourceViews.sumOf { it.screenReadCount })
            assertEquals(64, sourceViews.sumOf { it.windowReadCount })
            copier.completeAll(PixelCopy.SUCCESS)
            assertEquals(64, composedPlaneCount, "Same-size N-layer composition must stay O(N)")
            assertEquals(64, callbackCount)

            epochs.forEach { it.close() }
            coordinator.close()
        }
    }

    @Test
    fun sixtyFourMixedOutputSizesComposeLinearly() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val window = createWindow(context)
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            var compositionCalls = 0
            var composedPlaneCount = 0
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { 2L },
                prefixComposer = WindowPrefixComposer { _, _, additions ->
                    compositionCalls++
                    composedPlaneCount += additions.size
                },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val layerCount = 64
            val sourceViews = List(layerCount) { index ->
                CountingLocationView(context).apply {
                    layout(0, 0, 32, 32)
                    screenX = index
                }
            }
            val prefixes = sharedPrefixes(
                sourceViews.map { view ->
                    BackdropCaptureSource(view, window, coordinator)
                },
            )
            val epochs = mutableListOf<WindowPixelCopyCoordinator.Epoch>()
            val outputSizes = List(layerCount) { index -> 8 + index to 6 + index }
            var callbackCount = 0

            prefixes.forEachIndexed { index, prefix ->
                val (outputWidth, outputHeight) = outputSizes[index]
                coordinator.beginEpoch().also { epoch ->
                    epochs += epoch
                    epoch.requestPrefix(
                        prefix = prefix,
                        viewport = BackdropCaptureViewport(
                            screenRect = Rect(0, 0, 32, 32),
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
            assertEquals(layerCount, copier.requests.size)
            assertEquals(layerCount, sourceViews.sumOf { it.screenReadCount })
            assertEquals(layerCount, sourceViews.sumOf { it.windowReadCount })
            copier.completeAll(PixelCopy.SUCCESS)
            assertEquals(
                layerCount,
                composedPlaneCount,
                "Mixed-size N-layer source-plane composition must stay O(N)",
            )
            assertTrue(
                compositionCalls <= layerCount * 2,
                "Mixed-size delivery must use at most one composition and one resize per layer",
            )
            assertEquals(layerCount, callbackCount)

            epochs.forEach { it.close() }
            coordinator.close()
        }
    }

    @Test
    fun mixedOutputSizesResizeCanonicalPrefixThroughInactiveLayers() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val copier = AlphaEdgeWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { 2L },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val prefixes = sharedPrefixes(
                List(4) {
                    BackdropCaptureSource(
                        view = CountingLocationView(context).apply { layout(0, 0, 2, 1) },
                        window = createWindow(context),
                        pixelCopyCoordinator = coordinator,
                    )
                },
            )
            val epochs = List(2) { coordinator.beginEpoch() }
            var shallowPixel: Int? = null

            epochs[0].requestPrefix(
                prefix = prefixes[1],
                viewport = BackdropCaptureViewport(Rect(0, 0, 2, 1), 1, 1),
            ) { result, frame ->
                assertEquals(PixelCopy.SUCCESS, result)
                requireNotNull(frame).use { shallowPixel = it.bitmap.getPixel(0, 0) }
            }
            epochs[1].requestPrefix(
                prefix = prefixes[3],
                viewport = BackdropCaptureViewport(Rect(0, 0, 2, 1), 2, 1),
            ) { result, frame ->
                assertEquals(PixelCopy.SUCCESS, result)
                requireNotNull(frame).close()
            }

            batchScheduler.flush()
            copier.completeAll()

            val canonicalPrefix = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
            val sourcePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            }
            Canvas(canonicalPrefix).apply {
                drawBitmap(copier.snapshots[0], 0f, 0f, sourcePaint)
                drawBitmap(copier.snapshots[1], 0f, 0f, null)
            }
            val expected = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            Canvas(expected).drawBitmap(
                canonicalPrefix,
                null,
                Rect(0, 0, 1, 1),
                sourcePaint,
            )
            assertEquals(
                expected.getPixel(0, 0),
                shallowPixel,
                "Mixed-size delivery must resize the cumulative canonical prefix once",
            )

            canonicalPrefix.recycle()
            expected.recycle()
            copier.snapshots.forEach(Bitmap::recycle)
            epochs.forEach { it.close() }
            coordinator.close()
        }
    }

    @Test
    fun mixedOutputSizesReuseDeliveryBuffersAcrossEpochs() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val allocatedBitmaps = mutableListOf<Bitmap>()
            var frameEpoch = 1L
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { frameEpoch },
                bitmapFactory = { width, height ->
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        allocatedBitmaps += it
                    }
                },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val source = BackdropCaptureSource(
                view = CountingLocationView(context).apply { layout(0, 0, 32, 32) },
                window = createWindow(context),
                pixelCopyCoordinator = coordinator,
            )
            val prefixes = List(2) { BackdropCapturePrefix(parent = null, source = source) }
            val frames = mutableListOf<WindowPrefixFrame>()

            fun capture() {
                val epochs = List(2) { coordinator.beginEpoch() }
                prefixes.forEachIndexed { index, prefix ->
                    epochs[index].requestPrefix(
                        prefix = prefix,
                        viewport = BackdropCaptureViewport(
                            screenRect = Rect(0, 0, 32, 32),
                            outputWidth = 8 + index * 6,
                            outputHeight = 6 + index * 4,
                        ),
                    ) { result, frame ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        frames += requireNotNull(frame)
                    }
                }
                batchScheduler.flush()
                copier.completeAll(PixelCopy.SUCCESS)
                epochs.forEach { it.close() }
            }

            capture()
            val firstEpochAllocationCount = allocatedBitmaps.size
            frames.forEach(WindowPrefixFrame::close)
            frames.clear()
            frameEpoch++
            capture()

            assertEquals(
                firstEpochAllocationCount,
                allocatedBitmaps.size,
                "Stable mixed output sizes must reuse their delivery buffers",
            )

            frames.forEach(WindowPrefixFrame::close)
            coordinator.close()
        }
    }

    @Test
    fun partialMixedSizeChangePreservesLaterExactSizeReserve() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val allocatedBitmaps = mutableListOf<Bitmap>()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                bitmapFactory = { width, height ->
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        allocatedBitmaps += it
                    }
                },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            coordinator.setReusableBitmapLimit(4)
            repeat(2) {
                coordinator.retireBitmapForTest(
                    Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888),
                )
            }
            repeat(2) {
                coordinator.retireBitmapForTest(
                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888),
                )
            }
            val prefixes = sharedPrefixes(
                List(2) {
                    BackdropCaptureSource(
                        view = CountingLocationView(context).apply { layout(0, 0, 32, 32) },
                        window = createWindow(context),
                        pixelCopyCoordinator = coordinator,
                    )
                },
            )
            val frames = mutableListOf<WindowPrefixFrame>()
            val changedEpochs = List(2) { coordinator.beginEpoch() }
            listOf(prefixes[1] to 4, prefixes[0] to 16)
                .forEachIndexed { index, (prefix, size) ->
                    changedEpochs[index].requestPrefix(
                        prefix = prefix,
                        viewport = BackdropCaptureViewport(
                            screenRect = Rect(0, 0, 32, 32),
                            outputWidth = size,
                            outputHeight = size,
                        ),
                    ) { result, frame ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        frames += requireNotNull(frame)
                    }
                }
            batchScheduler.flush()
            copier.completeAll(PixelCopy.SUCCESS)

            assertEquals(
                2,
                allocatedBitmaps.size,
                "A new deep size must evict obsolete 8x8 buffers, not a later 16x16 reserve; " +
                    allocatedBitmaps.map { it.width to it.height },
            )

            changedEpochs.forEach { it.close() }
            frames.forEach(WindowPrefixFrame::close)
            coordinator.close()
        }
    }

    @Test
    fun heterogeneousPoolMissesStayLinearAndMemoryBounded() {
        onMain {
            val bitmapCount = 16
            val allocatedBitmaps = mutableListOf<Bitmap>()
            val coordinator = WindowPixelCopyCoordinator(
                bitmapFactory = { width, height ->
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        allocatedBitmaps += it
                    }
                },
            )
            val poolInspections = coordinator.installBitmapPoolInspectionCounter()
            coordinator.setReusableBitmapLimit(bitmapCount)
            repeat(bitmapCount) { index ->
                coordinator.retireBitmapForTest(
                    Bitmap.createBitmap(8 + index, 8, Bitmap.Config.ARGB_8888).also {
                        allocatedBitmaps += it
                    },
                )
            }

            poolInspections.inspectionCount = 0
            val outputs = List(bitmapCount) { index ->
                coordinator.obtainBitmapForTest(64 + index, 9)
            }

            assertTrue(
                poolInspections.inspectionCount <= bitmapCount * 2,
                "Heterogeneous pool misses must stay O(N); inspections=" +
                    poolInspections.inspectionCount,
            )
            assertEquals(
                bitmapCount,
                allocatedBitmaps.count { !it.isRecycled },
                "A size change must evict incompatible idle buffers before allocating",
            )

            outputs.forEach(Bitmap::recycle)
            coordinator.close()
        }
    }

    @Test
    fun mixedOutputLeaseRetirementDoesNotScanAllRequirements() {
        onMain {
            val requestCount = 16
            val context = ApplicationProvider.getApplicationContext<Context>()
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { 3L },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val requirementInspections = coordinator.installPoolRequirementInspectionCounter()
            val source = BackdropCaptureSource(
                view = CountingLocationView(context).apply { layout(0, 0, 32, 32) },
                window = createWindow(context),
                pixelCopyCoordinator = coordinator,
            )
            val frames = mutableListOf<WindowPrefixFrame>()
            val epochs = List(requestCount) { coordinator.beginEpoch() }

            epochs.forEachIndexed { index, epoch ->
                epoch.requestPrefix(
                    prefix = BackdropCapturePrefix(parent = null, source = source),
                    viewport = BackdropCaptureViewport(
                        screenRect = Rect(0, 0, 32, 32),
                        outputWidth = 8 + index,
                        outputHeight = 6 + index,
                    ),
                ) { result, frame ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    frames += requireNotNull(frame)
                }
            }

            batchScheduler.flush()
            copier.completeAll(PixelCopy.SUCCESS)
            epochs.forEach { it.close() }
            requirementInspections.inspectionCount = 0
            frames.forEach(WindowPrefixFrame::close)

            assertTrue(
                requirementInspections.inspectionCount <= requestCount,
                "Lease retirement must stay O(N); inspections=" +
                    requirementInspections.inspectionCount,
            )
            coordinator.close()
        }
    }

    @Test
    fun largerChildComposesFromFullResolutionParent() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val copier = PatternWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { 2L },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val prefixes = sharedPrefixes(
                List(2) { index ->
                    BackdropCaptureSource(
                        view = CountingLocationView(context).apply {
                            layout(0, 0, 32, 32)
                            screenX = index
                        },
                        window = createWindow(context),
                        pixelCopyCoordinator = coordinator,
                    )
                },
            )
            val epochs = List(2) { coordinator.beginEpoch() }
            var deepPixels: IntArray? = null

            epochs[0].requestPrefix(
                prefix = prefixes[0],
                viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), 8, 6),
            ) { result, frame ->
                assertEquals(PixelCopy.SUCCESS, result)
                requireNotNull(frame).close()
            }
            epochs[1].requestPrefix(
                prefix = prefixes[1],
                viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), 14, 10),
            ) { result, frame ->
                assertEquals(PixelCopy.SUCCESS, result)
                requireNotNull(frame).use { captured ->
                    deepPixels = IntArray(captured.bitmap.width * captured.bitmap.height).also {
                        captured.bitmap.getPixels(
                            it,
                            0,
                            captured.bitmap.width,
                            0,
                            0,
                            captured.bitmap.width,
                            captured.bitmap.height,
                        )
                    }
                }
            }

            batchScheduler.flush()
            assertEquals(listOf(14 to 10, 14 to 10), copier.destinationSizes)
            copier.completeAll()
            assertTrue(
                copier.parentPattern.contentEquals(requireNotNull(deepPixels)),
                "The larger child must not upscale the smaller parent delivery",
            )

            epochs.forEach { it.close() }
            coordinator.close()
        }
    }

    @Test
    fun equivalentPrefixHandlesShareOneMaterializedEntry() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val allocatedBitmaps = mutableListOf<Bitmap>()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { 3L },
                bitmapFactory = { width, height ->
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        allocatedBitmaps += it
                    }
                },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val source = BackdropCaptureSource(
                view = CountingLocationView(context).apply { layout(0, 0, 32, 32) },
                window = createWindow(context),
                pixelCopyCoordinator = coordinator,
            )
            val prefixes = List(2) { BackdropCapturePrefix(parent = null, source = source) }
            val epochs = List(2) { coordinator.beginEpoch() }
            val deliveredSizes = mutableListOf<Pair<Int, Int>>()

            prefixes.forEachIndexed { index, prefix ->
                val width = 8 + index * 4
                val height = 6 + index * 3
                epochs[index].requestPrefix(
                    prefix = prefix,
                    viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), width, height),
                ) { result, frame ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    requireNotNull(frame).use {
                        deliveredSizes += it.bitmap.width to it.bitmap.height
                    }
                }
            }

            batchScheduler.flush()
            assertEquals(1, copier.requests.size)
            copier.completeAll(PixelCopy.SUCCESS)
            assertEquals(listOf(8 to 6, 12 to 9), deliveredSizes)

            epochs.forEach { it.close() }
            coordinator.close()
            assertTrue(
                allocatedBitmaps.all(Bitmap::isRecycled),
                "Equivalent handles must not leave an overwritten prefix bitmap",
            )
        }
    }

    @Test
    fun resizedDeliveryComposerFailureCleansStateAndNextEpochSucceeds() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val allocatedBitmaps = mutableListOf<Bitmap>()
            var frameEpoch = 1L
            var failResizedDelivery = true
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { frameEpoch },
                bitmapFactory = { width, height ->
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        allocatedBitmaps += it
                    }
                },
                prefixComposer = WindowPrefixComposer { destination, _, _ ->
                    if (failResizedDelivery && destination.width == 8 && destination.height == 6) {
                        failResizedDelivery = false
                        error("forced resized delivery failure")
                    }
                },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val source = BackdropCaptureSource(
                view = CountingLocationView(context).apply { layout(0, 0, 32, 32) },
                window = createWindow(context),
                pixelCopyCoordinator = coordinator,
            )
            val prefixes = List(2) { BackdropCapturePrefix(parent = null, source = source) }
            val firstEpochs = List(2) { coordinator.beginEpoch() }
            var failureResult: Int? = null
            var failureFrame: WindowPrefixFrame? = null

            firstEpochs[0].requestPrefix(
                prefix = prefixes[0],
                viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), 8, 6),
            ) { result, frame ->
                failureResult = result
                failureFrame = frame
            }
            firstEpochs[1].requestPrefix(
                prefix = prefixes[1],
                viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), 12, 9),
            ) { _, frame ->
                frame?.close()
            }
            batchScheduler.flush()
            val completionFailure = runCatching {
                copier.completeAll(PixelCopy.SUCCESS)
            }.exceptionOrNull()
            firstEpochs.forEach(WindowPixelCopyCoordinator.Epoch::close)

            val extraBitmapsAfterFailure = coordinator.privateState<Int>("extraBitmapCount")
            val leasedOutputsAfterFailure =
                coordinator.privateState<Map<*, *>>("leasedOutputCounts")
            val retainedEpochsAfterFailure = coordinator.privateState<Map<*, *>>("leaseCounts")

            frameEpoch = 2L
            val retryEpoch = coordinator.beginEpoch()
            var retryResult: Int? = null
            retryEpoch.requestPrefix(
                prefix = BackdropCapturePrefix(parent = null, source = source),
                viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), 8, 6),
            ) { result, frame ->
                retryResult = result
                frame?.close()
            }
            batchScheduler.flush()
            copier.completeAll(PixelCopy.SUCCESS)
            retryEpoch.close()
            val retainedRootEpochs =
                coordinator.privateState<Map<Long, *>>("prefixRootsByEpoch").keys

            coordinator.close()

            assertEquals(
                null,
                completionFailure,
                "A resized delivery composition failure must be reported through the callback",
            )
            assertEquals(PixelCopy.ERROR_UNKNOWN, failureResult)
            assertEquals(null, failureFrame)
            assertEquals(0, extraBitmapsAfterFailure)
            assertTrue(leasedOutputsAfterFailure.isEmpty())
            assertTrue(retainedEpochsAfterFailure.isEmpty())
            assertEquals(PixelCopy.SUCCESS, retryResult)
            assertTrue(1L !in retainedRootEpochs, "The failed epoch must not retain a prefix root")
            assertTrue(
                allocatedBitmaps.all(Bitmap::isRecycled),
                "Failed delivery output and all coordinator buffers must be recycled on close",
            )
        }
    }

    @Test
    fun invalidBatchDoesNotRetainPartiallyMaterializedRoots() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { 4L },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val valid = BackdropCapturePrefix(
                parent = null,
                source = BackdropCaptureSource(
                    view = CountingLocationView(context).apply { layout(0, 0, 32, 32) },
                    window = createWindow(context),
                    pixelCopyCoordinator = coordinator,
                ),
            )
            val invalid = BackdropCapturePrefix(
                parent = valid,
                source = BackdropCaptureSource(
                    view = CountingLocationView(context).apply { layout(0, 0, 32, 32) },
                    window = null,
                    pixelCopyCoordinator = coordinator,
                ),
            )
            var callbackCount = 0

            repeat(3) { index ->
                coordinator.beginEpoch().use { epoch ->
                    epoch.requestPrefix(
                        prefix = invalid,
                        viewport = BackdropCaptureViewport(
                            screenRect = Rect(index, 0, index + 32, 32),
                            outputWidth = 8,
                            outputHeight = 8,
                        ),
                    ) { result, frame ->
                        assertEquals(PixelCopy.ERROR_UNKNOWN, result)
                        assertEquals(null, frame)
                        callbackCount++
                    }
                    batchScheduler.flush()
                }
            }

            assertEquals(3, callbackCount)
            assertEquals(0, copier.requests.size)
            assertEquals(0, coordinator.prefixRootCount())

            var retryCallbacks = 0
            coordinator.beginEpoch().use { epoch ->
                epoch.requestPrefix(
                    prefix = valid,
                    viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), 8, 8),
                ) { result, frame ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    requireNotNull(frame).close()
                    retryCallbacks++
                }
                batchScheduler.flush()
                assertEquals(1, copier.requests.size)
                copier.completeAll(PixelCopy.SUCCESS)
            }
            assertEquals(1, retryCallbacks)
            coordinator.close()
        }
    }

    @Test
    fun screenRectPartitionsPhysicalCaptureWhileOutputSizeScalesDelivery() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val window = createWindow(context)
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { 2L },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val sourceView = CountingLocationView(context).apply {
                layout(0, 0, 32, 32)
                screenX = 100
                screenY = 50
                windowX = 10
                windowY = 5
            }
            val prefix = BackdropCapturePrefix(
                parent = null,
                source = BackdropCaptureSource(sourceView, window, coordinator),
            )
            val epochs = mutableListOf<WindowPixelCopyCoordinator.Epoch>()
            val deliveredSizes = mutableListOf<Pair<Int, Int>>()
            val viewports = listOf(
                BackdropCaptureViewport(Rect(120, 80, 152, 112), 8, 6),
                BackdropCaptureViewport(Rect(124, 85, 156, 117), 8, 6),
                BackdropCaptureViewport(Rect(120, 80, 152, 112), 12, 9),
            )

            viewports.forEach { viewport ->
                coordinator.beginEpoch().also { epoch ->
                    epochs += epoch
                    epoch.requestPrefix(prefix, viewport) { result, frame ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        requireNotNull(frame).let { capturedFrame ->
                            deliveredSizes.add(
                                capturedFrame.bitmap.width to capturedFrame.bitmap.height,
                            )
                            capturedFrame.close()
                        }
                    }
                }
            }

            batchScheduler.flush()
            assertEquals(2, copier.requests.size)
            assertEquals(
                setOf(
                    Rect(30, 35, 62, 67),
                    Rect(34, 40, 66, 72),
                ),
                copier.requests.map { it.sourceRect }.toSet(),
            )
            assertEquals(2, sourceView.screenReadCount)
            assertEquals(2, sourceView.windowReadCount)

            copier.completeAll(PixelCopy.SUCCESS)
            assertEquals(
                viewports
                    .map { it.outputWidth to it.outputHeight }
                    .groupingBy { it }
                    .eachCount(),
                deliveredSizes.groupingBy { it }.eachCount(),
            )
            epochs.forEach { it.close() }
            coordinator.close()
        }
    }

    @Test
    fun stablePrefixHandleIsReusedAndRematerializedInANewEpoch() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val window = createWindow(context)
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            var frameEpoch = 1L
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { frameEpoch },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val sourceView = CountingLocationView(context).apply {
                layout(0, 0, 32, 32)
                screenX = 100
                screenY = 50
                windowX = 10
                windowY = 5
            }
            val prefix = BackdropCapturePrefix(
                parent = null,
                source = BackdropCaptureSource(sourceView, window, coordinator),
            )
            val viewport = BackdropCaptureViewport(Rect(120, 80, 152, 112), 8, 8)
            var callbackCount = 0

            coordinator.beginEpoch().use { epoch ->
                repeat(2) {
                    epoch.requestPrefix(prefix, viewport) { result, frame ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        requireNotNull(frame).close()
                        callbackCount++
                    }
                }

                batchScheduler.flush()
                assertEquals(1, copier.requests.size)
                assertEquals(1, sourceView.screenReadCount)
                assertEquals(1, sourceView.windowReadCount)
                copier.completeAll(PixelCopy.SUCCESS)
                assertEquals(2, callbackCount)
            }

            frameEpoch = 2L
            sourceView.screenX = 103
            coordinator.beginEpoch().use { epoch ->
                epoch.requestPrefix(prefix, viewport) { result, frame ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    requireNotNull(frame).close()
                    callbackCount++
                }

                batchScheduler.flush()
                assertEquals(2, copier.requests.size)
                assertEquals(Rect(27, 35, 59, 67), copier.requests.last().sourceRect)
                assertEquals(2, sourceView.screenReadCount)
                assertEquals(2, sourceView.windowReadCount)
                copier.completeAll(PixelCopy.SUCCESS)
                assertEquals(3, callbackCount)
            }

            coordinator.close()
        }
    }

    @Test
    fun stablePreparedWavesRetainSurfaceTrackersAcrossLooperTurns() {
        val completion = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var coordinator: WindowPixelCopyCoordinator? = null
            try {
                val depth = 3
                val context = ApplicationProvider.getApplicationContext<Context>()
                val copier = RecordingWindowPixelCopier()
                val mainHandler = Handler(Looper.getMainLooper())
                var frameEpoch = 0L
                val activeCoordinator = WindowPixelCopyCoordinator(
                    pixelCopier = copier,
                    epochProvider = { frameEpoch },
                    prefixComposer = WindowPrefixComposer { _, _, _ -> },
                )
                coordinator = activeCoordinator
                val sourceViews = List(depth) { index ->
                    CountingLocationView(context).apply {
                        layout(0, 0, 32, 32)
                        screenX = index
                    }
                }
                val sources = sourceViews.map { view ->
                    BackdropCaptureSource(view, createWindow(context), activeCoordinator)
                }
                val prefixes = sharedPrefixes(sources)
                val viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), 8, 8)
                var firstTrackers: List<Any>? = null
                var wave = 0

                fun finish(error: Throwable? = null) {
                    if (error != null) failure.compareAndSet(null, error)
                    runCatching { activeCoordinator.close() }
                        .exceptionOrNull()
                        ?.let { failure.compareAndSet(null, it) }
                    completion.countDown()
                }

                lateinit var captureWave: () -> Unit
                captureWave = {
                    frameEpoch++
                    val epochs = List(depth) { activeCoordinator.beginEpoch() }
                    var callbackCount = 0
                    prefixes.forEachIndexed { index, prefix ->
                        epochs[index].requestPrefix(prefix, viewport) { result, frame ->
                            check(result == PixelCopy.SUCCESS)
                            requireNotNull(frame).close()
                            callbackCount++
                        }
                    }
                    mainHandler.post {
                        try {
                            val trackers =
                                activeCoordinator.privateState<Map<View, Any>>(
                                    "surfaceViewTrackers",
                                )
                            val currentTrackers = sourceViews.map { view ->
                                requireNotNull(trackers[view])
                            }
                            val expectedTrackers = firstTrackers
                            if (expectedTrackers == null) {
                                firstTrackers = currentTrackers
                            } else {
                                expectedTrackers.indices.forEach { index ->
                                    assertTrue(
                                        expectedTrackers[index] === currentTrackers[index],
                                        "Stable source tracker $index was recreated across Looper turns",
                                    )
                                }
                            }

                            copier.completeAll(PixelCopy.SUCCESS)
                            assertEquals(depth, callbackCount)
                            epochs.forEach(WindowPixelCopyCoordinator.Epoch::close)
                            copier.requests.clear()
                            wave++
                            if (wave == 2) finish() else captureWave()
                        } catch (error: Throwable) {
                            finish(error)
                        }
                    }
                }

                captureWave()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                runCatching { coordinator?.close() }
                completion.countDown()
            }
        }

        assertTrue(
            completion.await(10, TimeUnit.SECONDS),
            "Timed out waiting for prepared waves to cross Looper turns",
        )
        failure.get()?.let { throw it }
    }

    @Test
    fun stablePreparedTopologyAllocatesLessWhileGeometryStaysLive() {
        onMain {
            val depth = 32
            val measuredWaves = 24
            val context = ApplicationProvider.getApplicationContext<Context>()
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            var frameEpoch = 0L
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { frameEpoch },
                prefixComposer = WindowPrefixComposer { _, _, _ -> },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val sourceViews = List(depth) { index ->
                CountingLocationView(context).apply {
                    layout(0, 0, 32, 32)
                    screenX = index
                }
            }
            val sources = sourceViews.map { view ->
                BackdropCaptureSource(view, createWindow(context), coordinator)
            }
            val stablePrefixes = sharedPrefixes(sources)
            val invalidatedPrefixWaves = List(measuredWaves) { sharedPrefixes(sources) }
            val viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), 8, 8)
            var callbackCount = 0
            var completedWaveCount = 0
            var recordedSourceRect: Rect? = null
            var recordedOutputSize: Pair<Int, Int>? = null

            fun captureWave(
                prefixes: List<BackdropCapturePrefix>,
                captureViewport: BackdropCaptureViewport = viewport,
                recordGeometry: Boolean = false,
            ) {
                frameEpoch++
                val epochs = List(depth) { coordinator.beginEpoch() }
                prefixes.forEachIndexed { index, prefix ->
                    epochs[index].requestPrefix(prefix, captureViewport) { result, frame ->
                        check(result == PixelCopy.SUCCESS)
                        requireNotNull(frame).use { capturedFrame ->
                            if (recordGeometry && index == prefixes.lastIndex) {
                                recordedOutputSize =
                                    capturedFrame.bitmap.width to capturedFrame.bitmap.height
                            }
                        }
                        callbackCount++
                    }
                }
                batchScheduler.flush()
                check(copier.requests.size == depth)
                if (recordGeometry) recordedSourceRect = Rect(copier.requests.first().sourceRect)
                copier.completeAll(PixelCopy.SUCCESS)
                epochs.forEach(WindowPixelCopyCoordinator.Epoch::close)
                copier.requests.clear()
                completedWaveCount++
            }

            try {
                captureWave(invalidatedPrefixWaves.first())
                captureWave(stablePrefixes)
                captureWave(stablePrefixes)

                val stableAllocations = measuredThreadAllocations {
                    repeat(measuredWaves) { captureWave(stablePrefixes) }
                }
                val invalidatedAllocations = measuredThreadAllocations {
                    invalidatedPrefixWaves.forEach(::captureWave)
                }

                // Epochs, callbacks, requests, and their Rects are test inputs, so zero
                // allocations is not a meaningful production gate. Keep their linear
                // per-layer cost bounded; tracker reuse is asserted independently across
                // real Looper turns by stablePreparedWavesRetainSurfaceTrackersAcrossLooperTurns.
                val maximumStableAllocations = measuredWaves * (128 + depth * 96)
                assertTrue(
                    stableAllocations <= maximumStableAllocations,
                    "Stable prepared-prefix allocations exceeded the linear budget; " +
                        "stable=$stableAllocations, budget=$maximumStableAllocations",
                )

                // Reinstall the stable topology, then change only dynamic capture inputs on a hit.
                captureWave(stablePrefixes)
                sourceViews.first().apply {
                    screenX = 13
                    screenY = 17
                    windowX = 3
                    windowY = 5
                }
                captureWave(
                    prefixes = stablePrefixes,
                    captureViewport = BackdropCaptureViewport(
                        screenRect = Rect(40, 50, 80, 90),
                        outputWidth = 10,
                        outputHeight = 6,
                    ),
                    recordGeometry = true,
                )

                assertEquals(Rect(30, 38, 70, 78), recordedSourceRect)
                assertEquals(10 to 6, recordedOutputSize)
                assertEquals(completedWaveCount * depth, callbackCount)

                val minimumExpectedSavings = measuredWaves * depth * 2
                assertTrue(
                    stableAllocations + minimumExpectedSavings <= invalidatedAllocations,
                    "Stable prepared topology should avoid at least $minimumExpectedSavings " +
                        "allocations across $measuredWaves waves; stable=$stableAllocations, " +
                        "identityChurn=$invalidatedAllocations",
                )
            } finally {
                coordinator.close()
            }
        }
    }

    @Test
    fun sameSizePrefixReusesBitmapReserveAcrossEpochs() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val allocatedBitmaps = mutableListOf<Bitmap>()
            var frameEpoch = 1L
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { frameEpoch },
                bitmapFactory = { width, height ->
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        allocatedBitmaps += it
                    }
                },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val prefix = BackdropCapturePrefix(
                parent = null,
                source = BackdropCaptureSource(
                    view = CountingLocationView(context).apply { layout(0, 0, 32, 32) },
                    window = createWindow(context),
                    pixelCopyCoordinator = coordinator,
                ),
            )
            val viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), 8, 8)
            var retainedFrame: WindowPrefixFrame? = null

            fun captureCurrentEpoch() {
                coordinator.beginEpoch().use { epoch ->
                    epoch.requestPrefix(prefix, viewport) { result, frame ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        retainedFrame = requireNotNull(frame)
                    }
                    batchScheduler.flush()
                    copier.completeAll(PixelCopy.SUCCESS)
                }
            }

            try {
                captureCurrentEpoch()
                assertEquals(
                    3 to 0,
                    allocatedBitmaps.size to allocatedBitmaps.count(Bitmap::isRecycled),
                    "The first retained frame needs one raw, one prefix, and one reserve bitmap",
                )

                retainedFrame?.close()
                retainedFrame = null
                frameEpoch++
                captureCurrentEpoch()

                assertEquals(
                    3 to 0,
                    allocatedBitmaps.size to allocatedBitmaps.count(Bitmap::isRecycled),
                    "An unchanged viewport must reuse the existing bitmap reserve",
                )
            } finally {
                retainedFrame?.close()
                coordinator.close()
            }
        }
    }

    @Test
    fun cancelledStablePrefixCanBeRetriedInTheSameEpoch() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val windows = List(2) { createWindow(context) }
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            val allocatedBitmaps = mutableListOf<Bitmap>()
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { 3L },
                bitmapFactory = { width, height ->
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        allocatedBitmaps += it
                    }
                },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val prefixes = sharedPrefixes(
                windows.mapIndexed { index, window ->
                    BackdropCaptureSource(
                        view = CountingLocationView(context).apply {
                            layout(0, 0, 32, 32)
                            screenX = index * 32
                        },
                        window = window,
                        pixelCopyCoordinator = coordinator,
                    )
                },
            )
            val viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), 8, 8)
            val epoch = coordinator.beginEpoch()
            var shallowCallbacks = 0
            var cancelledCallbacks = 0
            var retryCallbacks = 0

            epoch.requestPrefix(prefixes[0], viewport) { result, frame ->
                assertEquals(PixelCopy.SUCCESS, result)
                requireNotNull(frame).close()
                shallowCallbacks++
            }
            epoch.requestPrefix(prefixes[1], viewport) { _, frame ->
                frame?.close()
                cancelledCallbacks++
            }.cancel()

            assertEquals(0, copier.requests.size)
            batchScheduler.flush()
            assertEquals(1, copier.requests.size)
            copier.completeAll(PixelCopy.SUCCESS)

            epoch.requestPrefix(prefixes[1], viewport) { result, frame ->
                assertEquals(PixelCopy.SUCCESS, result)
                requireNotNull(frame).close()
                retryCallbacks++
            }

            batchScheduler.flush()
            assertEquals(2, copier.requests.size)
            copier.completeAll(PixelCopy.SUCCESS)
            epoch.close()
            coordinator.close()

            assertEquals(1, shallowCallbacks)
            assertEquals(0, cancelledCallbacks)
            val unrecycledBitmapCount = allocatedBitmaps.count { !it.isRecycled }
            assertTrue(
                retryCallbacks == 1 && unrecycledBitmapCount == 0,
                "Expected one retry callback and no leaked bitmaps; " +
                    "retryCallbacks=$retryCallbacks, " +
                    "unrecycledBitmaps=$unrecycledBitmapCount",
            )
        }
    }

    @Test
    fun sixtyFourStableRequestsKeepCachePruningLinear() {
        onMain {
            val requestCount = 64
            val context = ApplicationProvider.getApplicationContext<Context>()
            val copier = RecordingWindowPixelCopier()
            val batchScheduler = ManualPreparedPrefixBatchScheduler()
            var nextEpoch = 1L
            val coordinator = WindowPixelCopyCoordinator(
                pixelCopier = copier,
                epochProvider = { nextEpoch++ },
                preparedPrefixBatchPoster = batchScheduler::post,
            )
            val entryInspections = coordinator.installRawEntryInspectionCounter()
            val prefixes = List(requestCount) { index ->
                val window = createWindow(context)
                BackdropCapturePrefix(
                    parent = null,
                    source = BackdropCaptureSource(
                        view = CountingLocationView(context).apply {
                            layout(0, 0, 32, 32)
                            screenX = index * 32
                        },
                        window = window,
                        pixelCopyCoordinator = coordinator,
                    ),
                )
            }
            val viewport = BackdropCaptureViewport(Rect(0, 0, 32, 32), 8, 8)
            val epochs = mutableListOf<WindowPixelCopyCoordinator.Epoch>()
            var callbackCount = 0

            prefixes.forEach { prefix ->
                coordinator.beginEpoch().also { epoch ->
                    epochs += epoch
                    epoch.requestPrefix(prefix, viewport) { result, frame ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        requireNotNull(frame).close()
                        callbackCount++
                    }
                }
            }

            batchScheduler.flush()
            val setupInspections = entryInspections.inspectionCount
            entryInspections.inspectionCount = 0
            copier.completeAll(PixelCopy.SUCCESS)
            val completionInspections = entryInspections.inspectionCount

            epochs.forEach { it.close() }
            coordinator.close()

            assertEquals(requestCount, callbackCount)
            val maximumInspectionsPerRequest = 8
            val linearInspectionBudget = requestCount * maximumInspectionsPerRequest
            assertTrue(
                setupInspections <= linearInspectionBudget &&
                    completionInspections <= linearInspectionBudget,
                "Expected O(N) cache pruning for N=$requestCount with budget=" +
                    "$linearInspectionBudget per phase; setupInspections=$setupInspections, " +
                    "completionInspections=$completionInspections",
            )
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

    private fun createWindow(context: Context): Window =
        requireNotNull(Dialog(context, android.R.style.Theme_Material_Light).window)

    private fun WindowPixelCopyCoordinator.installRawEntryInspectionCounter():
        InspectionCountingLinkedHashMap<Any, Any> {
        val counter = InspectionCountingLinkedHashMap<Any, Any>()
        javaClass.getDeclaredField("entries").apply { isAccessible = true }.set(this, counter)
        return counter
    }

    private fun WindowPixelCopyCoordinator.installPoolRequirementInspectionCounter():
        InspectionCountingLinkedHashMap<Any, Any> {
        val counter = InspectionCountingLinkedHashMap<Any, Any>()
        javaClass.getDeclaredField("poolRequirements").apply { isAccessible = true }
            .set(this, counter)
        return counter
    }

    private fun WindowPixelCopyCoordinator.installBitmapPoolInspectionCounter():
        BitmapPoolInspectionCounter {
        val field = javaClass.getDeclaredField("reusableBitmaps").apply { isAccessible = true }
        return when (field.get(this)) {
            is ArrayDeque<*> -> InspectionCountingArrayDeque<Bitmap>().also {
                field.set(this, it)
            }
            is Map<*, *> -> InspectionCountingBitmapPoolMap().also {
                field.set(this, it)
            }
            else -> error("Unsupported bitmap pool")
        }
    }

    private fun WindowPixelCopyCoordinator.setReusableBitmapLimit(limit: Int) {
        javaClass.getDeclaredField("reusableBitmapLimit").apply { isAccessible = true }
            .setInt(this, limit)
    }

    private fun WindowPixelCopyCoordinator.retireBitmapForTest(bitmap: Bitmap) {
        javaClass.getDeclaredMethod("retireBitmap", Bitmap::class.java).apply {
            isAccessible = true
        }.invoke(this, bitmap)
    }

    private fun WindowPixelCopyCoordinator.obtainBitmapForTest(width: Int, height: Int): Bitmap =
        javaClass.getDeclaredMethod(
            "obtainBitmap",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(this, width, height) as Bitmap

    private fun WindowPixelCopyCoordinator.prefixRootCount(): Int =
        javaClass.getDeclaredField("prefixRoots").apply { isAccessible = true }
            .get(this)
            .let { it as Map<*, *> }
            .size

    @Suppress("UNCHECKED_CAST")
    private fun <T> WindowPixelCopyCoordinator.privateState(name: String): T =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

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

    private class CountingLocationView(context: Context) : View(context) {
        var screenX = 0
        var screenY = 0
        var windowX = 0
        var windowY = 0
        var screenReadCount = 0
        var windowReadCount = 0

        override fun getLocationOnScreen(outLocation: IntArray) {
            screenReadCount++
            outLocation[0] = screenX
            outLocation[1] = screenY
        }

        override fun getLocationInWindow(outLocation: IntArray) {
            windowReadCount++
            outLocation[0] = windowX
            outLocation[1] = windowY
        }
    }

    private class RecordingWindowPixelCopier : WindowPixelCopier {
        val requests = mutableListOf<Request>()

        override fun request(
            window: Window,
            sourceRect: Rect,
            destination: Bitmap,
            onResult: (Int) -> Unit,
        ) {
            requests += Request(
                sourceRect = Rect(sourceRect),
                destination = destination,
                onResult = onResult,
            )
        }

        fun completeAll(result: Int) {
            while (true) {
                val request = requests.firstOrNull { !it.completed } ?: return
                request.completed = true
                request.destination.eraseColor(Color.TRANSPARENT)
                request.onResult(result)
            }
        }

        data class Request(
            val sourceRect: Rect,
            val destination: Bitmap,
            val onResult: (Int) -> Unit,
            var completed: Boolean = false,
        )
    }

    private class PatternWindowPixelCopier : WindowPixelCopier {
        private val requests = mutableListOf<Request>()
        lateinit var parentPattern: IntArray
            private set

        val destinationSizes: List<Pair<Int, Int>>
            get() = requests.map { it.destination.width to it.destination.height }

        override fun request(
            window: Window,
            sourceRect: Rect,
            destination: Bitmap,
            onResult: (Int) -> Unit,
        ) {
            requests += Request(destination, onResult)
        }

        fun completeAll() {
            requests.forEachIndexed { index, request ->
                if (index == 0) {
                    parentPattern = IntArray(request.destination.width * request.destination.height) {
                        pixel ->
                        if ((pixel + pixel / request.destination.width) % 2 == 0) {
                            Color.WHITE
                        } else {
                            Color.BLACK
                        }
                    }
                    request.destination.setPixels(
                        parentPattern,
                        0,
                        request.destination.width,
                        0,
                        0,
                        request.destination.width,
                        request.destination.height,
                    )
                } else {
                    request.destination.eraseColor(Color.TRANSPARENT)
                }
                request.onResult(PixelCopy.SUCCESS)
            }
        }

        private data class Request(
            val destination: Bitmap,
            val onResult: (Int) -> Unit,
        )
    }

    private class AlphaEdgeWindowPixelCopier : WindowPixelCopier {
        private val requests = mutableListOf<Request>()
        val snapshots = mutableListOf<Bitmap>()

        override fun request(
            window: Window,
            sourceRect: Rect,
            destination: Bitmap,
            onResult: (Int) -> Unit,
        ) {
            requests += Request(destination, onResult)
        }

        fun completeAll() {
            requests.forEachIndexed { index, request ->
                val pixels = IntArray(request.destination.width * request.destination.height) {
                    pixel ->
                    val x = pixel % request.destination.width
                    when (index) {
                        0 -> if (x < request.destination.width / 2) Color.RED else Color.TRANSPARENT
                        1 -> if (x >= request.destination.width / 2) Color.BLUE else Color.TRANSPARENT
                        else -> Color.TRANSPARENT
                    }
                }
                request.destination.setPixels(
                    pixels,
                    0,
                    request.destination.width,
                    0,
                    0,
                    request.destination.width,
                    request.destination.height,
                )
                snapshots += request.destination.copy(Bitmap.Config.ARGB_8888, false)
                request.onResult(PixelCopy.SUCCESS)
            }
        }

        private data class Request(
            val destination: Bitmap,
            val onResult: (Int) -> Unit,
        )
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

    private interface BitmapPoolInspectionCounter {
        var inspectionCount: Int
    }

    private class InspectionCountingArrayDeque<E> :
        ArrayDeque<E>(),
        BitmapPoolInspectionCounter {
        override var inspectionCount = 0

        override fun descendingIterator(): MutableIterator<E> {
            val iterator = super.descendingIterator()
            return object : MutableIterator<E> {
                override fun hasNext(): Boolean = iterator.hasNext()

                override fun next(): E {
                    inspectionCount++
                    return iterator.next()
                }

                override fun remove() = iterator.remove()
            }
        }
    }

    private class InspectionCountingBitmapPoolMap :
        LinkedHashMap<Long, ArrayDeque<Bitmap>>(),
        BitmapPoolInspectionCounter {
        override var inspectionCount = 0

        override operator fun get(key: Long): ArrayDeque<Bitmap>? {
            inspectionCount++
            return super.get(key)
        }
    }
}
