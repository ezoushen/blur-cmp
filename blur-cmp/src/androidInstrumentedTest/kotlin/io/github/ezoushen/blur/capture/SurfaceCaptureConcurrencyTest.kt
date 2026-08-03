package io.github.ezoushen.blur.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ezoushen.blur.view.BlurView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceCaptureConcurrencyTest {

    @Test
    fun resizeCoalescesLatestCaptureBehindInFlightPixelCopy() {
        onMain {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val copier = FakeSurfacePixelCopier()
            val capture = SurfaceCapture(copier)
            val blurView = BlurView.kawase(context).apply { setIsLive(false) }
            val surfaceTexture = SurfaceTexture(0)
            val surface = Surface(surfaceTexture)
            val holder = FakeSurfaceHolder(surface)
            val surfaceView = object : SurfaceView(context) {
                override fun getHolder(): SurfaceHolder = holder
            }
            val output = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

            try {
                assertFalse(capture.capture(blurView, surfaceView, output, 1f))
                assertEquals(1, copier.requests.size)

                holder.dispatchChanged(width = 16, height = 16)
                blurView.requestSingleUpdate()
                assertFalse(capture.capture(blurView, surfaceView, output, 1f))
                assertEquals(
                    1,
                    copier.requests.size,
                    "A resize must not overlap the stale in-flight PixelCopy",
                )

                copier.completeNext(PixelCopy.SUCCESS)
                assertFalse(capture.capture(blurView, surfaceView, output, 1f))
                assertEquals(
                    2,
                    copier.requests.size,
                    "The newest capture must start after the stale callback",
                )

                copier.completeNext(PixelCopy.SUCCESS)
                assertTrue(capture.capture(blurView, surfaceView, output, 1f))
            } finally {
                capture.release()
                output.recycle()
                surface.release()
                surfaceTexture.release()
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private class FakeSurfacePixelCopier : SurfacePixelCopier {
        val requests = mutableListOf<Request>()

        override fun request(
            surface: Surface,
            destination: Bitmap,
            onResult: (Int) -> Unit,
            handler: Handler,
        ) {
            requests += Request(destination, onResult)
        }

        fun completeNext(result: Int) {
            val request = requests.first { !it.completed }
            request.completed = true
            request.onResult(result)
        }

        private data class Request(
            val destination: Bitmap,
            val onResult: (Int) -> Unit,
            var completed: Boolean = false,
        )
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

        fun dispatchChanged(width: Int, height: Int) {
            callbacks.toList().forEach {
                it.surfaceChanged(this, 0, width, height)
            }
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
