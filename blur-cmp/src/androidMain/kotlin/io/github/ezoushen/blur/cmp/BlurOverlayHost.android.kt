package io.github.ezoushen.blur.cmp

import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
 * Non-Normal blend mode tints are applied to the captured bitmap BEFORE blur
 * via BlurConfig.preBlurTintColor, so the blend mode interacts with actual
 * background pixels. Normal blend mode tints are applied AFTER blur via
 * BlurConfig.overlayColor.
 *
 * Alpha is applied to the BlurView's view.alpha for smooth fade transitions.
 * ContentOverlay is excluded from blur capture and has no alpha applied.
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
                        // Stop capture during alpha transitions: sourceView.draw()
                        // clears View dirty flags, freezing Compose animations.
                        // The blur holds its last captured frame while fading.
                        view.setIsLive(config.isLive && state.alpha == 1f)
                        view.alpha = state.alpha
                    },
                )

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
                        // Stop capture during alpha transitions: sourceView.draw()
                        // clears View dirty flags, freezing Compose animations.
                        // The blur holds its last captured frame while fading.
                        view.setIsLive(config.isLive && state.alpha == 1f)
                        view.alpha = state.alpha
                    },
                )

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
