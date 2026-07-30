package io.github.ezoushen.blur.demo

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ezoushen.blur.capture.DecorViewCapture
import io.github.ezoushen.blur.capture.SurfaceCapture
import io.github.ezoushen.blur.cmp.BackdropBlurDialog
import io.github.ezoushen.blur.cmp.BlurOverlay
import io.github.ezoushen.blur.cmp.BlurOverlayConfig
import io.github.ezoushen.blur.cmp.BlurOverlayState
import io.github.ezoushen.blur.cmp.BlurGradientType
import io.github.ezoushen.blur.cmp.rememberBlurOverlayState
import io.github.ezoushen.blur.cmp.withTint
import io.github.ezoushen.blur.view.BlurView
import io.github.ezoushen.blur.view.VariableBlurView
import io.github.ezoushen.blur.view.registerBlurCaptureCompositionOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class StackedBlurOverlayTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun registeredCompositionOrderDistinguishesMediaOverlayBeforeApi36() {
        if (android.os.Build.VERSION.SDK_INT >= 36) return

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val regular = SurfaceView(context).apply {
            setZOrderMediaOverlay(false)
            registerBlurCaptureCompositionOrder(-2)
        }
        val overlay = SurfaceView(context).apply {
            setZOrderMediaOverlay(true)
            registerBlurCaptureCompositionOrder(-1)
        }
        val capture = DecorViewCapture()
        val compositionOrder = DecorViewCapture::class.java.getDeclaredMethod(
            "compositionOrder",
            SurfaceView::class.java,
        ).apply {
            isAccessible = true
        }

        val regularOrder = compositionOrder.invoke(capture, regular) as Int
        val overlayOrder = compositionOrder.invoke(capture, overlay) as Int
        capture.release()

        assertTrue(
            overlayOrder > regularOrder,
            "Media-overlay sublayer must sort above the regular media sublayer; " +
                "regular=$regularOrder overlay=$overlayOrder",
        )
    }

    @Test
    fun platformCompositionOrderOverridesRegistrationFromApi36() {
        if (android.os.Build.VERSION.SDK_INT < 36) return

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val surface = SurfaceView(context).apply {
            registerBlurCaptureCompositionOrder(-2)
        }
        surface.javaClass
            .getMethod("setCompositionOrder", Int::class.javaPrimitiveType)
            .invoke(surface, -1)
        val capture = DecorViewCapture()
        val compositionOrder = DecorViewCapture::class.java.getDeclaredMethod(
            "compositionOrder",
            SurfaceView::class.java,
        ).apply {
            isAccessible = true
        }

        val actualOrder = compositionOrder.invoke(capture, surface) as Int
        capture.release()

        assertTrue(
            actualOrder == -1,
            "Android 36's current platform composition order must override legacy registration; " +
                "was $actualOrder",
        )
    }

    @Test
    fun blurViewHidesRecreatedTextureUntilItsFirstFrame() {
        val view = BlurView.kawase(ApplicationProvider.getApplicationContext())
        assertTextureGateResets(
            textureView = view.getChildAt(0) as TextureView,
            hasFirstFrame = view::hasFirstFrame,
            setOnFrameLostListener = view::setOnFrameLostListener,
        )
    }

    @Test
    fun variableBlurViewHidesRecreatedTextureUntilItsFirstFrame() {
        val view = VariableBlurView(ApplicationProvider.getApplicationContext())
        assertTextureGateResets(
            textureView = view.getChildAt(0) as TextureView,
            hasFirstFrame = view::hasFirstFrame,
            setOnFrameLostListener = view::setOnFrameLostListener,
        )
    }

    @Test
    fun siblingOverlaysShareOneBackdropStack() {
        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        EdgeMarker(
                            Modifier
                                .offset(x = 32.dp, y = 120.dp)
                                .size(160.dp),
                        )
                    }
                    BlurOverlay(
                        state = overlayB,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {}
                }
            }

            val screenshot = awaitScreenshot {
                isSoftenedEdge(it, yDp = 200)
            }
            assertSoftenedEdge(
                screenshot,
                yDp = 200,
                context = "Sibling Overlay A marker captured by Overlay B",
            )
        }
    }

    @Test
    fun plainDialogLayerDoesNotBlockBlurAboveIt() {
        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    BackdropBlurDialog(onDismissRequest = {}) {
                        EdgeMarker(
                            Modifier
                                .offset(x = 32.dp, y = 120.dp)
                                .size(160.dp),
                        )
                    }
                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {}
                }
            }

            assertSoftenedEdge(
                awaitScreenshot { isSoftenedEdge(it, yDp = 200) },
                yDp = 200,
                context = "plain dialog marker captured by blur above it",
            )
        }
    }

    @Test
    fun staticLowerLayerDoesNotRecaptureWhenSiblingMountsOrDismisses() {
        val background = mutableStateOf(Color.Red)
        val showUpper = mutableStateOf(false)

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(background.value)) {
                        EdgeMarker(
                            Modifier
                                .offset(x = 32.dp, y = 120.dp)
                                .size(160.dp),
                        )
                    }

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {}
                    if (showUpper.value) {
                        BlurOverlay(
                            state = overlayB,
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {}
                    }
                }
            }

            assertSoftenedEdge(
                awaitScreenshot { isSoftenedEdge(it, yDp = 200) },
                yDp = 200,
                context = "initial static lower layer",
            )
            scenario.onActivity {
                background.value = Color.Blue
                showUpper.value = true
            }
            composeRule.waitForIdle()
            Thread.sleep(300)
            val afterUpperMount =
                sample(takeScreenshot(), xFraction = 0.75f, yFraction = 0.75f)

            scenario.onActivity {
                showUpper.value = false
            }
            composeRule.waitForIdle()
            Thread.sleep(300)
            val afterUpperDismiss =
                sample(takeScreenshot(), xFraction = 0.75f, yFraction = 0.75f)
            assertRed(
                afterUpperMount,
                "static lower layer after upper mount; after dismiss was " +
                    "#${afterUpperDismiss.toUInt().toString(16)}",
            )
            assertRed(afterUpperDismiss, "static lower layer after upper dismiss")
        }
    }

    @Test
    fun nestedOverlayUsesWindowBoundsIndependentlyOfLowerOverlay() {
        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false)
                            .withTint(Color.Red.copy(alpha = 0.75f)),
                    )

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.size(160.dp),
                        onDismissRequest = {},
                    ) {
                        BlurOverlay(
                            state = overlayB,
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {}
                    }
                }
            }

            val outsideLowerOverlay = awaitPixel(xFraction = 0.8f, yFraction = 0.8f) {
                AndroidColor.red(it) > 80
            }
            assertTrue(
                android.graphics.Color.red(outsideLowerOverlay) > 80,
                "Nested overlay must cover the window beyond its lower overlay; " +
                    "sampled #${outsideLowerOverlay.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun thirdOverlayCapturesBothLowerOverlays() {
        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false)
                            .withTint(Color.Red.copy(alpha = 0.5f)),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false)
                            .withTint(Color.Green.copy(alpha = 0.5f)),
                    )
                    val overlayC = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            EdgeMarker(
                                Modifier
                                    .offset(x = 32.dp, y = 120.dp)
                                    .size(160.dp),
                            )

                            BlurOverlay(
                                state = overlayB,
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {
                                Box(Modifier.fillMaxSize()) {
                                    EdgeMarker(
                                        Modifier
                                            .offset(x = 32.dp, y = 360.dp)
                                            .size(160.dp),
                                    )

                                    BlurOverlay(
                                        state = overlayC,
                                        modifier = Modifier.fillMaxSize(),
                                        onDismissRequest = {},
                                    ) {}
                                }
                            }
                        }
                    }
                }
            }

            val screenshot = awaitScreenshot {
                val pixel = sample(it, xFraction = 0.8f, yFraction = 0.85f)
                AndroidColor.red(pixel) > 20 &&
                    AndroidColor.green(pixel) > 20 &&
                    isSoftenedEdge(it, yDp = 200) &&
                    isSoftenedEdge(it, yDp = 440)
            }
            assertSoftenedEdge(screenshot, yDp = 200, context = "Overlay A marker")
            assertSoftenedEdge(screenshot, yDp = 440, context = "Overlay B marker")
            val composedBackdrop = sample(screenshot, xFraction = 0.8f, yFraction = 0.85f)
            assertTrue(
                AndroidColor.red(composedBackdrop) in 45..85 &&
                    AndroidColor.green(composedBackdrop) in 105..150 &&
                    AndroidColor.blue(composedBackdrop) in 45..85,
                "Each lower tint must be composited exactly once; " +
                    "sampled #${composedBackdrop.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun transparentLowerOutputCompositesItsTintOnce() {
        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.size(1.dp),
                        onDismissRequest = {},
                    ) {
                        BlurOverlay(
                            state = rememberBlurOverlayState(
                                BlurOverlayConfig(radius = 12f, isLive = false)
                                    .withTint(Color.Green.copy(alpha = 0.5f)),
                            ),
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {
                            BlurOverlay(
                                state = rememberBlurOverlayState(
                                    BlurOverlayConfig(radius = 12f, isLive = false),
                                ),
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {}
                        }
                    }
                }
            }

            awaitPixel(xFraction = 0.8f, yFraction = 0.8f) {
                AndroidColor.green(it) > 90
            }
            val pixels = List(8) {
                Thread.sleep(100)
                sample(takeScreenshot(), xFraction = 0.8f, yFraction = 0.8f)
            }
            assertTrue(
                pixels.all { AndroidColor.green(it) in 105..150 },
                "A translucent lower tint must be composited exactly once; " +
                    "sampled ${pixels.joinToString { "#${it.toUInt().toString(16)}" }}",
            )
        }
    }

    @Test
    fun layersCanGoLiveIndependently() {
        val lowerColor = mutableStateOf(Color.Red)
        val greenContentComposed = AtomicBoolean()
        val activityRef = AtomicReference<MainActivity>()
        val middleState = AtomicReference<BlurOverlayState>()
        val upperState = AtomicReference<BlurOverlayState>()

        launchEmptyActivity().let { scenario ->
            try {
                scenario.onActivity { activity ->
                    activityRef.set(activity)
                    activity.setContent {
                        Box(Modifier.fillMaxSize().background(Color.Blue))

                        val overlayA = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        )
                        val overlayB = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ).also(middleState::set)
                        val overlayC = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ).also(upperState::set)

                        BlurOverlay(
                            state = overlayA,
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(lowerColor.value),
                            ) {
                                BlurOverlay(
                                    state = overlayB,
                                    modifier = Modifier.fillMaxSize(),
                                    onDismissRequest = {},
                                ) {
                                    EdgeMarker(
                                        Modifier
                                            .offset(x = 32.dp, y = 32.dp)
                                            .size(160.dp),
                                    )
                                    BlurOverlay(
                                        state = overlayC,
                                        modifier = Modifier.size(160.dp),
                                        onDismissRequest = {},
                                    ) {}
                                }
                                SideEffect {
                                    greenContentComposed.set(lowerColor.value == Color.Green)
                                }
                            }
                        }
                    }
                }

                val initialScreenshot = awaitScreenshot {
                    val pixel = sample(it, xFraction = 0.05f, yFraction = 0.05f)
                    AndroidColor.red(pixel) > AndroidColor.green(pixel) &&
                        isSoftenedEdge(it, yDp = 112)
                }
                val initial = sample(initialScreenshot, xFraction = 0.05f, yFraction = 0.05f)
                assertRed(initial, "initial stacked output")

                scenario.onActivity {
                    val middle = middleState.get()
                    middle.config = middle.config
                        .copy(isLive = true)
                        .withTint(Color.Green.copy(alpha = 0.75f))
                    lowerColor.value = Color.Green
                }
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    greenContentComposed.get()
                }
                val middleUpdatedScreenshot = awaitScreenshot {
                    val pixel = sample(it, xFraction = 0.8f, yFraction = 0.8f)
                    AndroidColor.green(pixel) > AndroidColor.red(pixel)
                }
                val liveMiddle = sample(middleUpdatedScreenshot, xFraction = 0.8f, yFraction = 0.8f)
                assertTrue(
                    android.graphics.Color.green(liveMiddle) > android.graphics.Color.red(liveMiddle),
                    "Live B must expose A's green content; sampled #${liveMiddle.toUInt().toString(16)}",
                )
                val frozenUpper = sample(middleUpdatedScreenshot, xFraction = 0.05f, yFraction = 0.05f)
                assertRed(frozenUpper, "non-live C must stay frozen while live B updates")

                scenario.onActivity {
                    val upper = upperState.get()
                    upper.config = upper.config.copy(isLive = true)
                }
                val refreshed = awaitPixel(xFraction = 0.05f, yFraction = 0.05f) {
                    AndroidColor.green(it) > AndroidColor.red(it)
                }
                assertTrue(
                    android.graphics.Color.green(refreshed) > android.graphics.Color.red(refreshed),
                    "Live C must expose live B's green content; sampled #${refreshed.toUInt().toString(16)}",
                )
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    middleState.get()?.let { middle ->
                        middle.config = middle.config.copy(isLive = false)
                    }
                    upperState.get()?.let { upper ->
                        upper.config = upper.config.copy(isLive = false)
                    }
                    activityRef.get()?.finish()
                }
                scenario.close()
            }
        }
    }

    @Test
    fun lowerLayerCaptureExcludesHigherPortalContent() {
        val lowerColor = mutableStateOf(Color.Blue)
        val showUpper = mutableStateOf(true)
        val middleState = AtomicReference<BlurOverlayState>()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Black))

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    ).also(middleState::set)
                    val overlayC = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        Box(Modifier.fillMaxSize().background(lowerColor.value)) {
                            BlurOverlay(
                                state = overlayB,
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {
                                if (showUpper.value) {
                                    BlurOverlay(
                                        state = overlayC,
                                        modifier = Modifier.fillMaxSize(),
                                        onDismissRequest = {},
                                    ) {
                                        Box(
                                            Modifier
                                                .offset(x = 32.dp, y = 96.dp)
                                                .size(160.dp)
                                                .background(Color.Red),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            scenario.onActivity {
                lowerColor.value = Color.Green
                middleState.get().requestUpdate()
            }
            awaitPixel(xFraction = 0.8f, yFraction = 0.8f) { pixel ->
                AndroidColor.green(pixel) > AndroidColor.blue(pixel)
            }
            scenario.onActivity {
                showUpper.value = false
            }
            composeRule.waitForIdle()

            val screenshot = takeScreenshot()
            val formerUpperRegion = sample(screenshot, xFraction = 0.25f, yFraction = 0.25f)
            assertTrue(
                AndroidColor.green(formerUpperRegion) > AndroidColor.red(formerUpperRegion),
                "B must not retain C's red content after C is removed; " +
                    "sampled #${formerUpperRegion.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun nestedOverlayCapturesTextureViewPixels() {
        val textureReady = AtomicBoolean()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        ColorTexture(
                            Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            onReady = { textureReady.set(true) },
                        )
                        BlurOverlay(
                            state = overlayB,
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {}
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) {
                textureReady.get()
            }
            val pixel = awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                AndroidColor.red(it) > AndroidColor.blue(it)
            }
            assertTrue(
                AndroidColor.red(pixel) > AndroidColor.blue(pixel),
                "Nested blur must capture yellow TextureView pixels; " +
                    "sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun windowPixelCopyDoesNotCompositeTextureViewTwice() {
        val textureReady = AtomicBoolean()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState().apply {
                        isEnabled = false
                    }
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        ColorTexture(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            color = AndroidColor.RED,
                            alpha = 0.5f,
                            onReady = { textureReady.set(true) },
                        )
                        BlurOverlay(
                            state = overlayB,
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {}
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { textureReady.get() }
            val pixel = awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                AndroidColor.red(it) > 90 && AndroidColor.blue(it) > 90
            }
            assertTrue(
                kotlin.math.abs(AndroidColor.red(pixel) - AndroidColor.blue(pixel)) < 30,
                "Window PixelCopy must preserve the TextureView's 0.5 alpha exactly once; " +
                    "sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun nestedGradientOverlayCapturesSurfaceViewPixels() {
        val showTopOverlay = mutableStateOf(false)
        val surfaceReady = AtomicBoolean()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(
                            radius = 12f,
                            gradient = BlurGradientType.Linear(
                                startIntensity = 1f,
                                endIntensity = 1f,
                            ),
                            isLive = false,
                        ),
                    )

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        YellowSurface(
                            Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            onReady = { surfaceReady.set(true) },
                        )
                        if (showTopOverlay.value) {
                            BlurOverlay(
                                state = overlayB,
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {}
                        }
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) {
                surfaceReady.get()
            }
            scenario.onActivity {
                showTopOverlay.value = true
            }
            val pixel = awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                AndroidColor.red(it) > AndroidColor.blue(it)
            }
            assertTrue(
                AndroidColor.red(pixel) > AndroidColor.blue(pixel),
                "Nested gradient blur must capture yellow SurfaceView pixels; " +
                    "sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun rootOverlayCapturesActivitySurfaceViewPixels() {
        val surfaceReady = AtomicBoolean()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue)) {
                        YellowSurface(
                            Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            onReady = { surfaceReady.set(true) },
                        )
                    }

                    val overlay = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = true),
                    )
                    BlurOverlay(
                        state = overlay,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {}
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) {
                surfaceReady.get()
            }
            val pixel = awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                AndroidColor.red(it) > AndroidColor.blue(it)
            }
            assertTrue(
                AndroidColor.red(pixel) > AndroidColor.blue(pixel),
                "Root blur must capture activity SurfaceView pixels; " +
                    "sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun rootOverlaySwitchesToSurfaceCaptureWhenSurfaceViewAppears() {
        val showSurface = mutableStateOf(false)
        val surfaceReady = AtomicBoolean()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue)) {
                        if (showSurface.value) {
                            YellowSurface(
                                Modifier
                                    .offset(x = 32.dp, y = 96.dp)
                                    .size(160.dp),
                                onReady = { surfaceReady.set(true) },
                            )
                        }
                    }

                    val overlay = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = true),
                    )
                    BlurOverlay(
                        state = overlay,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {}
                }
            }

            awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                AndroidColor.blue(it) > AndroidColor.red(it)
            }
            scenario.onActivity {
                showSurface.value = true
            }
            composeRule.waitUntil(timeoutMillis = 10_000) {
                surfaceReady.get()
            }
            val pixel = awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                AndroidColor.red(it) > AndroidColor.blue(it)
            }
            assertTrue(
                AndroidColor.red(pixel) > AndroidColor.blue(pixel),
                "AUTO blur must switch from external input to bitmap capture when a " +
                    "SurfaceView appears; sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun surfaceBitmapRetirementWaitsForPendingWindowCopy() {
        val capture = DecorViewCapture()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val pinnedField = DecorViewCapture::class.java
            .getDeclaredField("pinnedSurfaceBitmaps")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pinned = pinnedField.get(capture) as IdentityHashMap<Bitmap, Int>
        pinned[bitmap] = 1
        val retire = DecorViewCapture::class.java
            .getDeclaredMethod("retireSurfaceBitmap", Bitmap::class.java)
            .apply { isAccessible = true }
        val release = DecorViewCapture::class.java
            .getDeclaredMethod("releaseSurfaceBitmaps", List::class.java)
            .apply { isAccessible = true }

        retire.invoke(capture, bitmap)
        assertFalse(
            bitmap.isRecycled,
            "A surface bitmap retained by an in-flight Window PixelCopy must stay valid",
        )
        release.invoke(capture, listOf(bitmap))
        assertTrue(
            bitmap.isRecycled,
            "A retired surface bitmap must recycle after the Window PixelCopy releases it",
        )
        capture.release()
    }

    @Test
    fun nestedSurfaceCapturePreservesForegroundZOrder() {
        val showTopOverlay = mutableStateOf(false)
        val surfaceReady = AtomicBoolean()
        val surfaceView = AtomicReference<SurfaceView>()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        SolidSurface(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            color = AndroidColor.YELLOW,
                            onReady = {
                                surfaceView.set(it)
                                surfaceReady.set(true)
                            },
                        )
                        Box(
                            Modifier
                                .offset(x = 64.dp, y = 128.dp)
                                .size(96.dp)
                                .background(Color.Red),
                        )
                        if (showTopOverlay.value) {
                            BlurOverlay(
                                state = overlayB,
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {}
                        }
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) {
                surfaceReady.get()
            }
            scenario.onActivity {
                drawSurfaceEdgeMarker(surfaceView.get())
                showTopOverlay.value = true
            }
            assertSoftenedEdge(
                awaitScreenshot { isSoftenedEdge(it, yDp = 112) },
                yDp = 112,
                context = "Nested upper overlay lower SurfaceView edge",
            )
            val pixel = awaitPixel(xFraction = 0.27f, yFraction = 0.16f) {
                AndroidColor.red(it) > AndroidColor.green(it)
            }
            assertTrue(
                AndroidColor.red(pixel) > AndroidColor.green(pixel),
                "Foreground card must remain above captured SurfaceView; " +
                    "sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun nestedTextureCapturePreservesSiblingZOrder() {
        val textureReady = AtomicBoolean()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        TextureOverRedSibling(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            onReady = { textureReady.set(true) },
                        )
                        BlurOverlay(
                            state = rememberBlurOverlayState(
                                BlurOverlayConfig(radius = 12f, isLive = false),
                            ),
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {}
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { textureReady.get() }
            val pixel = awaitScreenshot {
                val sampled = sample(it, xDp = 112, yDp = 176)
                AndroidColor.green(sampled) > 180 && AndroidColor.blue(sampled) < 100
            }.let { sample(it, xDp = 112, yDp = 176) }
            assertTrue(
                AndroidColor.green(pixel) > 180 && AndroidColor.blue(pixel) < 100,
                "TextureView inserted above its red sibling must remain above it in capture; " +
                    "sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun nestedTextureCaptureHonorsAncestorClipAndRotation() {
        val textureReady = AtomicBoolean()
        val showUpper = mutableStateOf(false)

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        ClippedRotatedTexture(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(200.dp),
                            onReady = { textureReady.set(true) },
                        )
                        if (showUpper.value) {
                            BlurOverlay(
                                state = rememberBlurOverlayState(
                                    BlurOverlayConfig(radius = 12f, isLive = false),
                                ),
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {}
                        }
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { textureReady.get() }
            val visibleOutsideClip = sample(takeScreenshot(), xDp = 152, yDp = 136)
            assertTrue(
                AndroidColor.blue(visibleOutsideClip) > AndroidColor.green(visibleOutsideClip),
                "TextureView test precondition requires the live view to be clipped; " +
                    "sampled #${visibleOutsideClip.toUInt().toString(16)}",
            )
            scenario.onActivity { showUpper.value = true }
            Thread.sleep(1_000)
            val screenshot = takeScreenshot()
            val outsideClip = sample(screenshot, xDp = 152, yDp = 136)
            assertTrue(
                AndroidColor.blue(outsideClip) > AndroidColor.green(outsideClip),
                "Pixels outside the clipping parent must not leak into capture; " +
                    "sampled #${outsideClip.toUInt().toString(16)}",
            )
            val rotatedCorner = sample(screenshot, xDp = 38, yDp = 102)
            assertTrue(
                AndroidColor.blue(rotatedCorner) > AndroidColor.green(rotatedCorner),
                "Pixels outside the rotated TextureView must not appear in capture; " +
                    "sampled #${rotatedCorner.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun nestedSurfaceCaptureMatchesPlatformClipAndRotation() {
        verifyClippedRotatedSurface(onTop = false)
    }

    @Test
    fun onTopSurfaceCaptureMatchesPlatformClipAndRotation() {
        verifyClippedRotatedSurface(onTop = true)
    }

    private fun verifyClippedRotatedSurface(onTop: Boolean) {
        val surfaceReady = AtomicBoolean()
        val showUpper = mutableStateOf(false)

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        ClippedRotatedSurface(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(200.dp),
                            onTop = onTop,
                            onReady = { surfaceReady.set(true) },
                        )
                        if (showUpper.value) {
                            BlurOverlay(
                                state = rememberBlurOverlayState(
                                    BlurOverlayConfig(radius = 12f, isLive = false),
                                ),
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {}
                        }
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { surfaceReady.get() }
            Thread.sleep(1_000)
            val visibleOutsideClip = sample(takeScreenshot(), xDp = 152, yDp = 136)
            if (onTop) {
                assertTrue(
                    AndroidColor.green(visibleOutsideClip) > AndroidColor.blue(visibleOutsideClip),
                    "Android composes this on-top SurfaceView outside ViewGroup clipping; " +
                        "sampled #${visibleOutsideClip.toUInt().toString(16)}",
                )
            } else {
                assertTrue(
                    AndroidColor.blue(visibleOutsideClip) > AndroidColor.green(visibleOutsideClip),
                    "SurfaceView test precondition requires ViewGroup clipping; " +
                        "sampled #${visibleOutsideClip.toUInt().toString(16)}",
                )
            }
            val visibleRotatedCorner = sample(takeScreenshot(), xDp = 38, yDp = 102)
            if (onTop) {
                assertTrue(
                    AndroidColor.green(visibleRotatedCorner) >
                        AndroidColor.blue(visibleRotatedCorner),
                    "Android composes an on-top SurfaceView without the View rotation; " +
                        "sampled #${visibleRotatedCorner.toUInt().toString(16)}",
                )
            } else {
                assertTrue(
                    AndroidColor.blue(visibleRotatedCorner) >
                        AndroidColor.green(visibleRotatedCorner),
                    "SurfaceView test precondition requires the live surface to be rotated; " +
                        "sampled #${visibleRotatedCorner.toUInt().toString(16)}",
                )
            }
            scenario.onActivity { showUpper.value = true }
            Thread.sleep(1_000)
            val screenshot = takeScreenshot()
            val outsideClip = sample(screenshot, xDp = 152, yDp = 136)
            if (onTop) {
                assertTrue(
                    AndroidColor.green(outsideClip) > AndroidColor.blue(outsideClip),
                    "Capture must preserve Android's on-top SurfaceView clipping behavior; " +
                        "sampled #${outsideClip.toUInt().toString(16)}",
                )
            } else {
                assertTrue(
                    AndroidColor.blue(outsideClip) > AndroidColor.green(outsideClip),
                    "Capture must preserve Android's SurfaceView clipping behavior; " +
                        "sampled #${outsideClip.toUInt().toString(16)}",
                )
            }
            val rotatedCorner = sample(screenshot, xDp = 38, yDp = 102)
            if (onTop) {
                assertTrue(
                    AndroidColor.green(rotatedCorner) > AndroidColor.blue(rotatedCorner),
                    "Capture must preserve Android's on-top SurfaceView transform behavior; " +
                        "sampled #${rotatedCorner.toUInt().toString(16)}",
                )
            } else {
                assertTrue(
                    AndroidColor.blue(rotatedCorner) > AndroidColor.green(rotatedCorner),
                    "Capture must preserve Android's SurfaceView transform behavior; " +
                        "sampled #${rotatedCorner.toUInt().toString(16)}",
                )
            }
        }
    }

    @Test
    fun topSurfaceStaysBehindUpperOverlayAndCapturedAboveSiblingViews() {
        val surfaceReady = AtomicBoolean()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        SolidSurface(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            color = AndroidColor.RED,
                            onTop = true,
                            initialDraw = ::drawSurfaceEdgeMarker,
                            onReady = { surfaceReady.set(true) },
                        )
                        Box(
                            Modifier
                                .offset(x = 64.dp, y = 128.dp)
                                .size(96.dp)
                                .background(Color.Blue),
                        )
                        BlurOverlay(
                            state = rememberBlurOverlayState(
                                BlurOverlayConfig(radius = 12f, isLive = false),
                            ),
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {
                            Box(
                                Modifier
                                    .offset(x = 96.dp, y = 160.dp)
                                    .size(32.dp)
                                    .background(Color.Red),
                            )
                        }
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { surfaceReady.get() }
            assertSoftenedEdge(
                awaitScreenshot { isSoftenedEdge(it, yDp = 136) },
                yDp = 136,
                context = "Upper overlay on-top SurfaceView edge",
            )
            val capturedOverlap = awaitScreenshot {
                val pixel = sample(it, xDp = 80, yDp = 176)
                AndroidColor.red(pixel) > 200 &&
                    AndroidColor.green(pixel) > 200
            }
            val overlapPixel = sample(capturedOverlap, xDp = 80, yDp = 176)
            assertTrue(
                AndroidColor.red(overlapPixel) > 200 &&
                    AndroidColor.green(overlapPixel) > 200,
                "An on-top SurfaceView must remain above overlapping lower-window siblings " +
                    "inside the upper blur; sampled #${overlapPixel.toUInt().toString(16)}",
            )
            val pixel = awaitScreenshot {
                val sampled = sample(it, xDp = 112, yDp = 176)
                AndroidColor.red(sampled) > AndroidColor.green(sampled)
            }.let { sample(it, xDp = 112, yDp = 176) }
            assertTrue(
                AndroidColor.red(pixel) > AndroidColor.green(pixel),
                "Upper overlay content must remain above an on-top SurfaceView; " +
                    "sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun nonLiveOverlayDoesNotRecaptureLowerWindowUntilRequested() {
        val lowerColor = mutableStateOf(Color.Red)
        val topState = AtomicReference<BlurOverlayState>()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false)
                            .withTint(Color.Blue.copy(alpha = 0.25f)),
                    ).also(topState::set)

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        Box(Modifier.fillMaxSize().background(lowerColor.value))
                        BlurOverlay(
                            state = overlayB,
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {}
                    }
                }
            }

            awaitPixel(xFraction = 0.5f, yFraction = 0.5f) {
                AndroidColor.red(it) > AndroidColor.green(it) &&
                    AndroidColor.blue(it) > 20
            }

            scenario.onActivity {
                lowerColor.value = Color.Green
            }
            Thread.sleep(1_000)
            val frozen = sample(takeScreenshot(), xFraction = 0.5f, yFraction = 0.5f)
            assertTrue(
                AndroidColor.red(frozen) > AndroidColor.green(frozen),
                "A non-live overlay must not continuously recapture lower windows; " +
                    "sampled #${frozen.toUInt().toString(16)}",
            )

            scenario.onActivity {
                topState.get().requestUpdate()
            }
            val refreshed = awaitPixel(xFraction = 0.5f, yFraction = 0.5f) {
                AndroidColor.green(it) > AndroidColor.red(it)
            }
            assertTrue(
                AndroidColor.green(refreshed) > AndroidColor.red(refreshed),
                "Manual non-live refresh must display the newest lower-window frame; " +
                    "sampled #${refreshed.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun nonLiveOverlayRefreshesToLatestSurfaceFrame() {
        val surfaceReady = AtomicBoolean()
        val surfaceView = AtomicReference<SurfaceView>()
        val topState = AtomicReference<BlurOverlayState>()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false)
                            .withTint(Color.Blue.copy(alpha = 0.25f)),
                    ).also(topState::set)

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        SolidSurface(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            color = AndroidColor.RED,
                            onReady = {
                                surfaceView.set(it)
                                surfaceReady.set(true)
                            },
                        )
                        BlurOverlay(
                            state = overlayB,
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {}
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { surfaceReady.get() }
            awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                AndroidColor.red(it) > AndroidColor.green(it) &&
                    AndroidColor.blue(it) > 20
            }

            scenario.onActivity {
                drawSurface(surfaceView.get(), AndroidColor.GREEN)
            }
            Thread.sleep(1_000)
            val frozen = sample(takeScreenshot(), xFraction = 0.25f, yFraction = 0.25f)
            assertTrue(
                AndroidColor.red(frozen) > AndroidColor.green(frozen),
                "A non-live overlay must stay frozen until explicitly refreshed; " +
                    "sampled #${frozen.toUInt().toString(16)}",
            )

            scenario.onActivity {
                topState.get().requestUpdate()
            }
            val refreshed = awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                AndroidColor.green(it) > AndroidColor.red(it)
            }
            assertTrue(
                AndroidColor.green(refreshed) > AndroidColor.red(refreshed),
                "Manual non-live refresh must display the newest SurfaceView frame; " +
                    "sampled #${refreshed.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun surfaceCapturePreservesViewForeground() {
        val surfaceReady = AtomicBoolean()
        val showTopOverlay = mutableStateOf(false)
        val topState = AtomicReference<BlurOverlayState>()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false),
                    ).also(topState::set)

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        SolidSurface(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            color = AndroidColor.YELLOW,
                            foreground = AndroidColor.argb(128, 255, 0, 0),
                            onReady = { surfaceReady.set(true) },
                        )
                        if (showTopOverlay.value) {
                            BlurOverlay(
                                state = overlayB,
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {}
                        }
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { surfaceReady.get() }
            scenario.onActivity { showTopOverlay.value = true }
            awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                AndroidColor.green(it) in 100..160
            }
            repeat(3) {
                scenario.onActivity { topState.get().requestUpdate() }
                Thread.sleep(150)
            }
            val pixel = sample(takeScreenshot(), xFraction = 0.25f, yFraction = 0.25f)
            assertTrue(
                AndroidColor.green(pixel) in 100..160,
                "Translucent SurfaceView foreground must be composited exactly once; " +
                    "sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun drawingSurfaceStaysBehindForegroundSibling() {
        val surfaceReady = AtomicBoolean()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))
                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        SolidSurface(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            color = AndroidColor.YELLOW,
                            foreground = AndroidColor.argb(128, 255, 0, 0),
                            onReady = { surfaceReady.set(true) },
                        )
                        Box(
                            Modifier
                                .offset(x = 64.dp, y = 128.dp)
                                .size(96.dp)
                                .background(Color.Blue),
                        )
                        BlurOverlay(
                            state = rememberBlurOverlayState(
                                BlurOverlayConfig(radius = 12f, isLive = false),
                            ),
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {}
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { surfaceReady.get() }
            val screenshot = awaitScreenshot {
                val sampled = sample(it, xDp = 112, yDp = 176)
                AndroidColor.blue(sampled) > AndroidColor.red(sampled)
            }
            val pixel = sample(screenshot, xDp = 112, yDp = 176)
            assertTrue(
                AndroidColor.blue(pixel) > AndroidColor.red(pixel),
                "A below-window SurfaceView that draws must remain behind foreground siblings; " +
                    "sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun stackedSurfaceCapturePreservesMediaOverlayOrder() {
        val readyCount = AtomicInteger()
        val showUpper = mutableStateOf(false)

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))
                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        OverlappingSurfaceLayers(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            onReady = { readyCount.incrementAndGet() },
                        )
                        if (showUpper.value) {
                            BlurOverlay(
                                state = rememberBlurOverlayState(
                                    BlurOverlayConfig(radius = 12f, isLive = false),
                                ),
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {}
                        }
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { readyCount.get() == 2 }
            val visible = awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                AndroidColor.red(it) > 180 && AndroidColor.green(it) > 180
            }
            assertTrue(
                AndroidColor.red(visible) > 180 && AndroidColor.green(visible) > 180,
                "Media-overlay SurfaceView must be visibly above the regular SurfaceView",
            )
            scenario.onActivity { showUpper.value = true }
            val captured = awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                AndroidColor.red(it) > AndroidColor.blue(it)
            }
            assertTrue(
                AndroidColor.red(captured) > 180 && AndroidColor.green(captured) > 180,
                "Upper blur must preserve SurfaceView compositor order; " +
                    "sampled #${captured.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun stackedSurfaceCaptureHonorsAncestorClipBounds() {
        val surfaceReady = AtomicBoolean()
        val showUpper = mutableStateOf(false)

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))
                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        ClipBoundsSurface(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(200.dp),
                            onReady = { surfaceReady.set(true) },
                        )
                        if (showUpper.value) {
                            BlurOverlay(
                                state = rememberBlurOverlayState(
                                    BlurOverlayConfig(radius = 12f, isLive = false),
                                ),
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {}
                        }
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { surfaceReady.get() }
            val visibleScreenshot = awaitScreenshot {
                val pixel = sample(it, xDp = 152, yDp = 136)
                AndroidColor.blue(pixel) > AndroidColor.green(pixel)
            }
            val visible = sample(visibleScreenshot, xDp = 152, yDp = 136)
            assertTrue(
                AndroidColor.blue(visible) > AndroidColor.green(visible),
                "Test precondition requires the parent clipBounds to crop the live surface",
            )
            scenario.onActivity { showUpper.value = true }
            Thread.sleep(1_000)
            val captured = sample(takeScreenshot(), xDp = 152, yDp = 136)
            assertTrue(
                AndroidColor.blue(captured) > AndroidColor.green(captured),
                "Parent clipBounds must crop the captured SurfaceView; " +
                    "sampled #${captured.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun partialLowerOverlayPreservesBasePlaneForUpperBlur() {
        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue)) {
                        EdgeMarker(
                            Modifier
                                .offset(x = 32.dp, y = 120.dp)
                                .size(160.dp),
                        )
                    }
                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier
                            .offset(x = 240.dp, y = 120.dp)
                            .size(80.dp),
                        onDismissRequest = {},
                    ) {}
                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {}
                }
            }

            assertSoftenedEdge(
                awaitScreenshot { isSoftenedEdge(it, yDp = 200) },
                yDp = 200,
                context = "Base plane outside a partial lower overlay",
            )
        }
    }

    @Test
    fun nonLiveOverlayRetriesUntilSurfaceProducesFirstFrame() {
        val surfaceReady = AtomicBoolean()
        val surfaceView = AtomicReference<SurfaceView>()
        val activityRef = AtomicReference<MainActivity>()

        launchEmptyActivity().let { scenario ->
            try {
                scenario.onActivity { activity ->
                    activityRef.set(activity)
                    activity.setContent {
                        Box(Modifier.fillMaxSize().background(Color.Blue)) {
                            DelayedSurface(
                                modifier = Modifier
                                    .offset(x = 32.dp, y = 96.dp)
                                    .size(160.dp),
                                onReady = {
                                    surfaceView.set(it)
                                    surfaceReady.set(true)
                                },
                            )
                            BlurOverlay(
                                state = rememberBlurOverlayState(
                                    BlurOverlayConfig(radius = 12f, isLive = false)
                                        .withTint(Color.Red.copy(alpha = 0.5f)),
                                ),
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {}
                        }
                    }
                }

                composeRule.waitUntil(timeoutMillis = 10_000) { surfaceReady.get() }
                Thread.sleep(2_500)
                drawSurface(surfaceView.get(), AndroidColor.GREEN)
                val pixel = awaitPixel(xFraction = 0.25f, yFraction = 0.25f) {
                    AndroidColor.red(it) > 80 &&
                        AndroidColor.green(it) > AndroidColor.blue(it)
                }
                assertTrue(
                    AndroidColor.red(pixel) > 80 &&
                        AndroidColor.green(pixel) > AndroidColor.blue(pixel),
                    "Non-live capture must retry a SurfaceView that initially has no buffer; " +
                        "sampled #${pixel.toUInt().toString(16)}",
                )
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    activityRef.get()?.finish()
                }
                scenario.close()
            }
        }
    }

    @Test
    fun stackedNonLiveOverlayWaitsForLowerSurfaceFirstFrame() {
        val surfaceReady = AtomicBoolean()
        val surfaceView = AtomicReference<SurfaceView>()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))
                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        DelayedSurface(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            onReady = {
                                surfaceView.set(it)
                                surfaceReady.set(true)
                            },
                        )
                        BlurOverlay(
                            state = rememberBlurOverlayState(
                                BlurOverlayConfig(radius = 12f, isLive = false),
                            ),
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {}
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { surfaceReady.get() }
            Thread.sleep(2_500)
            scenario.onActivity {
                drawSurfaceEdgeMarker(surfaceView.get())
            }
            assertSoftenedEdge(
                awaitScreenshot { isSoftenedEdge(it, yDp = 176) },
                yDp = 176,
                context = "Upper static overlay delayed lower SurfaceView frame",
            )
        }
    }

    @Test
    fun stackedNonLiveOverlayWaitsForLowerTextureFirstFrame() {
        val textureReady = AtomicBoolean()
        val textureView = AtomicReference<TextureView>()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))
                    BlurOverlay(
                        state = rememberBlurOverlayState(
                            BlurOverlayConfig(radius = 12f, isLive = false),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        DelayedTexture(
                            modifier = Modifier
                                .offset(x = 32.dp, y = 96.dp)
                                .size(160.dp),
                            onReady = {
                                textureView.set(it)
                                textureReady.set(true)
                            },
                        )
                        BlurOverlay(
                            state = rememberBlurOverlayState(
                                BlurOverlayConfig(radius = 12f, isLive = false),
                            ),
                            modifier = Modifier.fillMaxSize(),
                            onDismissRequest = {},
                        ) {}
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 10_000) { textureReady.get() }
            Thread.sleep(2_500)
            scenario.onActivity {
                drawTextureEdgeMarker(textureView.get())
            }
            assertSoftenedEdge(
                awaitScreenshot { isSoftenedEdge(it, yDp = 176) },
                yDp = 176,
                context = "Upper static overlay delayed lower TextureView frame",
            )
        }
    }

    @Test
    fun surfaceCaptureCanBeReusedAfterRelease() {
        val surfaceReady = AtomicBoolean()
        val surfaceView = AtomicReference<SurfaceView>()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SolidSurface(
                        modifier = Modifier.size(160.dp),
                        color = AndroidColor.YELLOW,
                        onReady = {
                            surfaceView.set(it)
                            surfaceReady.set(true)
                        },
                    )
                }
            }
            composeRule.waitUntil(timeoutMillis = 10_000) { surfaceReady.get() }

            val capture = SurfaceCapture()
            val output = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            val blurView = AtomicReference<View>()
            scenario.onActivity { blurView.set(View(it)) }

            fun captureOnMain(): Boolean {
                val result = AtomicBoolean()
                scenario.onActivity {
                    result.set(capture.capture(blurView.get(), surfaceView.get(), output, 4f))
                }
                return result.get()
            }

            captureOnMain()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                captureOnMain() && AndroidColor.red(output.getPixel(20, 20)) > 100
            }

            scenario.onActivity {
                capture.release()
                drawSurface(surfaceView.get(), AndroidColor.GREEN)
            }
            captureOnMain()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                captureOnMain() &&
                    AndroidColor.green(output.getPixel(20, 20)) >
                    AndroidColor.red(output.getPixel(20, 20))
            }
        }
    }

    @Test
    fun unavailableTextureSchedulesBoundedRetry() {
        val drawCount = AtomicInteger()
        val blurView = AtomicReference<View>()
        val textureView = AtomicReference<TextureView>()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                val countingView = object : View(activity) {
                    override fun onDraw(canvas: android.graphics.Canvas) {
                        drawCount.incrementAndGet()
                    }
                }.apply {
                    setWillNotDraw(false)
                }
                activity.addContentView(
                    countingView,
                    ViewGroup.LayoutParams(1, 1),
                )
                blurView.set(countingView)
                textureView.set(TextureView(activity))
            }
            composeRule.waitUntil(timeoutMillis = 10_000) { drawCount.get() > 0 }
            composeRule.waitForIdle()
            Thread.sleep(100)

            val baseline = drawCount.get()
            val capture = SurfaceCapture()
            val output = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            scenario.onActivity {
                assertFalse(textureView.get().isAvailable)
                assertFalse(capture.capture(blurView.get(), textureView.get(), output, 4f))
            }
            composeRule.waitUntil(timeoutMillis = 1_000) {
                drawCount.get() > baseline
            }
            capture.release()
        }
    }

    @Test
    fun textureCaptureWaitsForFirstProducerFrame() {
        val textureReady = AtomicBoolean()
        val textureView = AtomicReference<TextureView>()

        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DelayedTexture(
                        modifier = Modifier.size(160.dp),
                        onReady = {
                            textureView.set(it)
                            textureReady.set(true)
                        },
                    )
                }
            }
            composeRule.waitUntil(timeoutMillis = 10_000) { textureReady.get() }

            val capture = SurfaceCapture()
            val output = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            val blurView = AtomicReference<View>()
            scenario.onActivity {
                blurView.set(View(it))
                assertFalse(capture.capture(blurView.get(), textureView.get(), output, 4f))
                drawTextureEdgeMarker(textureView.get())
                assertFalse(
                    capture.capture(blurView.get(), textureView.get(), output, 4f),
                    "An unavailable TextureView buffer must be retried on a bounded timer, " +
                        "not synchronously on every invalidation",
                )
            }
            composeRule.waitUntil(timeoutMillis = 10_000) {
                val captured = AtomicBoolean()
                scenario.onActivity {
                    captured.set(
                        capture.capture(blurView.get(), textureView.get(), output, 4f),
                    )
                }
                captured.get()
            }
            assertTrue(AndroidColor.alpha(output.getPixel(20, 20)) > 0)
            capture.release()
        }
    }

    @Test
    fun disabledLowerLayerStillParticipatesInStack() {
        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState().apply {
                        isEnabled = false
                    }
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false)
                            .withTint(Color.Green.copy(alpha = 0.9f)),
                    )

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.Red)) {
                            BlurOverlay(
                                state = overlayB,
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {}
                        }
                    }
                }
            }

            val pixel = awaitPixel(xFraction = 0.75f, yFraction = 0.75f) {
                AndroidColor.green(it) > AndroidColor.red(it)
            }
            assertTrue(
                AndroidColor.green(pixel) > AndroidColor.red(pixel),
                "B must capture content from disabled lower layer A; " +
                    "sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun zeroRadiusLowerLayerDoesNotBlockUpperLayer() {
        launchEmptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.Blue))

                    val overlayA = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 0f, isLive = false),
                    )
                    val overlayB = rememberBlurOverlayState(
                        BlurOverlayConfig(radius = 12f, isLive = false)
                            .withTint(Color.Blue.copy(alpha = 0.9f)),
                    )

                    BlurOverlay(
                        state = overlayA,
                        modifier = Modifier.fillMaxSize(),
                        onDismissRequest = {},
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.Red)) {
                            BlurOverlay(
                                state = overlayB,
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = {},
                            ) {}
                        }
                    }
                }
            }

            val pixel = awaitPixel(xFraction = 0.75f, yFraction = 0.75f) {
                AndroidColor.blue(it) > AndroidColor.red(it)
            }
            assertTrue(
                AndroidColor.blue(pixel) > AndroidColor.red(pixel),
                "B must render above a zero-radius A; sampled #${pixel.toUInt().toString(16)}",
            )
        }
    }

    private fun launchEmptyActivity(): ActivityScenario<MainActivity> {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).putExtra(MainActivity.EXTRA_EMPTY_CONTENT, true)
        return ActivityScenario.launch(intent)
    }

    private fun sample(bitmap: Bitmap, xDp: Int, yDp: Int): Int {
        val density = InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .resources
            .displayMetrics
            .density
        return bitmap.getPixel(
            (xDp * density).toInt().coerceIn(0, bitmap.width - 1),
            (yDp * density).toInt().coerceIn(0, bitmap.height - 1),
        )
    }

    private fun sample(bitmap: Bitmap, xFraction: Float, yFraction: Float): Int =
        bitmap.getPixel(
            (bitmap.width * xFraction).toInt().coerceIn(0, bitmap.width - 1),
            (bitmap.height * yFraction).toInt().coerceIn(0, bitmap.height - 1),
        )

    private fun assertSoftenedEdge(bitmap: Bitmap, yDp: Int, context: String) {
        assertTrue(
            isSoftenedEdge(bitmap, yDp),
            "$context must be present and visibly blurred",
        )
    }

    private fun isSoftenedEdge(bitmap: Bitmap, yDp: Int): Boolean {
        val farLeft = sample(bitmap, xDp = 88, yDp = yDp)
        val left = sample(bitmap, xDp = 108, yDp = yDp)
        val right = sample(bitmap, xDp = 116, yDp = yDp)
        val farRight = sample(bitmap, xDp = 136, yDp = yDp)
        val contrast = maxOf(
            kotlin.math.abs(
                android.graphics.Color.red(left) - android.graphics.Color.red(right),
            ),
            kotlin.math.abs(
                android.graphics.Color.green(left) - android.graphics.Color.green(right),
            ),
            kotlin.math.abs(
                android.graphics.Color.blue(left) - android.graphics.Color.blue(right),
            ),
        )
        val markerContrast = maxOf(
            kotlin.math.abs(
                android.graphics.Color.red(farLeft) - android.graphics.Color.red(farRight),
            ),
            kotlin.math.abs(
                android.graphics.Color.green(farLeft) - android.graphics.Color.green(farRight),
            ),
            kotlin.math.abs(
                android.graphics.Color.blue(farLeft) - android.graphics.Color.blue(farRight),
            ),
        )
        return markerContrast > 40 && contrast < markerContrast
    }

    private fun assertRed(pixel: Int, context: String) {
        assertTrue(
            android.graphics.Color.red(pixel) > android.graphics.Color.green(pixel),
            "$context must remain red; sampled #${pixel.toUInt().toString(16)}",
        )
    }

    private fun awaitPixel(
        xFraction: Float,
        yFraction: Float,
        predicate: (Int) -> Boolean,
    ): Int {
        var pixel = 0
        try {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                pixel = sample(takeScreenshot(), xFraction, yFraction)
                predicate(pixel)
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError(
                "Pixel condition timed out; sampled #${pixel.toUInt().toString(16)}",
                error,
            )
        }
        return pixel
    }

    private fun awaitScreenshot(predicate: (Bitmap) -> Boolean): Bitmap {
        var screenshot: Bitmap? = null
        composeRule.waitUntil(timeoutMillis = 10_000) {
            screenshot = takeScreenshot()
            predicate(requireNotNull(screenshot))
        }
        return requireNotNull(screenshot)
    }

    private fun takeScreenshot(): Bitmap {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val path = "/sdcard/stacked-blur-test.png"
        uiAutomation.executeShellCommand("screencap -p $path").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
        return uiAutomation.executeShellCommand("cat $path").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                requireNotNull(BitmapFactory.decodeStream(stream))
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun EdgeMarker(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Color.White, size = size.copy(width = size.width / 2f))
        drawRect(
            Color.Black,
            topLeft = androidx.compose.ui.geometry.Offset(x = size.width / 2f, y = 0f),
            size = size.copy(width = size.width / 2f),
        )
    }
}

@androidx.compose.runtime.Composable
private fun ColorTexture(
    modifier: Modifier,
    color: Int = AndroidColor.YELLOW,
    alpha: Float = 1f,
    onReady: () -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                this.alpha = alpha
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        val surface = Surface(surfaceTexture)
                        try {
                            val canvas = surface.lockCanvas(null)
                            try {
                                canvas.drawColor(color)
                            } finally {
                                surface.unlockCanvasAndPost(canvas)
                            }
                            onReady()
                        } finally {
                            surface.release()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(
                        surfaceTexture: SurfaceTexture,
                    ): Boolean = true

                    override fun onSurfaceTextureUpdated(
                        surfaceTexture: SurfaceTexture,
                    ) = Unit
                }
            }
        },
    )
}

@androidx.compose.runtime.Composable
private fun TextureOverRedSibling(
    modifier: Modifier,
    onReady: () -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                addView(
                    View(context).apply {
                        setBackgroundColor(AndroidColor.RED)
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    solidTextureView(context, onReady),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
    )
}

@androidx.compose.runtime.Composable
private fun ClippedRotatedTexture(
    modifier: Modifier,
    onReady: () -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                val clippingParent = FrameLayout(context).apply {
                    clipChildren = true
                    clipToPadding = true
                }
                addView(
                    clippingParent,
                    FrameLayout.LayoutParams(
                        80.dpToPx(context),
                        80.dpToPx(context),
                    ),
                )
                clippingParent.addView(
                    solidTextureView(context, onReady).apply { rotation = 45f },
                    FrameLayout.LayoutParams(160.dpToPx(context), 160.dpToPx(context)),
                )
            }
        },
    )
}

@androidx.compose.runtime.Composable
private fun ClippedRotatedSurface(
    modifier: Modifier,
    onTop: Boolean,
    onReady: () -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                val clippingParent = FrameLayout(context).apply {
                    clipChildren = true
                    clipToPadding = true
                }
                addView(
                    clippingParent,
                    FrameLayout.LayoutParams(
                        80.dpToPx(context),
                        80.dpToPx(context),
                    ),
                )
                clippingParent.addView(
                    SurfaceView(context).apply surface@{
                        setZOrderOnTop(onTop)
                        rotation = 45f
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                drawSurface(this@surface, AndroidColor.YELLOW)
                                onReady()
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                        })
                    },
                    FrameLayout.LayoutParams(160.dpToPx(context), 160.dpToPx(context)),
                )
            }
        },
    )
}

@androidx.compose.runtime.Composable
private fun OverlappingSurfaceLayers(
    modifier: Modifier,
    onReady: () -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                addView(
                    readySurfaceView(
                        context = context,
                        color = AndroidColor.YELLOW,
                        mediaOverlay = true,
                        onReady = onReady,
                    ),
                )
                addView(
                    readySurfaceView(
                        context = context,
                        color = AndroidColor.GREEN,
                        mediaOverlay = false,
                        onReady = onReady,
                    ),
                )
            }
        },
    )
}

@androidx.compose.runtime.Composable
private fun ClipBoundsSurface(
    modifier: Modifier,
    onReady: () -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                clipChildren = false
                clipBounds = android.graphics.Rect(
                    0,
                    0,
                    80.dpToPx(context),
                    200.dpToPx(context),
                )
                addView(
                    readySurfaceView(
                        context = context,
                        color = AndroidColor.YELLOW,
                        onReady = onReady,
                    ),
                    FrameLayout.LayoutParams(
                        160.dpToPx(context),
                        160.dpToPx(context),
                    ),
                )
            }
        },
    )
}

private fun readySurfaceView(
    context: android.content.Context,
    color: Int,
    mediaOverlay: Boolean = false,
    onReady: () -> Unit,
): SurfaceView {
    return SurfaceView(context).apply surface@{
        setZOrderMediaOverlay(mediaOverlay)
        registerBlurCaptureCompositionOrder(if (mediaOverlay) -1 else -2)
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                drawSurface(this@surface, color)
                onReady()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
    }
}

private fun solidTextureView(
    context: android.content.Context,
    onReady: () -> Unit,
): TextureView {
    return TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                val surface = Surface(surfaceTexture)
                try {
                    val canvas = surface.lockCanvas(null)
                    try {
                        canvas.drawColor(AndroidColor.YELLOW)
                    } finally {
                        surface.unlockCanvasAndPost(canvas)
                    }
                    onReady()
                } finally {
                    surface.release()
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
    }
}

private fun Int.dpToPx(context: android.content.Context): Int {
    return (this * context.resources.displayMetrics.density).toInt()
}

@androidx.compose.runtime.Composable
private fun YellowSurface(
    modifier: Modifier,
    onReady: () -> Unit,
) = SolidSurface(
    modifier = modifier,
    color = AndroidColor.YELLOW,
    onReady = { onReady() },
)

@androidx.compose.runtime.Composable
private fun SolidSurface(
    modifier: Modifier,
    color: Int,
    foreground: Int? = null,
    onTop: Boolean = false,
    initialDraw: ((SurfaceView) -> Unit)? = null,
    onReady: (SurfaceView) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                setZOrderOnTop(onTop)
                foreground?.let { this.foreground = ColorDrawable(it) }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        initialDraw?.invoke(this@apply) ?: drawSurface(this@apply, color)
                        onReady(this@apply)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                })
            }
        },
    )
}

@androidx.compose.runtime.Composable
private fun DelayedSurface(
    modifier: Modifier,
    onReady: (SurfaceView) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onReady(this@apply)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                })
            }
        },
    )
}

@androidx.compose.runtime.Composable
private fun DelayedTexture(
    modifier: Modifier,
    onReady: (TextureView) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        onReady(this@apply)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(
                        surfaceTexture: SurfaceTexture,
                    ): Boolean = true

                    override fun onSurfaceTextureUpdated(
                        surfaceTexture: SurfaceTexture,
                    ) = Unit
                }
            }
        },
    )
}

private fun drawSurface(surfaceView: SurfaceView, color: Int) {
    val canvas = surfaceView.holder.lockCanvas()
    try {
        canvas.drawColor(color)
    } finally {
        surfaceView.holder.unlockCanvasAndPost(canvas)
    }
}

private fun drawSurfaceEdgeMarker(surfaceView: SurfaceView) {
    val canvas = surfaceView.holder.lockCanvas()
    try {
        canvas.drawColor(AndroidColor.WHITE)
        canvas.drawRect(
            surfaceView.width / 2f,
            0f,
            surfaceView.width.toFloat(),
            surfaceView.height.toFloat(),
            android.graphics.Paint().apply { color = AndroidColor.BLACK },
        )
    } finally {
        surfaceView.holder.unlockCanvasAndPost(canvas)
    }
}

private fun drawTextureEdgeMarker(textureView: TextureView) {
    val surface = Surface(checkNotNull(textureView.surfaceTexture))
    try {
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawColor(AndroidColor.WHITE)
            canvas.drawRect(
                textureView.width / 2f,
                0f,
                textureView.width.toFloat(),
                textureView.height.toFloat(),
                android.graphics.Paint().apply { color = AndroidColor.BLACK },
            )
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    } finally {
        surface.release()
    }
}

private fun assertTextureGateResets(
    textureView: TextureView,
    hasFirstFrame: () -> Boolean,
    setOnFrameLostListener: ((() -> Unit)?) -> Unit,
) {
    val surfaceTexture = SurfaceTexture(0)
    val frameLost = AtomicBoolean(false)
    try {
        setOnFrameLostListener { frameLost.set(true) }
        val listener = checkNotNull(textureView.surfaceTextureListener)
        listener.onSurfaceTextureUpdated(surfaceTexture)
        assertTrue(hasFirstFrame())
        assertTrue(textureView.alpha == 1f)

        listener.onSurfaceTextureDestroyed(surfaceTexture)
        assertTrue(!hasFirstFrame(), "Destroyed output must clear first-frame readiness")
        assertTrue(textureView.alpha == 0f, "Destroyed output must hide its replacement surface")
        assertTrue(frameLost.get(), "Destroyed output must notify its host that readiness was lost")
    } finally {
        setOnFrameLostListener(null)
        surfaceTexture.release()
    }
}
