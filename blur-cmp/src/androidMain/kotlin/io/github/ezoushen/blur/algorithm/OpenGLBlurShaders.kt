package io.github.ezoushen.blur.algorithm

import android.opengl.EGL14

/**
 * Shared GLSL source strings + EGL config attributes used by [OpenGLBlur].
 *
 * Centralized so the runtime path (per-BlurView OpenGLBlur) and the
 * background prewarm path
 * ([io.github.ezoushen.blur.cmp.BlurOverlayPrewarm]) reference the
 * literal same `String` constants. This is required for the Adreno /
 * Mali driver shader-cache (EGL_ANDROID_blob_cache) to recognize
 * prewarm and runtime as the same compile job — the driver hashes the
 * raw source string, so any whitespace, line-ending, or interpolation
 * difference between the two sites silently invalidates the cache key
 * and erases the prewarm benefit.
 *
 * Do not run [String.trimIndent], [String.replace], or any other
 * transformation on these constants. They must reach
 * `glShaderSource` byte-identical from both call sites.
 */
internal object OpenGLBlurShaders {

    const val VERTEX_SHADER: String = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

    /**
     * Downsample fragment shader for GL_TEXTURE_EXTERNAL_OES input
     * (SurfaceTexture). Uses samplerExternalOES instead of sampler2D.
     * Only used for the first downsample pass.
     */
    const val DOWNSAMPLE_EXTERNAL_FRAGMENT_SHADER: String = """
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

    const val DOWNSAMPLE_FRAGMENT_SHADER: String = """
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

    const val UPSAMPLE_FRAGMENT_SHADER: String = """
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

    /**
     * EGL config attributes used by both runtime and prewarm contexts.
     * Driver may include the resolved EGL config in its blob-cache key,
     * so the two paths must request configs with the same renderable
     * type, surface flags, and channel sizes.
     */
    val EGL_CONFIG_ATTRIBS: IntArray = intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_NONE,
    )

    val EGL_CONTEXT_ATTRIBS: IntArray = intArrayOf(
        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL14.EGL_NONE,
    )
}
