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

            if (gradient != null) {
                val context = LocalContext.current
                val blurView = remember { VariableBlurView(context) }

                DisposableEffect(Unit) {
                    onDispose { blurView.setIsLive(false) }
                }

                // Single container for tint + blur layers; alpha applied here
                AndroidView(
                    factory = { ctx ->
                        val blurContainer = FrameLayout(ctx)
                        val matchParent = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                        if (hasTint && isNonNormalBlend) {
                            val tintView = TintOverlayView(ctx)
                            tintView.tag = "tint"
                            tintView.setTint(config.tintColorValue.toInt(), config.tintBlendMode)
                            blurContainer.addView(tintView, matchParent)
                        }
                        blurContainer.addView(blurView, matchParent)
                        blurContainer
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { container ->
                        val tintView = container.findViewWithTag<TintOverlayView>("tint")
                        if (hasTint && isNonNormalBlend) {
                            if (tintView == null) {
                                val matchParent = FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                )
                                val newTint = TintOverlayView(container.context)
                                newTint.tag = "tint"
                                container.addView(newTint, 0, matchParent)
                                newTint.setTint(config.tintColorValue.toInt(), config.tintBlendMode)
                            } else {
                                tintView.setTint(config.tintColorValue.toInt(), config.tintBlendMode)
                            }
                        } else {
                            tintView?.let { container.removeView(it) }
                        }
                        val blurGradient = AndroidGradientMapper.toBlurGradient(gradient, config.radius)
                        blurView.setBlurGradient(blurGradient)
                        blurView.setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
                        blurView.setIsLive(config.isLive)
                        container.alpha = state.alpha
                    },
                )

                // Content overlay — separate from blur container, no alpha
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

                // Single container for tint + blur layers; alpha applied here
                AndroidView(
                    factory = { ctx ->
                        val blurContainer = FrameLayout(ctx)
                        val matchParent = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                        if (hasTint && isNonNormalBlend) {
                            val tintView = TintOverlayView(ctx)
                            tintView.tag = "tint"
                            tintView.setTint(config.tintColorValue.toInt(), config.tintBlendMode)
                            blurContainer.addView(tintView, matchParent)
                        }
                        blurContainer.addView(blurView, matchParent)
                        blurContainer
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { container ->
                        val tintView = container.findViewWithTag<TintOverlayView>("tint")
                        if (hasTint && isNonNormalBlend) {
                            if (tintView == null) {
                                val matchParent = FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                )
                                val newTint = TintOverlayView(container.context)
                                newTint.tag = "tint"
                                container.addView(newTint, 0, matchParent)
                                newTint.setTint(config.tintColorValue.toInt(), config.tintBlendMode)
                            } else {
                                tintView.setTint(config.tintColorValue.toInt(), config.tintBlendMode)
                            }
                        } else {
                            tintView?.let { container.removeView(it) }
                        }
                        blurView.setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
                        blurView.setIsLive(config.isLive)
                        container.alpha = state.alpha
                    },
                )

                // Content overlay — separate from blur container, no alpha
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
 * blur capture. Uses a ContentHolder so content lambda changes propagate
 * across recompositions (the ComposeView is created once in factory but
 * reads from the mutable holder on each recomposition).
 */
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
