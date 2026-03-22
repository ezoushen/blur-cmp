# Android Blur Pipeline Optimization Plan

## Problem Statement

The current Android blur pipeline has **4 CPU↔GPU boundary crossings** per frame:

```
  DecorView ──software draw──► CPU Bitmap ──texImage2D──► GL Texture
      ①  GPU→CPU                    ②  CPU→GPU

  GL Texture ──Kawase blur──► GL FBO ──glReadPixels──► CPU Bitmap
                                         ③  GPU→CPU

  CPU Bitmap ──canvas.drawBitmap──► HWUI ──uploads──► GPU
                    ④  CPU→GPU

  Total: GPU→CPU→GPU→CPU→GPU
```

Each crossing involves memory bus transfer latency and synchronization stalls.
iOS has zero copies — `CABackdropLayer` operates entirely in the GPU compositor.

---

## Research Summary

### What other libraries do

| Library | Approach | CPU↔GPU Copies | API |
|---|---|---|---|
| **BlurView 3.0** (Dimezis) | RenderNode + RenderEffect | 0 (API 31+) | 31+ |
| **Haze** (Chris Banes) | GraphicsLayer + AGSL/RenderEffect | 0 (API 31+) | 31+ |
| **Telegram** | Native Stack Blur on background thread | 2 per frame | Any |
| **RealtimeBlurView** | Software canvas + RenderScript | 2 per frame | 17+ |
| **Android system blur** | SurfaceFlinger compositor | 0 | 31+ (window-level only) |

### Key APIs discovered

| API | Level | Purpose |
|---|---|---|
| `HardwareBuffer` | 26+ | GPU-resident buffer shareable across APIs |
| `Bitmap.wrapHardwareBuffer()` | 29+ | Zero-copy Hardware Bitmap from HardwareBuffer |
| `EGLImageKHR` from HardwareBuffer | 26+ | Import HardwareBuffer as OpenGL texture |
| `RenderNode.beginRecording()` | 29+ | Hardware-accelerated draw op recording |
| `HardwareRenderer` + `ImageReader` | 29+ | GPU-side rasterization to HardwareBuffer |
| `RenderEffect.createBlurEffect()` | 31+ | GPU Gaussian blur on RenderThread |
| `Window.setBackgroundBlurRadius()` | 31+ | Compositor-level window blur (cross-window only) |

---

## Optimization Plan: 3 Tiers

### Tier 1: RenderEffect Fast Path (API 31+, uniform blur)

**Eliminates: ALL copies (0 CPU↔GPU crossings)**

For non-gradient blur on API 31+, bypass the entire OpenGL pipeline:

```
  RenderNode.beginRecording()
    └─ rootView.draw(recordingCanvas)     ← records GPU draw ops (no rasterization)
  RenderNode.setRenderEffect(BlurEffect)
    └─ GPU Gaussian blur on RenderThread
  canvas.drawRenderNode(node)
    └─ composited directly by HWUI

  Pipeline: [GPU draw ops] → [GPU blur] → [GPU composite]
  CPU involvement: zero pixel access
```

**Tradeoff:** Uses Gaussian blur (not Kawase). Visual difference is negligible for
typical radii. Kawase is ~5x faster at large radii, but since the entire pipeline
is now GPU-only on the RenderThread, this is a net win.

**Not applicable to:** Variable/gradient blur (no RenderEffect support for this).

### Tier 2: HardwareBuffer Pipeline (API 29+)

**Eliminates: Steps 4-5 (glReadPixels + HWUI re-upload = 2 crossings removed)**

For variable blur, or when Tier 1 is unavailable:

```
  DecorView.draw(softwareCanvas) → CPU Bitmap     ← still needed < API 31
    OR
  RenderNode → HardwareRenderer → HardwareBuffer  ← API 29+ (GPU capture)

  CPU/GPU Bitmap → texImage2D → GL Texture
  Kawase blur passes → HardwareBuffer-backed FBO
    └─ via EGLImageKHR(AHardwareBuffer)
  Bitmap.wrapHardwareBuffer() → Hardware Bitmap
    └─ HWUI draws directly (zero re-upload)
```

On API 31+, combined with RenderNode capture, this is also fully GPU-resident.
On API 29-30, software capture remains but readback + re-upload are eliminated.

**Required EGL extensions (check at runtime):**
- `EGL_ANDROID_image_hardware_buffer`
- `EGL_ANDROID_get_native_client_buffer`
- `GL_OES_EGL_image`

### Tier 3: Legacy Software Pipeline (API < 29)

**Current pipeline, unchanged.** No optimization is possible without HardwareBuffer.

```
  software capture → texImage2D → Kawase → glReadPixels → canvas.drawBitmap
  4 CPU↔GPU crossings
```

---

## API Level Decision Matrix

```
  ┌──────────┬───────────────┬─────────────────────────────────┐
  │ API      │ Blur Type     │ Strategy                        │
  ├──────────┼───────────────┼─────────────────────────────────┤
  │ 31+      │ Uniform       │ Tier 1: RenderEffect            │
  │ 31+      │ Variable      │ Tier 2: RenderNode capture +    │
  │          │               │         HardwareBuffer Kawase   │
  │ 29-30    │ Any           │ Tier 2: Software capture +      │
  │          │               │         HardwareBuffer Kawase   │
  │ < 29     │ Any           │ Tier 3: Legacy pipeline         │
  └──────────┴───────────────┴─────────────────────────────────┘
```

---

## Implementation Architecture

```kotlin
interface BlurStrategy {
    fun init(blurView: View, sourceView: View)
    fun setConfig(config: BlurConfig)
    fun update(): Boolean
    fun draw(canvas: Canvas)
    fun release()
    fun addExcludedView(view: View)
    fun removeExcludedView(view: View)
    fun isCapturing(): Boolean
}

// Selection at runtime:
fun createStrategy(context: Context, config: BlurConfig): BlurStrategy {
    return when {
        Build.VERSION.SDK_INT >= 31 && config.gradient == null
            -> RenderEffectBlurStrategy(context)        // Tier 1
        Build.VERSION.SDK_INT >= 29 && hasEglExtensions()
            -> HardwareBufferBlurStrategy(context)      // Tier 2
        else
            -> LegacyBlurStrategy(context)              // Tier 3
    }
}
```

### HardwareBuffer FBO Setup (Tier 2 core)

```kotlin
// Allocate HardwareBuffer for blur output
val hwBuffer = HardwareBuffer.create(
    width, height, HardwareBuffer.RGBA_8888,
    1, // layers
    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
)

// Import into OpenGL via EGLImage
val clientBuffer = EGL14.eglGetNativeClientBufferANDROID(hwBuffer)  // NDK needed
val eglImage = EGL14.eglCreateImageKHR(
    display, EGL_NO_CONTEXT,
    EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attrs)

// Bind to FBO texture
glBindTexture(GL_TEXTURE_2D, outputTexture)
glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, eglImage)
glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outputTexture, 0)

// After blur: wrap as Hardware Bitmap (zero-copy)
val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, ColorSpace.get(Named.SRGB))
```

### RenderNode Capture (API 29+ replacement for software draw)

```kotlin
// Record draw ops (no pixel rasterization)
val captureNode = RenderNode("blur_capture")
captureNode.setPosition(0, 0, width, height)
val canvas = captureNode.beginRecording(width, height)
canvas.save()
canvas.scale(1f / downsample, 1f / downsample)
canvas.translate(-offsetX, -offsetY)
sourceView.draw(canvas)  // records to RecordingCanvas, NOT software
canvas.restore()
captureNode.endRecording()

// Rasterize to HardwareBuffer via HardwareRenderer
val imageReader = ImageReader.newInstance(
    scaledW, scaledH, PixelFormat.RGBA_8888, 2,
    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
)
val renderer = HardwareRenderer()
renderer.setSurface(imageReader.surface)
renderer.setContentRoot(captureNode)
renderer.createRenderRequest().syncAndDraw()

val image = imageReader.acquireNextImage()
val captureHwBuffer = image.hardwareBuffer  // GPU-resident, zero CPU access
```

---

## Expected Performance Impact

| Metric | Current (Tier 3) | Tier 2 (API 29+) | Tier 1 (API 31+) |
|---|---|---|---|
| CPU↔GPU crossings | 4 | 2 (capture only) | 0 |
| Memory bus transfers | ~4 per frame | ~2 per frame | 0 |
| glReadPixels stall | ~1-3ms | eliminated | eliminated |
| HWUI texture upload | ~0.5-1ms | eliminated | eliminated |
| Software canvas draw | ~2-5ms | eliminated (API 31+) | eliminated |
| Total overhead saved | baseline | ~2-4ms/frame | ~4-8ms/frame |

---

## References

- [Dimezis/BlurView](https://github.com/Dimezis/BlurView) — RenderEffect approach on API 31+
- [chrisbanes/haze](https://github.com/chrisbanes/haze) — GraphicsLayer + AGSL for Compose
- [Android RenderScript Migration Guide](https://developer.android.com/guide/topics/renderscript/migrate) — HardwareRenderer + ImageReader pattern
- [Android Window Blurs](https://source.android.com/docs/core/display/window-blurs) — SurfaceFlinger blur
- [EGL_ANDROID_image_hardware_buffer](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_image_hardware_buffer.txt) — extension spec
- [Glide Hardware Bitmaps](https://bumptech.github.io/glide/doc/hardwarebitmaps.html) — HWUI zero-copy confirmation

## Status (2026-03-22)

### Implemented
- **Tier 1: RenderEffect Fast Path (API 31+)** — Merged in `feat/android-blur-zero-copy` branch.
  Pure Compose `graphicsLayer` with `RenderEffect.createBlurEffect()`. Zero CPU-GPU copies.
  Applies to uniform blur with Normal blend mode tint only.

### Deferred
- **Tier 2: HardwareBuffer FBO Output (API 29+)** — Deferred based on review findings:
  - EGL functions (`eglGetNativeClientBufferANDROID`, `glEGLImageTargetTexture2DOES`) are NDK-only, not callable from Kotlin/JVM
  - At 4x downsample, `glReadPixels` costs only ~0.2ms — not the actual bottleneck
  - GPU driver bugs on Adreno (double-free in `eglDestroyImageKHR`) and Mali (crashes in `EGLImageTargetTexture2DOES`)
  - Would add ~500 LOC + 4-6MB RAM per BlurView for ~0.3ms savings

- **Tier 3: RenderNode Capture (API 29+)** — Deferred. Blocked on Tier 2.
  Also: `HardwareRenderer` ANR on Mali GPUs (Google #274207636), breaks under memory pressure on Android 14 (Flutter #147578).

- **Controller Duplication Refactor** — Orthogonal to optimization. Separate PR.
