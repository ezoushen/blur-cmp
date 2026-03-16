package io.github.ezoushen.blur.algorithm

import android.content.Context
import android.os.Build

/**
 * Factory for creating blur algorithm implementations.
 *
 * Default algorithm: [OpenGLBlur] (Dual Kawase, GPU-accelerated)
 *
 * The OpenGL Dual Kawase algorithm provides:
 * - Unlimited blur radius (controlled by iteration count)
 * - GPU-accelerated performance
 * - iOS-comparable blur quality
 */
object BlurAlgorithmFactory {

    /**
     * Creates the default blur algorithm (OpenGL Dual Kawase).
     *
     * @param context Application context (unused, kept for API compatibility)
     * @return OpenGL Dual Kawase blur algorithm
     */
    @JvmOverloads
    fun create(context: Context? = null): BlurAlgorithm = OpenGLBlur()

    /**
     * Creates a specific blur algorithm by type.
     *
     * @param type The desired algorithm type
     * @return The requested algorithm
     * @throws IllegalArgumentException if the algorithm is not available
     */
    fun create(type: AlgorithmType): BlurAlgorithm {
        return when (type) {
            AlgorithmType.OPENGL -> OpenGLBlur()
            AlgorithmType.RENDER_EFFECT -> {
                require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "RenderEffect requires API 31+"
                }
                RenderEffectBlur()
            }
            AlgorithmType.RENDER_SCRIPT -> {
                @Suppress("DEPRECATION")
                RenderScriptBlur()
            }
            AlgorithmType.TOOLKIT -> ToolkitBlur()
            AlgorithmType.NONE -> NoOpBlurAlgorithm()
        }
    }

    /**
     * Returns information about the default algorithm.
     *
     * @param context Application context (unused)
     * @return Description of the default algorithm
     */
    fun getSelectedAlgorithmInfo(context: Context? = null): String {
        return buildString {
            appendLine("Default Algorithm: OpenGL Dual Kawase")
            appendLine("API Level: ${Build.VERSION.SDK_INT}")
            appendLine("Max Blur Radius: Unlimited")
            appendLine("GPU Accelerated: Yes")
        }
    }

    /**
     * Returns a list of all available algorithms.
     */
    fun getAvailableAlgorithms(): List<AlgorithmType> {
        return buildList {
            add(AlgorithmType.OPENGL) // Default, always available (minSdk 23)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AlgorithmType.RENDER_EFFECT)
            }
            add(AlgorithmType.RENDER_SCRIPT)
            add(AlgorithmType.TOOLKIT)
        }
    }

    /**
     * Supported blur algorithm types.
     */
    enum class AlgorithmType {
        /** OpenGL Dual Kawase blur (default, GPU-accelerated, unlimited radius) */
        OPENGL,

        /** RenderEffect-based blur (API 31+) */
        RENDER_EFFECT,

        /** RenderScript-based blur (deprecated) */
        RENDER_SCRIPT,

        /** CPU-based Stack Blur */
        TOOLKIT,

        /** No blur */
        NONE
    }
}
