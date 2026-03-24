# Android Blur Pipeline — Performance Report

## Final Results (Pixel 9, Variable Blur, 270x600 @ 4x downsample)

```
  p50: 5.55ms    avg: 5.83ms    p90: 7.40ms    σ: 1.4ms
```

38% faster than the unoptimized baseline (p50 8.91ms). Comfortably within 16ms
budget for 60fps, with headroom for 120fps on flagship devices.

### Per-Component Breakdown

| Component | Baseline | Optimized | Change |
|-----------|----------|-----------|--------|
| Capture (SurfaceTexture) | 2.99ms | 1.78ms | -40% |
| Upload (OES texture) | 0.61ms | 0.11ms | -82% |
| Kawase pyramid | 4.75ms | 2.16ms | -55% |
| Composite | 0.18ms | 0.15ms | -17% |
| Output (glReadPixels) | 0.48ms | 0.97ms | +102% |
| **Total** | **9.59ms** | **5.83ms** | **-39%** |

### Cross-Device Results

| Device | GPU | p50 | Notes |
|--------|-----|-----|-------|
| Pixel 9 | Mali-G715 (10+ cores) | 5.55ms | Flagship, 120fps capable |
| OPPO Reno3 Pro | PowerVR GM 9446 | 7.28ms | Mid-range, glReadPixels very slow (8.5ms) — TextureView output critical |
| Samsung A35 | Mali-G68 | 6.78ms | Budget, good performance |
| Samsung A34 | Mali-G68 | 8.12ms | Budget, within 60fps |
| Samsung A51 | Mali-G72 MP3 (3 cores) | 15.2ms | 2018 GPU, composite shader is bottleneck (3.2ms due to thread divergence) |

---

## Optimizations Applied

| # | Optimization | Impact | Mechanism |
|---|-------------|--------|-----------|
| 1 | Shared downsample chain | -1.15ms | Single downsample chain reused by all pyramid levels. Process shallowest-first so upsample only overwrites completed work textures. Cuts draw calls from 40 to 20. |
| 2 | Branchless composite sampling | ~0ms | Reverted selective sampling — older Mali GPUs (G72) suffer thread divergence with branched texture fetches. Branchless costs nothing on modern GPUs due to texture cache. |
| 3 | Cached uniform/attribute locations | -0.5ms | Resolve `glGetUniformLocation`/`glGetAttribLocation` once after shader compilation instead of 28 string lookups per frame. |
| 4 | SurfaceTexture GPU capture | -1.2ms | `lockHardwareCanvas` records display list refs (~0ms) instead of CPU software rasterization (~3ms). OES texture eliminates texImage2D upload. |
| 5 | Direct pyramid FBO write | -0.5ms | Final upsample pass writes directly to pyramid framebuffer, eliminating one full-resolution blit per level. |
| 6 | Reduced GL state changes | -0.1ms | Hoisted `glUseProgram` out of per-level loop, batched vertex attrib enable/disable, set constant sampler uniforms once at init. |

---

## Failed Approaches and Caveats

### HardwareBufferCapture was slower than software capture

`HardwareRenderer.syncAndDraw(waitForPresent=true)` + `bitmap.copy(ARGB_8888)` costs
3.12ms vs DecorViewCapture's 2.74ms on Pixel 9. The GPU sync blocking and readback
overhead exceeded the cost of HWUI's optimized display list replay to software canvas.

**Lesson:** Emulator measurements (software GL, 3-5x inflation) do not predict real
device performance. Always profile on real hardware.

### EGLImage zero-copy was slower than bitmap copy

Per-frame `eglCreateImageFromHardwareBuffer` + `eglDestroyImageKHR` costs ~1ms,
exceeding the ~0.3ms cost of `bitmap.copy` + `texImage2D` at 4x downsampled resolution.

**Lesson:** Fewer CPU↔GPU crossings ≠ faster. At small buffer sizes (270x600 = 162K
pixels), the crossing cost is negligible and the EGLImage lifecycle overhead dominates.

### TextureView output — same speed as glReadPixels, but critical for TBDR GPUs

On Pixel 9 (Mali), TextureView swap (0.52ms) ≈ glReadPixels (0.48ms). But on OPPO
Reno3 Pro (PowerVR, tile-based deferred rendering), glReadPixels costs 8.55ms because
TBDR must resolve all tiles to linear memory. TextureView eliminates this entirely.

**TextureView is kept** for TBDR GPU compatibility despite producing harmless
`FrameEvents: updateAcquireFence` log spam (see below).

### FrameEvents log spam — cosmetic, cannot be suppressed

`SurfaceTextureCapture` uses `lockHardwareCanvas` (HWUI producer) → `updateTexImage`
(non-HWUI EGL consumer). The consumer's `FrameEventHistory` has no matching entries
from the producer, causing `E/FrameEvents: updateAcquireFence: Did not find frame`
on every frame.

**Investigated and rejected:**
- Single-buffer SurfaceTexture (`singleBufferMode=true`): deadlocks
- `setOnFrameAvailableListener`: no effect on real devices
- `eglSwapInterval(0)`: no effect (issue is frame tracking, not vsync)
- Removing TextureView: eliminates errors but 8.5ms slower on PowerVR

**Root cause:** Native `ALOGE` in `frameworks/native/libs/gui/FrameTimestamps.cpp`.
Cannot be suppressed from app code. No functional impact.

### Excluded view glow artifact with SurfaceTextureCapture

`SurfaceTextureCapture` is created lazily on first frame, but `addExcludedView()` is
called during Compose factory creation (before first frame). Two bugs compounded:

1. `surfaceTextureCapture?.addExcludedView(view)` silently dropped when null
2. `SurfaceTextureCapture.init()` calls `release()` which cleared `excludedViews`

Fix: pending excluded views list in controllers, flushed when SurfaceTextureCapture
is created. `release()` no longer clears `excludedViews`.

### glFinish() timing barriers inflate measurements

Adding `glFinish()` between pipeline stages for per-component timing accuracy adds
~1.2ms of artificial overhead by forcing GPU pipeline flushes. Production-accurate
measurements require `nanoTime()` only, with `eglSwapBuffers` as the natural sync point.

### Selective texture sampling hurts old GPUs

Branching with texture fetches inside `if/else` (sampling only 2 of 6 pyramid textures)
causes thread divergence on Mali-G72 MP3, costing 3.2ms vs 0.15ms for the branchless
version that samples all 6. Reverted to branchless — costs ~0ms on modern GPUs due to
texture cache coherence at 270x600 resolution.

---

## Profiling Infrastructure

Gated behind `BuildConfig.BLUR_PERF_ENABLED` (default: false). Zero overhead when
disabled — R8 eliminates all dead branches in release builds.

```bash
./gradlew :demoApp:assembleDebug -Pblur.perf.enabled=true
```

Enables:
- On-screen `PerfOverlay` composable with per-component timing
- `BlurPerf` logcat tag with microsecond breakdown
- `BlurPerfMonitor` singleton for programmatic access

### Memory Stability

Verified stable over 2 minutes continuous blur at 60fps:
- Java heap: 5.2-6.0 MB (flat)
- Native heap: 5.7-7.9 MB (flat after 20s settle)
- Total PSS: 65-69 MB (±3MB GC fluctuation)

Key allocations cached: `readPixelsBuffer` (ByteBuffer), `sortedStops` (gradient),
`BLEND_MODE_VALUES` (enum array). No per-frame heap allocations in the blur pipeline.
