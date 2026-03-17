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
import io.github.ezoushen.blur.BlurConfig
import io.github.ezoushen.blur.BlurController
import io.github.ezoushen.blur.cmp.R
import io.github.ezoushen.blur.capture.DecorViewCapture

/**
 * A View that provides real-time blur effect on the content behind it,
 * similar to iOS's UIVisualEffectView.
 *
 * This view captures the content behind it and applies a blur effect in real-time.
 * It's designed to work with any View hierarchy and supports both static and
 * dynamic content.
 *
 * Usage:
 * ```xml
 * <io.github.ezoushen.blur.view.BlurView
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:blurRadius="16dp"
 *     app:blurOverlayColor="#80FFFFFF" />
 * ```
 *
 * Or programmatically:
 * ```kotlin
 * val blurView = BlurView(context).apply {
 *     setBlurConfig(BlurConfig(radius = 16f, overlayColor = 0x80FFFFFF.toInt()))
 *     setBlurEnabled(true)
 * }
 * ```
 *
 * @see BlurConfig
 */
class BlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var blurConfig: BlurConfig = BlurConfig.Default
    private var isBlurEnabled: Boolean = true
    private var isLive: Boolean = true
    private var blurredView: View? = null

    private var blurController: BlurController? = null
    private var decorView: View? = null

    // For tracking rendering state to prevent infinite recursion
    private var isRendering = false

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (isBlurEnabled && isLive && isShown) {
            val controller = blurController
            if (controller != null) {
                controller.invalidate()
                if (controller.update()) {
                    invalidate()
                }
            }
        }
        true
    }

    init {
        // CRITICAL: Enable onDraw() for ViewGroup
        // ViewGroup sets setWillNotDraw(true) by default, so onDraw() is never called
        // We need onDraw() to draw the blurred content
        setWillNotDraw(false)

        // Parse XML attributes
        attrs?.let { parseAttributes(context, it) }

        // Initialize blur controller
        blurController = BlurController(context, blurConfig)
    }

    private fun parseAttributes(context: Context, attrs: AttributeSet) {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.BlurView)

        try {
            // Parse blur radius
            val defaultRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f,
                context.resources.displayMetrics
            )
            val radius = typedArray.getDimension(
                R.styleable.BlurView_blurRadius,
                defaultRadius
            )

            // Parse downsample factor
            val downsample = typedArray.getFloat(
                R.styleable.BlurView_blurDownsample,
                4f
            )

            // Parse overlay color (includes alpha)
            val overlayColor = typedArray.getColor(
                R.styleable.BlurView_blurOverlayColor,
                Color.TRANSPARENT
            ).takeIf { it != Color.TRANSPARENT }

            // Parse enabled state
            isBlurEnabled = typedArray.getBoolean(
                R.styleable.BlurView_blurEnabled,
                true
            )

            // Parse live state
            isLive = typedArray.getBoolean(
                R.styleable.BlurView_blurIsLive,
                true
            )

            // Apply parsed config
            blurConfig = BlurConfig(
                radius = radius,
                overlayColor = overlayColor,
                downsampleFactor = downsample
            )
        } finally {
            typedArray.recycle()
        }
    }

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
     *
     * @return The current blur configuration.
     */
    fun getBlurConfig(): BlurConfig = blurConfig

    /**
     * Sets the blur radius.
     *
     * @param radius The blur radius in pixels (0-25).
     */
    fun setBlurRadius(radius: Float) {
        blurConfig = blurConfig.copy(radius = radius.coerceIn(0f, 25f))
        blurController?.setConfig(blurConfig)
        blurController?.invalidate()
        invalidate()
    }

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
     *
     * @return True if blur is enabled, false otherwise.
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
     *
     * @return True if real-time updates are enabled, false otherwise.
     */
    fun isLive(): Boolean = isLive

    // Pending excluded views (stored until controller is ready)
    private val pendingExcludedViews = mutableListOf<View>()

    /**
     * Register a view to exclude from blur capture.
     * The view is hidden during capture to prevent its content from appearing
     * in the blurred bitmap (which causes glow artifacts when the view is also
     * drawn sharp on top of the blur).
     */
    fun addExcludedView(view: View) {
        val controller = blurController
        if (controller != null) {
            controller.addExcludedView(view)
        } else {
            pendingExcludedViews.add(view)
        }
    }

    /**
     * Unregister a previously excluded view.
     */
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
     * Updates the blur effect. Call this when the content behind the view changes
     * and real-time updates are disabled for performance reasons.
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
            // Skip draw if we're already rendering (shouldn't happen)
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

        // Unwrap ContextWrapper to find Activity
        repeat(4) {
            when (ctx) {
                is Activity -> return (ctx as Activity).window.decorView
                is ContextWrapper -> ctx = (ctx as ContextWrapper).baseContext
                else -> return null
            }
        }

        return null
    }
}
