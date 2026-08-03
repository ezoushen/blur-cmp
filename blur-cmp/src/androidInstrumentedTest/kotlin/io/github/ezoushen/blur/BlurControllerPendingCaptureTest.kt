package io.github.ezoushen.blur

import android.app.Dialog
import android.graphics.Bitmap
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

    private fun BlurController.capture(): DecorViewCapture =
        BlurController::class.java.getDeclaredField("capture")
            .apply { isAccessible = true }
            .get(this) as DecorViewCapture

    private fun BlurController.captureBitmap() =
        BlurController::class.java.getDeclaredField("captureBitmap")
            .apply { isAccessible = true }
            .get(this)

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
            .get(this)

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
        release: () -> Unit,
    ) {
        setPrivateField(
            "windowFrontPrefixFrame",
            WindowPrefixFrame(WindowPixelCopyLease(captured, release)),
        )
        setPrivateField("windowCapturePrefix", prefix)
        setPrivateField("windowPrefixRect", Rect(0, 0, 8, 8))
        setPrivateField("windowPrefixOutputWidth", 8)
        setPrivateField("windowPrefixOutputHeight", 8)
        setPrivateField("windowDeliveryPending", true)
        setPrivateField("windowDeliveryRequestVersion", 0L)
    }

    private fun DecorViewCapture.setPrivateField(name: String, value: Any) {
        DecorViewCapture::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(this, value)
    }
}
