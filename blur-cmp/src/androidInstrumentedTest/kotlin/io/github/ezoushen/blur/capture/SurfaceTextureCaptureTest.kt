package io.github.ezoushen.blur.capture

import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ezoushen.blur.algorithm.OpenGLBlur
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class SurfaceTextureCaptureTest {
    @Test
    fun animatedSizeChangeKeepsQueuedFramePending() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val capture = SurfaceTextureCapture()
        val algorithm = OpenGLBlur()
        val blurView = View(context).apply { layout(0, 0, 32, 24) }
        val sourceView = View(context).apply { layout(0, 0, 32, 24) }

        try {
            instrumentation.runOnMainSync {
                assertTrue(algorithm.prepare(context, width = 32, height = 24, radius = 12f))
                val textureId = algorithm.getExternalInputTextureId()
                assertTrue(textureId != 0)
                capture.init(textureId, width = 32, height = 24)
                assertFalse(capture.capture(blurView, sourceView, width = 32, height = 24))
                assertTrue(capture.isFirstFramePending())

                capture.init(textureId, width = 8, height = 6)
                assertTrue(capture.isFirstFramePending())
            }
        } finally {
            instrumentation.runOnMainSync {
                capture.release()
                algorithm.release()
            }
        }
    }
}
