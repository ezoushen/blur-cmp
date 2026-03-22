# Android Blur Pipeline Optimization — Implementation Report

**Branch:** `feat/android-blur-zero-copy` — 21 commits
**Device:** Pixel 8 Pro API 35 emulator
**Date:** 2026-03-23

---

## Performance Results

### Uniform Blur (API 31+ RenderEffect path)

```
┌─────────────────────────┬────────────┬────────────┬─────────┐
│ Metric                  │ Before     │ After      │ Change  │
├─────────────────────────┼────────────┼────────────┼─────────┤
│ Draw→Complete avg       │ 23.88 ms   │ 10.34 ms   │ -57%    │
│ Draw→Complete min       │ 17.06 ms   │  6.87 ms   │ -60%    │
│ Janky frames            │ 33.21%     │  4.21%     │  -8x    │
│ Slow UI thread          │ 93/280     │ 30/712     │  -7x    │
│ 50th percentile         │ 48 ms      │ 25 ms      │ -48%    │
│ 90th percentile         │ 48 ms      │ 32 ms      │ -33%    │
└─────────────────────────┴────────────┴────────────┴─────────┘
```

**How:** Bypass the entire View-based pipeline (BlurView, BlurController, OpenGLBlur,
DecorViewCapture) with RenderNode + RenderEffect on API 31+. Zero CPU pixel access.
Supports all blend modes via `createChainEffect`.

### Variable/Gradient Blur (Kawase path, all API levels)

```
┌─────────────────────────┬────────────┬────────────┬─────────┐
│ Metric                  │ Before     │ After      │ Change  │
├─────────────────────────┼────────────┼────────────┼─────────┤
│ Draw→Complete avg       │ 23.88 ms   │ ~33 ms     │  regr.  │
│ Janky frames            │ 33.21%     │ ~33%       │  ~same  │
└─────────────────────────┴────────────┴────────────┴─────────┘
```

Variable blur must use the Kawase pipeline (no RenderEffect equivalent for per-pixel
radius). The optimizations (RecordingCanvas capture, TextureView output) eliminate some
crossings but `HardwareRenderer.syncAndDraw()` adds comparable overhead on the emulator.
Net effect is roughly neutral. Real device performance may differ.

---

## CPU↔GPU Crossings

```
┌──────────┬────────┬────────┐
│ API      │ Before │ After  │
├──────────┼────────┼────────┤
│ 31+      │   4    │   0    │
│ 29-30    │   4    │   2    │
│ 26-28    │   4    │   2    │
│ 23-25    │   4    │   2    │
└──────────┴────────┴────────┘
```

---

## What Was Built

| Feature | API | Status | Impact |
|---------|-----|--------|--------|
| RenderNode + RenderEffect (uniform blur) | 31+ | Production | -57% draw time, -8x jank |
| RenderEffect blend mode chaining | 31+ | Production | All blend modes, zero-copy |
| RecordingCanvas capture (HardwareBufferCapture) | 29+ | Production | Replaces 2-5ms software capture |
| TextureView output (Kawase) | All | Production | Eliminates glReadPixels + drawBitmap |
| RenderNodeBlurController (backdrop blur) | 31+ | Production | Zero-copy for BlurOverlay |
| EGLImage zero-copy capture | 29+ | Opt-in | Slower than LEGACY at downsampled sizes |
| SurfaceTexture zero-copy capture | 26+ | Opt-in | Alternative zero-copy path |
| BlurPipelineStrategy enum | All | Production | Force any strategy for testing |
| BlurOverlayHost graphicsLayer RenderEffect | 31+ | Production | Zero-copy for explicit background |

---

## Key Discovery

**Eliminating crossings does not always mean faster.**

At 4x downsample (336x748 = 250K pixels), the CPU↔GPU crossing cost is:
- `bitmap.copy(ARGB_8888)`: ~0.15ms
- `texImage2D`: ~0.15ms
- Total: ~0.3ms

The EGLImage zero-copy path eliminates these 0.3ms but adds:
- `eglCreateImageFromHardwareBuffer`: ~0.5ms
- `eglDestroyImageKHR`: ~0.2ms
- EGL context management overhead: ~0.3ms
- Total: ~1.0ms

**Net: 0.7ms slower** with zero-copy EGLImage than with the "crossing" bitmap path.

The real wins came from:
1. **RenderEffect (API 31+):** Eliminates the entire Kawase pipeline — biggest win
2. **RecordingCanvas capture:** Eliminates 2-5ms software `DecorView.draw()`
3. **TextureView output:** Eliminates `glReadPixels + drawBitmap` (minor, ~0.3ms)

---

## Architecture Summary

### API 31+ Uniform Blur (0 crossings, production)
```
RecordingCanvas → captureNode.setRenderEffect(blur)
→ HardwareRenderer → HardwareBuffer → Hardware Bitmap → drawBitmap
```

### API 29+ Kawase Blur (2 crossings, production)
```
RecordingCanvas → HardwareRenderer → HW Bitmap
→ bitmap.copy(ARGB_8888) [①GPU→CPU] → texImage2D [②CPU→GPU]
→ Kawase blur → TextureView Surface (eglSwapBuffers)
```

### API 23-28 Kawase Blur (2 crossings, production)
```
decorView.draw(softwareCanvas) [①GPU→CPU] → texImage2D [②CPU→GPU]
→ Kawase blur → TextureView Surface (eglSwapBuffers)
```

### Opt-in Zero-Copy Paths (0 crossings, testing only)
```
EGL_IMAGE:        HardwareBuffer → EGLImage → GL_TEXTURE_2D
SURFACE_TEXTURE:  lockHardwareCanvas → SurfaceTexture → GL_TEXTURE_EXTERNAL_OES
```

---

## Files Changed

```
NEW:  BlurPipelineStrategy.kt          — configurable strategy enum
NEW:  RenderNodeBlurController.kt       — API 31+ backdrop blur
NEW:  SurfaceTextureCapture.kt          — API 26+ lockHardwareCanvas capture
MOD:  BlurConfig.kt                     — pipelineStrategy field
MOD:  BlurController.kt                 — strategy selection + wiring
MOD:  VariableBlurController.kt         — same
MOD:  OpenGLBlur.kt                     — EGLImage input, external OES shader, TextureView output
MOD:  VariableOpenGLBlur.kt             — same
MOD:  HardwareBufferCapture.kt          — raw HardwareBuffer exposure, API 29+ gate
MOD:  DecorViewCapture.kt               — excluded view improvements
MOD:  BlurView.kt                       — RenderNode path + TextureView child
MOD:  VariableBlurView.kt               — TextureView child
MOD:  BlurOverlayHost.android.kt        — RenderEffect + blend mode chaining
MOD:  BlurOverlayHost.kt (commonMain)   — EmptyBackground sentinel
MOD:  build.gradle.kts                  — graphics-core dependency
NEW:  docs/architecture.md              — updated with all pipeline diagrams
NEW:  docs/android-blur-pipelines.md    — CPU/GPU sequence diagrams
NEW:  docs/android-blur-optimization.md — performance data + crossing counts
NEW:  docs/known-android-api-bugs.md    — 25 bug guards, all verified
NEW:  docs/api-bug-triage.md            — 10 issues triaged (1 real, 9 misuse)
```

---

## Known Limitations

- Variable/gradient blur cannot use RenderEffect (no per-pixel radius support)
- EGLImage zero-copy is slower than bitmap copy at downsampled resolutions
- `HardwareRenderer.syncAndDraw()` blocks main thread (potential ANR on Mali GPUs)
- API 23-25: software capture remains (no `lockHardwareCanvas`)
- TextureView adds 1-3 frames latency + triple-buffer memory
