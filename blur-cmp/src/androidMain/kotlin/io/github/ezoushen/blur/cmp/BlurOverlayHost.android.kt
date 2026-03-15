package io.github.ezoushen.blur.cmp

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.example.blur.view.BlurView
import com.example.blur.view.VariableBlurView

@Composable
actual fun BlurOverlayHost(
    state: BlurOverlayState,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (!state.isEnabled) {
        content()
        return
    }

    val config = state.config

    Box(modifier = modifier) {
        // Blur view fills the entire parent, drawn first (behind content)
        if (config.gradient != null) {
            // Variable blur
            AndroidView(
                factory = { ctx ->
                    VariableBlurView(ctx).apply {
                        setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
                        setBlurGradient(
                            AndroidGradientMapper.toBlurGradient(config.gradient!!, config.radius),
                        )
                        setIsLive(config.isLive)
                    }
                },
                modifier = Modifier.matchParentSize(),
                update = { view ->
                    view.setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
                    config.gradient?.let {
                        view.setBlurGradient(
                            AndroidGradientMapper.toBlurGradient(it, config.radius),
                        )
                    }
                    view.setIsLive(config.isLive)
                },
            )
        } else {
            // Uniform blur
            AndroidView(
                factory = { ctx ->
                    BlurView(ctx).apply {
                        setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
                        setIsLive(config.isLive)
                    }
                },
                modifier = Modifier.matchParentSize(),
                update = { view ->
                    view.setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
                    view.setIsLive(config.isLive)
                },
            )
        }

        // Tint overlay for non-Normal blend modes
        if (config.tintColorValue != 0L && config.tintBlendMode != BlurBlendMode.Normal) {
            AndroidView(
                factory = { ctx -> TintOverlayView(ctx, config) },
                modifier = Modifier.matchParentSize(),
                update = { view -> view.updateConfig(config) },
            )
        }

        // Content drawn on top of blur
        content()
    }
}

/**
 * A view that draws a solid tint color with a specific blend mode.
 * Used when the blend mode is not Normal (blur-core's overlayColor only supports alpha blending).
 */
internal class TintOverlayView(
    context: android.content.Context,
    config: BlurOverlayConfig,
) : View(context) {

    private val paint = Paint()

    init {
        setWillNotDraw(false)
        updateConfig(config)
    }

    fun updateConfig(config: BlurOverlayConfig) {
        val color = config.tintColor ?: return
        paint.color = color.toArgb()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val blendMode = AndroidBlendModeMapper.toAndroidBlendMode(config.tintBlendMode)
            if (blendMode != null) {
                paint.blendMode = blendMode
            }
        } else {
            val porterDuff = AndroidBlendModeMapper.toPorterDuffMode(config.tintBlendMode)
            paint.xfermode = PorterDuffXfermode(porterDuff)
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
