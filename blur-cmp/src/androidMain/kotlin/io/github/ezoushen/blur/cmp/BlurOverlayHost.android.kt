package io.github.ezoushen.blur.cmp

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.blur.view.BlurView
import com.example.blur.view.VariableBlurView

/**
 * Android BlurOverlayHost using blur-core's native BlurView/VariableBlurView.
 *
 * These views use DecorView capture + OpenGL Dual Kawase blur for GPU-accelerated
 * real-time backdrop blur. The StopCaptureException has been replaced with a
 * return-based skip to avoid Compose RenderNode corruption.
 *
 * For color dodge ordering: background → tint+dodge → blur is achieved by
 * using blur-core's overlay system for Normal blend, and a custom TintOverlayView
 * underneath the blur for non-Normal blend modes (the blur captures and blurs
 * the combined background + tint).
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
                // For non-Normal blend modes: apply tint with blend mode BEFORE blur.
                // This TintOverlayView sits between background and blur, so the blur
                // captures and blurs the combined (background + tint+dodge) result.
                AndroidView(
                    factory = { ctx -> TintOverlayView(ctx) },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.setTint(config.tintColorValue.toInt(), config.tintBlendMode)
                    },
                )
            }

            if (gradient != null) {
                // Variable blur using blur-core's VariableBlurView (OpenGL pyramid)
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
                        // Only pass overlay for Normal blend (non-Normal is handled by TintOverlayView above)
                        view.setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
                        view.setIsLive(config.isLive)
                    },
                )
            } else {
                // Uniform blur using blur-core's BlurView (OpenGL Dual Kawase)
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
            }

            // For Normal blend, tint is already applied by blur-core's overlayColor.
            // No additional tint layer needed.
        }

        // Controls on top (always sharp)
        content()
    }
}

/**
 * A lightweight Android View that draws a tint overlay with a specified blend mode.
 * Used for non-Normal blend modes where the tint must be applied BEFORE the blur
 * captures the content (background → tint+dodge → blur ordering).
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
