package io.github.ezoushen.blur.view

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.ChecksSdkIntAtLeast
import io.github.ezoushen.blur.BlurConfig
import io.github.ezoushen.blur.BlurController
import io.github.ezoushen.blur.RenderNodeBlurController
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
 *     app:blurTintColor="#80FFFFFF" />
 * ```
 *
 * Or programmatically:
 * ```kotlin
 * val blurView = BlurView(context).apply {
 *     setBlurConfig(BlurConfig(radius = 16f, tintColor = 0x80FFFFFF.toInt()))
 *     setBlurEnabled(true)
 * }
 * ```
 *
 * @see BlurConfig
 */
class BlurView private constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    private val useRenderNode: Boolean,
) : FrameLayout(context, attrs, defStyleAttr) {

    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : this(context, attrs, defStyleAttr, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    private var blurConfig: BlurConfig = BlurConfig.Default
    private var isBlurEnabled: Boolean = true
    private var isLive: Boolean = true
    private var blurredView: View? = null

    private var blurController: BlurController? = null
    private var renderNodeController: RenderNodeBlurController? = null

    companion object {
        /**
         * Creates a BlurView that uses the Kawase/OpenGL pipeline regardless
         * of API level. For backdrop blur where the RenderNode path's
         * `syncAndDraw` blocks the main thread on complex view hierarchies.
         */
        fun kawase(context: Context): BlurView =
            BlurView(context, null, 0, useRenderNode = false)
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    private fun canUseRenderNode(): Boolean =
        useRenderNode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private var decorView: View? = null

    // For tracking rendering state to prevent infinite recursion
    private var isRendering = false
    private var hasFirstFrame = false
    private var blurTextureView: TextureView? = null
    private var blurSurface: Surface? = null

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            blurSurface?.release()
            blurSurface = Surface(st)
            blurController?.setOutputSurface(blurSurface, w, h)
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            blurSurface?.release()
            blurSurface = Surface(st)
            blurController?.setOutputSurface(blurSurface, w, h)
        }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            blurController?.setOutputSurface(null, 0, 0)
            blurSurface?.release()
            blurSurface = null
            return true
        }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (isBlurEnabled) {
            val needsFirstFrame = !hasFirstFrame
            if (canUseRenderNode()) {
                val controller = renderNodeController
                if (controller != null && (needsFirstFrame || (isLive && isShown))) {
                    if (isLive) {
                        controller.invalidate()
                    }
                    if (controller.update()) {
                        onFirstFrameAvailable()
                    }
                }
            } else {
                val controller = blurController
                if (controller != null) {
                    val hasPendingWork = controller.hasPendingDirty()
                    if (needsFirstFrame || hasPendingWork || (isLive && isShown)) {
                        if (isLive) {
                            controller.markContentDirty()
                        }
                        if (controller.update()) {
                            onFirstFrameAvailable()
                        }
                    }
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
        if (canUseRenderNode()) {
            renderNodeController = RenderNodeBlurController()
        } else {
            blurController = BlurController(context, blurConfig)
            // TextureView output eliminates glReadPixels (~8ms on PowerVR/Mediatek GPUs).
            // Produces harmless FrameEvents log warnings (non-HWUI EGL consumer).
            blurTextureView = TextureView(context).also { tv ->
                tv.isOpaque = false
                tv.surfaceTextureListener = surfaceTextureListener
                // Hide the TextureView's first paint until the GL pipeline
                // has rendered a frame to its `SurfaceTexture`. Before that
                // first frame lands the underlying surface is opaque on
                // some devices (Adreno / Mali), which reads as a black
                // flash on cold mount of a backdrop blur. Visibility is
                // flipped back to `VISIBLE` from [onFirstFrameAvailable]
                // — `INVISIBLE` (not `GONE`) keeps the view in layout so
                // its `SurfaceTexture` is still allocated when the view
                // attaches to the window.
                tv.visibility = INVISIBLE
                addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        }
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

            // Parse tint color (prefer blurTintColor, fall back to blurOverlayColor)
            val tintColor = typedArray.getColor(
                R.styleable.BlurView_blurTintColor,
                Color.TRANSPARENT
            ).takeIf { it != Color.TRANSPARENT }
                ?: typedArray.getColor(
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
                tintColor = tintColor,
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
        if (canUseRenderNode()) {
            renderNodeController?.setConfig(config)
        } else {
            blurController?.setConfig(config)
        }
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
        if (canUseRenderNode()) {
            renderNodeController?.setConfig(blurConfig)
        } else {
            blurController?.setConfig(blurConfig)
        }
        invalidate()
    }

    /**
     * Sets the tint color with alpha.
     *
     * @param color The tint color including alpha (e.g., 0x80FFFFFF).
     *              Use Color.TRANSPARENT or null for no tint.
     */
    fun setTintColor(color: Int?) {
        blurConfig = blurConfig.copy(tintColor = color?.takeIf { it != Color.TRANSPARENT })
        if (canUseRenderNode()) {
            renderNodeController?.setConfig(blurConfig)
        } else {
            blurController?.setConfig(blurConfig)
        }
        invalidate()
    }

    /**
     * Sets the downsample factor for performance tuning.
     *
     * @param factor The downsample factor (1-16). Higher = faster but lower quality.
     */
    fun setDownsampleFactor(factor: Float) {
        blurConfig = blurConfig.copy(downsampleFactor = factor.coerceIn(1f, 16f))
        if (canUseRenderNode()) {
            renderNodeController?.setConfig(blurConfig)
        } else {
            blurController?.setConfig(blurConfig)
        }
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
     * Listener notified the first time a blur frame has been rendered to
     * the GL output surface (TextureView path) or the RenderNode. Hosts
     * use this to gate the visibility of any compose layer wrapping the
     * BlurView until the cold-mount path has populated the GL surface —
     * before that point a backdrop-blur view paints opaque on some
     * devices, which reads as a black flash on a fresh menu/dialog.
     */
    fun interface OnFirstFrameListener {
        fun onFirstFrame()
    }

    private var firstFrameListener: OnFirstFrameListener? = null

    fun setOnFirstFrameListener(listener: OnFirstFrameListener?) {
        firstFrameListener = listener
        if (hasFirstFrame) listener?.onFirstFrame()
    }

    fun hasFirstFrame(): Boolean = hasFirstFrame

    private fun onFirstFrameAvailable() {
        if (hasFirstFrame) return
        hasFirstFrame = true
        // Reveal the GL output surface now that a frame is rendered to it
        // (see the `INVISIBLE` initialiser on [blurTextureView]).
        blurTextureView?.visibility = VISIBLE
        invalidate()
        firstFrameListener?.onFirstFrame()
    }

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
                blurController?.markContentDirty()
                if (canUseRenderNode()) {
                    renderNodeController?.invalidate()
                }
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

    /**
     * Request a single recapture+blur on the next frame regardless of the
     * isLive flag. Used by non-live (one-shot) consumers — e.g. dialog
     * overlays that capture once on enter and then composite the cached
     * blur for the lifetime of the dialog.
     */
    fun requestSingleUpdate() {
        blurController?.markContentDirty()
        if (canUseRenderNode()) {
            renderNodeController?.invalidate()
        }
        invalidate()
    }

    // Pending excluded views (stored until controller is ready)
    private val pendingExcludedViews = mutableListOf<View>()

    /**
     * Register a view to exclude from blur capture.
     * The view is hidden during capture to prevent its content from appearing
     * in the blurred bitmap (which causes glow artifacts when the view is also
     * drawn sharp on top of the blur).
     */
    fun addExcludedView(view: View) {
        if (canUseRenderNode()) {
            renderNodeController?.addExcludedView(view)
        } else {
            val controller = blurController
            if (controller != null) {
                controller.addExcludedView(view)
            } else {
                pendingExcludedViews.add(view)
            }
        }
    }

    /**
     * Unregister a previously excluded view.
     */
    fun removeExcludedView(view: View) {
        pendingExcludedViews.remove(view)
        if (canUseRenderNode()) {
            renderNodeController?.removeExcludedView(view)
        } else {
            blurController?.removeExcludedView(view)
        }
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
        if (canUseRenderNode()) {
            renderNodeController?.init(this, view)
        } else {
            blurController?.init(this, view)
        }
        invalidate()
    }

    /**
     * Updates the blur effect. Call this when the content behind the view changes
     * and real-time updates are disabled for performance reasons.
     */
    fun updateBlur() {
        if (canUseRenderNode()) {
            renderNodeController?.invalidate()
        } else {
            blurController?.invalidate()
        }
        invalidate()
    }

    /**
     * Returns the name of the blur algorithm being used.
     */
    fun getAlgorithmName(): String {
        if (canUseRenderNode()) return renderNodeController?.getAlgorithmName() ?: "None"
        return blurController?.getAlgorithmName() ?: "None"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Find the DecorView
        decorView = getActivityDecorView()

        // Initialize blur controller with source view
        val source = blurredView ?: decorView
        if (source != null) {
            if (canUseRenderNode()) {
                renderNodeController?.setConfig(blurConfig)
                renderNodeController?.init(this, source)
                // Forward pending excluded views
                for (view in pendingExcludedViews) {
                    renderNodeController?.addExcludedView(view)
                }
                pendingExcludedViews.clear()
            } else {
                blurController?.setConfig(blurConfig)
                blurController?.init(this, source)
                flushPendingExcludedViews()
            }

            // Drive blur updates from the BlurView's own VTO rather than the
            // source view's. Backdrop blur is typically hosted in a separate
            // Window (Dialog/Popup); the Activity decor's ViewTreeObserver
            // does not dispatch preDraws when the Activity itself is
            // quiescent, which left blur frozen after HOME→resume cycles
            // (decor stays attached but emits no preDraws once Compose
            // settles). The local VTO ticks whenever this BlurView's host
            // window draws — which is every frame during animations and
            // on-demand otherwise — and DecorViewCapture re-snapshots the
            // source synchronously each tick, so live capture stays correct.
            viewTreeObserver?.addOnPreDrawListener(preDrawListener)
        }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
        hasFirstFrame = false

        blurController?.setOutputSurface(null)
        blurSurface?.release()
        blurSurface = null

        if (canUseRenderNode()) {
            renderNodeController?.release()
        } else {
            blurController?.release()
        }

        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (canUseRenderNode()) {
            renderNodeController?.invalidate()
        } else {
            blurController?.markContentDirty()
        }
    }

    override fun draw(canvas: Canvas) {
        // Skip drawing during capture to prevent recursion.
        // Using return instead of throw to avoid corrupting Compose's RenderNode
        // recording state when BlurView is hosted inside a Compose render tree.
        if (canUseRenderNode()) {
            if (renderNodeController?.isCapturing() == true) return
        } else {
            if (blurController?.isCapturing() == true) return
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
                if (canUseRenderNode()) {
                    renderNodeController?.draw(canvas)
                } else if (blurController?.hasOutputSurface() != true) {
                    // Only draw via canvas when TextureView output is not active.
                    // When TextureView is connected, the blur result is displayed
                    // via eglSwapBuffers directly to the TextureView surface.
                    blurController?.draw(canvas)
                }
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
                is Activity -> return ctx.window.decorView
                is ContextWrapper -> ctx = ctx.baseContext
                else -> return null
            }
        }

        return null
    }
}
