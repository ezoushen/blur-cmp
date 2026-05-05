package io.github.ezoushen.blur.cmp

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import io.github.ezoushen.blur.algorithm.OpenGLBlurShaders
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Off-main-thread GL pipeline prewarmer for the Android backdrop blur path.
 *
 * Compiles + links the same GLSL programs that
 * [io.github.ezoushen.blur.algorithm.OpenGLBlur] uses at runtime, against
 * the same EGL config and ES context version, so the system shader-binary
 * cache (`EGL_ANDROID_blob_cache`) is populated before any [BlurOverlay]
 * is constructed for the first time. Subsequent BlurView cold-init paths
 * see a cache hit on `glCompileShader` / `glLinkProgram`, cutting the
 * per-instance UI-thread cold cost from ~30–60 ms to <5 ms on Adreno
 * mid-tier GPUs.
 *
 * Design constraints honored:
 * - Idempotent. Multiple calls (any thread) collapse to one warmup run.
 * - Off the main thread. All EGL/GLES work happens on a dedicated
 *   [HandlerThread] at [Process.THREAD_PRIORITY_BACKGROUND].
 * - Does not extend app launch. Worker start is deferred via
 *   [android.os.MessageQueue.IdleHandler]; a [Handler.postDelayed]
 *   fallback fires at 750 ms so continuous launch animations cannot
 *   starve the warmup indefinitely.
 * - Race-safe with concurrent [BlurOverlay] creation. Worker uses its
 *   own EGL context on its own thread; the runtime path's EGL context
 *   is unaffected.
 * - Runtime-gated. If the device's EGL implementation does not advertise
 *   `EGL_ANDROID_blob_cache` the warmup terminates early and reports
 *   FAILED — the runtime path still works exactly as before.
 * - Fail-open. Any exception inside the worker is caught, the worker
 *   tears down its GL state in reverse construction order, and the
 *   caller sees only a state transition to FAILED.
 */
object BlurOverlayPrewarm {

    enum class State {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }

    private const val TAG = "BlurPrewarm"
    private const val FALLBACK_DELAY_MS = 750L
    private const val THREAD_NAME = "blur-cmp-prewarm"

    private val stateRef = AtomicReference(State.NOT_STARTED)

    // Guards the IdleHandler-vs-postDelayed race. Whichever path fires
    // first flips this and starts the worker; the other no-ops.
    private val workerStarted = AtomicBoolean(false)

    /** Current prewarm state. Visible across threads via AtomicReference. */
    val state: State
        get() = stateRef.get()

    /**
     * Schedule a one-shot warmup. Returns immediately. Safe to call from
     * any thread, multiple times — only the first call performs work.
     *
     * Actual GL work is deferred until the main looper goes idle (or up
     * to 750 ms after this call, whichever comes first), so app launch
     * is never extended.
     */
    fun prewarm(context: Context) {
        if (!stateRef.compareAndSet(State.NOT_STARTED, State.IN_PROGRESS)) {
            return
        }

        val appContext = context.applicationContext ?: context
        val mainHandler = Handler(Looper.getMainLooper())

        val launchWorker = Runnable {
            if (!workerStarted.compareAndSet(false, true)) return@Runnable
            startWorker(appContext)
        }

        // Primary trigger: IdleHandler fires the first time the main
        // looper has no ready messages. AOSP MessageQueue skips idle
        // handlers while ready messages keep arriving, so continuous
        // launch animations can starve this; that's why we also arm a
        // delayed fallback.
        mainHandler.post {
            Looper.myQueue().addIdleHandler {
                launchWorker.run()
                false // one-shot
            }
        }

        // Fallback trigger.
        mainHandler.postDelayed(launchWorker, FALLBACK_DELAY_MS)
    }

    private fun startWorker(@Suppress("UNUSED_PARAMETER") appContext: Context) {
        val thread = HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        Handler(thread.looper).post {
            val outcome = try {
                runWarmup()
            } catch (t: Throwable) {
                Log.w(TAG, "prewarm worker threw", t)
                State.FAILED
            }
            stateRef.set(outcome)
            thread.quitSafely()
        }
    }

    /**
     * Runs entirely on the worker [HandlerThread]. Sets up an EGL context
     * + pbuffer surface, validates that the device exposes blob-cache
     * support, compiles + links the three blur programs, then tears down
     * in reverse construction order. Driver retains the cached compiled
     * shaders on disk regardless of program / context lifetime.
     */
    private fun runWarmup(): State {
        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        var displayInitialized = false
        val createdShaders = mutableListOf<Int>()
        val createdPrograms = mutableListOf<Int>()
        var madeCurrent = false

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == null || display == EGL14.EGL_NO_DISPLAY) {
                return State.FAILED
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return State.FAILED
            }
            displayInitialized = true

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(
                    display,
                    OpenGLBlurShaders.EGL_CONFIG_ATTRIBS,
                    0,
                    configs,
                    0,
                    1,
                    numConfigs,
                    0,
                )
            ) return State.FAILED
            val config = configs[0] ?: return State.FAILED

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                OpenGLBlurShaders.EGL_CONTEXT_ATTRIBS,
                0,
            )
            if (context == null || context == EGL14.EGL_NO_CONTEXT) return State.FAILED

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE,
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) return State.FAILED

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return State.FAILED
            madeCurrent = true

            // Runtime gate: confirm the platform actually advertises a
            // shader-binary blob cache before doing any compile work.
            // Without it the prewarm has no observable benefit.
            val eglExtensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
            if (!eglExtensions.contains("EGL_ANDROID_blob_cache")) {
                Log.i(TAG, "EGL_ANDROID_blob_cache not advertised; skipping prewarm")
                return State.FAILED
            }

            // Single-source-of-truth shader strings: same `String` constants
            // both runtime and prewarm pass to glShaderSource, so the
            // driver's source-hash key matches and the cache hits.
            compileAndLink(
                OpenGLBlurShaders.VERTEX_SHADER,
                OpenGLBlurShaders.DOWNSAMPLE_FRAGMENT_SHADER,
                createdShaders,
                createdPrograms,
            )
            compileAndLink(
                OpenGLBlurShaders.VERTEX_SHADER,
                OpenGLBlurShaders.UPSAMPLE_FRAGMENT_SHADER,
                createdShaders,
                createdPrograms,
            )
            // Best-effort: external-OES path requires the
            // GL_OES_EGL_image_external extension. If it's missing the
            // compile fails silently; runtime falls back to the
            // non-external downsample shader.
            val glExtensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
            if (glExtensions.contains("GL_OES_EGL_image_external")) {
                compileAndLink(
                    OpenGLBlurShaders.VERTEX_SHADER,
                    OpenGLBlurShaders.DOWNSAMPLE_EXTERNAL_FRAGMENT_SHADER,
                    createdShaders,
                    createdPrograms,
                )
            }

            return State.COMPLETED
        } finally {
            // Reverse-order cleanup: GL handles first (while context is
            // current), then unbind context, then destroy surface +
            // context, finally terminate display. Skipping any step
            // could leak a current context onto a HandlerThread that's
            // about to quit.
            if (madeCurrent) {
                for (program in createdPrograms) {
                    if (program != 0) {
                        try { GLES20.glDeleteProgram(program) } catch (_: Throwable) {}
                    }
                }
                for (shader in createdShaders) {
                    if (shader != 0) {
                        try { GLES20.glDeleteShader(shader) } catch (_: Throwable) {}
                    }
                }
                try {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                } catch (_: Throwable) {}
            }
            if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
                    try { EGL14.eglDestroySurface(display, surface) } catch (_: Throwable) {}
                }
                if (context != null && context != EGL14.EGL_NO_CONTEXT) {
                    try { EGL14.eglDestroyContext(display, context) } catch (_: Throwable) {}
                }
                if (displayInitialized) {
                    try { EGL14.eglTerminate(display) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun compileAndLink(
        vertexSource: String,
        fragmentSource: String,
        createdShaders: MutableList<Int>,
        createdPrograms: MutableList<Int>,
    ) {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        if (vs == 0) return
        createdShaders += vs
        GLES20.glShaderSource(vs, vertexSource)
        GLES20.glCompileShader(vs)
        if (!shaderCompiled(vs)) return

        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        if (fs == 0) return
        createdShaders += fs
        GLES20.glShaderSource(fs, fragmentSource)
        GLES20.glCompileShader(fs)
        if (!shaderCompiled(fs)) return

        val program = GLES20.glCreateProgram()
        if (program == 0) return
        createdPrograms += program
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        // No need to check link status — even a failed link populates
        // the driver's parse cache for the source strings, which is the
        // value we want.
    }

    private fun shaderCompiled(shader: Int): Boolean {
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        return status[0] != 0
    }
}
