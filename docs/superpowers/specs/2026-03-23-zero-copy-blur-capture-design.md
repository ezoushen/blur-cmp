# Zero-Copy Blur Capture Design Spec

## Goal

Eliminate all remaining CPU↔GPU crossings from the Kawase blur pipeline on API 23+, achieving 0 crossings across all supported Android API levels.

## Background

The current pipeline has 2 crossings on API 29+ (HW bitmap → mutable copy, then texImage2D) and 2 crossings on API 23-28 (software capture, then texImage2D). This spec describes two new capture strategies that eliminate both crossings.

## Architecture

```
┌──────────┬──────────────────────────────────────────────┬──────────┐
│ API      │ Pipeline                                     │ Crossings│
├──────────┼──────────────────────────────────────────────┼──────────┤
│ 31+      │ RENDER_EFFECT (already done)                 │    0     │
│ uniform  │ Selected by BlurView/BlurOverlayHost layer   │          │
├──────────┼──────────────────────────────────────────────┼──────────┤
│ 29+      │ EGL_IMAGE: RecordingCanvas → HardwareRenderer│    0     │
│ Kawase   │ → HardwareBuffer → EGLImage                  │          │
│          │ → GL_TEXTURE_2D → Kawase → TextureView       │          │
│          │ Requires: GL_OES_EGL_image extension         │          │
├──────────┼──────────────────────────────────────────────┼──────────┤
│ 26-28    │ SURFACE_TEXTURE: lockHardwareCanvas()        │    0     │
│ Kawase   │ → RecordingCanvas → SurfaceTexture           │          │
│          │ → GL_TEXTURE_EXTERNAL_OES → Kawase           │          │
│          │ → TextureView                                │          │
│          │ Requires: GL_OES_EGL_image_external ext      │          │
├──────────┼──────────────────────────────────────────────┼──────────┤
│ 23-25    │ LEGACY: software capture → texImage2D        │    2     │
│ or       │ → Kawase → TextureView                       │          │
│ fallback │ (lockHardwareCanvas unavailable on 23-25)    │          │
└──────────┴──────────────────────────────────────────────┴──────────┘
```

Note: `Surface.lockHardwareCanvas()` was added in **API 26**, not 23. On API 23-25 the SURFACE_TEXTURE strategy is unavailable and LEGACY is used.

## Components

### 1. BlurPipelineStrategy enum (androidMain only)

```kotlin
enum class BlurPipelineStrategy {
    /** API 31+, uniform blur. RenderNode + RenderEffect.
     *  Selected at BlurView/BlurOverlayHost layer, not by controllers. */
    RENDER_EFFECT,

    /** API 29+. HardwareBuffer → EGLImage → GL_TEXTURE_2D.
     *  Zero crossings. Requires GL_OES_EGL_image extension. */
    EGL_IMAGE,

    /** API 26+. lockHardwareCanvas → SurfaceTexture → GL_TEXTURE_EXTERNAL_OES.
     *  Zero crossings. Requires GL_OES_EGL_image_external extension. */
    SURFACE_TEXTURE,

    /** Any API. Software Canvas → texImage2D. 2 crossings. Always works. */
    LEGACY,

    /** Auto-select best strategy for current device. */
    AUTO;
}
```

Added to `BlurConfig` (androidMain only, NOT in commonMain `BlurOverlayConfig`):

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

Existing presets (`Default`, `Light`, `Medium`, `Heavy`) inherit `AUTO` via the default.

iOS uses system visual effects only — no strategy enum in commonMain.

### 2. AUTO selection logic

```
// At BlurView/BlurOverlayHost level (uniform blur check):
if (API >= 31 && uniform blur)  → RENDER_EFFECT (already handled, not in controller)

// In BlurController/VariableBlurController (Kawase pipeline):
if (config.pipelineStrategy != AUTO) → use specified strategy
else:
  if (API >= 29 && hasGLExtension("GL_OES_EGL_image"))
      → EGL_IMAGE
  if (API >= 26 && hasGLExtension("GL_OES_EGL_image_external"))
      → SURFACE_TEXTURE
  else
      → LEGACY
```

GL extension checks performed at EGL init time, cached for the lifetime of the blur algorithm instance.

### 3. EGL_IMAGE strategy (API 29+)

**New dependency:** `androidx.graphics:graphics-core`

This library provides `eglCreateImageFromHardwareBuffer()` and `glEGLImageTargetTexture2DOES()` accessible from Kotlin/JVM (wraps NDK calls internally via JNI in `egl_utils.cpp`).

**Exact API calls:**
```kotlin
import androidx.opengl.EGLExt
import androidx.opengl.EGLImageKHR

// Import HardwareBuffer as EGLImage (zero-copy GPU pointer alias)
val eglImage: EGLImageKHR = EGLExt.eglCreateImageFromHardwareBuffer(
    eglDisplay,      // android.opengl.EGLDisplay
    hardwareBuffer   // android.hardware.HardwareBuffer
) ?: error("EGLImage creation failed")

// Bind to a DEDICATED input texture (NOT textures[0] — see below)
GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
EGLExt.glEGLImageTargetTexture2DOES(GLES20.GL_TEXTURE_2D, eglImage)

// ... run Kawase blur reading from inputTexture ...

// Destroy EGLImage after blur passes (must be on GL thread)
EGLExt.eglDestroyImageKHR(eglDisplay, eglImage)
```

**Required GL extension:** `GL_OES_EGL_image` (for `glEGLImageTargetTexture2DOES` with `GL_TEXTURE_2D` target). Check via `GLES20.glGetString(GLES20.GL_EXTENSIONS).contains("GL_OES_EGL_image")`.

**Texture slot:** Use a **dedicated input texture** separate from `textures[0]`. The existing `textures[0]` is attached to `framebuffers[0]` as an FBO color attachment — aliasing it as an EGLImage would make the FBO incomplete. Instead:
- Allocate a new `inputTexture` at init time
- Bind EGLImage to `inputTexture` each frame
- First downsample pass reads from `inputTexture` (instead of `textures[0]`)
- Remaining passes use `textures[]` / `framebuffers[]` as before

**EGLImage lifecycle:** Created and destroyed each frame on the GL thread. The `HardwareBuffer` from `ImageReader` must remain alive while the EGLImage references it — close the `Image` after `eglDestroyImageKHR`.

**Shader changes:** None. `GL_TEXTURE_2D` + `sampler2D` works for RGBA-format HardwareBuffers.

### 4. SURFACE_TEXTURE strategy (API 26-28)

**New class:** `SurfaceTextureCapture.kt`

**Lifecycle:**
1. **Init:** Create a GL texture for input (`glGenTextures`). Create `SurfaceTexture(texId)`. Set buffer size via `surfaceTexture.setDefaultBufferSize(scaledWidth, scaledHeight)`. Create `Surface(surfaceTexture)`.
2. **Per frame:** Hide views → `surface.lockHardwareCanvas()` → `decorView.draw(recordingCanvas)` → `surface.unlockCanvasAndPost()` → in GL context: `surfaceTexture.updateTexImage()`. Content is now in `texId` as `GL_TEXTURE_EXTERNAL_OES`.
3. **Resize:** When downsample dimensions change, call `surfaceTexture.setDefaultBufferSize(newW, newH)` BEFORE the next `lockHardwareCanvas`. No need to recreate Surface or SurfaceTexture.
4. **Release:** `surface.release()`, `surfaceTexture.release()`.

**Required GL extension:** `GL_OES_EGL_image_external` (for `samplerExternalOES`). Distinct from `GL_OES_EGL_image` used by the EGL_IMAGE strategy.

**Shader variant:** One new fragment shader for the first downsample pass:
```glsl
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
```
Compiled at init time alongside the existing shaders. Selected for the first downsample pass when input mode is SURFACE_TEXTURE. All subsequent passes use `sampler2D`.

**View exclusion:** Same visibility toggle as DecorViewCapture and HardwareBufferCapture.

**Fallback:** `lockHardwareCanvas()` wrapped in try-catch. On failure → LEGACY.

**Threading:** `lockHardwareCanvas()` and `unlockCanvasAndPost()` on main thread. `updateTexImage()` on GL thread (which is the main thread in our pipeline — same thread).

### 5. Same changes for VariableOpenGLBlur

Both `OpenGLBlur` and `VariableOpenGLBlur` get:
- Dedicated input texture for EGLImage
- `samplerExternalOES` shader variant for first pass
- Input mode selection (EGL_IMAGE / SURFACE_TEXTURE / BITMAP)

### 6. Platform boundary

- `BlurPipelineStrategy` enum: `androidMain` only (in `BlurConfig.kt`)
- `BlurOverlayConfig` (commonMain): unchanged
- iOS: unchanged, uses system visual effects

## New dependency

```kotlin
// blur-cmp/build.gradle.kts, androidMain dependencies
implementation("androidx.graphics:graphics-core:1.0.2")
```

## Testing

Force any strategy on any device via `BlurConfig.pipelineStrategy`:
```kotlin
BlurConfig(radius = 25f, pipelineStrategy = BlurPipelineStrategy.EGL_IMAGE)
BlurConfig(radius = 25f, pipelineStrategy = BlurPipelineStrategy.SURFACE_TEXTURE)
BlurConfig(radius = 25f, pipelineStrategy = BlurPipelineStrategy.LEGACY)
```

Test matrix (all runnable on API 35 emulator):
- Each strategy: verify blur visible, correct orientation, no crash
- Each strategy: radius 0 → max → 0 (no crash, transparent at 0)
- Each strategy: all blend modes (Normal, ColorDodge, etc.)
- Each strategy: variable blur (gradient patterns correct)
- AUTO: verify selects EGL_IMAGE on API 35
- Tab switching between all 4 demo modes with each strategy

## Risks

| Risk | Mitigation |
|------|-----------|
| `GL_OES_EGL_image` absent on budget GPU | Fall back to LEGACY |
| `GL_OES_EGL_image_external` absent | Fall back to LEGACY |
| `lockHardwareCanvas()` fails on vendor ROM | try-catch → LEGACY |
| EGLImage leaked (not destroyed per frame) | Destroy in finally block on GL thread |
| `graphics-core` adds ~50KB | Acceptable for the crossing elimination |
| `SurfaceTexture.updateTexImage()` wrong thread | Always called on main thread (same as GL context) |
| FBO incompleteness from texture aliasing | Dedicated input texture, not `textures[0]` |
