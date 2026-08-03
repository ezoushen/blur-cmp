package io.github.ezoushen.blur.cmp

import android.graphics.BlendModeColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ezoushen.blur.BlurPipelineStrategy
import io.github.ezoushen.blur.capture.BackdropCapturePrefix
import io.github.ezoushen.blur.capture.BackdropCaptureSource
import io.github.ezoushen.blur.view.BlurView
import io.github.ezoushen.blur.view.VariableBlurView

/**
 * Android BlurOverlayHost using blur-core's native BlurView/VariableBlurView.
 *
 * Rendering pipeline: capture → tint (with blend mode) → blur → render.
 *
 * Alpha capture strategy:
 * - Fade-in (alpha increasing): live capture stays ON. The blur needs fresh
 *   content since there may be no prior captured frame. The dirty flag side
 *   effect of sourceView.draw() is acceptable because the blur is covering
 *   the background anyway.
 * - Fade-out (alpha decreasing): live capture stops. The blur holds its last
 *   captured frame while fading. This avoids sourceView.draw() clearing View
 *   dirty flags, which would freeze Compose animations visible behind the
 *   semi-transparent blur.
 * - Alpha == 1.0: normal live capture.
 * - Alpha == 0.0: capture off (invisible).
 */
@Composable
actual fun BlurOverlayHost(
    state: BlurOverlayState,
    modifier: Modifier,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val config = state.config
    DisposableEffect(state) {
        onDispose {
            state.isReady = false
        }
    }
    val backdropStack = LocalAndroidBackdropStack.current
    val currentView = LocalView.current
    val activity = LocalContext.current.findActivity()
    val platformCaptureSources = LocalBlurOverlayPlatformContext.current.captureSources
    val injectedCaptureSources = remember(platformCaptureSources) {
        platformCaptureSources.map { BackdropCaptureSource(it.view, it.window) }
    }
    val registeredCapture = registeredBackdropCapture(
        activity = activity,
        currentView = currentView,
    )
    val automaticCaptureSources = registeredCapture?.sources.orEmpty()
    val rememberedBackdropLayer = remember { AndroidBackdropLayer() }
    val backdropLayer = backdropStack?.currentLayer ?: rememberedBackdropLayer
    DisposableEffect(backdropLayer) {
        backdropLayer.claim()
        onDispose {
            backdropLayer.release()
        }
    }
    val stackedCapturePrefix = backdropStack?.capturePrefix
    val lowerReadiness = backdropStack?.lowerReadiness ?: registeredCapture?.lowerReadiness
    val lowerLayersReady = lowerReadiness?.isReady ?: true
    val explicitCaptureSources = injectedCaptureSources
        .ifEmpty {
            automaticCaptureSources.takeIf { stackedCapturePrefix == null }.orEmpty()
        }
        .takeIf { it.isNotEmpty() }
    val capturePrefix = stackedCapturePrefix.takeIf { explicitCaptureSources == null }

    Box(modifier = modifier) {
        background()

        // Mount the BlurView as soon as the overlay is enabled, even when
        // the radius is still zero or sub-pixel (the start of an enter
        // animation). This pays the EGL/shader/FBO cold-init cost on a
        // frame where the blur is invisible behind the dim scrim, so the
        // visible 0→target radius animation runs against a warm GL
        // context. Previously the BlurView only mounted once radius>0,
        // causing the *first visible* blur frame to also be the cold-init
        // frame — a 200–280 ms UI-thread stall that visually freezes the
        // dialog at "no blur" then snaps to "fully blurred".
        if (state.isEnabled) {
            // Tier 1: API 31+ uniform blur (any blend mode) — only when the caller
            // provides an explicit background composable. BlurOverlay passes
            // background = {} (empty) because it blurs the DecorView behind it,
            // which requires the Kawase capture pipeline. RenderEffect can only blur
            // the composable's OWN content, not what's behind it.
            //
            // We detect "has background" by checking if background is NOT the
            // empty lambda singleton that BlurOverlay passes. Since Kotlin lambda
            // identity is unreliable, we use a marker: BlurOverlay always passes
            // the same empty lambda reference stored in EmptyBackground.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                config.gradient == null &&
                background !== EmptyBackground
            ) {
                SideEffect {
                    backdropLayer.captureView = null
                    backdropLayer.isReady = true
                    state.isReady = true
                }
                RenderEffectBlurOverlay(state, background)
                ContentOverlay(
                    blurView = null,
                    backdropStack = backdropStack,
                    content = content,
                )
                return@Box
            }

            val gradient = config.gradient
            val isBackdropMode = background === EmptyBackground
            val pipelineStrategy = if (
                backdropStack != null ||
                (isBackdropMode && explicitCaptureSources != null)
            ) {
                BlurPipelineStrategy.LEGACY
            } else {
                BlurPipelineStrategy.AUTO
            }
            val androidBlurConfig = remember(config, pipelineStrategy) {
                AndroidGradientMapper.toBlurConfig(config).copy(
                    pipelineStrategy = pipelineStrategy,
                )
            }

            if (gradient != null) {
                val context = LocalContext.current
                val blurView = remember {
                    VariableBlurView(context).apply {
                        if (isBackdropMode) {
                            setBackdropCapture(capturePrefix, explicitCaptureSources)
                        }
                        setBlurEnabled(lowerReadiness == null)
                        setIsLive(false)
                    }
                }
                val androidBlurGradient = remember(gradient, config.radius) {
                    AndroidGradientMapper.toBlurGradient(gradient, config.radius)
                }
                var firstFrameReady by remember(blurView) {
                    mutableStateOf(blurView.hasFirstFrame())
                }
                val overlayReady = firstFrameReady || config.radius < 1f

                SideEffect {
                    val captureView = blurView.takeIf { config.radius >= 1f }
                    if (backdropLayer.captureView !== captureView) {
                        backdropLayer.captureView = captureView
                        backdropLayer.isReady = blurView.hasFirstFrame()
                    }
                    if (captureView == null) backdropLayer.isReady = true
                    state.isReady = overlayReady
                }
                DisposableEffect(blurView, backdropStack != null, state) {
                    blurView.setOnFirstFrameListener {
                        firstFrameReady = true
                        backdropLayer.isReady = true
                    }
                    blurView.setOnFrameLostListener {
                        firstFrameReady = false
                        backdropLayer.isReady = backdropLayer.captureView == null
                        state.isReady = state.config.radius < 1f
                    }
                    onDispose {
                        blurView.setOnFirstFrameListener(null)
                        blurView.setOnFrameLostListener(null)
                        blurView.setIsLive(false)
                        backdropLayer.captureView = null
                        backdropLayer.isReady = true
                    }
                }
                SideEffect {
                    if (isBackdropMode) {
                        blurView.setBackdropCapture(capturePrefix, explicitCaptureSources)
                    }
                }

                AndroidView(
                    factory = { blurView },
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = if (firstFrameReady) 1f else 0f },
                    update = { view ->
                        view.setBlurGradient(androidBlurGradient)
                        view.setBlurConfig(androidBlurConfig)
                        view.alpha = state.alpha
                    },
                )

                LaunchedEffect(state, config.isLive, blurView) {
                    var previousAlpha = state.alpha
                    snapshotFlow { state.alpha }.collect { alpha ->
                        val fadingOut = alpha < previousAlpha
                        blurView.setIsLive(config.isLive && alpha > 0f && !fadingOut)
                        previousAlpha = alpha
                    }
                }

                LaunchedEffect(lowerReadiness, lowerLayersReady) {
                    if (lowerReadiness == null) return@LaunchedEffect
                    withFrameNanos {}
                    val ready = lowerReadiness.isReady
                    blurView.setBlurEnabled(ready)
                    if (ready) blurView.requestSingleUpdate()
                }

                // Honor BlurOverlayState.requestUpdate() in non-live mode:
                // increment of updateTrigger forces a one-shot recapture.
                LaunchedEffect(state.updateTrigger) {
                    if (state.updateTrigger != 0L) blurView.requestSingleUpdate()
                }

                ContentOverlay(
                    blurView = blurView,
                    backdropStack = backdropStack,
                    content = content,
                )
            } else {
                val context = LocalContext.current
                val blurView = remember {
                    // Backdrop blur captures the DecorView every frame. The
                    // RenderNode path's syncAndDraw blocks the main thread for
                    // seconds on complex hierarchies — use Kawase instead.
                    (if (isBackdropMode) BlurView.kawase(context) else BlurView(context)).apply {
                        if (isBackdropMode) {
                            setBackdropCapture(capturePrefix, explicitCaptureSources)
                        }
                        setBlurEnabled(lowerReadiness == null)
                        setIsLive(false)
                    }
                }
                var firstFrameReady by remember(blurView) {
                    mutableStateOf(blurView.hasFirstFrame())
                }
                val overlayReady = firstFrameReady || config.radius < 1f

                SideEffect {
                    val captureView = blurView.takeIf { config.radius >= 1f }
                    if (backdropLayer.captureView !== captureView) {
                        backdropLayer.captureView = captureView
                        backdropLayer.isReady = blurView.hasFirstFrame()
                    }
                    if (captureView == null) backdropLayer.isReady = true
                    state.isReady = overlayReady
                }
                DisposableEffect(blurView, backdropStack != null, state) {
                    blurView.setOnFirstFrameListener {
                        firstFrameReady = true
                        backdropLayer.isReady = true
                    }
                    blurView.setOnFrameLostListener {
                        firstFrameReady = false
                        backdropLayer.isReady = backdropLayer.captureView == null
                        state.isReady = state.config.radius < 1f
                    }
                    onDispose {
                        blurView.setOnFirstFrameListener(null)
                        blurView.setOnFrameLostListener(null)
                        blurView.setIsLive(false)
                        backdropLayer.captureView = null
                        backdropLayer.isReady = true
                    }
                }
                SideEffect {
                    if (isBackdropMode) {
                        blurView.setBackdropCapture(capturePrefix, explicitCaptureSources)
                    }
                }

                AndroidView(
                    factory = { blurView },
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = if (firstFrameReady) 1f else 0f },
                    update = { view ->
                        view.setBlurConfig(androidBlurConfig)
                        view.alpha = state.alpha
                    },
                )

                LaunchedEffect(state, config.isLive, blurView) {
                    var previousAlpha = state.alpha
                    snapshotFlow { state.alpha }.collect { alpha ->
                        val fadingOut = alpha < previousAlpha
                        blurView.setIsLive(config.isLive && alpha > 0f && !fadingOut)
                        previousAlpha = alpha
                    }
                }

                LaunchedEffect(lowerReadiness, lowerLayersReady) {
                    if (lowerReadiness == null) return@LaunchedEffect
                    withFrameNanos {}
                    val ready = lowerReadiness.isReady
                    blurView.setBlurEnabled(ready)
                    if (ready) blurView.requestSingleUpdate()
                }

                LaunchedEffect(state.updateTrigger) {
                    if (state.updateTrigger != 0L) blurView.requestSingleUpdate()
                }

                ContentOverlay(
                    blurView = blurView,
                    backdropStack = backdropStack,
                    content = content,
                )
            }
        } else {
            SideEffect {
                backdropLayer.captureView = null
                backdropLayer.isReady = true
                state.isReady = true
            }
            ContentOverlay(
                blurView = null,
                backdropStack = backdropStack,
                content = content,
            )
        }
    }
}

private fun BlurView.setBackdropCapture(
    prefix: BackdropCapturePrefix?,
    sources: List<BackdropCaptureSource>?,
) {
    if (prefix != null) setBlurredPrefix(prefix) else setBlurredWindows(sources)
}

private fun VariableBlurView.setBackdropCapture(
    prefix: BackdropCapturePrefix?,
    sources: List<BackdropCaptureSource>?,
) {
    if (prefix != null) setBlurredPrefix(prefix) else setBlurredWindows(sources)
}

/**
 * API 31+ blur overlay using RenderEffect on graphicsLayer.
 *
 * Eliminates all CPU-GPU roundtrips: no software capture, no OpenGL, no readback.
 * The background composable is rendered inside a graphicsLayer with a chained
 * RenderEffect that preserves the Kawase pipeline order:
 *
 *   capture (graphicsLayer content) → tint (ColorFilter) → blur (BlurEffect) → render
 *
 * - POST_BLUR tint: applied AFTER blur via createChainEffect(tint, blur)
 * - PRE_BLUR tint: applied BEFORE blur via createChainEffect(blur, tint)
 *   so the tint interacts with the actual background pixels before they
 *   are blurred.
 * - No tint: blur only.
 *
 * background() is invoked here AND as an unblurred sibling at the Box root.
 * When alpha=1, only the blurred version is visible. When alpha=0, the blurred
 * layer is invisible and the sharp background shows through.
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun RenderEffectBlurOverlay(
    state: BlurOverlayState,
    background: @Composable () -> Unit,
) {
    val config = state.config
    val blurRenderEffect = remember(config) {
        val radius = config.radius
        if (radius > 0f) {
            buildBlurRenderEffect(
                radius * config.downsampleFactor,
                config,
            )?.asComposeRenderEffect()
        } else {
            null
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                renderEffect = blurRenderEffect
                alpha = state.alpha
            }
    ) {
        background()
    }
}

/**
 * Builds a chained RenderEffect based on tintOrder:
 *
 * - No tint: blur only
 * - POST_BLUR: blur first, then tint on top → result = tint(blur(source))
 * - PRE_BLUR: tint first, then blur → result = blur(tint(source))
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun buildBlurRenderEffect(radius: Float, config: BlurOverlayConfig): RenderEffect? {
    val blurEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)

    val hasTint = config.tintColorValue != 0L
    if (!hasTint) return blurEffect

    val tintArgb = config.tintColorValue.toInt()
    val blendMode = AndroidBlendModeMapper.toAndroidBlendMode(config.tintBlendMode)
        ?: return blurEffect // fallback: no tint if blend mode unavailable

    val tintEffect = RenderEffect.createColorFilterEffect(
        BlendModeColorFilter(tintArgb, blendMode)
    )

    return if (config.tintOrder == TintOrder.PRE_BLUR) {
        // Pre-blur: tint first → then blur
        // result = blur(tint(source))
        RenderEffect.createChainEffect(blurEffect, tintEffect)
    } else {
        // Post-blur (default): blur first → tint on top
        // result = tint(blur(source))
        RenderEffect.createChainEffect(tintEffect, blurEffect)
    }
}

@Composable
private fun BoxScope.ContentOverlay(
    blurView: View?,
    backdropStack: AndroidBackdropStack?,
    content: @Composable () -> Unit,
) {
    val contentHolder = remember { AndroidContentHolder() }
    contentHolder.content = content
    contentHolder.stack = backdropStack

    AndroidView(
        factory = { ctx ->
            val container = FrameLayout(ctx)
            val composeView = ComposeView(ctx).apply {
                setContent {
                    CompositionLocalProvider(
                        LocalAndroidBackdropStack provides contentHolder.stack,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            contentHolder.content()
                        }
                    }
                }
            }
            container.addView(
                composeView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
            when (blurView) {
                is BlurView -> blurView.addExcludedView(container)
                is VariableBlurView -> blurView.addExcludedView(container)
            }
            container
        },
        modifier = Modifier.matchParentSize(),
        onRelease = { container ->
            when (blurView) {
                is BlurView -> blurView.removeExcludedView(container)
                is VariableBlurView -> blurView.removeExcludedView(container)
            }
        },
    )
}

private class AndroidContentHolder {
    var content: @Composable () -> Unit by mutableStateOf({})
    var stack: AndroidBackdropStack? by mutableStateOf(null)
}
