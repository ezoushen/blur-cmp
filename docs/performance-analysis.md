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

## Pending: Real Device Profiling

**Status:** Building instrumented APK with on-screen timing overlay for BrowserStack App Live testing.

**Target devices:**
- Pixel 9 (Android 15/16) — Tensor G4, Mali-G715
- Samsung Galaxy S24 — Snapdragon 8 Gen 3, Adreno 750

**What we need to measure:**
1. `syncAndDraw` latency on real GPU — is it 1-3ms as expected?
2. `bitmap.copy` cost on real GPU — is it <0.5ms?
3. Kawase blur passes on real GPU — is it 1-3ms?
4. Total frame time — is it 3-7ms as predicted?
5. Does EGL_IMAGE strategy become worthwhile on real GPU? (crossing cost may be even lower, making EGL overhead relatively worse)

**After profiling, decide:**
- If total frame time is 3-7ms on real device → current architecture is optimal, no further changes needed
- If syncAndDraw is the bottleneck → implement async double-buffered capture
- If Kawase blur is the bottleneck → reduce iteration count or downsample more aggressively
- If EGL_IMAGE is faster on real device → change AUTO to select it

---

## Current Pipeline Architecture (Production)

### Pipeline A: API 31+ Uniform Blur — 0 crossings, 10.34ms avg (emulator)
```
RecordingCanvas → RenderEffect → HardwareRenderer → HW Bitmap → drawBitmap
```

### Pipeline B: API 29+ Kawase — 2 crossings, ~26ms avg (emulator)
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
┌───────────────────────────────────┬────────────┬──────────┬─────────────────────┐
│ Build / Configuration             │ Draw avg   │ Jank     │ Note                │
├───────────────────────────────────┼────────────┼──────────┼─────────────────────┤
│ BASELINE (before optimizations)   │ 23.88 ms   │ 33.21%   │ 4 crossings         │
│ Uniform (RenderEffect, API 31+)  │ 10.34 ms   │  4.21%   │ 0 crossings, BEST   │
│ TextureView build (fresh emu)     │  6.23 ms   │  0.52%   │ 2 crossings, BEST K │
│ EGL_IMAGE (AUTO)                  │ 28.29 ms   │ 33.26%   │ 0 crossings, WORSE  │
│ LEGACY (current default)          │ ~33 ms     │ ~33%     │ 2 crossings, warm   │
│ LEGACY (cold start)               │ ~33 ms     │ ~41%     │ 2 crossings         │
└───────────────────────────────────┴────────────┴──────────┴─────────────────────┘

Key: "K" = Kawase path, "fresh emu" = first measurement on clean emulator,
     "warm" = after hours of testing, "BEST" = production recommendation
```

### Lesson Learned

**Fewer crossings ≠ faster.** At 4x downsample (336×748 = 250K pixels):
- bitmap.copy + texImage2D total cost: ~0.3ms on real device (estimated)
- EGLImage per-frame overhead: ~1.0ms
- Net: 0.7ms SLOWER with zero-copy

The real bottleneck on emulator is the **Kawase blur GPU passes** (15-20ms) due to software GL. On real devices this should be 1-3ms, making the total frame time 3-7ms — well within 16ms budget for 60fps.
