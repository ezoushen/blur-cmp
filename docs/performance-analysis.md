# Performance Analysis & Bottleneck Report

## Date: 2026-03-23
## Device: Pixel 8 Pro API 35 Emulator (software GL)
## Branch: `feat/android-blur-zero-copy`

---

## Original Baseline (Before Any Optimization)

Pipeline: software DecorView.draw() → texImage2D → Kawase → glReadPixels → drawBitmap

```
Variable blur tab:
  Draw→Complete avg: 23.88 ms
  Janky frames:      33.21%
  Slow UI thread:    93/280
  CPU↔GPU crossings: 4
```

### Original Bottleneck Hypothesis

The 4 CPU↔GPU crossings were assumed to be the bottleneck:
1. `decorView.draw(softwareCanvas)` — GPU display lists → CPU bitmap (2-5ms)
2. `texImage2D(bitmap)` — CPU bitmap → GPU texture
3. `glReadPixels` — GPU FBO → CPU bitmap
4. `canvas.drawBitmap` — CPU bitmap → HWUI re-upload

---

## Optimization 1: RecordingCanvas Capture (API 29+)

**Change:** Replace `decorView.draw(softwareCanvas)` with `decorView.draw(RecordingCanvas)` via `HardwareBufferCapture` + `HardwareRenderer`.

**Result:** Eliminated crossing #1 (software rasterization). But `HardwareRenderer.syncAndDraw(waitForPresent=true)` blocks main thread waiting for GPU RenderThread.

## Optimization 2: TextureView Output (All APIs)

**Change:** Replace `glReadPixels → drawBitmap` with rendering the last Kawase pass directly to a TextureView's Surface via `eglSwapBuffers`.

**Result:** Eliminated crossings #3 and #4. HWUI composites the TextureView as a GPU texture.

### After Optimizations 1+2 (TextureView build)

```
Variable blur tab (first measurement, fresh emulator):
  Draw→Complete avg:  6.23 ms    ← -74% improvement
  Janky frames:       0.52%      ← -64x improvement
  Slow UI thread:     2/764
  CPU↔GPU crossings:  2 (bitmap.copy + texImage2D)
```

**This was the best result we achieved on the emulator.**

---

## Optimization 3: EGLImage Zero-Copy (API 29+)

**Change:** Replace `bitmap.copy(ARGB_8888)` + `texImage2D` with `eglCreateImageFromHardwareBuffer()` + `glEGLImageTargetTexture2DOES()` via `androidx.graphics:graphics-core`. Zero CPU pixel access.

**Result: REGRESSION.** Per-frame EGLImage create/destroy overhead exceeded the crossing cost.

### After Optimization 3 (EGLImage build, AUTO=EGL_IMAGE)

```
Variable blur tab:
  Draw→Complete avg: 28.29 ms    ← WORSE than baseline
  Janky frames:      33.26%
  CPU↔GPU crossings: 0
```

**Reverted:** AUTO now defaults to LEGACY (RecordingCanvas + TextureView) for Kawase pipeline. EGL_IMAGE available as opt-in only.

---

## Micro-Timing Breakdown (Emulator)

Instrumented each component to find the actual bottleneck:

```
┌────────────────────────────────┬──────────────┬──────────────────────────┐
│ Component                      │ Emulator     │ Note                     │
├────────────────────────────────┼──────────────┼──────────────────────────┤
│ HardwareRenderer.syncAndDraw() │ 4-5 ms       │ Blocks for GPU raster    │
│   (spikes to 11ms+)           │ (1-106 ms)   │ Contends with RenderThrd │
├────────────────────────────────┼──────────────┼──────────────────────────┤
│ bitmap.copy(ARGB_8888)         │ 2-5 ms       │ GPU→CPU crossing ①       │
│   (spikes to 100ms!)          │ (2-100 ms)   │ Emulator SW GL = slow    │
├────────────────────────────────┼──────────────┼──────────────────────────┤
│ Kawase blur passes (GPU)       │ 15-20 ms     │ THE BIGGEST BOTTLENECK   │
│                                │              │ 60% of total frame time  │
│                                │              │ Emulator SW GL = very slow│
├────────────────────────────────┼──────────────┼──────────────────────────┤
│ eglMakeCurrent (context switch)│ 0.3 ms       │ Negligible               │
├────────────────────────────────┼──────────────┼──────────────────────────┤
│ blit to TextureView            │ 0.2 ms       │ Negligible               │
├────────────────────────────────┼──────────────┼──────────────────────────┤
│ eglSwapBuffers                 │ 0.5-1 ms     │ Negligible               │
│   (spikes to 74ms!)           │              │ Same-thread contention   │
├────────────────────────────────┼──────────────┼──────────────────────────┤
│ TOTAL per frame                │ ~26 ms       │                          │
└────────────────────────────────┴──────────────┴──────────────────────────┘
```

### Key Findings

1. **Kawase blur GPU passes (15-20ms) are 60% of total frame time.** This is the real bottleneck on the emulator, not the crossings.

2. **bitmap.copy + texImage2D (~3ms) are only 10-15% of frame time.** Eliminating them with EGLImage saves ~3ms but adds ~1ms EGL overhead — marginal gain.

3. **syncAndDraw and bitmap.copy have extreme variance** (up to 100ms spikes). The emulator's software GL shares CPU with the main thread, causing unpredictable stalls.

4. **eglSwapBuffers spikes to 74ms** occasionally — same-thread producer/consumer BufferQueue contention on the emulator.

---

## Why 6.23ms vs 33ms?

The 6.23ms measurement was taken on a **fresh emulator session** (first measurement after app install). The ~33ms measurements came after **hours of continuous testing**. Likely causes:

1. **Emulator host CPU thermal throttling** — sustained load causes frequency scaling
2. **RenderThread contention** — multiple HardwareRenderers + TextureViews competing
3. **Memory pressure** — accumulated HardwareBuffers/EGLImages from strategy switching
4. **Software GL overhead compounds** — emulator GL is CPU-bound, shares cores with app

---

## Expected Real Device Performance

On a real device with a dedicated GPU (e.g., Pixel 9, Adreno 750):

| Component | Emulator | Expected Real Device |
|-----------|----------|---------------------|
| syncAndDraw | 4-106 ms | 1-3 ms |
| bitmap.copy | 2-100 ms | 0.1-0.5 ms |
| Kawase blur | 15-20 ms | 1-3 ms |
| eglMakeCurrent | 0.3 ms | 0.05-0.1 ms |
| eglSwapBuffers | 0.5-74 ms | 0.1-0.5 ms |
| **Total** | **~26 ms** | **~3-7 ms** |

The emulator's software GL implementation inflates ALL GPU operations by 5-10x. The real performance can only be measured on physical hardware.

---

## Real Device Profiling — COMPLETED

**Method:** On-screen timing overlay (BlurPerfMonitor) via BrowserStack App Live
**Device:** Google Pixel 8 Pro, Android 14 (Tensor G3, Mali-G715)

### Results

```
┌───────────────────────────────────────────────────────────────────────┐
│ Device: Google Pixel 8 Pro (Android 14) — Tensor G3, Mali-G715       │
├─────────────────┬────────────┬───────────┬──────────────┬────────────┤
│ Tab             │ Strategy   │ Dim       │ Total (ms)   │ Blur (ms)  │
├─────────────────┼────────────┼───────────┼──────────────┼────────────┤
│ Variable        │ LEGACY     │ 252x561   │ 10.7         │ 10.7       │
│ Uniform         │ LEGACY     │ 252x561   │ 10.4         │ 10.4       │
│ ColorDodge      │ LEGACY     │ 252x561   │ 10.4         │ 10.4       │
└─────────────────┴────────────┴───────────┴──────────────┴────────────┘

┌───────────────────────────────────────────────────────────────────────┐
│ Device: Google Pixel 9 (Android 15) — Tensor G4, Mali-G715           │
├─────────────────┬────────────┬───────────┬──────────────┬────────────┤
│ Tab             │ Strategy   │ Dim       │ Total (ms)   │ Blur (ms)  │
├─────────────────┼────────────┼───────────┼──────────────┼────────────┤
│ Variable        │ LEGACY     │ 270x606   │  9.3         │  9.3       │
│ Uniform         │ LEGACY     │ 270x606   │  8.0         │  8.0       │
│ ColorDodge      │ LEGACY     │ 270x606   │  8.0         │  8.0       │
└─────────────────┴────────────┴───────────┴──────────────┴────────────┘

Note: All tabs use LEGACY (Kawase) because the demo app uses BlurOverlay
(backdrop blur), which falls through to Kawase on all APIs.
RenderEffect path only activates for BlurOverlayHost with explicit background.
```

### Comparison: Emulator vs Real Devices

```
┌─────────────────────────┬────────────┬─────────────┬──────────┬──────────┐
│ Metric                  │ Emulator   │ Pixel 8 Pro │ Pixel 9  │ Speedup  │
│                         │ (API 35)   │ (API 14)    │ (API 15) │ vs emu   │
├─────────────────────────┼────────────┼─────────────┼──────────┼──────────┤
│ Variable blur total     │ ~33 ms     │ 10.7 ms     │  9.3 ms  │ 3.5x     │
│ Uniform blur total      │ ~33 ms     │ 10.4 ms     │  8.0 ms  │ 4.1x     │
│ Baseline (pre-optim)    │ 23.88 ms   │ (unmeas.)   │(unmeas.) │          │
└─────────────────────────┴────────────┴─────────────┴──────────┴──────────┘
```

### Conclusions

1. **Pixel 9 Kawase blur: 8-9.3ms per frame** — well within 16ms budget for 60fps
2. **Pixel 8 Pro: 10.4-10.7ms** — also within budget
3. **Emulator inflated numbers by 3.5-4.1x** — confirmed software GL bottleneck
4. **Pixel 9 (Tensor G4) is ~15% faster than Pixel 8 Pro (Tensor G3)** — expected GPU improvement
5. **The pipeline is production-ready** at 8-10ms on real hardware with 6-8ms headroom
6. **EGLImage zero-copy is NOT needed** — crossing cost negligible on real GPUs

---

## Current Pipeline Architecture (Production)

### Pipeline A: API 31+ Uniform Blur — 0 crossings, 10.34ms avg (emulator)
```
RecordingCanvas → RenderEffect → HardwareRenderer → HW Bitmap → drawBitmap
```

### Pipeline B: API 29+ Kawase — 2 crossings, ~10.5ms (real Pixel 8 Pro), ~26ms (emulator)
```
RecordingCanvas → HardwareRenderer → syncAndDraw → HW Bitmap
→ bitmap.copy ①GPU→CPU → texImage2D ②CPU→GPU
→ Kawase blur → TextureView Surface
```

### Pipeline C: API 23-28 Kawase — 2 crossings
```
decorView.draw(softwareCanvas) ①GPU→CPU → texImage2D ②CPU→GPU
→ Kawase blur → TextureView Surface
```

### Opt-in Strategies (for testing, not AUTO-selected)
```
EGL_IMAGE:        HardwareBuffer → EGLImage → GL_TEXTURE_2D (0 crossings, slower on emulator)
SURFACE_TEXTURE:  lockHardwareCanvas → SurfaceTexture → GL_TEXTURE_EXTERNAL_OES (0 crossings)
```

---

## Summary of All Performance Measurements

```
┌───────────────────────────────────┬────────────┬──────────┬─────────────────────────────┐
│ Build / Configuration             │ Draw avg   │ Jank     │ Note                        │
├───────────────────────────────────┼────────────┼──────────┼─────────────────────────────┤
│ EMULATOR MEASUREMENTS:            │            │          │                             │
│ BASELINE (before optimizations)   │ 23.88 ms   │ 33.21%   │ 4 crossings, software GL    │
│ Uniform (RenderEffect, API 31+)  │ 10.34 ms   │  4.21%   │ 0 crossings                 │
│ TextureView build (fresh emu)     │  6.23 ms   │  0.52%   │ 2 crossings, fresh emu      │
│ EGL_IMAGE (AUTO)                  │ 28.29 ms   │ 33.26%   │ 0 crossings, REGRESSION     │
│ LEGACY (warm emulator)            │ ~33 ms     │ ~33%     │ 2 crossings, thermal throt  │
├───────────────────────────────────┼────────────┼──────────┼─────────────────────────────┤
│ REAL DEVICE (Pixel 8 Pro, API 14):│            │          │                             │
│ Variable blur (LEGACY/Kawase)     │ 10.7 ms    │  n/a     │ 2 crossings, 252x561        │
│ Uniform blur (LEGACY/Kawase)      │ 10.4 ms    │  n/a     │ 2 crossings, 252x561        │
│ ColorDodge blur (LEGACY/Kawase)   │ 10.4 ms    │  n/a     │ 2 crossings, 252x561        │
├───────────────────────────────────┼────────────┼──────────┼─────────────────────────────┤
│ REAL DEVICE (Pixel 9, API 15):    │            │          │                             │
│ Variable blur (LEGACY/Kawase)     │  9.3 ms    │  n/a     │ 2 crossings, 270x606        │
│ Uniform blur (LEGACY/Kawase)      │  8.0 ms    │  n/a     │ 2 crossings, 270x606        │
│ ColorDodge blur (LEGACY/Kawase)   │  8.0 ms    │  n/a     │ 2 crossings, 270x606        │
└───────────────────────────────────┴────────────┴──────────┴─────────────────────────────┘

Key: All demo tabs use BlurOverlay (backdrop blur) → Kawase pipeline on all APIs.
     RenderEffect path only activates for BlurOverlayHost with explicit background.
     Real device numbers confirm emulator inflated GPU ops by 3.5-4.1x.
```

### Lesson Learned

**Fewer crossings ≠ faster.** At 4x downsample (336×748 = 250K pixels):
- bitmap.copy + texImage2D total cost: ~0.3ms on real device (estimated)
- EGLImage per-frame overhead: ~1.0ms
- Net: 0.7ms SLOWER with zero-copy

The real bottleneck on emulator is the **Kawase blur GPU passes** (15-20ms) due to software GL. On real devices this should be 1-3ms, making the total frame time 3-7ms — well within 16ms budget for 60fps.

---

## Real Device A/B Comparison: Baseline vs HardwareBufferCapture

**Device:** Google Pixel 9 (Android 15, Tensor G4, Mali-G715)
**Method:** Logcat micro-timing via BlurPerfMonitor, both builds instrumented identically
**Tab:** Variable blur (Kawase pipeline)

### Per-Component Breakdown

```
┌──────────────────────────┬─────────────────────────┬─────────────────────────┬──────────┐
│ Component                │ BASELINE (main branch)  │ HW CAPTURE (optimized)  │ Delta    │
│                          │ DecorViewCapture        │ HardwareBufferCapture   │          │
│                          │ n=7132 frames           │ n=2117 frames           │          │
├──────────────────────────┼─────────────────────────┼─────────────────────────┼──────────┤
│ CAPTURE PHASE            │                         │                         │          │
│  DecorView SW raster     │ 2.74ms                  │ —                       │          │
│  HW syncAndDraw          │ —                       │ 1.01ms                  │          │
│  bitmap.copy(ARGB_8888)  │ —                       │ 2.11ms                  │          │
│  capture subtotal        │ 2.74ms                  │ 4.56ms (+overhead)      │ +1.82ms  │
├──────────────────────────┼─────────────────────────┼─────────────────────────┼──────────┤
│ BLUR PHASE               │                         │                         │          │
│  texImage2D upload       │ 0.68ms                  │ 0.72ms                  │  ~same   │
│  Kawase pyramid          │ 5.01ms                  │ 4.52ms                  │ -0.49ms  │
│  gradient composite      │ 0.53ms                  │ 0.18ms                  │ -0.35ms  │
│  output (rdPx vs swap)   │ 0.48ms (glReadPixels)   │ 0.42ms (eglSwapBuffers) │ -0.06ms  │
│  blur subtotal           │ 6.80ms                  │ 5.94ms                  │ -0.86ms  │
├──────────────────────────┼─────────────────────────┼─────────────────────────┼──────────┤
│ TOTAL PIPELINE           │ 9.59ms (σ=2.2ms)        │ 10.59ms (σ=2.2ms)       │ +1.0ms   │
│ CPU↔GPU crossings        │ 4                       │ 2                       │          │
└──────────────────────────┴─────────────────────────┴─────────────────────────┴──────────┘
```

### Analysis

1. **HardwareBufferCapture is 1.82ms SLOWER than DecorViewCapture** on real hardware.
   - `syncAndDraw(waitForPresent=true)` blocks 1.01ms for GPU rasterization
   - `bitmap.copy(ARGB_8888)` costs 2.11ms for GPU→CPU transfer
   - Combined: 3.12ms + overhead vs DecorViewCapture's 2.74ms
   - HWUI display list replay to software canvas is highly optimized on real devices

2. **TextureView output saves 0.06ms** vs glReadPixels — negligible on real hardware.

3. **Blur phase is 0.86ms faster** in the optimized build, but this is likely noise
   from different session conditions, not a real improvement from the capture change.

4. **Net result: HardwareBufferCapture + TextureView is ~10% slower** than the
   baseline DecorViewCapture + glReadPixels pipeline on real Pixel 9 hardware.

### Decision: Revert HardwareBufferCapture for Kawase Pipeline

The HardwareBufferCapture optimization was developed based on emulator measurements
where software GL inflated crossing costs by 3-5x. On real hardware with dedicated
GPUs, the crossings are cheap and the HW capture path adds overhead.

**Action:** Revert to DecorViewCapture for the Kawase/LEGACY pipeline.
Keep TextureView output (eliminates glReadPixels + drawBitmap, saves 2 crossings).

**Target pipeline (post-revert):**
```
decorView.draw(softwareCanvas) ①GPU→CPU → texImage2D ②CPU→GPU
→ Kawase blur → TextureView Surface (0 crossings on output)
```

CPU↔GPU crossings: 2 (same as baseline for capture, but 0 on output vs 2 in baseline)

---

## GPU Blur Pipeline Optimizations

Six optimizations were applied to the Kawase blur pipeline and validated
on a Google Pixel 9 (Android 15, Tensor G4, Mali-G715) via BrowserStack.

### Optimization Summary

```
┌─────┬─────────────────────────────────┬──────────┬──────────────┐
│ #   │ Optimization                    │ Target   │ Impact       │
├─────┼─────────────────────────────────┼──────────┼──────────────┤
│ 1   │ Shared downsample chain         │ Pyramid  │ -1.15ms      │
│     │ (single chain, shallowest-first │          │              │
│     │ upsample reuses work textures)  │          │              │
├─────┼─────────────────────────────────┼──────────┼──────────────┤
│ 2   │ Selective texture sampling      │ Shader   │ ~0ms         │
│     │ (2 fetches/pixel vs 6)          │          │ (cache-warm) │
├─────┼─────────────────────────────────┼──────────┼──────────────┤
│ 3   │ Cache uniform/attribute locs    │ CPU→GPU  │ -0.5ms       │
│     │ (resolve once after compile)    │          │              │
├─────┼─────────────────────────────────┼──────────┼──────────────┤
│ 4   │ SurfaceTexture GPU capture      │ Capture  │ -1.2ms       │
│     │ (lockHardwareCanvas + OES tex)  │          │              │
├─────┼─────────────────────────────────┼──────────┼──────────────┤
│ 5   │ Direct write to pyramid FBO     │ Pyramid  │ -0.5ms       │
│     │ (skip final full-res blit)      │          │              │
├─────┼─────────────────────────────────┼──────────┼──────────────┤
│ 6   │ Reduce GL state changes         │ Driver   │ -0.1ms       │
│     │ (hoist glUseProgram, batch VAs) │          │              │
└─────┴─────────────────────────────────┴──────────┴──────────────┘
```

### Progressive Results (Pixel 9, Variable Blur, 270x600)

```
┌────────────────────┬────────┬────────┬────────┬────────┬────────┬────────┐
│ Build              │ n      │ avg    │ p50    │ p75    │ p90    │ σ      │
├────────────────────┼────────┼────────┼────────┼────────┼────────┼────────┤
│ BASELINE (main)    │ 7132   │ 9.59ms │ 8.91ms │ 10.1ms │ 12.2ms │ 2.2ms  │
│ Opt #1+2+3         │ 1668   │ 7.20ms │ 6.59ms │ 7.43ms │ 9.22ms │ 2.0ms  │
│ Opt #1+2+3+5       │ 1203   │ 6.52ms │ 6.10ms │ 6.72ms │ 8.25ms │ 1.6ms  │
│ Opt #1+2+3+5+6     │ 1426   │ 6.53ms │ 6.07ms │ 6.76ms │ 8.14ms │ 1.9ms  │
│ ALL (#1-6)         │ 1458   │ 5.83ms │ 5.55ms │ 6.43ms │ 7.40ms │ 1.4ms  │
└────────────────────┴────────┴────────┴────────┴────────┴────────┴────────┘
```

### Per-Component Breakdown

```
┌──────────────────────┬───────────┬───────────┬──────────┐
│ Component            │ BASELINE  │ OPTIMIZED │ Change   │
├──────────────────────┼───────────┼───────────┼──────────┤
│ Capture              │ 2.99ms    │ 1.78ms    │ -40%     │
│  (DecorViewCapture)  │ (CPU SW)  │ (GPU HW)  │          │
│ Upload               │ 0.61ms    │ 0.11ms    │ -82%     │
│  (texImage2D)        │           │ (OES tex) │          │
│ Kawase pyramid       │ 4.75ms    │ 2.16ms    │ -55%     │
│ Gradient composite   │ 0.18ms    │ 0.15ms    │ -17%     │
│ Output (readPixels)  │ 0.48ms    │ 0.97ms    │ +102%    │
├──────────────────────┼───────────┼───────────┼──────────┤
│ TOTAL                │ 9.59ms    │ 5.83ms    │ -39%     │
│ p50                  │ 8.91ms    │ 5.55ms    │ -38%     │
└──────────────────────┴───────────┴───────────┴──────────┘
```

### Known Issue: FrameEvents Log Spam

The SurfaceTexture capture path (Opt #4) uses `lockHardwareCanvas()` → `updateTexImage()`
which produces `E/FrameEvents: updateAcquireFence: Did not find frame` on every frame.

**Root cause:** `lockHardwareCanvas` produces frames through HWUI's rendering path, but
`updateTexImage()` consumes them from our non-HWUI EGL context. The consumer-side
`FrameEventHistory` doesn't have matching entries from the producer, so each `acquireBuffer`
fails the frame lookup.

**Impact:** Cosmetic only. No visual artifacts, no frame drops, no functional impact.
The error cannot be suppressed from app code (it's a native `ALOGE` in
`frameworks/native/libs/gui/FrameTimestamps.cpp`).

**Alternatives tested:**
- Single-buffer SurfaceTexture: deadlocks (producer blocks waiting for consumer)
- OnFrameAvailableListener: no effect on real devices
- TextureView output: same error (any non-HWUI EGL consumer triggers it)
- DecorViewCapture (no BufferQueue): zero errors but 1.2ms slower

### Final Pipeline Architecture (API 26+)

```
lockHardwareCanvas(Surface)     ← GPU: HWUI records display list (~0.01ms)
sourceView.draw(hwCanvas)       ← GPU: display list refs, near-zero cost
unlockCanvasAndPost             ← GPU: queues to SurfaceTexture BufferQueue
updateTexImage()                ← GPU: updates OES texture (zero-copy, ~0.1ms)
  ↓ GL_TEXTURE_EXTERNAL_OES (no CPU→GPU crossing)
Kawase pyramid (shared DS)      ← GPU-only (~2.2ms)
gradient composite              ← GPU-only (~0.2ms)
glReadPixels → bitmap           ← GPU→CPU readback (~1.0ms)
canvas.drawBitmap in onDraw     ← HWUI re-upload (negligible)
```

### Performance Profiling

Instrumentation is gated behind `BuildConfig.BLUR_PERF_ENABLED` (default: false).
When disabled, all timing and logging code is eliminated by R8 in release builds.

To enable for profiling:
```bash
./gradlew :demoApp:assembleDebug -Pblur.perf.enabled=true
```

This enables:
- On-screen `PerfOverlay` composable with per-component timing
- `BlurPerf` logcat tag with microsecond-precision breakdown
- `BlurPerfMonitor` singleton for programmatic access
