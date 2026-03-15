package io.github.ezoushen.blur.cmp

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.example.blur.view.BlurView
import com.example.blur.view.VariableBlurView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
actual fun BlurOverlayHost(
    state: BlurOverlayState,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val activity = LocalContext.current as? Activity ?: run {
        // Fallback: render content without blur if not in an Activity
        content()
        return
    }

    val decorView = activity.window.decorView as? ViewGroup ?: run {
        content()
        return
    }

    val currentState by rememberUpdatedState(state)

    // Manage the native blur view lifecycle
    DisposableEffect(decorView) {
        val blurContainer = createBlurContainer(decorView, currentState.config)
        decorView.addView(
            blurContainer,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        onDispose {
            decorView.removeView(blurContainer)
        }
    }

    // React to config changes
    LaunchedEffect(Unit) {
        snapshotFlow { currentState.config }
            .distinctUntilChanged()
            .collectLatest { config ->
                val blurContainer = findBlurContainer(decorView) ?: return@collectLatest
                updateBlurContainer(blurContainer, config)
            }
    }

    // React to enabled state
    LaunchedEffect(Unit) {
        snapshotFlow { currentState.isEnabled }
            .distinctUntilChanged()
            .collectLatest { enabled ->
                val blurContainer = findBlurContainer(decorView) ?: return@collectLatest
                blurContainer.visibility = if (enabled) View.VISIBLE else View.GONE
            }
    }

    // React to manual update trigger
    LaunchedEffect(Unit) {
        snapshotFlow { currentState.updateTrigger }
            .distinctUntilChanged()
            .collectLatest {
                val blurContainer = findBlurContainer(decorView) ?: return@collectLatest
                val blurView = blurContainer.getChildAt(0)
                when (blurView) {
                    is BlurView -> blurView.updateBlur()
                    is VariableBlurView -> blurView.updateBlur()
                }
            }
    }

    // Compose content renders on top (in the normal Compose layer, above DecorView index 0)
    content()
}

private const val BLUR_CONTAINER_TAG = "blur_cmp_container"

private fun createBlurContainer(decorView: ViewGroup, config: BlurOverlayConfig): FrameLayout {
    val context = decorView.context
    val container = FrameLayout(context).apply {
        tag = BLUR_CONTAINER_TAG
    }

    val blurView: View = if (config.gradient != null) {
        VariableBlurView(context).apply {
            setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
            setBlurGradient(AndroidGradientMapper.toBlurGradient(config.gradient, config.radius))
            setIsLive(config.isLive)
            setBlurredView(decorView)
        }
    } else {
        BlurView(context).apply {
            setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
            setIsLive(config.isLive)
            setBlurredView(decorView)
        }
    }

    container.addView(
        blurView,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ),
    )

    // Add tint overlay with blend mode if needed
    if (config.tintColorValue != 0L && config.tintBlendMode != BlurBlendMode.Normal) {
        val tintOverlay = TintOverlayView(context, config)
        container.addView(
            tintOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    return container
}

private fun findBlurContainer(decorView: ViewGroup): FrameLayout? {
    for (i in 0 until decorView.childCount) {
        val child = decorView.getChildAt(i)
        if (child.tag == BLUR_CONTAINER_TAG) return child as? FrameLayout
    }
    return null
}

private fun updateBlurContainer(container: FrameLayout, config: BlurOverlayConfig) {
    val blurView = container.getChildAt(0)
    val decorView = container.parent as? ViewGroup ?: return

    when {
        // Switching from uniform to variable requires recreation
        config.gradient != null && blurView is BlurView -> {
            container.removeAllViews()
            val newBlur = VariableBlurView(container.context).apply {
                setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
                setBlurGradient(AndroidGradientMapper.toBlurGradient(config.gradient, config.radius))
                setIsLive(config.isLive)
                setBlurredView(decorView)
            }
            container.addView(
                newBlur,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        config.gradient == null && blurView is VariableBlurView -> {
            container.removeAllViews()
            val newBlur = BlurView(container.context).apply {
                setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
                setIsLive(config.isLive)
                setBlurredView(decorView)
            }
            container.addView(
                newBlur,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        blurView is BlurView -> {
            blurView.setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
            blurView.setIsLive(config.isLive)
        }

        blurView is VariableBlurView -> {
            blurView.setBlurConfig(AndroidGradientMapper.toBlurConfig(config))
            if (config.gradient != null) {
                blurView.setBlurGradient(
                    AndroidGradientMapper.toBlurGradient(config.gradient, config.radius),
                )
            }
            blurView.setIsLive(config.isLive)
        }
    }

    // Update or add/remove tint overlay
    updateTintOverlay(container, config)
}

private fun updateTintOverlay(container: FrameLayout, config: BlurOverlayConfig) {
    val existingTint = if (container.childCount > 1) container.getChildAt(1) else null

    if (config.tintColorValue != 0L && config.tintBlendMode != BlurBlendMode.Normal) {
        if (existingTint is TintOverlayView) {
            existingTint.updateConfig(config)
        } else {
            existingTint?.let { container.removeView(it) }
            container.addView(
                TintOverlayView(container.context, config),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    } else {
        existingTint?.let { container.removeView(it) }
    }
}

/**
 * A view that draws a solid tint color with a specific blend mode.
 * Used when the blend mode is not Normal (blur-core's overlayColor only supports alpha blending).
 */
private class TintOverlayView(
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
