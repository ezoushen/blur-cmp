package io.github.ezoushen.blur.algorithm

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.createBitmap
import io.github.ezoushen.blur.BlurGradient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ln

/**
 * GPU-accelerated variable blur implementation using OpenGL ES 2.0.
 *
 * This algorithm generates a blur pyramid (multiple blur levels) and then
 * composites the final result by sampling from appropriate levels based
 * on a gradient function. This enables per-pixel variable blur radius.
 *
 * **How it works:**
 * 1. Generate blur pyramid: Level 0 (original), Level 1 (~2px), Level 2 (~4px), etc.
 * 2. In the composite pass, for each pixel:
 *    - Calculate gradient factor (0-1) based on gradient type and position
 *    - Map gradient factor to blur radius
 *    - Map radius to pyramid level
 *    - Sample and interpolate between adjacent levels
 *
 * **Performance:** ~10-15% overhead compared to uniform blur due to:
 * - Storing all pyramid levels (instead of reusing framebuffers)
 * - Additional composite pass
 *
 * Supported API: 23+ (minSdk)
 */
class VariableOpenGLBlur : BlurAlgorithm {

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null
    private var windowSurface: EGLSurface? = null
    private var pendingSurface: android.view.Surface? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    private var downsampleProgram = 0
    private var upsampleProgram = 0
    private var compositeProgram = 0
    private var blitProgram = 0

    // Pyramid storage: each level stores the blur result at that iteration
    private var pyramidFramebuffers: IntArray? = null
    private var pyramidTextures: IntArray? = null

    // Working framebuffers for blur computation
    private var workFramebuffers: IntArray? = null
    private var workTextures: IntArray? = null

    // Output framebuffer at full resolution
    private var outputFramebuffer = 0
    private var outputTexture = 0

    private var outputBitmap: Bitmap? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var isInitialized = false

    private var currentGradient: BlurGradient? = null
    private var currentOverlayColor: Int? = null

    private val vertexBuffer: FloatBuffer by lazy {
        ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_VERTICES)
                position(0)
            }
    }

    private val texCoordBuffer: FloatBuffer by lazy {
        ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_TEX_COORDS)
                position(0)
            }
    }

    /**
     * Sets the blur gradient for variable blur effect.
     */
    fun setGradient(gradient: BlurGradient) {
        currentGradient = gradient
    }

    /**
     * Sets the overlay color for gradient-aware overlay blending.
     * The overlay alpha follows the blur gradient - more blur = more overlay.
     *
     * @param color The overlay color with alpha (e.g., 0x80FFFFFF), or null for no overlay.
     */
    fun setOverlayColor(color: Int?) {
        currentOverlayColor = color
    }

    override fun prepare(context: Context, width: Int, height: Int, radius: Float): Boolean {
        if (width <= 0 || height <= 0) return false

        try {
            if (!isInitialized) {
                if (!initEGL()) return false
                if (!initShaders()) return false
                isInitialized = true
                // Create deferred window surface if setSurface was called before EGL init
                val pending = pendingSurface
                if (pending != null && pending.isValid && windowSurface == null) {
                    createWindowSurface(pending)
                }
            }

            if (lastWidth != width || lastHeight != height) {
                if (!makeCurrent()) return false

                releaseFramebuffers()
                if (!initFramebuffers(width, height)) return false

                outputBitmap?.recycle()
                outputBitmap = createBitmap(width, height)

                lastWidth = width
                lastHeight = height
            }

            return true
        } catch (e: Exception) {
            release()
            return false
        }
    }

    override fun blur(input: Bitmap, radius: Float): Bitmap {
        val output = outputBitmap ?: return input
        val gradient = currentGradient ?: return input

        if (!isInitialized) return input

        // Handle radius=0: clear TextureView to transparent if active
        if (radius <= 0 || gradient.maxRadius <= 0) {
            if (hasOutputSurface()) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
                    GLES20.glClearColor(0f, 0f, 0f, 0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    EGL14.eglSwapBuffers(eglDisplay, windowSurface)
                } finally {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                }
                return output
            }
            val pixels = IntArray(input.width * input.height)
            input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
            output.setPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
            return output
        }

        try {
            makeCurrent()

            // Calculate required pyramid levels based on gradient's min and max radius
            // OPTIMIZATION: Only generate levels that will actually be sampled
            val minRadius = gradient.minRadius
            val maxRadius = gradient.maxRadius

            // minLevel: the lowest pyramid level needed (based on min blur radius)
            // If minRadius is 0, we need level 0 (original, no blur generation needed)
            // If minRadius > 0, calculate the level and we can skip lower levels
            val minLevel = if (minRadius <= 0.5f) 0 else
                (ln(minRadius / BASE_SIGMA) / LN_2).toInt().coerceIn(0, MAX_PYRAMID_LEVELS - 1)

            // maxLevel: the highest pyramid level needed (based on max blur radius)
            val maxLevel = if (maxRadius <= 0) 0 else
                ((ln(maxRadius / BASE_SIGMA) / LN_2).toInt() + 1).coerceIn(1, MAX_PYRAMID_LEVELS - 1)

            // Upload input to level 0
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pyramidTextures!![0])
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, input, 0)

            // Generate blur pyramid only for levels in the required range
            generateBlurPyramid(minLevel, maxLevel)

            // Composite final result using gradient (writes to outputFramebuffer)
            compositeWithGradient(gradient, minLevel, maxLevel)

            val useWindowSurface = hasOutputSurface()
            if (useWindowSurface) {
                // Blit the composite result to the window surface
                try {
                    EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    // Viewport must match the TextureView's actual size, not the
                    // downsampled blur size. The blit shader scales the texture.
                    val vw = if (surfaceWidth > 0) surfaceWidth else lastWidth
                    val vh = if (surfaceHeight > 0) surfaceHeight else lastHeight
                    GLES20.glViewport(0, 0, vw, vh)
                    blitTexture(outputTexture, 0, vw, vh)
                    EGL14.eglSwapBuffers(eglDisplay, windowSurface)
                } finally {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                }
                return output
            }

            // Fallback: glReadPixels
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
            val buffer = ByteBuffer.allocateDirect(lastWidth * lastHeight * 4)
                .order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, lastWidth, lastHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            buffer.rewind()
            output.copyPixelsFromBuffer(buffer)

            return output
        } catch (e: Exception) {
            return input
        }
    }

    /**
     * Generates the blur pyramid by computing blur at each level.
     * Each level stores the result of N iterations of Dual Kawase blur.
     *
     * Optimized:
     * - Uses GPU-only render-to-texture operations, no CPU readbacks.
     * - Only generates levels in [minLevel, maxLevel] range (lazy level generation).
     *
     * @param minLevel Minimum level to generate (skip lower levels for performance)
     * @param maxLevel Maximum level to generate
     */
    private fun generateBlurPyramid(minLevel: Int, maxLevel: Int) {
        val workFbs = workFramebuffers ?: return
        val workTexs = workTextures ?: return
        val pyramidFbs = pyramidFramebuffers ?: return
        val pyramidTexs = pyramidTextures ?: return

        // Only generate levels that will actually be sampled
        // If minLevel > 1, we can skip generating lower blur levels (performance optimization)
        val startLevel = minLevel.coerceAtLeast(1) // Level 0 is original, never generated

        for (level in startLevel..maxLevel) {
            // GPU-only blit: copy pyramid level 0 (original) to work texture 0
            // This uses render-to-texture instead of slow glReadPixels
            blitTexture(pyramidTexs[0], workFbs[0], lastWidth, lastHeight)

            // Perform blur with 'level' iterations
            performBlurPasses(workFbs, workTexs, level)

            // Copy result to pyramid level (also GPU-only)
            blitTexture(workTexs[0], pyramidFbs[level], lastWidth, lastHeight)
        }
    }

    /**
     * GPU-only texture copy using render-to-texture (blit shader).
     * Much faster than glReadPixels + glTexSubImage2D.
     */
    private fun blitTexture(srcTexture: Int, dstFramebuffer: Int, width: Int, height: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFramebuffer)
        GLES20.glViewport(0, 0, width, height)

        GLES20.glUseProgram(blitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexture)

        val textureLoc = GLES20.glGetUniformLocation(blitProgram, "uTexture")
        GLES20.glUniform1i(textureLoc, 0)

        drawQuad(blitProgram)
    }

    /**
     * Performs downsample and upsample blur passes.
     */
    private fun performBlurPasses(fbs: IntArray, texs: IntArray, iterations: Int) {
        var currentTexture = texs[0]
        var currentWidth = lastWidth
        var currentHeight = lastHeight

        val offset = 1.0f

        // Downsample passes
        GLES20.glUseProgram(downsampleProgram)
        for (i in 0 until iterations) {
            val targetWidth = (currentWidth / 2).coerceAtLeast(1)
            val targetHeight = (currentHeight / 2).coerceAtLeast(1)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbs[i + 1])
            GLES20.glViewport(0, 0, targetWidth, targetHeight)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexture)

            val halfPixelLoc = GLES20.glGetUniformLocation(downsampleProgram, "uHalfPixel")
            GLES20.glUniform2f(halfPixelLoc, offset * 0.5f / currentWidth, offset * 0.5f / currentHeight)

            drawQuad(downsampleProgram)

            currentTexture = texs[i + 1]
            currentWidth = targetWidth
            currentHeight = targetHeight
        }

        // Upsample passes
        GLES20.glUseProgram(upsampleProgram)
        for (i in iterations - 1 downTo 0) {
            val targetWidth = if (i == 0) lastWidth else (lastWidth shr i)
            val targetHeight = if (i == 0) lastHeight else (lastHeight shr i)

            val targetFb = if (i == 0) fbs[0] else fbs[i]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFb)
            GLES20.glViewport(0, 0, targetWidth, targetHeight)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexture)

            val halfPixelLoc = GLES20.glGetUniformLocation(upsampleProgram, "uHalfPixel")
            GLES20.glUniform2f(halfPixelLoc, offset * 0.5f / currentWidth, offset * 0.5f / currentHeight)

            drawQuad(upsampleProgram)

            currentTexture = texs[if (i == 0) 0 else i]
            currentWidth = targetWidth
            currentHeight = targetHeight
        }
    }

    /**
     * Composites the final result by sampling from pyramid levels based on gradient.
     *
     * @param gradient The blur gradient defining variable blur
     * @param minLevel Minimum pyramid level that was generated
     * @param maxLevel Maximum pyramid level that was generated
     */
    private fun compositeWithGradient(gradient: BlurGradient, minLevel: Int, maxLevel: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, lastWidth, lastHeight)

        GLES20.glUseProgram(compositeProgram)

        // Bind pyramid textures to texture units
        // We bind all levels 0 to maxLevel (level 0 is always the original)
        for (i in 0..maxLevel.coerceAtMost(MAX_PYRAMID_LEVELS - 1)) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pyramidTextures!![i])
            val loc = GLES20.glGetUniformLocation(compositeProgram, "uLevel$i")
            GLES20.glUniform1i(loc, i)
        }

        // Set aspect ratio for radial gradient correction
        val aspectRatioLoc = GLES20.glGetUniformLocation(compositeProgram, "uAspectRatio")
        val aspectRatio = lastWidth.toFloat() / lastHeight.toFloat()
        GLES20.glUniform1f(aspectRatioLoc, aspectRatio)

        // Set gradient parameters
        setGradientUniforms(gradient)

        // Set level range (use float for GLSL ES 2.0 compatibility)
        val minLevelLoc = GLES20.glGetUniformLocation(compositeProgram, "uMinLevel")
        val maxLevelLoc = GLES20.glGetUniformLocation(compositeProgram, "uMaxLevel")
        GLES20.glUniform1f(minLevelLoc, minLevel.toFloat())
        GLES20.glUniform1f(maxLevelLoc, maxLevel.toFloat())

        // Set overlay color uniforms
        setOverlayUniforms()

        drawQuad(compositeProgram)

        // Reset to texture unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /**
     * Sets overlay-related uniforms in the composite shader.
     */
    private fun setOverlayUniforms() {
        val hasOverlayLoc = GLES20.glGetUniformLocation(compositeProgram, "uHasOverlay")
        val overlayColorLoc = GLES20.glGetUniformLocation(compositeProgram, "uOverlayColor")

        val overlayColor = currentOverlayColor
        if (overlayColor != null) {
            GLES20.glUniform1f(hasOverlayLoc, 1.0f)

            // Extract RGBA components (0-1 range)
            val a = ((overlayColor shr 24) and 0xFF) / 255f
            val r = ((overlayColor shr 16) and 0xFF) / 255f
            val g = ((overlayColor shr 8) and 0xFF) / 255f
            val b = (overlayColor and 0xFF) / 255f

            GLES20.glUniform4f(overlayColorLoc, r, g, b, a)
        } else {
            GLES20.glUniform1f(hasOverlayLoc, 0.0f)
            GLES20.glUniform4f(overlayColorLoc, 0f, 0f, 0f, 0f)
        }
    }

    /**
     * Sets gradient-related uniforms in the composite shader.
     */
    private fun setGradientUniforms(gradient: BlurGradient) {
        val typeLoc = GLES20.glGetUniformLocation(compositeProgram, "uGradientType")
        val startLoc = GLES20.glGetUniformLocation(compositeProgram, "uGradientStart")
        val endLoc = GLES20.glGetUniformLocation(compositeProgram, "uGradientEnd")
        val centerLoc = GLES20.glGetUniformLocation(compositeProgram, "uGradientCenter")
        val radiusLoc = GLES20.glGetUniformLocation(compositeProgram, "uGradientRadius")
        val startRadiusLoc = GLES20.glGetUniformLocation(compositeProgram, "uStartRadius")
        val endRadiusLoc = GLES20.glGetUniformLocation(compositeProgram, "uEndRadius")
        val stopCountLoc = GLES20.glGetUniformLocation(compositeProgram, "uStopCount")

        when (gradient) {
            is BlurGradient.Linear -> {
                GLES20.glUniform1f(typeLoc, GRADIENT_TYPE_LINEAR.toFloat())
                GLES20.glUniform2f(startLoc, gradient.start.x, gradient.start.y)
                GLES20.glUniform2f(endLoc, gradient.end.x, gradient.end.y)
                GLES20.glUniform1f(startRadiusLoc, gradient.startRadius)
                GLES20.glUniform1f(endRadiusLoc, gradient.endRadius)
                GLES20.glUniform1f(stopCountLoc, 0f) // No custom stops
            }
            is BlurGradient.LinearWithStops -> {
                GLES20.glUniform1f(typeLoc, GRADIENT_TYPE_LINEAR.toFloat())
                GLES20.glUniform2f(startLoc, gradient.start.x, gradient.start.y)
                GLES20.glUniform2f(endLoc, gradient.end.x, gradient.end.y)
                GLES20.glUniform1f(startRadiusLoc, gradient.stops.first().second)
                GLES20.glUniform1f(endRadiusLoc, gradient.stops.last().second)
                // Pass stops data
                setStopsUniforms(gradient.stops)
            }
            is BlurGradient.Radial -> {
                GLES20.glUniform1f(typeLoc, GRADIENT_TYPE_RADIAL.toFloat())
                GLES20.glUniform2f(centerLoc, gradient.center.x, gradient.center.y)
                GLES20.glUniform1f(radiusLoc, gradient.radius)
                GLES20.glUniform1f(startRadiusLoc, gradient.centerRadius)
                GLES20.glUniform1f(endRadiusLoc, gradient.edgeRadius)
                GLES20.glUniform1f(stopCountLoc, 0f) // No custom stops
            }
            is BlurGradient.RadialWithStops -> {
                GLES20.glUniform1f(typeLoc, GRADIENT_TYPE_RADIAL.toFloat())
                GLES20.glUniform2f(centerLoc, gradient.center.x, gradient.center.y)
                GLES20.glUniform1f(radiusLoc, gradient.radius)
                GLES20.glUniform1f(startRadiusLoc, gradient.stops.first().second)
                GLES20.glUniform1f(endRadiusLoc, gradient.stops.last().second)
                // Pass stops data
                setStopsUniforms(gradient.stops)
            }
        }
    }

    /**
     * Sets the stops uniform arrays for multi-stop gradient interpolation.
     * Supports up to 8 stops (MAX_STOPS).
     */
    private fun setStopsUniforms(stops: List<Pair<Float, Float>>) {
        val stopCountLoc = GLES20.glGetUniformLocation(compositeProgram, "uStopCount")

        // Sort stops by position and limit to MAX_STOPS
        val sortedStops = stops.sortedBy { it.first }.take(MAX_STOPS)
        val count = sortedStops.size

        GLES20.glUniform1f(stopCountLoc, count.toFloat())

        // Set positions array
        val positions = FloatArray(MAX_STOPS) { 0f }
        val radii = FloatArray(MAX_STOPS) { 0f }

        sortedStops.forEachIndexed { index, (position, radius) ->
            positions[index] = position
            radii[index] = radius
        }

        // Set uniform arrays - OpenGL ES 2.0 requires setting each element individually
        for (i in 0 until MAX_STOPS) {
            val posLoc = GLES20.glGetUniformLocation(compositeProgram, "uStopPositions[$i]")
            val radLoc = GLES20.glGetUniformLocation(compositeProgram, "uStopRadii[$i]")
            GLES20.glUniform1f(posLoc, positions[i])
            GLES20.glUniform1f(radLoc, radii[i])
        }
    }


    private fun initEGL(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            return false
        }
        eglConfig = configs[0] ?: return false

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        return makeCurrent()
    }

    private fun makeCurrent(): Boolean {
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    /**
     * Sets the output Surface for direct rendering. When set, the final composite
     * result is blitted to this Surface via eglSwapBuffers instead of glReadPixels.
     */
    fun setSurface(surface: android.view.Surface?, width: Int = 0, height: Int = 0) {
        if (pendingSurface === surface) return
        // Destroy old window surface
        val display = eglDisplay
        val ws = windowSurface
        if (display != null && ws != null && ws != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, ws)
        }
        windowSurface = null
        pendingSurface = surface
        surfaceWidth = width
        surfaceHeight = height
        // Create immediately if EGL is ready
        if (isInitialized && surface != null && surface.isValid) {
            createWindowSurface(surface)
        }
    }

    private fun createWindowSurface(surface: android.view.Surface) {
        val display = eglDisplay ?: return
        val config = eglConfig ?: return
        val attribs = intArrayOf(EGL14.EGL_NONE)
        windowSurface = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
    }

    fun hasOutputSurface(): Boolean =
        windowSurface != null && windowSurface != EGL14.EGL_NO_SURFACE

    private fun initShaders(): Boolean {
        downsampleProgram = createProgram(VERTEX_SHADER, DOWNSAMPLE_FRAGMENT_SHADER)
        if (downsampleProgram == 0) return false

        upsampleProgram = createProgram(VERTEX_SHADER, UPSAMPLE_FRAGMENT_SHADER)
        if (upsampleProgram == 0) return false

        compositeProgram = createProgram(VERTEX_SHADER, COMPOSITE_FRAGMENT_SHADER)
        if (compositeProgram == 0) return false

        blitProgram = createProgram(VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
        if (blitProgram == 0) return false

        return true
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            android.util.Log.e(TAG, "Failed to compile vertex shader")
            return 0
        }

        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            android.util.Log.e(TAG, "Failed to compile fragment shader")
            return 0
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val error = GLES20.glGetProgramInfoLog(program)
            android.util.Log.e(TAG, "Failed to link program: $error")
            GLES20.glDeleteProgram(program)
            return 0
        }

        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            android.util.Log.e(TAG, "Shader compilation failed: $error")
            GLES20.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    private fun initFramebuffers(width: Int, height: Int): Boolean {
        // Initialize pyramid storage (full resolution for all levels)
        pyramidFramebuffers = IntArray(MAX_PYRAMID_LEVELS)
        pyramidTextures = IntArray(MAX_PYRAMID_LEVELS)

        GLES20.glGenFramebuffers(MAX_PYRAMID_LEVELS, pyramidFramebuffers, 0)
        GLES20.glGenTextures(MAX_PYRAMID_LEVELS, pyramidTextures, 0)

        for (i in 0 until MAX_PYRAMID_LEVELS) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pyramidTextures!![i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, pyramidFramebuffers!![i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, pyramidTextures!![i], 0
            )
        }

        // Initialize working framebuffers for blur computation (mipmap sizes)
        val workCount = MAX_PYRAMID_LEVELS + 1
        workFramebuffers = IntArray(workCount)
        workTextures = IntArray(workCount)

        GLES20.glGenFramebuffers(workCount, workFramebuffers, 0)
        GLES20.glGenTextures(workCount, workTextures, 0)

        var w = width
        var h = height

        for (i in 0 until workCount) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, workTextures!![i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, workFramebuffers!![i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, workTextures!![i], 0
            )

            w = (w / 2).coerceAtLeast(1)
            h = (h / 2).coerceAtLeast(1)
        }

        // Output framebuffer
        val outputFbArray = IntArray(1)
        val outputTexArray = IntArray(1)
        GLES20.glGenFramebuffers(1, outputFbArray, 0)
        GLES20.glGenTextures(1, outputTexArray, 0)
        outputFramebuffer = outputFbArray[0]
        outputTexture = outputTexArray[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, outputTexture, 0
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return true
    }

    private fun drawQuad(program: Int) {
        val positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        val textureLoc = GLES20.glGetUniformLocation(program, "uTexture")

        GLES20.glUniform1i(textureLoc, 0)

        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordLoc)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionLoc)
        GLES20.glDisableVertexAttribArray(texCoordLoc)
    }

    private fun releaseFramebuffers() {
        pyramidFramebuffers?.let { GLES20.glDeleteFramebuffers(it.size, it, 0) }
        pyramidTextures?.let { GLES20.glDeleteTextures(it.size, it, 0) }
        pyramidFramebuffers = null
        pyramidTextures = null

        workFramebuffers?.let { GLES20.glDeleteFramebuffers(it.size, it, 0) }
        workTextures?.let { GLES20.glDeleteTextures(it.size, it, 0) }
        workFramebuffers = null
        workTextures = null

        if (outputFramebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(outputFramebuffer), 0)
            outputFramebuffer = 0
        }
        if (outputTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(outputTexture), 0)
            outputTexture = 0
        }
    }

    override fun release() {
        if (isInitialized) {
            makeCurrent()
            releaseFramebuffers()

            if (downsampleProgram != 0) {
                GLES20.glDeleteProgram(downsampleProgram)
                downsampleProgram = 0
            }
            if (upsampleProgram != 0) {
                GLES20.glDeleteProgram(upsampleProgram)
                upsampleProgram = 0
            }
            if (compositeProgram != 0) {
                GLES20.glDeleteProgram(compositeProgram)
                compositeProgram = 0
            }
            if (blitProgram != 0) {
                GLES20.glDeleteProgram(blitProgram)
                blitProgram = 0
            }
        }

        val ws = windowSurface
        if (ws != null && ws != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, ws)
        }
        windowSurface = null
        pendingSurface = null

        eglSurface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
        eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
        eglDisplay?.let { EGL14.eglTerminate(it) }

        eglSurface = null
        eglContext = null
        eglDisplay = null
        eglConfig = null

        outputBitmap?.recycle()
        outputBitmap = null

        lastWidth = 0
        lastHeight = 0
        isInitialized = false
    }

    override fun canModifyBitmap(): Boolean = false
    override fun getSupportedBitmapConfig(): Bitmap.Config = Bitmap.Config.ARGB_8888
    override fun isAvailable(): Boolean = true
    override fun getName(): String = "OpenGL Variable Blur"

    companion object {
        private const val TAG = "VariableOpenGLBlur"
        private const val MAX_PYRAMID_LEVELS = 6
        private const val MAX_STOPS = 8
        private const val LN_2 = 0.693147180559945
        private const val BASE_SIGMA = 1.0f

        private const val GRADIENT_TYPE_LINEAR = 0
        private const val GRADIENT_TYPE_RADIAL = 1

        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
        )

        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
        )

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val DOWNSAMPLE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uHalfPixel;
            varying vec2 vTexCoord;
            void main() {
                vec4 sum = texture2D(uTexture, vTexCoord) * 4.0;
                sum += texture2D(uTexture, vTexCoord - uHalfPixel);
                sum += texture2D(uTexture, vTexCoord + uHalfPixel);
                sum += texture2D(uTexture, vTexCoord + vec2(uHalfPixel.x, -uHalfPixel.y));
                sum += texture2D(uTexture, vTexCoord - vec2(uHalfPixel.x, -uHalfPixel.y));
                gl_FragColor = sum / 8.0;
            }
        """

        private const val UPSAMPLE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uHalfPixel;
            varying vec2 vTexCoord;
            void main() {
                vec4 sum = texture2D(uTexture, vTexCoord + vec2(-uHalfPixel.x * 2.0, 0.0));
                sum += texture2D(uTexture, vTexCoord + vec2(-uHalfPixel.x, uHalfPixel.y)) * 2.0;
                sum += texture2D(uTexture, vTexCoord + vec2(0.0, uHalfPixel.y * 2.0));
                sum += texture2D(uTexture, vTexCoord + vec2(uHalfPixel.x, uHalfPixel.y)) * 2.0;
                sum += texture2D(uTexture, vTexCoord + vec2(uHalfPixel.x * 2.0, 0.0));
                sum += texture2D(uTexture, vTexCoord + vec2(uHalfPixel.x, -uHalfPixel.y)) * 2.0;
                sum += texture2D(uTexture, vTexCoord + vec2(0.0, -uHalfPixel.y * 2.0));
                sum += texture2D(uTexture, vTexCoord + vec2(-uHalfPixel.x, -uHalfPixel.y)) * 2.0;
                gl_FragColor = sum / 12.0;
            }
        """

        /** Simple blit/passthrough shader for GPU-only texture copies */
        private const val BLIT_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        /**
         * Composite shader that samples from pyramid levels based on gradient.
         * Includes aspect ratio correction for radial gradients.
         * Supports multi-stop gradients for complex blur patterns.
         *
         * Note: Uses float comparisons instead of int to maximize GLSL ES 2.0 compatibility.
         */
        private const val COMPOSITE_FRAGMENT_SHADER = """
            precision mediump float;

            // Pyramid levels
            uniform sampler2D uLevel0;
            uniform sampler2D uLevel1;
            uniform sampler2D uLevel2;
            uniform sampler2D uLevel3;
            uniform sampler2D uLevel4;
            uniform sampler2D uLevel5;

            // Gradient parameters - use float for gradient type for better compatibility
            uniform float uGradientType;
            uniform vec2 uGradientStart;
            uniform vec2 uGradientEnd;
            uniform vec2 uGradientCenter;
            uniform float uGradientRadius;
            uniform float uStartRadius;
            uniform float uEndRadius;
            uniform float uMinLevel;  // Minimum generated pyramid level (for lazy generation optimization)
            uniform float uMaxLevel;
            uniform float uAspectRatio;

            // Multi-stop gradient support (max 8 stops)
            uniform float uStopCount;
            uniform float uStopPositions[8];
            uniform float uStopRadii[8];

            // Overlay color (RGBA, premultiplied alpha)
            uniform vec4 uOverlayColor;
            uniform float uHasOverlay;

            varying vec2 vTexCoord;

            const float BASE_SIGMA = 1.0;
            const float LN_2 = 0.693147;

            float calculateGradientFactor() {
                if (uGradientType < 0.5) {
                    // Linear gradient (type 0)
                    vec2 gradientVec = uGradientEnd - uGradientStart;
                    vec2 pointVec = vTexCoord - uGradientStart;
                    float gradientLength = length(gradientVec);
                    if (gradientLength < 0.001) return 0.0;
                    float projection = dot(pointVec, gradientVec) / (gradientLength * gradientLength);
                    return clamp(projection, 0.0, 1.0);
                }
                else {
                    // Radial gradient (type 1)
                    // radius is a normalized distance in view coordinates:
                    // - 0.5 with center (0.5, 0.5) creates a circle touching all 4 edges
                    // - 1.0 with center (0.5, 0.5) extends beyond the view
                    vec2 delta = vTexCoord - uGradientCenter;
                    float dist = length(delta);
                    return clamp(dist / uGradientRadius, 0.0, 1.0);
                }
            }

            // Interpolate blur radius at a given position using multi-stop gradient
            float getRadiusAtPosition(float pos) {
                // If no stops or single stop, use simple linear interpolation
                if (uStopCount < 1.5) {
                    return mix(uStartRadius, uEndRadius, pos);
                }

                // Clamp position to [0, 1]
                float p = clamp(pos, 0.0, 1.0);
                int stopCount = int(uStopCount);

                // Before first stop
                if (p <= uStopPositions[0]) {
                    return uStopRadii[0];
                }

                // Find surrounding stops and interpolate
                // Unrolled loop for GLSL ES 2.0 compatibility (max 8 stops = 7 segments)
                if (stopCount >= 2 && p >= uStopPositions[0] && p <= uStopPositions[1]) {
                    float t = (p - uStopPositions[0]) / (uStopPositions[1] - uStopPositions[0]);
                    return mix(uStopRadii[0], uStopRadii[1], t);
                }
                if (stopCount >= 3 && p >= uStopPositions[1] && p <= uStopPositions[2]) {
                    float t = (p - uStopPositions[1]) / (uStopPositions[2] - uStopPositions[1]);
                    return mix(uStopRadii[1], uStopRadii[2], t);
                }
                if (stopCount >= 4 && p >= uStopPositions[2] && p <= uStopPositions[3]) {
                    float t = (p - uStopPositions[2]) / (uStopPositions[3] - uStopPositions[2]);
                    return mix(uStopRadii[2], uStopRadii[3], t);
                }
                if (stopCount >= 5 && p >= uStopPositions[3] && p <= uStopPositions[4]) {
                    float t = (p - uStopPositions[3]) / (uStopPositions[4] - uStopPositions[3]);
                    return mix(uStopRadii[3], uStopRadii[4], t);
                }
                if (stopCount >= 6 && p >= uStopPositions[4] && p <= uStopPositions[5]) {
                    float t = (p - uStopPositions[4]) / (uStopPositions[5] - uStopPositions[4]);
                    return mix(uStopRadii[4], uStopRadii[5], t);
                }
                if (stopCount >= 7 && p >= uStopPositions[5] && p <= uStopPositions[6]) {
                    float t = (p - uStopPositions[5]) / (uStopPositions[6] - uStopPositions[5]);
                    return mix(uStopRadii[5], uStopRadii[6], t);
                }
                if (stopCount >= 8 && p >= uStopPositions[6] && p <= uStopPositions[7]) {
                    float t = (p - uStopPositions[6]) / (uStopPositions[7] - uStopPositions[6]);
                    return mix(uStopRadii[6], uStopRadii[7], t);
                }

                // After last stop - return last stop's radius
                // Use conditional based on stop count
                if (stopCount >= 8) return uStopRadii[7];
                if (stopCount >= 7) return uStopRadii[6];
                if (stopCount >= 6) return uStopRadii[5];
                if (stopCount >= 5) return uStopRadii[4];
                if (stopCount >= 4) return uStopRadii[3];
                if (stopCount >= 3) return uStopRadii[2];
                if (stopCount >= 2) return uStopRadii[1];
                return uStopRadii[0];
            }

            // Sample from pyramid level using float comparison for GLSL ES 2.0 compatibility
            vec4 sampleAtLevel(float level) {
                // Clamp level to valid range
                float l = clamp(level, 0.0, 5.0);

                // Sample all levels and blend based on level value
                // This approach avoids dynamic branching on level parameter
                vec4 c0 = texture2D(uLevel0, vTexCoord);
                vec4 c1 = texture2D(uLevel1, vTexCoord);
                vec4 c2 = texture2D(uLevel2, vTexCoord);
                vec4 c3 = texture2D(uLevel3, vTexCoord);
                vec4 c4 = texture2D(uLevel4, vTexCoord);
                vec4 c5 = texture2D(uLevel5, vTexCoord);

                // Interpolate between adjacent levels based on fractional level
                float levelFloor = floor(l);
                float levelFrac = l - levelFloor;

                // Select colors based on level
                vec4 colorLow, colorHigh;
                if (levelFloor < 0.5) {
                    colorLow = c0;
                    colorHigh = c1;
                } else if (levelFloor < 1.5) {
                    colorLow = c1;
                    colorHigh = c2;
                } else if (levelFloor < 2.5) {
                    colorLow = c2;
                    colorHigh = c3;
                } else if (levelFloor < 3.5) {
                    colorLow = c3;
                    colorHigh = c4;
                } else {
                    colorLow = c4;
                    colorHigh = c5;
                }

                return mix(colorLow, colorHigh, levelFrac);
            }

            void main() {
                float gradientFactor = calculateGradientFactor();

                // Get blur radius - use multi-stop interpolation if stops are provided
                float blurRadius;
                if (uStopCount > 1.5) {
                    blurRadius = getRadiusAtPosition(gradientFactor);
                } else {
                    blurRadius = mix(uStartRadius, uEndRadius, gradientFactor);
                }

                vec4 blurredColor;

                // No blur case
                if (blurRadius < 0.5) {
                    blurredColor = texture2D(uLevel0, vTexCoord);
                } else {
                    // Calculate pyramid level from blur radius
                    float level = log(blurRadius / BASE_SIGMA) / LN_2;
                    // Clamp to the valid range of generated levels
                    // uMinLevel/uMaxLevel define which levels were actually generated
                    level = clamp(level, uMinLevel, uMaxLevel);

                    // Sample with interpolation between levels
                    blurredColor = sampleAtLevel(level);
                }

                // Apply gradient-aware overlay if enabled
                if (uHasOverlay > 0.5) {
                    // Calculate overlay factor based on blur radius
                    // Normalize blur radius to 0-1 range using max of start/end radius
                    float maxRadius = max(uStartRadius, uEndRadius);
                    float overlayFactor = maxRadius > 0.0 ? clamp(blurRadius / maxRadius, 0.0, 1.0) : 0.0;

                    // Scale overlay alpha by the factor (more blur = more overlay)
                    float scaledAlpha = uOverlayColor.a * overlayFactor;

                    // Blend overlay color with blurred content using standard alpha blending
                    // result = overlay * scaledAlpha + blur * (1 - scaledAlpha)
                    blurredColor.rgb = mix(blurredColor.rgb, uOverlayColor.rgb, scaledAlpha);
                }

                gl_FragColor = blurredColor;
            }
        """
    }
}
