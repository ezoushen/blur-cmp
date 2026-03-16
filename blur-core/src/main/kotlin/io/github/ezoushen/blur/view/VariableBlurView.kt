package io.github.ezoushen.blur.view

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Offset
import io.github.ezoushen.blur.BlurConfig
import io.github.ezoushen.blur.BlurGradient
import io.github.ezoushen.blur.R
import io.github.ezoushen.blur.VariableBlurController
import io.github.ezoushen.blur.capture.DecorViewCapture

/**
 * A View that provides variable blur effect where the blur radius varies
 * across the view based on a gradient.
 *
 * This enables effects like:
 * - Depth-of-field (sharp center, blurred edges)
 * - Directional blur (blur increasing from top to bottom)
 * - Spotlight effect (sharp focus point with surrounding blur)
 *
 * Usage:
 * ```xml
 * <io.github.ezoushen.blur.view.VariableBlurView
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:gradientType="radial"
 *     app:startRadius="0"
 *     app:endRadius="30"
 *     app:blurOverlayColor="#40FFFFFF" />
 * ```
 *
 * Or programmatically:
 * ```kotlin
 * val blurView = VariableBlurView(context).apply {
 *     setBlurGradient(BlurGradient.radialGradient(centerRadius = 0f, edgeRadius = 30f))
 *     setBlurEnabled(true)
 * }
 * ```
 *
 * @see BlurGradient
 * @see BlurConfig
 */
class VariableBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var blurConfig: BlurConfig = BlurConfig.Default
    private var blurGradient: BlurGradient = BlurGradient.verticalGradient(0f, 16f)
    private var isBlurEnabled: Boolean = true
    private var isLive: Boolean = true
    private var blurredView: View? = null

    private var blurController: VariableBlurController? = null
    private var decorView: View? = null

    // For tracking rendering state to prevent infinite recursion
    private var isRendering = false

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (isBlurEnabled && isLive && isShown) {
            val controller = blurController
            if (controller != null) {
                // Mark as dirty to capture updated background content
                controller.invalidate()
                if (controller.update()) {
                    // Blur was updated, invalidate to redraw with new blur
                    invalidate()
                }
            }
        }
        true
    }

    init {
        // CRITICAL: Enable onDraw() for ViewGroup
        setWillNotDraw(false)

        // Parse XML attributes
        attrs?.let { parseAttributes(context, it) }

        // Initialize blur controller
        blurController = VariableBlurController(context, blurConfig).apply {
            setGradient(blurGradient)
        }
    }

    private fun parseAttributes(context: Context, attrs: AttributeSet) {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.VariableBlurView)

        try {
            // Parse gradient type
            val gradientType = typedArray.getInt(
                R.styleable.VariableBlurView_gradientType,
                GRADIENT_TYPE_LINEAR
            )

            // Parse start and end radius
            val defaultRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f,
                context.resources.displayMetrics
            )
            val startRadius = typedArray.getDimension(
                R.styleable.VariableBlurView_startRadius,
                0f
            )
            val endRadius = typedArray.getDimension(
                R.styleable.VariableBlurView_endRadius,
                defaultRadius
            )

            // Parse gradient center (for radial/sweep)
            val centerX = typedArray.getFloat(
                R.styleable.VariableBlurView_gradientCenterX,
                0.5f
            )
            val centerY = typedArray.getFloat(
                R.styleable.VariableBlurView_gradientCenterY,
                0.5f
            )

            // Parse gradient radius (for radial)
            val gradientRadius = typedArray.getFloat(
                R.styleable.VariableBlurView_gradientRadius,
                1f
            )

            // Parse gradient angle (for linear)
            val angle = typedArray.getFloat(
                R.styleable.VariableBlurView_gradientAngle,
                90f // Default: top to bottom
            )

            // Parse downsample factor
            val downsample = typedArray.getFloat(
                R.styleable.VariableBlurView_blurDownsample,
                4f
            )

            // Parse overlay color
            val overlayColor = typedArray.getColor(
                R.styleable.VariableBlurView_blurOverlayColor,
                Color.TRANSPARENT
            ).takeIf { it != Color.TRANSPARENT }

            // Parse enabled state
            isBlurEnabled = typedArray.getBoolean(
                R.styleable.VariableBlurView_blurEnabled,
                true
            )

            // Parse live state
            isLive = typedArray.getBoolean(
                R.styleable.VariableBlurView_blurIsLive,
                true
            )

            // Create gradient based on type
            blurGradient = when (gradientType) {
                GRADIENT_TYPE_LINEAR -> BlurGradient.angledGradient(startRadius, endRadius, angle)
                GRADIENT_TYPE_RADIAL -> BlurGradient.radialGradient(
                    centerRadius = startRadius,
                    edgeRadius = endRadius,
                    center = Offset(centerX, centerY),
                    radius = gradientRadius
                )
                else -> BlurGradient.verticalGradient(startRadius, endRadius)
            }

            // Apply config
            blurConfig = BlurConfig(
                radius = endRadius, // Use max radius for config
                overlayColor = overlayColor,
                downsampleFactor = downsample
            )
        } finally {
            typedArray.recycle()
        }
    }

    /**
     * Sets the blur gradient for variable blur effect.
     *
     * @param gradient The gradient that defines how blur radius varies
     */
    fun setBlurGradient(gradient: BlurGradient) {
        blurGradient = gradient
        blurController?.setGradient(gradient)
        blurController?.invalidate()
        invalidate()
    }

    /**
     * Gets the current blur gradient.
     */
    fun getBlurGradient(): BlurGradient = blurGradient

    /**
     * Sets the blur configuration.
     *
     * @param config The blur configuration to apply.
     */
    fun setBlurConfig(config: BlurConfig) {
        blurConfig = config
        blurController?.setConfig(config)
        blurController?.invalidate()
        invalidate()
    }

    /**
     * Gets the current blur configuration.
     */
    fun getBlurConfig(): BlurConfig = blurConfig

    /**
     * Sets the overlay color with alpha.
     *
     * @param color The overlay color including alpha (e.g., 0x80FFFFFF).
     *              Use Color.TRANSPARENT or null for no overlay.
     */
    fun setOverlayColor(color: Int?) {
        blurConfig = blurConfig.copy(overlayColor = color?.takeIf { it != Color.TRANSPARENT })
        blurController?.setConfig(blurConfig)
        blurController?.invalidate()
        invalidate()
    }

    /**
     * Sets the downsample factor for performance tuning.
     *
     * @param factor The downsample factor (1-16). Higher = faster but lower quality.
     */
    fun setDownsampleFactor(factor: Float) {
        blurConfig = blurConfig.copy(downsampleFactor = factor.coerceIn(1f, 16f))
        blurController?.setConfig(blurConfig)
        blurController?.invalidate()
        invalidate()
    }

    /**
     * Enables or disables the blur effect.
     *
     * @param enabled True to enable blur, false to disable.
     */
    fun setBlurEnabled(enabled: Boolean) {
        if (isBlurEnabled != enabled) {
            isBlurEnabled = enabled
            invalidate()
        }
    }

    /**
     * Checks if the blur effect is enabled.
     */
    fun isBlurEnabled(): Boolean = isBlurEnabled

    /**
     * Sets whether the blur updates in real-time.
     *
     * When `live` is true (default), the blur updates every frame to reflect
     * changes in the background content. Set to false to save energy when
     * the background is static.
     *
     * @param live True to enable real-time updates, false to disable.
     */
    fun setIsLive(live: Boolean) {
        if (isLive != live) {
            isLive = live
            if (live) {
                // Trigger an update when going live
                blurController?.invalidate()
                invalidate()
            }
        }
    }

    /**
     * Checks if the blur is updating in real-time.
     */
    fun isLive(): Boolean = isLive

    /**
     * Register a view to exclude from blur capture.
     */
    private val pendingExcludedViews = mutableListOf<View>()

    fun addExcludedView(view: View) {
        val controller = blurController
        if (controller != null) {
            controller.addExcludedView(view)
        } else {
            pendingExcludedViews.add(view)
        }
    }

    fun removeExcludedView(view: View) {
        pendingExcludedViews.remove(view)
        blurController?.removeExcludedView(view)
    }

    private fun flushPendingExcludedViews() {
        val controller = blurController ?: return
        for (view in pendingExcludedViews) {
            controller.addExcludedView(view)
        }
        pendingExcludedViews.clear()
    }

    /**
     * Sets the view to blur. If not set, the view will blur everything behind it.
     *
     * @param view The view to use as the blur source.
     */
    fun setBlurredView(view: View) {
        blurredView = view
        blurController?.init(this, view)
        invalidate()
    }

    /**
     * Updates the blur effect manually.
     */
    fun updateBlur() {
        blurController?.invalidate()
        invalidate()
    }

    /**
     * Returns the name of the blur algorithm being used.
     */
    fun getAlgorithmName(): String = blurController?.getAlgorithmName() ?: "None"

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Find the DecorView
        decorView = getActivityDecorView()

        // Initialize blur controller with source view
        val source = blurredView ?: decorView
        if (source != null) {
            blurController?.setConfig(blurConfig)
            blurController?.setGradient(blurGradient)
            blurController?.init(this, source)

            // Forward any pending excluded views to the controller
            flushPendingExcludedViews()

            // Add pre-draw listener to update blur before each frame
            decorView?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
        }
    }

    override fun onDetachedFromWindow() {
        // Remove pre-draw listener
        decorView?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)

        // Release resources
        blurController?.release()

        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        blurController?.invalidate()
    }

    override fun draw(canvas: Canvas) {
        // Skip drawing during capture to prevent recursion.
        // Using return instead of throw to avoid corrupting Compose's RenderNode
        // recording state when BlurView is hosted inside a Compose render tree.
        if (blurController?.isCapturing() == true) {
            return
        }

        if (isRendering) {
            return
        }

        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        if (isBlurEnabled && !isInEditMode) {
            isRendering = true
            try {
                blurController?.draw(canvas)
            } finally {
                isRendering = false
            }
        }
        super.onDraw(canvas)
    }

    /**
     * Finds the DecorView of the Activity this view is attached to.
     */
    private fun getActivityDecorView(): View? {
        var ctx: Context? = context

        repeat(4) {
            when (ctx) {
                is Activity -> return (ctx as Activity).window.decorView
                is ContextWrapper -> ctx = (ctx as ContextWrapper).baseContext
                else -> return null
            }
        }

        return null
    }

    companion object {
        private const val GRADIENT_TYPE_LINEAR = 0
        private const val GRADIENT_TYPE_RADIAL = 1
    }
}
