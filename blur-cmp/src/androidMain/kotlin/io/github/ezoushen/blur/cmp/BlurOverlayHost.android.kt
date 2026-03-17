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
