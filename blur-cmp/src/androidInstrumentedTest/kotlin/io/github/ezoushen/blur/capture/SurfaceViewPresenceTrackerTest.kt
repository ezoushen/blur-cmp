package io.github.ezoushen.blur.capture

import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceViewPresenceTrackerTest {

    @Test
    fun cachesSurfaceHierarchyUntilGlobalLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val root = FrameLayout(context)
            val surface = SurfaceView(context)
            root.addView(surface)
            val tracker = SurfaceViewPresenceTracker()

            try {
                tracker.setSource(root)
                val first = tracker.surfaceViews()
                val cached = tracker.surfaceViews()

                assertSame(first, cached)
                assertEquals(listOf(surface), first)
                assertEquals(true, tracker.containsSurfaceView())
                assertSame(first, tracker.surfaceViews())

                val texture = TextureView(context)
                root.addView(texture)
                root.viewTreeObserver.dispatchOnGlobalLayout()
                val refreshed = tracker.surfaceViews()

                assertNotSame(first, refreshed)
                assertEquals(listOf(surface, texture), refreshed)

                root.removeView(surface)
                root.viewTreeObserver.dispatchOnGlobalLayout()
                assertEquals(listOf(texture), tracker.surfaceViews())
                assertEquals(false, tracker.containsSurfaceView())
            } finally {
                tracker.release()
            }

            assertEquals(emptyList(), tracker.surfaceViews())
        }
    }
}
