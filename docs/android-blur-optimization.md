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

## Status (2026-03-23)

### Implemented

**RenderNodeBlurController (API 31+, uniform blur) — `feat/android-blur-zero-copy` branch**

Replaces the entire software capture + OpenGL Kawase pipeline with:
1. `decorView.draw(RecordingCanvas)` — records display list references (~0ms vs 2-5ms software)
2. `captureNode.setRenderEffect(blurEffect)` — GPU Gaussian blur on RenderThread
3. `HardwareRenderer` → `ImageReader` → `HardwareBuffer` — GPU rasterization (severs RenderNode graph)
4. `Bitmap.wrapHardwareBuffer()` → `canvas.drawBitmap()` — zero-copy GPU bitmap

Also supports all blend modes via `RenderEffect.createChainEffect()`:
- Non-Normal (ColorDodge, Overlay, etc.): `blur(tint(source))` — pre-blur tint
- Normal: `tint(blur(source))` — post-blur overlay

**BlurOverlayHost with explicit background (API 31+)**
Uses pure Compose `graphicsLayer { renderEffect = ... }` — no View pipeline at all.

#### Measured Performance (Pixel 8 Pro API 35 emulator)

```
┌─────────────────────────┬────────────┬─────────────┬──────────────┬─────────┐
│ Metric                  │ Old Kawase │ RenderNode  │ +TextureView │ Total   │
│                         │ (baseline) │ (capture)   │ (output)     │ Change  │
├─────────────────────────┼────────────┼─────────────┼──────────────┼─────────┤
│ Draw→Complete avg       │ 23.88 ms   │ 12.85 ms    │  6.23 ms     │ -74%    │
│ Draw→Complete min       │ 17.06 ms   │  6.75 ms    │  4.37 ms     │ -74%    │
│ Janky frames            │ 33.21%     │  2.54%      │  0.52%       │ -64x    │
│ Slow UI thread frames   │ 93/280     │ 16/629      │  2/764       │ -46x    │
│ 50th percentile         │ 48 ms      │ 25 ms       │ 22 ms        │ -54%    │
│ 90th percentile         │ 48 ms      │ 32 ms       │ 27 ms        │ -44%    │
└─────────────────────────┴────────────┴─────────────┴──────────────┴─────────┘
```

#### What was eliminated
- Software `DecorView.draw(softwareCanvas)` — the 2-5ms capture bottleneck
- `glReadPixels` readback — eliminated via TextureView Surface output
- `canvas.drawBitmap` HWUI re-upload — TextureView composites as GPU texture
- OpenGL EGL context + Dual Kawase shader pipeline (API 31+ only)
- `isLive` fade-direction gating (dirty flag issue gone with RecordingCanvas)

**TextureView output (all API levels):** Final Kawase blur pass renders directly
to TextureView's Surface via `eglSwapBuffers`. HWUI composites the TextureView
as a GPU texture. Eliminates 2 CPU-GPU crossings from the output side.

**RecordingCanvas capture (API 29+):** `decorView.draw(RecordingCanvas)` records
display list pointers instead of software-rendering pixels. Eliminates the
2-5ms capture bottleneck.

### Still using Kawase
- Variable/gradient blur — no RenderEffect equivalent for per-pixel radius
- API < 31 — RenderNode/RenderEffect not available

### CPU↔GPU Crossing Summary

```
┌──────────┬──────────────────────────────────────────┬────────┬────────┐
│ API      │ Pipeline                                 │ Before │ After  │
├──────────┼──────────────────────────────────────────┼────────┼────────┤
│ 31+      │ RecordingCanvas → RenderEffect           │   4    │   0    │
│ uniform  │   → HardwareRenderer → HW Bitmap         │        │        │
├──────────┼──────────────────────────────────────────┼────────┼────────┤
│ 29+      │ RecordingCanvas → HardwareRenderer        │   4    │   2    │
│ Kawase   │   → HW Bitmap → mutable Bitmap  ①CPU    │        │        │
│          │   → texImage2D ②GPU → Kawase             │        │        │
│          │   → last pass to TextureView Surface     │        │        │
├──────────┼──────────────────────────────────────────┼────────┼────────┤
│ < 29     │ software capture  ①CPU → texImage2D ②GPU │   4    │   2    │
│ Kawase   │   → Kawase → TextureView Surface         │        │        │
└──────────┴──────────────────────────────────────────┴────────┴────────┘
```

### Deferred
- **HardwareBuffer FBO Output (API 29+)** — NDK-only EGL functions, GPU driver bugs, premature
- **Controller Duplication Refactor** — Orthogonal. Separate PR.
