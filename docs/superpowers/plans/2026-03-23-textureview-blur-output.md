# TextureView Blur Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the `glReadPixels` + `canvas.drawBitmap` CPU↔GPU roundtrip from the Kawase blur pipeline by rendering the final upsample pass directly to a TextureView's Surface.

**Architecture:** Replace BlurView's onDraw-based bitmap rendering with a TextureView child. OpenGLBlur renders the last upsample pass directly to the TextureView's Surface via `eglCreateWindowSurface` + FBO 0 (no extra blit). HWUI composites the TextureView as a GPU texture — zero bitmap intermediary. Benefits ALL API levels (TextureView is API 14).

**Tech Stack:** TextureView, SurfaceTexture, EGL14, OpenGL ES 2.0, Compose AndroidView

---

## Review-Incorporated Fixes

This plan addresses 7 issues from plan review:
1. **[High] No extra blit** — last upsample pass writes directly to window surface FBO 0
2. **[High] Deferred Surface creation** — `setSurface` stores pending Surface, `prepare()` creates window surface after EGL init
3. **[Medium] Surface leak** — store Surface in a field, release old one before creating new
4. **[Low] Z-order** — documented: TextureView added first, content overlay on top
5. **[Medium] radius=0** — when radius≤0, clear TextureView to transparent via `glClear`
6. **[Medium] Exception safety** — try/finally around window surface rendering
7. **[Low] HW accel** — documented constraint

---

## File Structure

```
MODIFY: blur-cmp/.../algorithm/OpenGLBlur.kt
  - EGL config: PBUFFER_BIT | WINDOW_BIT
  - setSurface()/pendingSurface for deferred creation
  - performBlurPasses: last upsample to window surface FBO 0
  - eglSwapBuffers instead of glReadPixels

MODIFY: blur-cmp/.../algorithm/VariableOpenGLBlur.kt
  - Same Surface output changes

MODIFY: blur-cmp/.../view/BlurView.kt
  - Add TextureView child for Kawase path
  - SurfaceTextureListener wiring
  - Skip onDraw bitmap drawing when Surface active

MODIFY: blur-cmp/.../view/VariableBlurView.kt
  - Same TextureView changes

MODIFY: blur-cmp/.../BlurController.kt
  - Pass Surface to algorithm, hasOutputSurface()

MODIFY: blur-cmp/.../VariableBlurController.kt
  - Same Surface passing
```

---

### Task 1: Add Surface output to OpenGLBlur

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/algorithm/OpenGLBlur.kt`

- [ ] **Step 1: Change EGL config to support window surfaces**

In `initEGL()`, change the config attribs:
```kotlin
EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
```

- [ ] **Step 2: Add Surface management fields**

After the existing EGL fields, add:
```kotlin
    private var windowSurface: EGLSurface? = null
    private var pendingSurface: android.view.Surface? = null

    /**
     * Sets the output Surface. If EGL is not yet initialized, stores as pending
     * and creates the window surface in the next prepare() call.
     */
    fun setSurface(surface: android.view.Surface?) {
        if (pendingSurface === surface) return

        // Release old window surface
        val display = eglDisplay
        val ws = windowSurface
        if (display != null && ws != null && ws != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, ws)
        }
        windowSurface = null

        pendingSurface = surface

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
```

- [ ] **Step 3: Create pending window surface in prepare()**

In `prepare()`, after `isInitialized = true` (line 91), add:
```kotlin
                isInitialized = true
                // Create deferred window surface if setSurface was called before EGL init
                val pending = pendingSurface
                if (pending != null && pending.isValid && windowSurface == null) {
                    createWindowSurface(pending)
                }
```

- [ ] **Step 4: Modify performBlurPasses to render last pass to window surface**

Change the last upsample pass (when `i == 0`) to render to the window surface's default FBO instead of `fbs[0]`. Pass a flag:

Add a parameter to `performBlurPasses`:
```kotlin
    private fun performBlurPasses(
        fbs: IntArray, texs: IntArray, iterations: Int, offset: Float,
        renderToWindowSurface: Boolean = false
    ) {
```

In the upsample loop, change the `i == 0` case:
```kotlin
        for (i in iterations - 1 downTo 0) {
            val targetWidth = if (i == 0) lastWidth else (lastWidth shr i)
            val targetHeight = if (i == 0) lastHeight else (lastHeight shr i)

            if (i == 0 && renderToWindowSurface) {
                // Last pass: render directly to window surface (FBO 0)
                EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            } else {
                val targetFb = if (i == 0) fbs[0] else fbs[i]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFb)
            }
            GLES20.glViewport(0, 0, targetWidth, targetHeight)

            // ... rest unchanged (texture bind, uniform, drawQuad) ...

            currentTexture = texs[if (i == 0) 0 else i]
            currentWidth = targetWidth
            currentHeight = targetHeight
        }
```

- [ ] **Step 5: Modify blur() to use Surface output path**

Replace the glReadPixels block (lines 138-146) with:
```kotlin
            val useWindowSurface = hasOutputSurface()

            performBlurPasses(fbs, texs, params.iterations, params.offset,
                renderToWindowSurface = useWindowSurface)

            if (useWindowSurface) {
                try {
                    EGL14.eglSwapBuffers(eglDisplay, windowSurface)
                } finally {
                    // Always restore PBuffer context for next frame's FBO work
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                }
                return output // bitmap unused but returned for API compat
            }

            // Fallback: glReadPixels (when no Surface)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbs[0])
            val buffer = ByteBuffer.allocateDirect(lastWidth * lastHeight * 4)
                .order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, lastWidth, lastHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            buffer.rewind()
            output.copyPixelsFromBuffer(buffer)
```

- [ ] **Step 6: Handle radius=0 — clear TextureView to transparent**

In `blur()`, the `radius <= 0` early return (lines 120-125) bypasses GL entirely. When a window surface is active, the TextureView would show stale content. Add:
```kotlin
        if (radius <= 0 || !isInitialized) {
            // Clear TextureView to transparent when no blur
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
            // Legacy: pixel copy for bitmap output
            val pixels = IntArray(input.width * input.height)
            ...
        }
```

- [ ] **Step 7: Clean up in release()**

Add before existing EGL cleanup:
```kotlin
        val ws = windowSurface
        if (ws != null && ws != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, ws)
        }
        windowSurface = null
        pendingSurface = null
```

- [ ] **Step 8: Build and verify**

Run: `./gradlew :blur-cmp:compileReleaseKotlinAndroid`

- [ ] **Step 9: Commit**

```bash
git commit -m "feat: OpenGLBlur renders final pass to TextureView Surface

Last upsample pass writes directly to the window surface's FBO 0,
eliminating glReadPixels + canvas.drawBitmap (2 CPU-GPU crossings).

Deferred window surface creation handles setSurface() before EGL init.
radius=0 clears TextureView to transparent. Exception-safe context restore."
```

---

### Task 2: Add Surface output to VariableOpenGLBlur

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/algorithm/VariableOpenGLBlur.kt`

Apply the same pattern as Task 1. Read the file first — the variable blur has a composite pass instead of simple upsample, so the final Surface output happens AFTER the composite pass (not during upsample).

- [ ] **Step 1: Read VariableOpenGLBlur.kt, find EGL init and glReadPixels equivalent**
- [ ] **Step 2: Change EGL config to PBUFFER_BIT | WINDOW_BIT**
- [ ] **Step 3: Add setSurface(), pendingSurface, createWindowSurface(), hasOutputSurface()**
- [ ] **Step 4: Create pending window surface in prepare()**
- [ ] **Step 5: After composite pass, if window surface active: eglMakeCurrent → blit composite FBO to FBO 0 → eglSwapBuffers (for variable blur, an extra blit IS needed since the composite pass writes to a specific FBO, not the window surface)**
- [ ] **Step 6: Handle radius=0 — clear TextureView**
- [ ] **Step 7: Clean up in release()**
- [ ] **Step 8: Build and verify**
- [ ] **Step 9: Commit**

---

### Task 3: Wire TextureView into BlurView

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/view/BlurView.kt`

- [ ] **Step 1: Add imports**

```kotlin
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
```

- [ ] **Step 2: Add TextureView field and Surface lifecycle**

```kotlin
    private var blurTextureView: TextureView? = null
    private var blurSurface: Surface? = null  // stored to release properly

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            blurSurface?.release()
            blurSurface = Surface(st)
            blurController?.setOutputSurface(blurSurface)
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            blurSurface?.release()
            blurSurface = Surface(st)
            blurController?.setOutputSurface(blurSurface)
        }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            blurController?.setOutputSurface(null)
            blurSurface?.release()
            blurSurface = null
            return true
        }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }
```

- [ ] **Step 3: Create TextureView in init block (Kawase path only)**

In `init`, after the controller creation:
```kotlin
        if (!useRenderNode) {
            blurTextureView = TextureView(context).also { tv ->
                tv.isOpaque = false  // transparency required for blur overlay
                tv.surfaceTextureListener = surfaceTextureListener
                // Added first → drawn behind content overlay (FrameLayout z-order)
                addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        }
```

- [ ] **Step 4: Update onDraw — skip bitmap drawing when Surface active**

```kotlin
    override fun onDraw(canvas: Canvas) {
        if (isBlurEnabled && !isInEditMode) {
            isRendering = true
            try {
                if (useRenderNode) {
                    renderNodeController?.draw(canvas)
                } else if (blurController?.hasOutputSurface() != true) {
                    // Fallback: bitmap draw only when no TextureView Surface
                    blurController?.draw(canvas)
                }
            } finally {
                isRendering = false
            }
        }
        super.onDraw(canvas)
    }
```

- [ ] **Step 5: Clean up in onDetachedFromWindow**

```kotlin
        blurController?.setOutputSurface(null)
        blurSurface?.release()
        blurSurface = null
```

- [ ] **Step 6: Build and verify**
- [ ] **Step 7: Commit**

```bash
git commit -m "feat: BlurView uses TextureView for zero-copy Kawase output

TextureView child added for Kawase path. Blur renders directly to the
TextureView's Surface. HWUI composites as a GPU texture — no bitmap.
Surface lifecycle managed via SurfaceTextureListener with proper release."
```

---

### Task 4: Add setOutputSurface to BlurController

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/BlurController.kt`

- [ ] **Step 1: Add methods**

```kotlin
    fun setOutputSurface(surface: android.view.Surface?) {
        (algorithm as? OpenGLBlur)?.setSurface(surface)
    }

    fun hasOutputSurface(): Boolean =
        (algorithm as? OpenGLBlur)?.hasOutputSurface() == true
```

- [ ] **Step 2: Add import**

```kotlin
import io.github.ezoushen.blur.algorithm.OpenGLBlur
```

- [ ] **Step 3: Build and verify**
- [ ] **Step 4: Commit**

```bash
git commit -m "feat: BlurController bridges Surface to OpenGLBlur"
```

---

### Task 5: Same for VariableBlurView + VariableBlurController

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/view/VariableBlurView.kt`
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/VariableBlurController.kt`

- [ ] **Step 1: Add TextureView to VariableBlurView (same pattern as Task 3)**
- [ ] **Step 2: Add setOutputSurface/hasOutputSurface to VariableBlurController**
- [ ] **Step 3: Build and verify**
- [ ] **Step 4: Commit**

```bash
git commit -m "feat: VariableBlurView uses TextureView for zero-copy output"
```

---

### Task 6: Test on emulator

- [ ] **Step 1: Install and test all 4 demo tabs**
- [ ] **Step 2: Test radius slider 0 → max → 0 (verify transparent clear at 0)**
- [ ] **Step 3: Test tab switching (verify no crash)**
- [ ] **Step 4: Test rotation (Surface size change)**
- [ ] **Step 5: Verify TextureView is actually receiving content (not showing stale/empty)**

---

### Task 7: Measure performance and update docs

- [ ] **Step 1: Profile with dumpsys gfxinfo**
- [ ] **Step 2: Update docs/android-blur-optimization.md with new roundtrip counts**
- [ ] **Step 3: Commit**

---

## Final Roundtrip Counts

```
  ┌──────────┬──────────────────────────────────────────┬────────┐
  │ API      │ Pipeline                                 │Crossings│
  ├──────────┼──────────────────────────────────────────┼────────┤
  │ 31+      │ RecordingCanvas → RenderEffect           │   0    │
  │ uniform  │   → HardwareRenderer → HW Bitmap         │        │
  ├──────────┼──────────────────────────────────────────┼────────┤
  │ 29+      │ RecordingCanvas → HardwareRenderer        │        │
  │ Kawase   │   → HW Bitmap → mutable Bitmap  ①CPU    │   1    │
  │          │   → texImage2D → Kawase                  │(was 3) │
  │          │   → last pass to TextureView Surface     │        │
  ├──────────┼──────────────────────────────────────────┼────────┤
  │ < 29     │ software capture  ①CPU → texImage2D ②GPU │   2    │
  │ Kawase   │   → Kawase → TextureView Surface         │(was 4) │
  └──────────┴──────────────────────────────────────────┴────────┘
```

**Constraint:** TextureView requires hardware-accelerated window (default for all Activities since API 14). Non-HW-accelerated windows fall back to glReadPixels + drawBitmap.
