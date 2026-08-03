package io.github.ezoushen.blur.demo

import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ezoushen.blur.capture.SurfaceCapture
import io.github.ezoushen.blur.view.BlurView
import io.github.ezoushen.blur.view.VariableBlurView
import org.junit.Test
import org.junit.runner.RunWith
import java.util.IdentityHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class AsyncCaptureFreshnessTest {
    @Test
    fun manualUpdateSupersedesPendingAsyncCapture() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val blurView = BlurView.kawase(context)
        val variableBlurView = VariableBlurView(context)

        val blurVersion = captureRequestVersion(blurView)
        blurView.updateBlur()
        assertEquals(blurVersion + 1, captureRequestVersion(blurView))

        val variableVersion = captureRequestVersion(variableBlurView)
        variableBlurView.updateBlur()
        assertEquals(variableVersion + 1, captureRequestVersion(variableBlurView))
    }

    @Test
    fun invalidSurfaceDoesNotSatisfyNewerRequestWithCachedFrame() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val capture = SurfaceCapture()
        val blurView = BlurView.kawase(context).apply { setIsLive(false) }
        val surfaceView = SurfaceView(context)
        val output = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

        assertFalse(capture.capture(blurView, surfaceView, output, 1f))
        val frame = surfaceFrames(capture)[surfaceView]
            ?: error("Surface capture did not retain its frame state")
        frame.javaClass.getDeclaredField("front").run {
            isAccessible = true
            set(frame, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        }
        frame.javaClass.getDeclaredField("deliveredRequestVersion").run {
            isAccessible = true
            setLong(frame, captureRequestVersion(blurView))
        }

        blurView.requestSingleUpdate()
        assertFalse(
            capture.capture(blurView, surfaceView, output, 1f),
            "An invalid Surface must not let an older cached frame satisfy a newer request",
        )

        capture.release()
        output.recycle()
    }

    private fun captureRequestVersion(view: Any): Long =
        view.javaClass.getDeclaredField("captureRequestVersion").run {
            isAccessible = true
            getLong(view)
        }

    @Suppress("UNCHECKED_CAST")
    private fun surfaceFrames(capture: SurfaceCapture): IdentityHashMap<SurfaceView, Any> =
        SurfaceCapture::class.java.getDeclaredField("surfaceFrames").run {
            isAccessible = true
            get(capture) as IdentityHashMap<SurfaceView, Any>
        }
}
