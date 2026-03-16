package io.github.ezoushen.blur.cmp

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.blur.view.BlurView
import com.example.blur.view.VariableBlurView

/**
 * Android BlurOverlayHost using blur-core's native BlurView/VariableBlurView.
 *
 * Content is wrapped in a separate Android View container and registered as
 * "excluded" from blur capture. During DecorView capture, the content container
 * is set to INVISIBLE so its pixels don't appear in the captured bitmap.
 * This prevents the glow artifact (blurred text behind sharp text).
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
        // Layer 0: Background content
        background()

        if (state.isEnabled && config.radius > 0f) {
            val gradient = config.gradient
            val hasTint = config.tintColorValue != 0L
            val isNonNormalBlend = config.tintBlendMode != BlurBlendMode.Normal

            if (hasTint && isNonNormalBlend) {
                AndroidView(
                    factory = { ctx -> TintOverlayView(ctx) },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.setTint(config.tintColorValue.toInt(), config.tintBlendMode)
                    },
                )
            }

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
                        view.setIsLive(config.isLive)
                    },
                )

                // Content in a ComposeView wrapped by a FrameLayout, excluded from capture
                ContentOverlay(
                    blurView = blurView,
                    content = content,
                )
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
                        view.setIsLive(config.isLive)
                    },
                )

                // Content in a ComposeView wrapped by a FrameLayout, excluded from capture
                ContentOverlay(
                    blurView = blurView,
                    content = content,
                )
            }
        } else {
            // Blur disabled — render content directly
            content()
        }
    }
}

/**
 * Renders content in a separate Android View container that is excluded from
 * blur capture. The container is set to INVISIBLE during DecorView capture,
 * preventing content pixels from appearing in the blurred bitmap.
 */
@Composable
private fun ContentOverlay(
    blurView: View,
    content: @Composable () -> Unit,
) {
    AndroidView(
        factory = { ctx ->
            val container = FrameLayout(ctx)
            val composeView = ComposeView(ctx).apply {
                setContent { content() }
            }
            container.addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )
            // Register this container to be hidden during blur capture
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

/**
 * A lightweight Android View that draws a tint overlay with a specified blend mode.
 */
private class TintOverlayView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tintColor: Int = 0
    private var blendMode: BlurBlendMode = BlurBlendMode.Normal

    fun setTint(color: Int, mode: BlurBlendMode) {
        tintColor = color
        blendMode = mode
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (tintColor == 0) return
        paint.color = tintColor

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val androidMode = AndroidBlendModeMapper.toAndroidBlendMode(blendMode)
            if (androidMode != null) {
                paint.blendMode = androidMode
            }
        } else {
            paint.xfermode = android.graphics.PorterDuffXfermode(
                AndroidBlendModeMapper.toPorterDuffMode(blendMode)
            )
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
