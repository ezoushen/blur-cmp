# Zero-Copy Blur Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the last 2 CPU↔GPU crossings from the Kawase blur pipeline by importing HardwareBuffer as EGLImage (API 29+) and using SurfaceTexture capture (API 26-28).

**Architecture:** Two new input modes for the Kawase pipeline: EGL_IMAGE (zero-copy HardwareBuffer → GL texture via `androidx.graphics:graphics-core`) and SURFACE_TEXTURE (zero-copy `lockHardwareCanvas` → SurfaceTexture → GL external texture). Configurable via `BlurPipelineStrategy` enum in `BlurConfig` for testing any path on any device.

**Tech Stack:** `androidx.graphics:graphics-core`, EGLImage, SurfaceTexture, OpenGL ES 2.0, `samplerExternalOES`

---

## File Structure

```
CREATE: blur-cmp/.../BlurPipelineStrategy.kt          — enum (RENDER_EFFECT, EGL_IMAGE, SURFACE_TEXTURE, LEGACY, AUTO)
CREATE: blur-cmp/.../capture/SurfaceTextureCapture.kt  — API 26+ lockHardwareCanvas capture
MODIFY: blur-cmp/.../BlurConfig.kt                     — add pipelineStrategy field
MODIFY: blur-cmp/.../algorithm/OpenGLBlur.kt            — EGLImage input + samplerExternalOES shader
MODIFY: blur-cmp/.../algorithm/VariableOpenGLBlur.kt    — same as OpenGLBlur
MODIFY: blur-cmp/.../capture/HardwareBufferCapture.kt   — expose raw HardwareBuffer
MODIFY: blur-cmp/.../BlurController.kt                  — strategy selection + wire new capture modes
MODIFY: blur-cmp/.../VariableBlurController.kt           — same as BlurController
MODIFY: blur-cmp/build.gradle.kts                       — add graphics-core dependency
```

---

### Task 1: Add BlurPipelineStrategy enum + BlurConfig field + gradle dependency

**Files:**
- Create: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/BlurPipelineStrategy.kt`
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/BlurConfig.kt`
- Modify: `blur-cmp/build.gradle.kts`

- [ ] **Step 1: Create BlurPipelineStrategy.kt**

```kotlin
package io.github.ezoushen.blur

/**
 * Selects the blur pipeline strategy. Android-only (iOS uses system blur).
 * Use [AUTO] for best performance on the current device.
 * Override to force a specific strategy for testing.
 */
enum class BlurPipelineStrategy {
    /** API 31+, uniform blur. RenderNode + RenderEffect. 0 crossings. */
    RENDER_EFFECT,
    /** API 29+. HardwareBuffer → EGLImage → GL_TEXTURE_2D. 0 crossings. */
    EGL_IMAGE,
    /** API 26+. lockHardwareCanvas → SurfaceTexture → GL_TEXTURE_EXTERNAL_OES. 0 crossings. */
    SURFACE_TEXTURE,
    /** Any API. Software Canvas → texImage2D. 2 crossings. Always works. */
    LEGACY,
    /** Auto-select best for current device. */
    AUTO
}
```

- [ ] **Step 2: Add pipelineStrategy to BlurConfig**

Add field with default `AUTO`:
```kotlin
data class BlurConfig(
    val radius: Float = 16f,
    val overlayColor: Int? = null,
    val downsampleFactor: Float = 4f,
    val preBlurTintColor: Int? = null,
    val preBlurBlendModeOrdinal: Int? = null,
    val pipelineStrategy: BlurPipelineStrategy = BlurPipelineStrategy.AUTO,
)
```

- [ ] **Step 3: Add graphics-core dependency**

In `blur-cmp/build.gradle.kts`, inside the `sourceSets` `androidMain.dependencies` block, add:
```kotlin
implementation("androidx.graphics:graphics-core:1.0.2")
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew :blur-cmp:compileReleaseKotlinAndroid`

- [ ] **Step 5: Commit**

```
feat: add BlurPipelineStrategy enum and graphics-core dependency
```

---

### Task 2: EGLImage input mode for OpenGLBlur (API 29+)

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/algorithm/OpenGLBlur.kt`
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/capture/HardwareBufferCapture.kt`

This is the core zero-copy change. Instead of `bitmap.copy()` + `texImage2D`, import HardwareBuffer directly as a GL texture via EGLImage.

- [ ] **Step 1: Add EGLImage fields and methods to OpenGLBlur**

After existing fields, add:
```kotlin
    // Dedicated input texture for EGLImage (separate from textures[0] which is an FBO attachment)
    private var inputTexture = 0
    private var currentEglImage: Any? = null  // EGLImageKHR from graphics-core
```

Add method:
```kotlin
    /**
     * Imports a HardwareBuffer as the input texture via EGLImage.
     * Zero-copy: the GL texture aliases the HardwareBuffer's GPU memory.
     * Must be called with the EGL context current.
     * Returns true if successful.
     */
    fun setInputFromHardwareBuffer(hardwareBuffer: android.hardware.HardwareBuffer): Boolean {
        try {
            makeCurrent()
            // Destroy previous EGLImage (Adreno workaround: destroy BEFORE any texture ops)
            destroyCurrentEglImage()

            // Create EGLImage from HardwareBuffer (zero-copy GPU pointer alias)
            val image = androidx.opengl.EGLExt.eglCreateImageFromHardwareBuffer(
                eglDisplay, hardwareBuffer
            ) ?: return false
            currentEglImage = image

            // Bind to dedicated input texture (NOT textures[0] which is an FBO attachment)
            if (inputTexture == 0) {
                val texId = IntArray(1)
                GLES20.glGenTextures(1, texId, 0)
                inputTexture = texId[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
            }

            androidx.opengl.EGLExt.glEGLImageTargetTexture2DOES(GLES20.GL_TEXTURE_2D, image)
            return true
        } catch (e: Exception) {
            destroyCurrentEglImage()
            return false
        }
    }

    private fun destroyCurrentEglImage() {
        val image = currentEglImage ?: return
        try {
            // Must cast back to EGLImageKHR for the destroy call
            @Suppress("UNCHECKED_CAST")
            androidx.opengl.EGLExt.eglDestroyImageKHR(eglDisplay, image as androidx.opengl.EGLImageKHR)
        } catch (_: Exception) {}
        currentEglImage = null
    }

    fun hasEglImageSupport(): Boolean {
        if (!isInitialized) return false
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: return false
        return extensions.contains("GL_OES_EGL_image")
    }
```

- [ ] **Step 2: Modify blur() to use inputTexture when available**

In `blur()`, replace the `texImage2D` line (line 166) with:
```kotlin
            // Upload input texture: use inputTexture (EGLImage) if set, else texImage2D
            if (inputTexture != 0 && currentEglImage != null) {
                // EGLImage path: inputTexture already contains the content
                // Bind it for the first downsample pass
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[0])
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, input, 0)
            }
```

Then in `performBlurPasses`, the first downsample pass reads from `currentTexture` which is set to `texs[0]`. We need to start from `inputTexture` instead when EGLImage is active:

```kotlin
            val startTexture = if (inputTexture != 0 && currentEglImage != null) inputTexture else texs[0]
            performBlurPasses(fbs, texs, params.iterations, params.offset,
                renderToWindowSurface = useWindowSurface, startTexture = startTexture)
```

Add `startTexture` parameter to `performBlurPasses`:
```kotlin
    private fun performBlurPasses(..., startTexture: Int = 0) {
        var currentTexture = if (startTexture != 0) startTexture else texs[0]
```

- [ ] **Step 3: Clean up in release()**

```kotlin
        destroyCurrentEglImage()
        if (inputTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(inputTexture), 0)
            inputTexture = 0
        }
```

- [ ] **Step 4: Expose raw HardwareBuffer from HardwareBufferCapture**

In `HardwareBufferCapture.kt`, add a new method that returns the HardwareBuffer without copying:
```kotlin
    /**
     * Captures content and returns the raw HardwareBuffer (GPU-resident).
     * Caller is responsible for closing the Image after use.
     * Returns null if capture fails.
     */
    fun captureToHardwareBuffer(
        blurView: View,
        sourceView: View,
        width: Int,
        height: Int,
        downsampleFactor: Float
    ): android.media.Image? {
        if (width <= 0 || height <= 0) return null
        try {
            if (lastWidth != width || lastHeight != height) {
                releaseResources()
                if (!initializeResources(width, height)) return null
                lastWidth = width
                lastHeight = height
            }
            val reader = imageReader ?: return null
            val renderer = hardwareRenderer ?: return null
            val node = renderNode ?: return null

            val blurViewLocation = IntArray(2)
            val sourceLocation = IntArray(2)
            blurView.getLocationOnScreen(blurViewLocation)
            sourceView.getLocationOnScreen(sourceLocation)
            val offsetX = blurViewLocation[0] - sourceLocation[0]
            val offsetY = blurViewLocation[1] - sourceLocation[1]

            val hiddenViews = mutableListOf<View>()
            try {
                isCapturing = true
                if (blurView.visibility == View.VISIBLE) {
                    blurView.visibility = View.INVISIBLE
                    hiddenViews.add(blurView)
                }
                for (excluded in excludedViews) {
                    if (excluded.visibility == View.VISIBLE) {
                        excluded.visibility = View.INVISIBLE
                        hiddenViews.add(excluded)
                    }
                }
                val canvas = node.beginRecording(width, height)
                try {
                    val scaleX = width.toFloat() / blurView.width
                    val scaleY = height.toFloat() / blurView.height
                    canvas.scale(scaleX, scaleY)
                    canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
                    sourceView.draw(canvas)
                } finally {
                    node.endRecording()
                }
            } finally {
                for (hidden in hiddenViews) hidden.visibility = View.VISIBLE
                isCapturing = false
            }

            renderer.setContentRoot(node)
            val syncResult = renderer.createRenderRequest()
                .setWaitForPresent(true)
                .syncAndDraw()
            if (syncResult != android.graphics.HardwareRenderer.SYNC_OK &&
                syncResult != android.graphics.HardwareRenderer.SYNC_REDRAW_REQUESTED) {
                return null
            }
            return reader.acquireLatestImage()
        } catch (e: Exception) {
            return null
        }
    }
```

- [ ] **Step 5: Build and verify**
- [ ] **Step 6: Commit**

```
feat: EGLImage zero-copy input for OpenGLBlur (API 29+)
```

---

### Task 3: SurfaceTexture capture for API 26-28 + external OES shader

**Files:**
- Create: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/capture/SurfaceTextureCapture.kt`
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/algorithm/OpenGLBlur.kt`

- [ ] **Step 1: Create SurfaceTextureCapture.kt**

New class that uses `Surface.lockHardwareCanvas()` + `SurfaceTexture` to capture the view tree directly into a GL texture with zero CPU pixel access.

Key design: the caller provides a GL texture ID. SurfaceTextureCapture attaches a SurfaceTexture to it, captures via lockHardwareCanvas, and calls updateTexImage. The texture becomes `GL_TEXTURE_EXTERNAL_OES`.

```kotlin
package io.github.ezoushen.blur.capture

import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.View
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.O) // API 26 for lockHardwareCanvas
class SurfaceTextureCapture {
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var lastWidth = 0
    private var lastHeight = 0

    @Volatile
    var isCapturing = false
        private set

    private val excludedViews = mutableListOf<View>()

    fun addExcludedView(view: View) { if (view !in excludedViews) excludedViews.add(view) }
    fun removeExcludedView(view: View) { excludedViews.remove(view) }

    /**
     * Initialize with a GL texture ID. The SurfaceTexture will be attached to this texture.
     * After capture(), the texture contains the view content as GL_TEXTURE_EXTERNAL_OES.
     */
    fun init(glTextureId: Int, width: Int, height: Int) {
        if (lastWidth == width && lastHeight == height && surfaceTexture != null) return
        release()
        val st = SurfaceTexture(glTextureId)
        st.setDefaultBufferSize(width, height)
        surfaceTexture = st
        surface = Surface(st)
        lastWidth = width
        lastHeight = height
    }

    /**
     * Captures the source view into the GL texture via lockHardwareCanvas.
     * After this call, the texture (GL_TEXTURE_EXTERNAL_OES) contains the view content.
     * Returns true on success.
     */
    fun capture(blurView: View, sourceView: View, width: Int, height: Int): Boolean {
        val surf = surface ?: return false
        val st = surfaceTexture ?: return false

        val blurViewLocation = IntArray(2)
        val sourceLocation = IntArray(2)
        blurView.getLocationOnScreen(blurViewLocation)
        sourceView.getLocationOnScreen(sourceLocation)
        val offsetX = blurViewLocation[0] - sourceLocation[0]
        val offsetY = blurViewLocation[1] - sourceLocation[1]

        val hiddenViews = mutableListOf<View>()
        try {
            isCapturing = true
            if (blurView.visibility == View.VISIBLE) {
                blurView.visibility = View.INVISIBLE
                hiddenViews.add(blurView)
            }
            for (excluded in excludedViews) {
                if (excluded.visibility == View.VISIBLE) {
                    excluded.visibility = View.INVISIBLE
                    hiddenViews.add(excluded)
                }
            }

            // lockHardwareCanvas returns a RecordingCanvas (hardware-accelerated)
            val canvas = surf.lockHardwareCanvas()
            try {
                canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                val scaleX = width.toFloat() / blurView.width
                val scaleY = height.toFloat() / blurView.height
                canvas.scale(scaleX, scaleY)
                canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
                sourceView.draw(canvas)
            } finally {
                surf.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            return false
        } finally {
            for (hidden in hiddenViews) hidden.visibility = View.VISIBLE
            isCapturing = false
        }

        // Make texture content available (must be on GL thread with correct context)
        st.updateTexImage()
        return true
    }

    fun release() {
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        lastWidth = 0
        lastHeight = 0
        excludedViews.clear()
    }

    companion object {
        fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
```

- [ ] **Step 2: Add samplerExternalOES shader to OpenGLBlur**

Add new shader constant:
```kotlin
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
```

Add field and init:
```kotlin
    private var downsampleExternalProgram = 0
    private var externalInputTexture = 0
```

In `initShaders()`:
```kotlin
        downsampleExternalProgram = createProgram(VERTEX_SHADER, DOWNSAMPLE_EXTERNAL_FRAGMENT_SHADER)
        // This may fail if GL_OES_EGL_image_external is not supported — that's OK
```

Add method:
```kotlin
    fun getExternalInputTextureId(): Int {
        if (externalInputTexture == 0) {
            val texId = IntArray(1)
            GLES20.glGenTextures(1, texId, 0)
            externalInputTexture = texId[0]
            // External textures need different parameters
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalInputTexture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        return externalInputTexture
    }

    fun hasExternalOesSupport(): Boolean {
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: return false
        return extensions.contains("GL_OES_EGL_image_external")
    }
```

- [ ] **Step 3: Use external downsample program for first pass when input is external OES**

Add `useExternalInput` parameter to `performBlurPasses`:
```kotlin
    private fun performBlurPasses(..., useExternalInput: Boolean = false) {
```

In the first downsample pass (`i == 0`), use the external program:
```kotlin
        GLES20.glUseProgram(if (useExternalInput) downsampleExternalProgram else downsampleProgram)
        for (i in 0 until iterations) {
            if (i == 1) GLES20.glUseProgram(downsampleProgram) // switch back after first pass
```

And bind the external texture for the first pass:
```kotlin
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            if (i == 0 && useExternalInput) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, currentTexture)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexture)
            }
```

- [ ] **Step 4: Add import for GLES11Ext**
```kotlin
import android.opengl.GLES11Ext
```

- [ ] **Step 5: Build and verify**
- [ ] **Step 6: Commit**

```
feat: SurfaceTexture capture + external OES shader for API 26+
```

---

### Task 4: Wire strategies into BlurController

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/BlurController.kt`
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/VariableBlurController.kt`

- [ ] **Step 1: Add strategy resolution to BlurController**

Add method to resolve AUTO:
```kotlin
    private fun resolveStrategy(): BlurPipelineStrategy {
        val requested = config.pipelineStrategy
        if (requested != BlurPipelineStrategy.AUTO) return requested

        val algo = algorithm as? OpenGLBlur
        if (algo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && algo.hasEglImageSupport()) {
                return BlurPipelineStrategy.EGL_IMAGE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && algo.hasExternalOesSupport()) {
                return BlurPipelineStrategy.SURFACE_TEXTURE
            }
        }
        return BlurPipelineStrategy.LEGACY
    }
```

- [ ] **Step 2: Add SurfaceTextureCapture field**
```kotlin
    private var surfaceTextureCapture: SurfaceTextureCapture? = null
```

- [ ] **Step 3: Modify update() to use the resolved strategy**

In `update()`, after computing `scaledWidth`/`scaledHeight` and `effectiveDownsample`, replace the capture + blur section:

```kotlin
        val strategy = resolveStrategy()

        when (strategy) {
            BlurPipelineStrategy.EGL_IMAGE -> {
                // Zero-copy: capture → HardwareBuffer → EGLImage → GL texture
                val hwCapture = capture as? HardwareBufferCapture ?: return false
                val image = hwCapture.captureToHardwareBuffer(view, source, scaledWidth, scaledHeight, effectiveDownsample)
                    ?: return false
                try {
                    val hwBuffer = image.hardwareBuffer ?: return false
                    try {
                        val algo = algorithm as? OpenGLBlur ?: return false
                        if (!algo.prepare(context, scaledWidth, scaledHeight, scaledRadius)) return false
                        if (!algo.setInputFromHardwareBuffer(hwBuffer)) {
                            // EGLImage failed — fall through to legacy
                            return updateLegacy(view, source, scaledWidth, scaledHeight, effectiveDownsample, scaledRadius)
                        }
                        blurredBitmap = algo.blur(captureOutput ?: return false, scaledRadius)
                    } finally {
                        hwBuffer.close()
                    }
                } finally {
                    image.close()
                }
            }
            BlurPipelineStrategy.SURFACE_TEXTURE -> {
                // Zero-copy: lockHardwareCanvas → SurfaceTexture → GL external OES texture
                val algo = algorithm as? OpenGLBlur ?: return false
                if (!algo.prepare(context, scaledWidth, scaledHeight, scaledRadius)) return false
                val texId = algo.getExternalInputTextureId()
                if (surfaceTextureCapture == null) surfaceTextureCapture = SurfaceTextureCapture()
                val stCapture = surfaceTextureCapture!!
                // Forward excluded views
                stCapture.init(texId, scaledWidth, scaledHeight)
                if (!stCapture.capture(view, source, scaledWidth, scaledHeight)) {
                    // lockHardwareCanvas failed — fall through to legacy
                    return updateLegacy(view, source, scaledWidth, scaledHeight, effectiveDownsample, scaledRadius)
                }
                // Blur with external input
                blurredBitmap = algo.blur(captureOutput ?: return false, scaledRadius)
            }
            else -> {
                // LEGACY path (existing code)
                return updateLegacy(view, source, scaledWidth, scaledHeight, effectiveDownsample, scaledRadius)
            }
        }
```

Extract the existing capture+blur code into `updateLegacy()`.

- [ ] **Step 4: Same changes for VariableBlurController**
- [ ] **Step 5: Build and verify**
- [ ] **Step 6: Commit**

```
feat: wire BlurPipelineStrategy into controllers with AUTO selection
```

---

### Task 5: End-to-end visual testing on emulator

This is the critical QA task. Force each strategy and visually verify blur output.

- [ ] **Step 1: Install demo app**

```bash
./gradlew :demoApp:installDebug
```

- [ ] **Step 2: Test AUTO strategy (default)**

Launch app, test all 4 tabs (Uniform, Variable, ColorDodge, Transition). Screenshot each. Verify blur is visible and correct.

- [ ] **Step 3: Force EGL_IMAGE strategy**

Temporarily modify the demo app to pass `BlurPipelineStrategy.EGL_IMAGE`. Test all 4 tabs. Compare screenshots with AUTO — blur should look identical.

- [ ] **Step 4: Force SURFACE_TEXTURE strategy**

Same test. The first downsample pass uses `samplerExternalOES` — verify blur is still correct and not inverted/distorted.

- [ ] **Step 5: Force LEGACY strategy**

Same test. This is the old path — should look identical to previous builds.

- [ ] **Step 6: Edge cases**

- Radius slider 0 → max → 0 with each strategy (no crash)
- All blend modes with each strategy
- Tab switching rapidly
- Transition animation (Blur In/Out)

- [ ] **Step 7: Commit any fixes, update docs**

```
docs: update crossing counts and pipeline diagrams
```

---

## Final Crossing Counts After This Change

```
┌──────────┬────────┬────────┐
│ API      │ Before │ After  │
├──────────┼────────┼────────┤
│ 31+      │   0    │   0    │
│ 29-30    │   2    │   0    │
│ 26-28    │   2    │   0    │
│ 23-25    │   2    │   2    │
└──────────┴────────┴────────┘
```
