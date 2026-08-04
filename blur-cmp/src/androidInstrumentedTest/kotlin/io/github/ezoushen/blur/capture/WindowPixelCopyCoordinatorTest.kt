package io.github.ezoushen.blur.capture

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.PixelCopy
import android.view.Window
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowPixelCopyCoordinatorTest {

    @Test
    fun clearsPixelCopyDestinationBeforeCapture() {
        val window = createWindows(1).single()
        val copier = FakeWindowPixelCopier { _, destination ->
            assertEquals(Color.TRANSPARENT, destination.getPixel(0, 0))
            destination.eraseColor(Color.RED)
        }
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            bitmapFactory = { width, height ->
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.CYAN)
                }
            },
        )

        onMain {
            coordinator.beginEpoch().use { epoch ->
                epoch.requestPrefix(
                    planes = listOf(WindowPixelCopyPlane(window, Rect(0, 0, 8, 8))),
                    width = 8,
                    height = 8,
                ) { _, lease -> lease?.close() }
                copier.completeAll(PixelCopy.SUCCESS)
            }
            coordinator.close()
        }
    }

    @Test
    fun adversarialPrefixesKeepLogicalCaptureGraphLinear() {
        val windows = createWindows(8)
        val copier = FakeWindowPixelCopier()
        val composer = CountingWindowPrefixComposer()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 17L },
            prefixComposer = composer,
        )
        val epochs = mutableListOf<WindowPixelCopyCoordinator.Epoch>()

        onMain {
            listOf(8, 1, 7, 2, 6, 3, 5, 4).forEach { prefixSize ->
                coordinator.beginEpoch().also { epoch ->
                    epochs += epoch
                    epoch.requestPrefix(
                        planes = windows.take(prefixSize).map { window ->
                            WindowPixelCopyPlane(window, Rect(0, 0, 32, 32))
                        },
                        width = 8,
                        height = 8,
                    ) { result, lease ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        lease?.close()
                    }
                }
            }

            assertEquals(8, copier.requests.size)
            assertEquals(
                8,
                coordinator.rawSubscriberCount(),
                "Eight nested prefixes need one raw dependency per unique prefix node",
            )
            copier.completeAll(PixelCopy.SUCCESS)
            assertEquals(15, composer.bitmapDrawCount)

            epochs.forEach { it.close() }
            coordinator.close()
        }
    }

    @Test
    fun matchingStackPrefixesComposeWithLinearBitmapDraws() {
        val windows = createWindows(3)
        val copier = FakeWindowPixelCopier()
        val composer = CountingWindowPrefixComposer()
        val allocatedBitmaps = mutableListOf<Bitmap>()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 7L },
            bitmapFactory = { width, height ->
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    allocatedBitmaps += it
                }
            },
            prefixComposer = composer,
        )
        var callbackCount = 0
        val leases = mutableListOf<WindowPixelCopyLease>()

        onMain {
            val epochs = (1..windows.size).map { prefixSize ->
                val epoch = coordinator.beginEpoch()
                epoch.requestPrefix(
                    planes = windows.take(prefixSize).map { window ->
                        WindowPixelCopyPlane(window, Rect(0, 0, 32, 32))
                    },
                    width = 8,
                    height = 8,
                ) { result, lease ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    assertEquals(8, lease?.bitmap?.width)
                    callbackCount++
                    leases += requireNotNull(lease)
                }
                epoch
            }

            assertEquals(3, copier.requests.size)
            copier.completeAll(PixelCopy.SUCCESS)

            assertEquals(3, callbackCount)
            assertEquals(5, composer.bitmapDrawCount)
            assertEquals(6, allocatedBitmaps.size)
            epochs.forEach { it.close() }
            coordinator.close()
            assertTrue(copier.requests.all { it.destination.isRecycled })
            assertTrue(leases.none { it.bitmap.isRecycled })
            leases.forEach(WindowPixelCopyLease::close)
            assertTrue(allocatedBitmaps.all(Bitmap::isRecycled))
        }
    }

    @Test
    fun sharedPrefixesPreserveExactPlanePixels() {
        val windows = createWindows(3)
        val pixels = IdentityHashMap<Window, IntArray>().apply {
            put(windows[0], intArrayOf(Color.RED, Color.TRANSPARENT))
            put(windows[1], intArrayOf(Color.TRANSPARENT, Color.GREEN))
            put(windows[2], intArrayOf(Color.BLUE, Color.TRANSPARENT))
        }
        val copier = FakeWindowPixelCopier { window, destination ->
            destination.setPixels(
                requireNotNull(pixels[window]),
                0,
                destination.width,
                0,
                0,
                destination.width,
                destination.height,
            )
        }
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 11L },
        )
        val leases = arrayOfNulls<WindowPixelCopyLease>(3)

        onMain {
            val epochs = (1..windows.size).map { prefixSize ->
                val epoch = coordinator.beginEpoch()
                epoch.requestPrefix(
                    planes = windows.take(prefixSize).map { window ->
                        WindowPixelCopyPlane(window, Rect(0, 0, 2, 1))
                    },
                    width = 2,
                    height = 1,
                ) { result, lease ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    leases[prefixSize - 1] = requireNotNull(lease)
                }
                epoch
            }

            copier.completeAll(PixelCopy.SUCCESS)

            assertEquals(Color.RED, leases[0]?.bitmap?.getPixel(0, 0))
            assertEquals(Color.TRANSPARENT, leases[0]?.bitmap?.getPixel(1, 0))
            assertEquals(Color.RED, leases[1]?.bitmap?.getPixel(0, 0))
            assertEquals(Color.GREEN, leases[1]?.bitmap?.getPixel(1, 0))
            assertEquals(Color.BLUE, leases[2]?.bitmap?.getPixel(0, 0))
            assertEquals(Color.GREEN, leases[2]?.bitmap?.getPixel(1, 0))
            leases.forEach { it?.close() }
            epochs.forEach { it.close() }
            coordinator.close()
        }
    }

    @Test
    fun differentOutputSizesStayAtRequestedResolution() {
        val windows = createWindows(3)
        val copier = FakeWindowPixelCopier()
        val composer = CountingWindowPrefixComposer()
        var bitmapAllocations = 0
        var allocatedPixels = 0
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 12L },
            bitmapFactory = { width, height ->
                bitmapAllocations++
                allocatedPixels += width * height
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            },
            prefixComposer = composer,
        )
        val outputSizes = listOf(8, 4, 2)

        onMain {
            val epochs = outputSizes.mapIndexed { index, outputSize ->
                val epoch = coordinator.beginEpoch()
                epoch.requestPrefix(
                    planes = windows.take(index + 1).map { window ->
                        WindowPixelCopyPlane(window, Rect(0, 0, 32, 32))
                    },
                    width = outputSize,
                    height = outputSize,
                ) { result, lease ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    assertEquals(outputSize, lease?.bitmap?.width)
                    lease?.close()
                }
                epoch
            }

            assertEquals(6, copier.requests.size)
            copier.completeAll(PixelCopy.SUCCESS)

            assertEquals(6, composer.bitmapDrawCount)
            assertEquals(9, bitmapAllocations)
            assertEquals(192, allocatedPixels)
            epochs.forEach { it.close() }
            coordinator.close()
        }
    }

    @Test
    fun cancellingOnlyPrefixSubscriberSkipsComposition() {
        val windows = createWindows(3)
        val copier = FakeWindowPixelCopier()
        val composer = CountingWindowPrefixComposer()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 13L },
            prefixComposer = composer,
        )
        var callbackCount = 0

        onMain {
            val epoch = coordinator.beginEpoch()
            val request = epoch.requestPrefix(
                planes = windows.map { window ->
                    WindowPixelCopyPlane(window, Rect(0, 0, 32, 32))
                },
                width = 8,
                height = 8,
            ) { _, lease ->
                callbackCount++
                lease?.close()
            }

            request.cancel()
            assertEquals(3, copier.requests.size)
            copier.completeAll(PixelCopy.SUCCESS)

            assertEquals(0, callbackCount)
            assertEquals(0, composer.bitmapDrawCount)
            epoch.close()
            coordinator.close()
        }
    }

    @Test
    fun shallowerEpochReleasesTransientDeepPool() {
        val windows = createWindows(3)
        val copier = FakeWindowPixelCopier()
        var frameEpoch = 1L
        val allocatedBitmaps = mutableListOf<Bitmap>()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { frameEpoch },
            bitmapFactory = { width, height ->
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    allocatedBitmaps += it
                }
            },
        )

        onMain {
            val deepEpochs = (1..windows.size).map { prefixSize ->
                val epoch = coordinator.beginEpoch()
                epoch.requestPrefix(
                    planes = windows.take(prefixSize).map { window ->
                        WindowPixelCopyPlane(window, Rect(0, 0, 32, 32))
                    },
                    width = 8,
                    height = 8,
                ) { _, lease -> lease?.close() }
                epoch
            }
            copier.completeAll(PixelCopy.SUCCESS)
            deepEpochs.forEach { it.close() }
            assertEquals(6, allocatedBitmaps.count { !it.isRecycled })

            frameEpoch = 2L
            val shallowEpoch = coordinator.beginEpoch()
            shallowEpoch.requestPrefix(
                planes = listOf(WindowPixelCopyPlane(windows[0], Rect(0, 0, 32, 32))),
                width = 8,
                height = 8,
            ) { _, lease -> lease?.close() }
            assertEquals(6, allocatedBitmaps.size)
            copier.completeAll(PixelCopy.SUCCESS)
            shallowEpoch.close()

            assertEquals(2, allocatedBitmaps.count { !it.isRecycled })
            coordinator.close()
            assertTrue(allocatedBitmaps.all(Bitmap::isRecycled))
        }
    }

    @Test
    fun stackedDecorCapturesUseSharedPrefixComposites() {
        val windows = createWindows(3)
        val copier = FakeWindowPixelCopier()
        val composer = CountingWindowPrefixComposer()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 9L },
            prefixComposer = composer,
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sourceViews = windows.map {
            View(context).apply { layout(0, 0, 32, 32) }
        }
        val blurViews = windows.map {
            View(context).apply { layout(0, 0, 32, 32) }
        }
        val captures = windows.indices.map { DecorViewCapture() }
        val outputs = windows.indices.map {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        }

        onMain {
            captures.forEachIndexed { index, capture ->
                capture.setCaptureSources(
                    windows.indices.take(index + 1).map { sourceIndex ->
                        BackdropCaptureSource(
                            view = sourceViews[sourceIndex],
                            window = windows[sourceIndex],
                            pixelCopyCoordinator = coordinator,
                        )
                    },
                )
                assertFalse(
                    capture.capture(
                        blurView = blurViews[index],
                        sourceView = sourceViews[index],
                        output = outputs[index],
                        downsampleFactor = 4f,
                    ),
                )
            }

            assertEquals(3, copier.requests.size)
            copier.completeAll(PixelCopy.SUCCESS)
            assertEquals(3, copier.requests.size)
            assertEquals(5, composer.bitmapDrawCount)

            captures.forEachIndexed { index, capture ->
                assertTrue(
                    capture.capture(
                        blurView = blurViews[index],
                        sourceView = sourceViews[index],
                        output = outputs[index],
                        downsampleFactor = 4f,
                    ),
                )
                capture.release()
                outputs[index].recycle()
            }
            coordinator.close()
        }
    }

    @Test
    fun matchingStackPrefixesShareOnePhysicalCopyPerWindow() {
        val windows = createWindows(3)
        val copier = FakeWindowPixelCopier()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 7L },
        )
        val callbackCount = AtomicReference(0)

        onMain {
            val epochs = listOf(1, 2, 3).map { prefixSize ->
                val epoch = coordinator.beginEpoch()

                fun requestPlane(index: Int) {
                    if (index == prefixSize) return
                    epoch.request(
                        window = windows[index],
                        sourceRect = Rect(0, 0, 32, 32),
                        width = 8,
                        height = 8,
                    ) { result, bitmap ->
                        assertEquals(PixelCopy.SUCCESS, result)
                        assertEquals(8, bitmap?.width)
                        callbackCount.set(callbackCount.get() + 1)
                        requestPlane(index + 1)
                    }
                }

                requestPlane(0)
                epoch
            }

            assertEquals(1, copier.requests.size)
            copier.completeAll(PixelCopy.SUCCESS)
            assertEquals(3, copier.requests.size)
            assertEquals(6, callbackCount.get())
            epochs.forEach { it.close() }
            coordinator.close()
        }
    }

    @Test
    fun newEpochCopiesAgainWithoutAllocatingAnotherBitmap() {
        val windows = createWindows(3)
        val copier = FakeWindowPixelCopier()
        var frameEpoch = 1L
        var bitmapAllocations = 0
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { frameEpoch },
            bitmapFactory = { width, height ->
                bitmapAllocations++
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            },
        )

        onMain {
            coordinator.beginEpoch().use { epoch ->
                windows.forEach { window ->
                    epoch.request(window, Rect(0, 0, 32, 32), 8, 8) { _, _ -> }
                }
                copier.completeAll(PixelCopy.SUCCESS)
            }
            assertEquals(3, bitmapAllocations)
            frameEpoch = 2L
            coordinator.beginEpoch().use { epoch ->
                windows.forEach { window ->
                    epoch.request(window, Rect(0, 0, 32, 32), 8, 8) { _, _ -> }
                }
                assertEquals(6, copier.requests.size)
                assertEquals(3, bitmapAllocations)
                copier.completeAll(PixelCopy.SUCCESS)
            }
            coordinator.close()
        }
    }

    @Test
    fun retainedPrefixLeasesDoNotAllocateInNextEpoch() {
        val windows = createWindows(3)
        val copier = FakeWindowPixelCopier()
        var frameEpoch = 1L
        val allocatedBitmaps = mutableListOf<Bitmap>()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { frameEpoch },
            bitmapFactory = { width, height ->
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    allocatedBitmaps += it
                }
            },
        )
        val frontLeases = arrayOfNulls<WindowPixelCopyLease>(windows.size)

        fun requestStackFrame() {
            windows.indices.forEach { index ->
                lateinit var epoch: WindowPixelCopyCoordinator.Epoch
                epoch = coordinator.beginEpoch()
                epoch.requestPrefix(
                    planes = windows.take(index + 1).map { window ->
                        WindowPixelCopyPlane(window, Rect(0, 0, 32, 32))
                    },
                    width = 8,
                    height = 8,
                ) { result, lease ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    frontLeases[index]?.close()
                    frontLeases[index] = requireNotNull(lease)
                    epoch.close()
                }
            }
            copier.completeAll(PixelCopy.SUCCESS)
        }

        onMain {
            requestStackFrame()
            val warmAllocationCount = allocatedBitmaps.size

            frameEpoch = 2L
            requestStackFrame()

            assertEquals(
                warmAllocationCount,
                allocatedBitmaps.size,
                "A steady stacked frame must reuse raw, prefix, and retained-front buffers",
            )
            coordinator.close()
            frontLeases.forEach { it?.close() }
            assertTrue(allocatedBitmaps.all(Bitmap::isRecycled))
        }
    }

    @Test
    fun retainedPrefixReserveTracksDepthAndOutputSize() {
        val windows = createWindows(3)
        val copier = FakeWindowPixelCopier()
        var frameEpoch = 1L
        val allocatedBitmaps = mutableListOf<Bitmap>()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { frameEpoch },
            bitmapFactory = { width, height ->
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    allocatedBitmaps += it
                }
            },
        )
        val frontLeases = arrayOfNulls<WindowPixelCopyLease>(windows.size)

        onMain {
            windows.indices.forEach { index ->
                lateinit var epoch: WindowPixelCopyCoordinator.Epoch
                epoch = coordinator.beginEpoch()
                epoch.requestPrefix(
                    planes = windows.take(index + 1).map { window ->
                        WindowPixelCopyPlane(window, Rect(0, 0, 32, 32))
                    },
                    width = 8,
                    height = 8,
                ) { result, lease ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    frontLeases[index] = requireNotNull(lease)
                    epoch.close()
                }
            }
            copier.completeAll(PixelCopy.SUCCESS)

            frontLeases[1]?.close()
            frontLeases[1] = null
            frontLeases[2]?.close()
            frontLeases[2] = null
            frameEpoch = 2L

            lateinit var shallowEpoch: WindowPixelCopyCoordinator.Epoch
            shallowEpoch = coordinator.beginEpoch()
            shallowEpoch.requestPrefix(
                planes = listOf(WindowPixelCopyPlane(windows[0], Rect(0, 0, 32, 32))),
                width = 16,
                height = 12,
            ) { result, lease ->
                assertEquals(PixelCopy.SUCCESS, result)
                frontLeases[0]?.close()
                frontLeases[0] = requireNotNull(lease)
                shallowEpoch.close()
            }
            copier.completeAll(PixelCopy.SUCCESS)

            assertTrue(
                allocatedBitmaps.filter { it.width == 8 }.all(Bitmap::isRecycled),
                "Changing depth and output size must recycle the old reserve",
            )
            assertEquals(
                3,
                allocatedBitmaps.count { !it.isRecycled && it.width == 16 && it.height == 12 },
                "One raw, one leased prefix, and one reserve bitmap should remain",
            )

            coordinator.close()
            assertEquals(1, allocatedBitmaps.count { !it.isRecycled })
            frontLeases[0]?.close()
            assertTrue(allocatedBitmaps.all(Bitmap::isRecycled))
        }
    }

    @Test
    fun differentRectOrOutputSizeDoesNotShareCopy() {
        val window = createWindows(1).single()
        val copier = FakeWindowPixelCopier()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 3L },
        )

        onMain {
            coordinator.beginEpoch().use { epoch ->
                epoch.request(window, Rect(0, 0, 32, 32), 8, 8) { _, _ -> }
                epoch.request(window, Rect(1, 0, 33, 32), 8, 8) { _, _ -> }
                epoch.request(window, Rect(0, 0, 32, 32), 9, 8) { _, _ -> }
                assertEquals(3, copier.requests.size)
                copier.completeAll(PixelCopy.SUCCESS)
            }
            coordinator.close()
        }
    }

    @Test
    fun cancellingOneSubscriberDoesNotCancelSharedCopy() {
        val window = createWindows(1).single()
        val copier = FakeWindowPixelCopier()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 5L },
        )
        var cancelledCallbacks = 0
        var liveCallbacks = 0

        onMain {
            coordinator.beginEpoch().use { epoch ->
                val cancelled = epoch.request(
                    window,
                    Rect(0, 0, 32, 32),
                    8,
                    8,
                ) { _, _ -> cancelledCallbacks++ }
                epoch.request(window, Rect(0, 0, 32, 32), 8, 8) { result, _ ->
                    assertEquals(PixelCopy.SUCCESS, result)
                    liveCallbacks++
                }

                cancelled.cancel()
                assertEquals(1, copier.requests.size)
                copier.completeAll(PixelCopy.SUCCESS)
                assertEquals(0, cancelledCallbacks)
                assertEquals(1, liveCallbacks)
                assertFalse(copier.requests.single().destination.isRecycled)
            }
            coordinator.close()
            assertTrue(copier.requests.single().destination.isRecycled)
        }
    }

    @Test
    fun failedCopyReturnsBitmapToPoolForRetry() {
        val window = createWindows(1).single()
        val copier = FakeWindowPixelCopier()
        var bitmapAllocations = 0
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 6L },
            bitmapFactory = { width, height ->
                bitmapAllocations++
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            },
        )

        onMain {
            coordinator.beginEpoch().use { epoch ->
                epoch.request(window, Rect(0, 0, 32, 32), 8, 8) { result, bitmap ->
                    assertEquals(PixelCopy.ERROR_UNKNOWN, result)
                    assertEquals(null, bitmap)
                }
                val firstBitmap = copier.requests.single().destination
                copier.completeAll(PixelCopy.ERROR_UNKNOWN)

                epoch.request(window, Rect(0, 0, 32, 32), 8, 8) { result, _ ->
                    assertEquals(PixelCopy.SUCCESS, result)
                }
                assertEquals(1, bitmapAllocations)
                assertTrue(copier.requests.last().destination === firstBitmap)
                copier.completeAll(PixelCopy.SUCCESS)
            }
            coordinator.close()
        }
    }

    @Test
    fun failureCallbackCanImmediatelyRetryWithoutLosingCallback() {
        val window = createWindows(1).single()
        val copier = FakeWindowPixelCopier()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 6L },
        )
        var retryCallbacks = 0

        onMain {
            coordinator.beginEpoch().use { epoch ->
                epoch.request(window, Rect(0, 0, 32, 32), 8, 8) { result, _ ->
                    assertEquals(PixelCopy.ERROR_UNKNOWN, result)
                    epoch.request(window, Rect(0, 0, 32, 32), 8, 8) { retryResult, _ ->
                        assertEquals(PixelCopy.SUCCESS, retryResult)
                        retryCallbacks++
                    }
                }

                copier.completeNext(PixelCopy.ERROR_UNKNOWN)
                assertEquals(2, copier.requests.size)
                copier.completeNext(PixelCopy.SUCCESS)
                assertEquals(1, retryCallbacks)
            }
            coordinator.close()
        }
    }

    @Test
    fun closingPendingCoordinatorRecyclesOnlyAfterPhysicalCopyCompletes() {
        val window = createWindows(1).single()
        val copier = FakeWindowPixelCopier()
        val coordinator = WindowPixelCopyCoordinator(
            pixelCopier = copier,
            epochProvider = { 8L },
        )
        var callbackCount = 0

        onMain {
            val epoch = coordinator.beginEpoch()
            epoch.request(window, Rect(0, 0, 32, 32), 8, 8) { _, _ -> callbackCount++ }
            val pendingBitmap = copier.requests.single().destination

            coordinator.close()
            assertFalse(pendingBitmap.isRecycled)
            copier.completeAll(PixelCopy.SUCCESS)
            assertTrue(pendingBitmap.isRecycled)
            assertEquals(0, callbackCount)
            epoch.close()
        }
    }

    @Test
    fun contentFreshFrontBitmapWithDifferentOutputSizeIsScaled() {
        val window = createWindows(1).single()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val capture = DecorViewCapture()
        val blurView = View(context).apply { layout(0, 0, 40, 40) }
        val output = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val capturedFront = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        var releaseCount = 0

        onMain {
            capture.setSourceWindow(window)
            capture.setPrivateField(
                "windowFrontLease",
                WindowPixelCopyLease(capturedFront) { releaseCount++ },
            )
            capture.setPrivateField("windowSourceRects", listOf(Rect(0, 0, 40, 40)))
            capture.setPrivateField("windowDeliveryPending", true)
            capture.setPrivateField("windowDeliveryRequestVersion", 0L)
            capture.setPrivateField("windowPending", true)

            assertTrue(capture.capture(blurView, blurView, output, 1f))
            assertEquals(Color.CYAN, output.getPixel(0, 0))
            assertTrue(capture.takeDeliveryNeedsRefresh())
            assertEquals(1, releaseCount)
            capture.release()
            assertEquals(1, releaseCount)
            capturedFront.recycle()
            output.recycle()
        }
    }

    @Test
    fun contentFreshPreparedPrefixWithDifferentOutputSizeIsScaledAndReleased() {
        val window = createWindows(1).single()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val capture = DecorViewCapture()
        val blurView = View(context).apply { layout(0, 0, 40, 40) }
        val output = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val captured = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        val prefix = BackdropCapturePrefix(
            parent = null,
            source = BackdropCaptureSource(blurView, window),
        )
        var releaseCount = 0

        onMain {
            capture.setCapturePrefix(prefix)
            capture.setPrivateField(
                "windowFrontPrefixFrame",
                WindowPrefixFrame(WindowPixelCopyLease(captured) { releaseCount++ }),
            )
            capture.setPrivateField("windowCapturePrefix", prefix)
            capture.setPrivateField("windowPrefixRect", Rect(0, 0, 40, 40))
            capture.setPrivateField("windowPrefixOutputWidth", 8)
            capture.setPrivateField("windowPrefixOutputHeight", 8)
            capture.setPrivateField("windowDeliveryPending", true)
            capture.setPrivateField("windowDeliveryRequestVersion", 0L)

            assertTrue(capture.capture(blurView, blurView, output, 1f))
            assertEquals(Color.CYAN, output.getPixel(0, 0))
            assertTrue(capture.takeDeliveryNeedsRefresh())
            assertEquals(1, releaseCount)
            capture.release()
            assertEquals(1, releaseCount)
            captured.recycle()
            output.recycle()
        }
    }

    @Test
    fun readyCoordinatorLeaseTransfersWithoutCopy() {
        val window = createWindows(1).single()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val capture = DecorViewCapture()
        val blurView = View(context).apply { layout(0, 0, 40, 40) }
        val output = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        val captured = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        var releaseCount = 0

        onMain {
            capture.setSourceWindow(window)
            capture.setPrivateField(
                "windowFrontLease",
                WindowPixelCopyLease(captured) { releaseCount++ },
            )
            capture.setPrivateField("windowSourceRects", listOf(Rect(0, 0, 40, 40)))
            capture.setPrivateField("windowDeliveryPending", true)
            capture.setPrivateField("windowDeliveryRequestVersion", 0L)

            assertTrue(capture.captureForBlur(blurView, blurView, output, 1f))
            val frame = requireNotNull(capture.takeDirectWindowFrame())
            assertTrue(frame.bitmap === captured)
            assertFalse(capture.takeDeliveryNeedsRefresh())
            assertEquals(Color.MAGENTA, output.getPixel(0, 0))
            assertEquals(0, releaseCount)

            frame.close()
            assertEquals(1, releaseCount)
            capture.release()
            output.recycle()
        }
    }

    @Test
    fun readyPreparedPrefixFrameTransfersWithoutCopy() {
        val window = createWindows(1).single()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val capture = DecorViewCapture()
        val blurView = View(context).apply { layout(0, 0, 40, 40) }
        val captured = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val prefix = BackdropCapturePrefix(
            parent = null,
            source = BackdropCaptureSource(blurView, window),
        )
        var releaseCount = 0

        onMain {
            capture.setCapturePrefix(prefix)
            capture.setPrivateField(
                "windowFrontPrefixFrame",
                WindowPrefixFrame(WindowPixelCopyLease(captured) { releaseCount++ }),
            )
            capture.setPrivateField("windowCapturePrefix", prefix)
            capture.setPrivateField("windowPrefixRect", Rect(0, 0, 40, 40))
            capture.setPrivateField("windowPrefixOutputWidth", 16)
            capture.setPrivateField("windowPrefixOutputHeight", 16)
            capture.setPrivateField("windowDeliveryPending", true)
            capture.setPrivateField("windowDeliveryRequestVersion", 0L)

            assertEquals(
                true,
                capture.capturePreparedPrefixForBlur(
                    blurView = blurView,
                    outputWidth = 16,
                    outputHeight = 16,
                ),
            )
            val frame = requireNotNull(capture.takeDirectWindowFrame())
            assertTrue(frame.bitmap === captured)
            assertFalse(capture.takeDeliveryNeedsRefresh())
            assertEquals(0, releaseCount)

            frame.close()
            assertEquals(1, releaseCount)
            capture.release()
        }
    }

    private fun createWindows(count: Int): List<Window> = onMain {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        List(count) {
            requireNotNull(Dialog(context, android.R.style.Theme_Material_Light).window)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val result = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result.set(runCatching(block))
        }
        return result.get().getOrThrow()
    }

    private fun Any.setPrivateField(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun WindowPixelCopyCoordinator.rawSubscriberCount(): Int {
        val entries = javaClass.getDeclaredField("entries").let { field ->
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            field.get(this) as Map<*, *>
        }
        return entries.values.sumOf { entry ->
            requireNotNull(entry).javaClass.getDeclaredField("subscribers").let { field ->
                field.isAccessible = true
                (field.get(entry) as Collection<*>).size
            }
        }
    }

    private class FakeWindowPixelCopier(
        private val fillDestination: (Window, Bitmap) -> Unit = { _, destination ->
            destination.eraseColor(Color.TRANSPARENT)
        },
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
                if (!completeNext(result)) return
            }
        }

        fun completeNext(result: Int): Boolean {
            val request = requests.firstOrNull { !it.completed } ?: return false
            request.completed = true
            fillDestination(request.window, request.destination)
            request.onResult(result)
            return true
        }

        data class Request(
            val window: Window,
            val destination: Bitmap,
            val onResult: (Int) -> Unit,
            var completed: Boolean = false,
        )
    }

    private class CountingWindowPrefixComposer : WindowPrefixComposer {
        var bitmapDrawCount = 0

        override fun compose(
            destination: Bitmap,
            prefix: Bitmap?,
            additions: List<Bitmap>,
        ) {
            bitmapDrawCount += additions.size + if (prefix == null) 0 else 1
            destination.eraseColor(Color.TRANSPARENT)
        }
    }
}
