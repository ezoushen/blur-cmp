package io.github.ezoushen.blur

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ezoushen.blur.capture.BackdropCapturePrefix
import io.github.ezoushen.blur.capture.BackdropCaptureSource
import io.github.ezoushen.blur.capture.DecorViewCapture
import io.github.ezoushen.blur.capture.WindowCapturedBitmap
import io.github.ezoushen.blur.capture.WindowPixelCopyLease
import io.github.ezoushen.blur.capture.WindowPrefixFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlurControllerPendingCaptureTest {

    @Test
    fun pendingWindowCopySkipsCapturePreparation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val controller = BlurController(
                context,
                BlurConfig(pipelineStrategy = BlurPipelineStrategy.LEGACY),
            )
            val source = FrameLayout(context).apply { layout(0, 0, 40, 40) }
            val blurView = View(context).apply { layout(0, 0, 40, 40) }
            val window = requireNotNull(Dialog(context, android.R.style.Theme_Material_Light).window)

            try {
                controller.init(blurView, source)
                controller.setSourceWindow(window)
                controller.capture().setPendingWindowCopy()

                controller.update()

                assertNull(
                    controller.captureBitmap(),
                    "A pending async capture must not allocate or prepare another capture frame",
                )
            } finally {
                controller.release()
            }
        }
    }

    @Test
    fun variableBlurPendingWindowCopySkipsCapturePreparation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val controller = VariableBlurController(
                context,
                BlurConfig(pipelineStrategy = BlurPipelineStrategy.LEGACY),
            )
            val source = FrameLayout(context).apply { layout(0, 0, 40, 40) }
            val blurView = View(context).apply { layout(0, 0, 40, 40) }
            val window = requireNotNull(Dialog(context, android.R.style.Theme_Material_Light).window)

            try {
                controller.init(blurView, source)
                controller.setGradient(BlurGradient.verticalGradient(0f, 16f))
                controller.setSourceWindow(window)
                controller.capture().setPendingWindowCopy()

                controller.update()

                assertNull(
                    controller.captureBitmap(),
                    "A pending variable-blur capture must not prepare another capture frame",
                )
            } finally {
                controller.release()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun readyPrefixSkipsFallbackCaptureBitmap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val controller = BlurController(
                context,
                BlurConfig(
                    radius = 1f,
                    downsampleFactor = 1f,
                    pipelineStrategy = BlurPipelineStrategy.LEGACY,
                ),
            )
            val source = FrameLayout(context).apply { layout(0, 0, 8, 8) }
            val blurView = View(context).apply { layout(0, 0, 8, 8) }
            val window = requireNotNull(Dialog(context, android.R.style.Theme_Material_Light).window)
            val prefix = BackdropCapturePrefix(null, BackdropCaptureSource(source, window))
            val captured = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            var releaseCount = 0

            try {
                controller.init(blurView, source)
                controller.setCapturePrefix(prefix)
                controller.capture().setReadyPrefix(prefix, captured) { releaseCount++ }

                assertTrue(controller.update())
                assertNull(
                    controller.captureBitmap(),
                    "A ready transferable prefix must not allocate a fallback capture bitmap",
                )
                assertSame(captured, controller.directCaptureFrame().bitmap)
                assertEquals(0, releaseCount)
            } finally {
                controller.release()
            }

            assertEquals(1, releaseCount)
            captured.recycle()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun readyVariablePrefixSkipsFallbackCaptureBitmap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val controller = VariableBlurController(
                context,
                BlurConfig(
                    radius = 1f,
                    downsampleFactor = 1f,
                    pipelineStrategy = BlurPipelineStrategy.LEGACY,
                ),
            )
            val source = FrameLayout(context).apply { layout(0, 0, 8, 8) }
            val blurView = View(context).apply { layout(0, 0, 8, 8) }
            val window = requireNotNull(Dialog(context, android.R.style.Theme_Material_Light).window)
            val prefix = BackdropCapturePrefix(null, BackdropCaptureSource(source, window))
            val captured = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            var releaseCount = 0

            try {
                controller.init(blurView, source)
                controller.setGradient(BlurGradient.verticalGradient(0f, 1f))
                controller.setCapturePrefix(prefix)
                controller.capture().setReadyPrefix(prefix, captured) { releaseCount++ }

                assertTrue(controller.update())
                assertNull(
                    controller.captureBitmap(),
                    "A ready transferable variable prefix must not allocate a fallback capture bitmap",
                )
                assertSame(captured, controller.directCaptureFrame().bitmap)
                assertEquals(0, releaseCount)
            } finally {
                controller.release()
            }

            assertEquals(1, releaseCount)
            captured.recycle()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun earlierRadiusPrefixCompletesBlurTransition() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val controller = BlurController(
                context,
                BlurConfig(
                    radius = 16f,
                    downsampleFactor = 4f,
                    pipelineStrategy = BlurPipelineStrategy.LEGACY,
                ),
            )
            val source = FrameLayout(context).apply { layout(0, 0, 32, 24) }
            val blurView = View(context).apply { layout(0, 0, 32, 24) }
            val window = requireNotNull(Dialog(context, android.R.style.Theme_Material_Light).window)
            val prefix = BackdropCapturePrefix(null, BackdropCaptureSource(source, window))
            val captured = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.MAGENTA)
            }
            val exactCapture = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
            var releaseCount = 0

            try {
                controller.init(blurView, source)
                controller.setCapturePrefix(prefix)
                controller.capture().setReadyPrefix(
                    prefix,
                    captured,
                    Rect(0, 0, 32, 24),
                ) { releaseCount++ }

                assertTrue(
                    controller.update(),
                    "A content-fresh frame from an earlier radius must complete the transition",
                )
                val captureBitmap = requireNotNull(controller.captureBitmap())
                assertEquals(8, captureBitmap.width)
                assertEquals(6, captureBitmap.height)
                assertEquals(Color.MAGENTA, captureBitmap.getPixel(4, 3))
                assertTrue(
                    controller.hasPendingDirty(),
                    "A scaled transition frame must request one exact-size refresh",
                )
                assertEquals(1, releaseCount)

                controller.capture().setReadyPrefix(
                    prefix,
                    exactCapture,
                    Rect(0, 0, 32, 24),
                ) { releaseCount++ }
                assertTrue(controller.update())
                assertFalse(controller.hasPendingDirty())
                assertFalse(controller.update(), "An exact delivery must settle after one refresh")
                assertEquals(1, releaseCount)
            } finally {
                controller.release()
                captured.recycle()
                exactCapture.recycle()
            }
            assertEquals(2, releaseCount)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun earlierRadiusPrefixCompletesVariableBlurTransition() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val controller = VariableBlurController(
                context,
                BlurConfig(
                    downsampleFactor = 4f,
                    pipelineStrategy = BlurPipelineStrategy.LEGACY,
                ),
            )
            val source = FrameLayout(context).apply { layout(0, 0, 32, 24) }
            val blurView = View(context).apply { layout(0, 0, 32, 24) }
            val window = requireNotNull(Dialog(context, android.R.style.Theme_Material_Light).window)
            val prefix = BackdropCapturePrefix(null, BackdropCaptureSource(source, window))
            val captured = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.MAGENTA)
            }
            val exactCapture = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
            var releaseCount = 0

            try {
                controller.init(blurView, source)
                controller.setGradient(BlurGradient.verticalGradient(0f, 16f))
                controller.setCapturePrefix(prefix)
                controller.capture().setReadyPrefix(
                    prefix,
                    captured,
                    Rect(0, 0, 32, 24),
                ) { releaseCount++ }

                assertTrue(
                    controller.update(),
                    "A content-fresh frame from an earlier radius must complete the variable transition",
                )
                val captureBitmap = requireNotNull(controller.captureBitmap())
                assertEquals(8, captureBitmap.width)
                assertEquals(6, captureBitmap.height)
                assertEquals(Color.MAGENTA, captureBitmap.getPixel(4, 3))
                assertTrue(
                    controller.hasPendingDirty(),
                    "A scaled variable transition frame must request one exact-size refresh",
                )
                assertEquals(1, releaseCount)

                controller.capture().setReadyPrefix(
                    prefix,
                    exactCapture,
                    Rect(0, 0, 32, 24),
                ) { releaseCount++ }
                assertTrue(controller.update())
                assertFalse(controller.hasPendingDirty())
                assertFalse(controller.update(), "An exact delivery must settle after one refresh")
                assertEquals(1, releaseCount)
            } finally {
                controller.release()
                captured.recycle()
                exactCapture.recycle()
            }
            assertEquals(2, releaseCount)
        }
    }

    private fun BlurController.capture(): DecorViewCapture =
        BlurController::class.java.getDeclaredField("capture")
            .apply { isAccessible = true }
            .get(this) as DecorViewCapture

    private fun BlurController.captureBitmap() =
        BlurController::class.java.getDeclaredField("captureBitmap")
            .apply { isAccessible = true }
            .get(this) as Bitmap?

    private fun BlurController.directCaptureFrame() =
        BlurController::class.java.getDeclaredField("directCaptureFrame")
            .apply { isAccessible = true }
            .get(this) as WindowCapturedBitmap

    private fun VariableBlurController.capture(): DecorViewCapture =
        VariableBlurController::class.java.getDeclaredField("capture")
            .apply { isAccessible = true }
            .get(this) as DecorViewCapture

    private fun VariableBlurController.captureBitmap() =
        VariableBlurController::class.java.getDeclaredField("captureBitmap")
            .apply { isAccessible = true }
            .get(this) as Bitmap?

    private fun VariableBlurController.directCaptureFrame() =
        VariableBlurController::class.java.getDeclaredField("directCaptureFrame")
            .apply { isAccessible = true }
            .get(this) as WindowCapturedBitmap

    private fun DecorViewCapture.setPendingWindowCopy() {
        DecorViewCapture::class.java.getDeclaredField("windowPending")
            .apply { isAccessible = true }
            .setBoolean(this, true)
    }

    private fun DecorViewCapture.setReadyPrefix(
        prefix: BackdropCapturePrefix,
        captured: Bitmap,
        screenRect: Rect = Rect(0, 0, captured.width, captured.height),
        release: () -> Unit,
    ) {
        setPrivateField(
            "windowFrontPrefixFrame",
            WindowPrefixFrame(WindowPixelCopyLease(captured, release)),
        )
        setPrivateField("windowCapturePrefix", prefix)
        setPrivateField("windowPrefixRect", screenRect)
        setPrivateField("windowPrefixOutputWidth", captured.width)
        setPrivateField("windowPrefixOutputHeight", captured.height)
        setPrivateField("windowDeliveryPending", true)
        setPrivateField("windowDeliveryRequestVersion", 0L)
    }

    private fun DecorViewCapture.setPrivateField(name: String, value: Any) {
        DecorViewCapture::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(this, value)
    }
}
