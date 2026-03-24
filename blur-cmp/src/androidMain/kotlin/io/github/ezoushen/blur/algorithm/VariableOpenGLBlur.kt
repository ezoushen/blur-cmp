package io.github.ezoushen.blur.algorithm

import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.createBitmap
import androidx.opengl.EGLExt
import androidx.opengl.EGLImageKHR
import android.util.Log
import io.github.ezoushen.blur.BlurGradient
import io.github.ezoushen.blur.BlurPerfMonitor
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
    private var downsampleExternalProgram = 0
    private var upsampleProgram = 0
    private var compositeProgram = 0
    private var blitProgram = 0

    // Cached uniform/attribute locations (resolved once after shader compilation)
    private var dsHalfPixelLoc = -1
    private var dsPositionLoc = -1
    private var dsTexCoordLoc = -1
    private var dsTextureLoc = -1
    private var usHalfPixelLoc = -1
    private var usPositionLoc = -1
    private var usTexCoordLoc = -1
    private var usTextureLoc = -1
    private var blitPositionLoc = -1
    private var blitTexCoordLoc = -1
    private var blitTextureLoc = -1
    private var dsExtTextureLoc = -1
    private var dsExtHalfPixelLoc = -1
    private var dsExtPositionLoc = -1
    private var dsExtTexCoordLoc = -1
    private var compPositionLoc = -1
    private var compTexCoordLoc = -1
    private var compLevelLocs = IntArray(MAX_PYRAMID_LEVELS) { -1 }
    private var compAspectRatioLoc = -1
    private var compMinLevelLoc = -1
    private var compMaxLevelLoc = -1
    private var compGradientTypeLoc = -1
    private var compGradientStartLoc = -1
    private var compGradientEndLoc = -1
    private var compGradientCenterLoc = -1
    private var compGradientRadiusLoc = -1
    private var compStartRadiusLoc = -1
    private var compEndRadiusLoc = -1
    private var compStopCountLoc = -1
    private var compStopPositionLocs = IntArray(MAX_STOPS) { -1 }
    private var compStopRadiiLocs = IntArray(MAX_STOPS) { -1 }

    // EGLImage zero-copy input (API 29+)
    private var inputTexture = 0
    private var currentEglImage: EGLImageKHR? = null

    // External OES texture for SurfaceTexture input (API 26+)
    private var externalInputTexture = 0

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
    private var readPixelsBuffer: ByteBuffer? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var isInitialized = false
    private var hasEglImage = false
    private var hasExternalOes = false

    private var currentGradient: BlurGradient? = null
    // Pre-sorted stops cache (avoids per-frame sortedBy allocation)
    private var sortedStops: List<Pair<Float, Float>>? = null

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

    private val texCoordFlippedBuffer: FloatBuffer by lazy {
        ByteBuffer.allocateDirect(QUAD_TEX_COORDS_FLIPPED.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_TEX_COORDS_FLIPPED)
                position(0)
            }
    }

    /**
     * Sets the blur gradient for variable blur effect.
     */
    fun setGradient(gradient: BlurGradient) {
        currentGradient = gradient
        sortedStops = when (gradient) {
            is BlurGradient.LinearWithStops -> gradient.stops.sortedBy { it.first }.take(MAX_STOPS)
            is BlurGradient.RadialWithStops -> gradient.stops.sortedBy { it.first }.take(MAX_STOPS)
            else -> null
        }
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
            val c = android.graphics.Canvas(output)
            c.drawBitmap(input, 0f, 0f, null)
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

            // Upload input to level 0: zero-copy if available, else legacy texImage2D
            val perf = BlurPerfMonitor.enabled
            val t0 = if (perf) System.nanoTime() else 0L
            val hasEglImageInput = inputTexture != 0 && currentEglImage != null
            val hasExternalInput = externalInputTexture != 0 && downsampleExternalProgram != 0
            if (hasEglImageInput) {
                blitTexture(inputTexture, pyramidFramebuffers!![0], lastWidth, lastHeight)
            } else if (hasExternalInput) {
                blitExternalTexture(externalInputTexture, pyramidFramebuffers!![0], lastWidth, lastHeight)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pyramidTextures!![0])
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, input, 0)
            }
            val t1 = if (perf) System.nanoTime() else 0L

            generateBlurPyramid(minLevel, maxLevel)
            val t2 = if (perf) System.nanoTime() else 0L

            compositeWithGradient(gradient, minLevel, maxLevel)
            val t3 = if (perf) System.nanoTime() else 0L

            val useWindowSurface = hasOutputSurface()
            if (useWindowSurface) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    val vw = if (surfaceWidth > 0) surfaceWidth else lastWidth
                    val vh = if (surfaceHeight > 0) surfaceHeight else lastHeight
                    GLES20.glViewport(0, 0, vw, vh)
                    blitTexture(outputTexture, 0, vw, vh, flipY = true)
                    EGL14.eglSwapBuffers(eglDisplay, windowSurface)
                } finally {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                }
                if (perf) {
                    val swapUs = (System.nanoTime() - t3) / 1000
                    Log.i("BlurPerf", "    VarGL upload=${(t1-t0)/1000}us pyramid=${(t2-t1)/1000}us composite=${(t3-t2)/1000}us swap=${swapUs}us total=${(t1-t0+t2-t1+t3-t2)/1000+swapUs}us")
                    BlurPerfMonitor.reportBlur((t1-t0)/1000, (t2-t1)/1000, (t3-t2)/1000, swapUs)
                }
                return output
            }

            // Fallback: glReadPixels (reuse cached buffer)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
            val bufSize = lastWidth * lastHeight * 4
            var buffer = readPixelsBuffer
            if (buffer == null || buffer.capacity() < bufSize) {
                buffer = ByteBuffer.allocateDirect(bufSize).order(ByteOrder.nativeOrder())
                readPixelsBuffer = buffer
            }
            buffer.clear()
            GLES20.glReadPixels(0, 0, lastWidth, lastHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            buffer.rewind()
            output.copyPixelsFromBuffer(buffer)
            if (perf) {
                val readPixelsUs = (System.nanoTime() - t3) / 1000
                Log.i("BlurPerf", "    VarGL upload=${(t1-t0)/1000}us pyramid=${(t2-t1)/1000}us composite=${(t3-t2)/1000}us readPixels=${readPixelsUs}us total=${(t1-t0+t2-t1+t3-t2)/1000+readPixelsUs}us")
                BlurPerfMonitor.reportBlur((t1-t0)/1000, (t2-t1)/1000, (t3-t2)/1000, readPixelsUs)
            }

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

        val startLevel = minLevel.coerceAtLeast(1)
        val offset = 1.0f

        // Step 1: Downsample chain — one program bind, one attrib setup for all passes
        GLES20.glUseProgram(downsampleProgram)
        GLES20.glEnableVertexAttribArray(dsPositionLoc)
        GLES20.glVertexAttribPointer(dsPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(dsTexCoordLoc)
        GLES20.glVertexAttribPointer(dsTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        var currentTexture = pyramidTexs[0]
        var cw = lastWidth
        var ch = lastHeight

        for (i in 0 until maxLevel) {
            val tw = (cw / 2).coerceAtLeast(1)
            val th = (ch / 2).coerceAtLeast(1)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, workFbs[i + 1])
            GLES20.glViewport(0, 0, tw, th)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexture)
            GLES20.glUniform2f(dsHalfPixelLoc, offset * 0.5f / cw, offset * 0.5f / ch)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            currentTexture = workTexs[i + 1]
            cw = tw
            ch = th
        }

        GLES20.glDisableVertexAttribArray(dsPositionLoc)
        GLES20.glDisableVertexAttribArray(dsTexCoordLoc)

        // Step 2: Upsample — one program bind for all levels and passes
        GLES20.glUseProgram(upsampleProgram)
        GLES20.glEnableVertexAttribArray(usPositionLoc)
        GLES20.glVertexAttribPointer(usPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(usTexCoordLoc)
        GLES20.glVertexAttribPointer(usTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        for (level in startLevel..maxLevel) {
            var upTex = workTexs[level]
            var uw = (lastWidth shr level).coerceAtLeast(1)
            var uh = (lastHeight shr level).coerceAtLeast(1)

            for (i in level - 1 downTo 0) {
                val tw = if (i == 0) lastWidth else (lastWidth shr i).coerceAtLeast(1)
                val th = if (i == 0) lastHeight else (lastHeight shr i).coerceAtLeast(1)

                val targetFb = if (i == 0) pyramidFbs[level] else workFbs[i]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFb)
                GLES20.glViewport(0, 0, tw, th)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, upTex)
                GLES20.glUniform2f(usHalfPixelLoc, offset * 0.5f / uw, offset * 0.5f / uh)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                upTex = if (i == 0) pyramidTexs[level] else workTexs[i]
                uw = tw
                uh = th
            }
        }

        GLES20.glDisableVertexAttribArray(usPositionLoc)
        GLES20.glDisableVertexAttribArray(usTexCoordLoc)
    }

    /**
     * GPU-only texture copy using render-to-texture (blit shader).
     * Much faster than glReadPixels + glTexSubImage2D.
     */
    private fun blitTexture(srcTexture: Int, dstFramebuffer: Int, width: Int, height: Int, flipY: Boolean = false) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFramebuffer)
        GLES20.glViewport(0, 0, width, height)

        GLES20.glUseProgram(blitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexture)
        GLES20.glUniform1i(blitTextureLoc, 0)

        drawQuadCached(blitPositionLoc, blitTexCoordLoc, flipY)
    }

    /**
     * GPU-only blit from a GL_TEXTURE_EXTERNAL_OES texture to a framebuffer.
     * Uses the external downsample program as a simple passthrough (with zero offset).
     */
    private fun blitExternalTexture(srcTexture: Int, dstFramebuffer: Int, width: Int, height: Int) {
        if (downsampleExternalProgram == 0) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFramebuffer)
        GLES20.glViewport(0, 0, width, height)

        GLES20.glUseProgram(downsampleExternalProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, srcTexture)
        GLES20.glUniform1i(dsExtTextureLoc, 0)
        GLES20.glUniform2f(dsExtHalfPixelLoc, 0f, 0f)

        drawQuadCached(dsExtPositionLoc, dsExtTexCoordLoc)

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
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

        for (i in 0..maxLevel.coerceAtMost(MAX_PYRAMID_LEVELS - 1)) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pyramidTextures!![i])
        }

        GLES20.glUniform1f(compAspectRatioLoc, lastWidth.toFloat() / lastHeight.toFloat())
        setGradientUniforms(gradient)
        GLES20.glUniform1f(compMinLevelLoc, minLevel.toFloat())
        GLES20.glUniform1f(compMaxLevelLoc, maxLevel.toFloat())

        drawQuadCached(compPositionLoc, compTexCoordLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /**
     * Sets gradient-related uniforms in the composite shader.
     */
    private fun setGradientUniforms(gradient: BlurGradient) {
        when (gradient) {
            is BlurGradient.Linear -> {
                GLES20.glUniform1f(compGradientTypeLoc, GRADIENT_TYPE_LINEAR.toFloat())
                GLES20.glUniform2f(compGradientStartLoc, gradient.start.x, gradient.start.y)
                GLES20.glUniform2f(compGradientEndLoc, gradient.end.x, gradient.end.y)
                GLES20.glUniform1f(compStartRadiusLoc, gradient.startRadius)
                GLES20.glUniform1f(compEndRadiusLoc, gradient.endRadius)
                GLES20.glUniform1f(compStopCountLoc, 0f)
            }
            is BlurGradient.LinearWithStops -> {
                GLES20.glUniform1f(compGradientTypeLoc, GRADIENT_TYPE_LINEAR.toFloat())
                GLES20.glUniform2f(compGradientStartLoc, gradient.start.x, gradient.start.y)
                GLES20.glUniform2f(compGradientEndLoc, gradient.end.x, gradient.end.y)
                GLES20.glUniform1f(compStartRadiusLoc, gradient.stops.first().second)
                GLES20.glUniform1f(compEndRadiusLoc, gradient.stops.last().second)
                setStopsUniforms(gradient.stops)
            }
            is BlurGradient.Radial -> {
                GLES20.glUniform1f(compGradientTypeLoc, GRADIENT_TYPE_RADIAL.toFloat())
                GLES20.glUniform2f(compGradientCenterLoc, gradient.center.x, gradient.center.y)
                GLES20.glUniform1f(compGradientRadiusLoc, gradient.radius)
                GLES20.glUniform1f(compStartRadiusLoc, gradient.centerRadius)
                GLES20.glUniform1f(compEndRadiusLoc, gradient.edgeRadius)
                GLES20.glUniform1f(compStopCountLoc, 0f)
            }
            is BlurGradient.RadialWithStops -> {
                GLES20.glUniform1f(compGradientTypeLoc, GRADIENT_TYPE_RADIAL.toFloat())
                GLES20.glUniform2f(compGradientCenterLoc, gradient.center.x, gradient.center.y)
                GLES20.glUniform1f(compGradientRadiusLoc, gradient.radius)
                GLES20.glUniform1f(compStartRadiusLoc, gradient.stops.first().second)
                GLES20.glUniform1f(compEndRadiusLoc, gradient.stops.last().second)
                setStopsUniforms(gradient.stops)
            }
        }
    }

    /**
     * Sets the stops uniform arrays for multi-stop gradient interpolation.
     * Supports up to 8 stops (MAX_STOPS).
     */
    private fun setStopsUniforms(stops: List<Pair<Float, Float>>) {
        val sorted = sortedStops ?: stops.sortedBy { it.first }.take(MAX_STOPS)
        GLES20.glUniform1f(compStopCountLoc, sorted.size.toFloat())

        for (i in 0 until MAX_STOPS) {
            if (i < sorted.size) {
                GLES20.glUniform1f(compStopPositionLocs[i], sorted[i].first)
                GLES20.glUniform1f(compStopRadiiLocs[i], sorted[i].second)
            } else {
                GLES20.glUniform1f(compStopPositionLocs[i], 0f)
                GLES20.glUniform1f(compStopRadiiLocs[i], 0f)
            }
        }
    }


    /**
     * Imports a HardwareBuffer as a GL texture via EGLImage (zero-copy, API 29+).
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun setInputFromHardwareBuffer(hwBuffer: HardwareBuffer): Boolean {
        val display = eglDisplay ?: return false

        // Adreno workaround: destroy old EGLImage BEFORE texture operations
        destroyCurrentEglImage()

        try {
            val image = EGLExt.eglCreateImageFromHardwareBuffer(display, hwBuffer)
                ?: return false

            if (inputTexture == 0) {
                val texArray = IntArray(1)
                GLES20.glGenTextures(1, texArray, 0)
                inputTexture = texArray[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
            }

            EGLExt.glEGLImageTargetTexture2DOES(GLES20.GL_TEXTURE_2D, image)
            currentEglImage = image
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun destroyCurrentEglImage() {
        val image = currentEglImage ?: return
        val display = eglDisplay ?: return
        try {
            EGLExt.eglDestroyImageKHR(display, image)
        } catch (_: Exception) {}
        currentEglImage = null
    }

    fun hasEglImageSupport(): Boolean = isInitialized && hasEglImage

    fun hasExternalOesSupport(): Boolean = isInitialized && hasExternalOes

    fun getExternalInputTextureId(): Int {
        if (externalInputTexture != 0) return externalInputTexture

        val texArray = IntArray(1)
        GLES20.glGenTextures(1, texArray, 0)
        externalInputTexture = texArray[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalInputTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return externalInputTexture
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

        // External OES downsample shader — allow failure if extension not supported
        downsampleExternalProgram = createProgram(VERTEX_SHADER, DOWNSAMPLE_EXTERNAL_FRAGMENT_SHADER)

        cacheUniformLocations()
        setConstantUniforms()
        cacheExtensionSupport()
        return true
    }

    private fun setConstantUniforms() {
        // Sampler bindings to texture unit 0 never change.
        GLES20.glUseProgram(downsampleProgram)
        GLES20.glUniform1i(dsTextureLoc, 0)
        GLES20.glUseProgram(upsampleProgram)
        GLES20.glUniform1i(usTextureLoc, 0)
        GLES20.glUseProgram(blitProgram)
        GLES20.glUniform1i(blitTextureLoc, 0)
        // Composite level samplers: uLevel0=0, uLevel1=1, ...
        GLES20.glUseProgram(compositeProgram)
        for (i in 0 until MAX_PYRAMID_LEVELS) {
            GLES20.glUniform1i(compLevelLocs[i], i)
        }
        GLES20.glUseProgram(0)
    }

    private fun cacheExtensionSupport() {
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        hasEglImage = extensions.contains("GL_OES_EGL_image")
        hasExternalOes = extensions.contains("GL_OES_EGL_image_external")
    }

    private fun cacheUniformLocations() {
        // Downsample program
        dsHalfPixelLoc = GLES20.glGetUniformLocation(downsampleProgram, "uHalfPixel")
        dsPositionLoc = GLES20.glGetAttribLocation(downsampleProgram, "aPosition")
        dsTexCoordLoc = GLES20.glGetAttribLocation(downsampleProgram, "aTexCoord")
        dsTextureLoc = GLES20.glGetUniformLocation(downsampleProgram, "uTexture")

        // Upsample program
        usHalfPixelLoc = GLES20.glGetUniformLocation(upsampleProgram, "uHalfPixel")
        usPositionLoc = GLES20.glGetAttribLocation(upsampleProgram, "aPosition")
        usTexCoordLoc = GLES20.glGetAttribLocation(upsampleProgram, "aTexCoord")
        usTextureLoc = GLES20.glGetUniformLocation(upsampleProgram, "uTexture")

        // Blit program
        blitPositionLoc = GLES20.glGetAttribLocation(blitProgram, "aPosition")
        blitTexCoordLoc = GLES20.glGetAttribLocation(blitProgram, "aTexCoord")
        blitTextureLoc = GLES20.glGetUniformLocation(blitProgram, "uTexture")

        // External OES program
        if (downsampleExternalProgram != 0) {
            dsExtTextureLoc = GLES20.glGetUniformLocation(downsampleExternalProgram, "uTexture")
            dsExtHalfPixelLoc = GLES20.glGetUniformLocation(downsampleExternalProgram, "uHalfPixel")
            dsExtPositionLoc = GLES20.glGetAttribLocation(downsampleExternalProgram, "aPosition")
            dsExtTexCoordLoc = GLES20.glGetAttribLocation(downsampleExternalProgram, "aTexCoord")
        }

        // Composite program
        compPositionLoc = GLES20.glGetAttribLocation(compositeProgram, "aPosition")
        compTexCoordLoc = GLES20.glGetAttribLocation(compositeProgram, "aTexCoord")

        for (i in 0 until MAX_PYRAMID_LEVELS) {
            compLevelLocs[i] = GLES20.glGetUniformLocation(compositeProgram, "uLevel$i")
        }
        compAspectRatioLoc = GLES20.glGetUniformLocation(compositeProgram, "uAspectRatio")
        compMinLevelLoc = GLES20.glGetUniformLocation(compositeProgram, "uMinLevel")
        compMaxLevelLoc = GLES20.glGetUniformLocation(compositeProgram, "uMaxLevel")
        compGradientTypeLoc = GLES20.glGetUniformLocation(compositeProgram, "uGradientType")
        compGradientStartLoc = GLES20.glGetUniformLocation(compositeProgram, "uGradientStart")
        compGradientEndLoc = GLES20.glGetUniformLocation(compositeProgram, "uGradientEnd")
        compGradientCenterLoc = GLES20.glGetUniformLocation(compositeProgram, "uGradientCenter")
        compGradientRadiusLoc = GLES20.glGetUniformLocation(compositeProgram, "uGradientRadius")
        compStartRadiusLoc = GLES20.glGetUniformLocation(compositeProgram, "uStartRadius")
        compEndRadiusLoc = GLES20.glGetUniformLocation(compositeProgram, "uEndRadius")
        compStopCountLoc = GLES20.glGetUniformLocation(compositeProgram, "uStopCount")
        for (i in 0 until MAX_STOPS) {
            compStopPositionLocs[i] = GLES20.glGetUniformLocation(compositeProgram, "uStopPositions[$i]")
            compStopRadiiLocs[i] = GLES20.glGetUniformLocation(compositeProgram, "uStopRadii[$i]")
        }
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

    private fun drawQuadCached(positionLoc: Int, texCoordLoc: Int, flipY: Boolean = false) {
        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texBuf = if (flipY) texCoordFlippedBuffer else texCoordBuffer
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)

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

            // Destroy EGLImage before texture deletion (Adreno workaround)
            destroyCurrentEglImage()

            releaseFramebuffers()

            // Delete zero-copy input textures
            if (inputTexture != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(inputTexture), 0)
                inputTexture = 0
            }
            if (externalInputTexture != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(externalInputTexture), 0)
                externalInputTexture = 0
            }

            if (downsampleProgram != 0) {
                GLES20.glDeleteProgram(downsampleProgram)
                downsampleProgram = 0
            }
            if (downsampleExternalProgram != 0) {
                GLES20.glDeleteProgram(downsampleExternalProgram)
                downsampleExternalProgram = 0
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

        private val QUAD_TEX_COORDS_FLIPPED = floatArrayOf(
            0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f
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

        private const val DOWNSAMPLE_EXTERNAL_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
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

            // Sample from pyramid level.
            // Branchless: fetches all 6 levels then selects via math.
            // On modern GPUs (Mali-G715, Adreno 7xx) the extra fetches are
            // nearly free due to texture cache. On older GPUs (Mali-G72)
            // branching with texture fetches causes thread divergence that
            // is MORE expensive than redundant fetches.
            vec4 sampleAtLevel(float level) {
                float l = clamp(level, 0.0, 5.0);

                vec4 c0 = texture2D(uLevel0, vTexCoord);
                vec4 c1 = texture2D(uLevel1, vTexCoord);
                vec4 c2 = texture2D(uLevel2, vTexCoord);
                vec4 c3 = texture2D(uLevel3, vTexCoord);
                vec4 c4 = texture2D(uLevel4, vTexCoord);
                vec4 c5 = texture2D(uLevel5, vTexCoord);

                float levelFloor = floor(l);
                float levelFrac = l - levelFloor;

                vec4 colorLow, colorHigh;
                if (levelFloor < 0.5) {
                    colorLow = c0; colorHigh = c1;
                } else if (levelFloor < 1.5) {
                    colorLow = c1; colorHigh = c2;
                } else if (levelFloor < 2.5) {
                    colorLow = c2; colorHigh = c3;
                } else if (levelFloor < 3.5) {
                    colorLow = c3; colorHigh = c4;
                } else {
                    colorLow = c4; colorHigh = c5;
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

                gl_FragColor = blurredColor;
            }
        """
    }
}
