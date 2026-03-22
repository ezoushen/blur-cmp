package io.github.ezoushen.blur.cmp

import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
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

    Box(modifier = modifier) {
        background()

        if (state.isEnabled && config.radius > 0f) {
            // Tier 1: API 31+ uniform blur with Normal blend mode tint.
            // Bypasses entire View-based pipeline — pure Compose graphicsLayer.
            val hasNonNormalTint = config.tintColorValue != 0L &&
                config.tintBlendMode != BlurBlendMode.Normal
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                config.gradient == null && !hasNonNormalTint
            ) {
                RenderEffectBlurOverlay(state, config, background)
                content()
                return@Box
            }

            val gradient = config.gradient
            var prevAlpha by remember { mutableFloatStateOf(state.alpha) }

            if (gradient != null) {
                val context = LocalContext.current
                val blurView = remember { VariableBlurView(context) }

                DisposableEffect(Unit) {
                    onDispose { blurView.setIsLive(false) }
                }

                AndroidView(
                    factory = { blurView },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        val blurGradient = AndroidGradientMapper.toBlurGradient(gradient, config.radius)
                        view.setBlurGradient(blurGradient)
                        view.setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
                        view.alpha = state.alpha
                    },
                )

                LaunchedEffect(state.alpha) {
                    val fadingOut = state.alpha < prevAlpha
                    blurView.alpha = state.alpha
                    blurView.setIsLive(config.isLive && state.alpha > 0f && !fadingOut)
                    prevAlpha = state.alpha
                }

                ContentOverlay(blurView = blurView, content = content)
            } else {
                val context = LocalContext.current
                val blurView = remember { BlurView(context) }

                DisposableEffect(Unit) {
                    onDispose { blurView.setIsLive(false) }
                }

                AndroidView(
                    factory = { blurView },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
                        view.alpha = state.alpha
                    },
                )

                LaunchedEffect(state.alpha) {
                    val fadingOut = state.alpha < prevAlpha
                    blurView.alpha = state.alpha
                    blurView.setIsLive(config.isLive && state.alpha > 0f && !fadingOut)
                    prevAlpha = state.alpha
                }

                ContentOverlay(blurView = blurView, content = content)
            }
        } else {
            content()
        }
    }
}

/**
 * API 31+ blur overlay using RenderEffect on graphicsLayer.
 *
 * Eliminates all CPU-GPU roundtrips: no software capture, no OpenGL, no readback.
 * The background composable is rendered inside a graphicsLayer with RenderEffect blur.
 * Tint overlay is a separate sibling (outside the blur) so it stays crisp.
 *
 * background() is invoked here AND as an unblurred sibling at the Box root.
 * When alpha=1, only the blurred version is visible. When alpha=0, the blurred
 * layer is invisible and the sharp background shows through.
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun RenderEffectBlurOverlay(
    state: BlurOverlayState,
    config: BlurOverlayConfig,
    background: @Composable () -> Unit,
) {
    // Blurred background layer.
    // Lambda graphicsLayer: updates properties without recomposition.
    // Pattern matches BlurModifier.kt:41-48.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Guard: RenderEffect.createBlurEffect(0,0) crashes on API 31+
                // (Google Issue Tracker #241546169 — unfixed upstream).
                val r = config.radius
                renderEffect = if (r > 0f) {
                    RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                } else {
                    null
                }
                alpha = state.alpha
            }
    ) {
        background()
    }

    // Tint overlay — SEPARATE from the blurred graphicsLayer so tint stays crisp.
    // Matches BlurModifier.kt:50-55 pattern (tint drawn AFTER blur, on top).
    val tintColor = config.tintColor
    if (tintColor != null && state.alpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.alpha }
                .drawBehind { drawRect(tintColor) }
        )
    }
}

@Composable
private fun ContentOverlay(
    blurView: View,
    content: @Composable () -> Unit,
) {
    val contentHolder = remember { AndroidContentHolder() }
    contentHolder.content = content

    AndroidView(
        factory = { ctx ->
            val container = FrameLayout(ctx)
            val composeView = ComposeView(ctx).apply {
                setContent { contentHolder.content() }
            }
            container.addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )
            when (blurView) {
                is BlurView -> blurView.addExcludedView(container)
                is VariableBlurView -> blurView.addExcludedView(container)
            }
            container
        },
        modifier = Modifier.fillMaxSize(),
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
}
