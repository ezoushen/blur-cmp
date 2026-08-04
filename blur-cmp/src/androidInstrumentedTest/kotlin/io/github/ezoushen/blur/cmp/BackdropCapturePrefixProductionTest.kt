package io.github.ezoushen.blur.cmp

import android.view.View
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ezoushen.blur.capture.BackdropCapturePrefix
import io.github.ezoushen.blur.capture.BackdropCaptureSource
import io.github.ezoushen.blur.capture.DecorViewCapture
import io.github.ezoushen.blur.view.BlurView
import io.github.ezoushen.blur.view.VariableBlurView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackdropCapturePrefixProductionTest {

    @Test
    fun readinessChangeInvalidatesOnlyTheAggregateWhoseValueChanged() = onMain {
        val layers = List(64) { AndroidBackdropLayer() }
        val readiness = mutableListOf<AndroidBackdropReadiness>()
        var parent: AndroidBackdropReadiness? = null
        layers.forEach { layer ->
            parent = AndroidBackdropReadiness(parent, layer).also(readiness::add)
        }

        val invalidatedAggregates = mutableSetOf<Int>()
        val observer = SnapshotStateObserver { callback -> callback() }
        val onChanged: (Int) -> Unit = invalidatedAggregates::add

        observer.start()
        try {
            readiness.forEachIndexed { index, aggregate ->
                observer.observeReads(index, onChanged) { aggregate.isReady }
            }

            layers.first().contentReady = true
            Snapshot.sendApplyNotifications()

            assertEquals(
                setOf(0),
                invalidatedAggregates,
                "A readiness change must not invalidate aggregates whose value remains false",
            )
        } finally {
            observer.stop()
        }
    }

    @Test
    fun sixtyFourBackdropLayersRetainOneLinkedPrefixInsteadOfSourceLists() = onMain {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var stack: AndroidBackdropStack? = null

        repeat(64) { depth ->
            val root = View(context)
            val prefix = BackdropCapturePrefix(
                parent = stack?.capturePrefix,
                source = BackdropCaptureSource(root, window = null),
            )
            val layer = AndroidBackdropLayer()
            val lowerReadiness = stack?.readiness
            stack = AndroidBackdropStack(
                capturePrefix = prefix,
                pixelCopyCoordinator = null,
                currentRoot = root,
                currentWindow = null,
                depth = depth,
                lowerReadiness = lowerReadiness,
                readiness = AndroidBackdropReadiness(lowerReadiness, layer),
                currentLayer = layer,
            )
        }

        assertEquals(64, requireNotNull(stack).capturePrefix.size)
        assertFalse(
            AndroidBackdropStack::class.java.declaredFields.any { it.name == "captureSources" },
            "Backdrop stacks must not retain a copied List of every lower source",
        )
    }

    @Test
    fun uniformBlurPrefixSwitchReachesCaptureAndRevokesItsReadyFrame() = onMain {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = BackdropCapturePrefix(
            parent = null,
            source = BackdropCaptureSource(View(context), window = null),
        )
        val second = BackdropCapturePrefix(
            parent = first,
            source = BackdropCaptureSource(View(context), window = null),
        )
        val blurView = BlurView.kawase(context)
        var frameLosses = 0
        blurView.setOnFrameLostListener { frameLosses++ }

        blurView.setBlurredPrefix(first)
        blurView.markFirstFrameReady()
        frameLosses = 0
        blurView.setBlurredPrefix(second)

        assertFalse(blurView.hasFirstFrame())
        assertEquals(1, frameLosses)
        assertSame(second, blurView.decorCapture().capturePrefix())
        assertNull(blurView.decorCapture().captureSources())
    }

    @Test
    fun variableBlurPrefixSwitchReachesCaptureAndRevokesItsReadyFrame() = onMain {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = BackdropCapturePrefix(
            parent = null,
            source = BackdropCaptureSource(View(context), window = null),
        )
        val second = BackdropCapturePrefix(
            parent = first,
            source = BackdropCaptureSource(View(context), window = null),
        )
        val blurView = VariableBlurView(context)
        var frameLosses = 0
        blurView.setOnFrameLostListener { frameLosses++ }

        blurView.setBlurredPrefix(first)
        blurView.markFirstFrameReady()
        frameLosses = 0
        blurView.setBlurredPrefix(second)

        assertFalse(blurView.hasFirstFrame())
        assertEquals(1, frameLosses)
        assertSame(second, blurView.decorCapture().capturePrefix())
        assertNull(blurView.decorCapture().captureSources())
    }

    private fun BlurView.markFirstFrameReady() {
        BlurView::class.java.getDeclaredMethod("onFirstFrameAvailable")
            .apply { isAccessible = true }
            .invoke(this)
        assertTrue(hasFirstFrame())
    }

    private fun VariableBlurView.markFirstFrameReady() {
        VariableBlurView::class.java.getDeclaredMethod("onFirstFrameAvailable")
            .apply { isAccessible = true }
            .invoke(this)
        assertTrue(hasFirstFrame())
    }

    private fun BlurView.decorCapture(): DecorViewCapture = controllerCapture(
        BlurView::class.java.getDeclaredField("blurController")
            .apply { isAccessible = true }
            .get(this),
    )

    private fun VariableBlurView.decorCapture(): DecorViewCapture = controllerCapture(
        VariableBlurView::class.java.getDeclaredField("blurController")
            .apply { isAccessible = true }
            .get(this),
    )

    private fun controllerCapture(controller: Any?): DecorViewCapture {
        val value = requireNotNull(controller)
        return value.javaClass.getDeclaredField("capture")
            .apply { isAccessible = true }
            .get(value) as DecorViewCapture
    }

    private fun DecorViewCapture.capturePrefix(): BackdropCapturePrefix? =
        DecorViewCapture::class.java.getDeclaredField("capturePrefix")
            .apply { isAccessible = true }
            .get(this) as? BackdropCapturePrefix

    private fun DecorViewCapture.captureSources(): List<*>? =
        DecorViewCapture::class.java.getDeclaredField("captureSources")
            .apply { isAccessible = true }
            .get(this) as? List<*>

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
