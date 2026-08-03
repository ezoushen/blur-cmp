package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

@OptIn(
    ExperimentalForeignApi::class,
    BetaInteropApi::class,
    ExperimentalComposeUiApi::class,
)
class IosBlurStateTest {
    @Test
    fun readinessTracksBackdropLifecycle() {
        val state = IosBlurState()

        assertFalse(state.hasBackdrop)
        state.setupAsBackdrop(UIView(), BlurOverlayConfig.Default)
        assertTrue(state.hasBackdrop)

        state.cleanupBackdrop()
        assertFalse(state.hasBackdrop)
    }

    @Test
    fun replacingStateTransfersReadinessWithoutRecreatingBackdrop() {
        val rootViewController = UIViewController()
        val window = UIWindow(frame = CGRectMake(0.0, 0.0, 1.0, 1.0)).apply {
            this.rootViewController = rootViewController
        }
        val firstState = BlurOverlayState(BlurOverlayConfig.Default)
        val replacementState = BlurOverlayState(BlurOverlayConfig.Default)
        val currentState = mutableStateOf(firstState)
        val scene = ImageComposeScene(width = 1, height = 1) {
            CompositionLocalProvider(
                LocalBlurOverlayPlatformContext provides
                    BlurOverlayPlatformContext(contentWindow = window),
            ) {
                BlurOverlay(state = currentState.value) {}
            }
        }

        try {
            scene.render()
            assertTrue(firstState.isReady)
            val originalBackdrop = rootViewController.view.subviews.single()

            currentState.value = replacementState
            scene.render(1L)

            assertTrue(originalBackdrop == rootViewController.view.subviews.single())
            assertFalse(firstState.isReady)
            assertTrue(replacementState.isReady)
        } finally {
            scene.close()
        }

        assertFalse(replacementState.isReady)
        assertTrue(rootViewController.view.subviews.isEmpty())
    }
}
