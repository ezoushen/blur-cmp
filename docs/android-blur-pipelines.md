# Android Blur Pipeline Sequence Diagrams

Three distinct pipelines selected by API level and blur type.

---

## Pipeline A: API 31+ Uniform Blur (RenderNodeBlurController)

**0 CPU↔GPU crossings. Everything stays on GPU.**

```
     CPU (Main Thread)              │  GPU (RenderThread / HWUI)
     ───────────────────────────────│──────────────────────────────────
                                    │
  ┌─ onPreDraw ─────────────────┐   │
  │                              │   │
  │  Hide BlurView (INVISIBLE)   │   │
  │  Hide excluded views         │   │
  │                              │   │
  │  captureNode.beginRecording()│   │
  │       │                      │   │
  │       ▼                      │   │
  │  decorView.draw(recCanvas)   │   │
  │       │                      │   │
  │       │  For each child:     │   │
  │       │  updateDisplayList() │   │
  │       │  → returns existing  │   │
  │       │    RenderNode (cached)   │
  │       │  recCanvas.drawRender│   │
  │       │    Node(child.node)  │   │
  │       │  → records POINTER   │   │
  │       │    (not pixels)      │   │
  │       ▼                      │   │
  │  captureNode.endRecording()  │   │
  │                              │   │
  │  Restore visibility          │   │
  │                              │   │
  │  captureNode.setRenderEffect(│   │
  │    BlurEffect(r, r, CLAMP)   │   │
  │  )                           │   │
  │  ── or chained: ──           │   │
  │  createChainEffect(          │   │
  │    blur, tint) for non-Normal│   │
  │                              │   │
  │  HardwareRenderer            │   │
  │    .syncAndDraw() ──────────────►│  Rasterize captureNode
  │       │  (blocks until done) │   │    ├── Replay display list refs
  │       │                      │   │    ├── Apply RenderEffect blur
  │       │                      │   │    │   (Skia Gaussian, auto-downsample)
  │       │                      │   │    └── Write to ImageReader Surface
  │       ▼                      │   │          │
  │  image = reader              │   │          ▼
  │    .acquireLatestImage()     │   │    HardwareBuffer (GPU memory)
  │       │                      │   │
  │  hwBitmap = Bitmap           │   │
  │    .wrapHardwareBuffer(buf)  │   │    ← zero-copy: wraps GPU buffer
  │                              │   │
  └──────────────────────────────┘   │
                                     │
  ┌─ onDraw(canvas) ─────────────┐   │
  │                              │   │
  │  canvas.drawBitmap(hwBitmap) ────►│  HWUI draws Hardware Bitmap
  │       │                      │   │    └── references existing GPU texture
  │       │  (no re-upload:      │   │       (no CPU→GPU copy)
  │       │   hwBitmap is already│   │
  │       │   GPU-resident)      │   │
  │                              │   │
  └──────────────────────────────┘   │
                                     │
  Crossings: 0                       │
  CPU pixel access: none             │
```

---

## Pipeline B: API 29+ Kawase Blur (BlurController + TextureView)

**1 CPU↔GPU crossing (capture bitmap → GL texture upload).**

```
     CPU (Main Thread)              │  GPU
     ───────────────────────────────│──────────────────────────────────
                                    │
  ┌─ onPreDraw ─────────────────┐   │
  │                              │   │
  │  ── CAPTURE (HardwareBuffer  │   │
  │     Capture, RecordingCanvas)│   │
  │                              │   │
  │  Hide BlurView + excluded    │   │
  │                              │   │
  │  renderNode.beginRecording() │   │
  │  decorView.draw(recCanvas)   │   │  (records display list pointers,
  │  renderNode.endRecording()   │   │   ~0ms, no software rasterization)
  │                              │   │
  │  HardwareRenderer            │   │
  │    .syncAndDraw() ──────────────►│  Rasterize at downsampled size
  │                              │   │    └── Write to HardwareBuffer
  │  image = reader              │   │          │
  │    .acquireLatestImage()     │   │          ▼
  │  hwBitmap = wrapHardware     │   │    GPU-resident bitmap
  │    Buffer(buf)               │   │
  │                              │   │
  │  mutableBitmap = hwBitmap    │   │
  │    .copy(ARGB_8888, true)    │   │    ← GPU→CPU copy ①
  │       │                      │   │      (only crossing in pipeline)
  │       ▼                      │   │
  │  mutable Bitmap (CPU)        │   │
  │                              │   │
  │  ── PRE-BLUR TINT ──         │   │
  │  Canvas(mutableBitmap)       │   │
  │    .drawRect(tintColor,      │   │
  │      blendMode)              │   │
  │  (CPU, only for non-Normal)  │   │
  │                              │   │
  │  ── BLUR (OpenGL Kawase) ──  │   │
  │                              │   │
  │  eglMakeCurrent(pbuffer)     │   │
  │  texImage2D(mutableBitmap)──────►│  Upload to GL texture
  │                              │   │    (CPU→GPU, but same ① crossing
  │  performBlurPasses(          │   │     since the copy above was GPU→CPU)
  │    fbs, texs, iters, offset, │   │
  │    renderToWindowSurface=true│   │
  │  )                           │   │
  │       │                      │   │
  │       │  Downsample passes:  │   │
  │       │  tex[0] → tex[1]     ────►│  GPU: 5-tap Kawase downsample
  │       │  tex[1] → tex[2]     ────►│  GPU: 5-tap Kawase downsample
  │       │  tex[2] → tex[3]     ────►│  GPU: 5-tap Kawase downsample
  │       │  ...N iterations     │   │
  │       │                      │   │
  │       │  Upsample passes:    │   │
  │       │  tex[N] → tex[N-1]   ────►│  GPU: 9-tap Kawase upsample
  │       │  ...                 │   │
  │       │  tex[1] → FBO 0      ────►│  GPU: 9-tap upsample
  │       │  (last pass: FBO 0   │   │    → TextureView window surface
  │       │   = TextureView      │   │    (Y-flipped tex coords)
  │       │   window surface)    │   │
  │       │                      │   │
  │       ▼                      │   │
  │  eglSwapBuffers(windowSurf) ────►│  Present to TextureView
  │  eglMakeCurrent(pbuffer)     │   │    SurfaceTexture
  │                              │   │
  └──────────────────────────────┘   │
                                     │
  ┌─ HWUI composites ───────────┐    │
  │                              │   │
  │  TextureView's SurfaceTexture────►│  HWUI composites TextureView
  │  is a GPU texture that HWUI  │   │    as a native GPU texture layer
  │  composites directly.        │   │    (no bitmap, no re-upload)
  │  No onDraw needed.           │   │
  │                              │   │
  └──────────────────────────────┘   │
                                     │
  Crossings: 1                       │
    ① HW Bitmap → mutable Bitmap     │
      (GPU→CPU, needed for           │
       texImage2D + pre-blur tint)   │
```

---

## Pipeline C: API < 29 Kawase Blur (BlurController + TextureView)

**2 CPU↔GPU crossings (software capture + GL texture upload).**

```
     CPU (Main Thread)              │  GPU
     ───────────────────────────────│──────────────────────────────────
                                    │
  ┌─ onPreDraw ─────────────────┐   │
  │                              │   │
  │  ── CAPTURE (DecorViewCapture│   │
  │     SOFTWARE Canvas) ──      │   │
  │                              │   │
  │  Hide BlurView + excluded    │   │
  │                              │   │
  │  Canvas(mutableBitmap)       │   │
  │    .scale(1/downsample)      │   │
  │    .translate(-offset)       │   │
  │  decorView.draw(swCanvas) ◄─────│  GPU→CPU ①: software rasterize
  │       │                      │   │    entire view tree to CPU bitmap
  │       │  (SLOW: 2-5ms)      │   │    (every child re-renders in
  │       │  Every child View's  │   │     software mode)
  │       │  onDraw() fires      │   │
  │       │  again on the CPU    │   │
  │       ▼                      │   │
  │  mutable Bitmap (CPU)        │   │
  │  at downsampled resolution   │   │
  │                              │   │
  │  Restore visibility          │   │
  │                              │   │
  │  ── PRE-BLUR TINT ──         │   │
  │  (same as Pipeline B)        │   │
  │                              │   │
  │  ── BLUR (OpenGL Kawase) ──  │   │
  │                              │   │
  │  eglMakeCurrent(pbuffer)     │   │
  │  texImage2D(mutableBitmap)──────►│  CPU→GPU ②: upload bitmap
  │                              │   │    to GL texture
  │  performBlurPasses(          │   │
  │    renderToWindowSurface=true│   │
  │  )                           │   │
  │       │                      │   │
  │       │  Downsample passes   ────►│  GPU: Kawase downsample
  │       │  Upsample passes     ────►│  GPU: Kawase upsample
  │       │  Last pass → FBO 0   ────►│  GPU → TextureView Surface
  │       │  (Y-flipped)         │   │
  │       ▼                      │   │
  │  eglSwapBuffers(windowSurf) ────►│  Present to TextureView
  │  eglMakeCurrent(pbuffer)     │   │
  │                              │   │
  └──────────────────────────────┘   │
                                     │
  ┌─ HWUI composites ───────────┐    │
  │                              │   │
  │  TextureView composited      ────►│  GPU texture layer
  │  by HWUI (no bitmap)         │   │  (no onDraw, no re-upload)
  │                              │   │
  └──────────────────────────────┘   │
                                     │
  Crossings: 2                       │
    ① decorView.draw(swCanvas)       │
      CPU software rasterization     │
      (2-5ms — the bottleneck)       │
    ② texImage2D                     │
      CPU bitmap → GPU texture       │
```

---

## Comparison Summary

```
  ┌──────────┬───────────┬───────────┬───────────┬────────────────────────┐
  │          │ Capture   │ Blur      │ Output    │ Crossings              │
  ├──────────┼───────────┼───────────┼───────────┼────────────────────────┤
  │ API 31+  │ Recording │ Render    │ Hardware  │ 0                      │
  │ uniform  │ Canvas    │ Effect    │ Bitmap    │ fully GPU-resident     │
  │          │ (~0ms)    │ (GPU)     │ (zero-copy│                        │
  │          │           │           │  wrap)    │                        │
  ├──────────┼───────────┼───────────┼───────────┼────────────────────────┤
  │ API 29+  │ Recording │ OpenGL    │ TextureView│ 1                     │
  │ Kawase   │ Canvas    │ Kawase    │ Surface   │ HW Bitmap→mutable only │
  │          │ + HW      │ (GPU)     │ (GPU-GPU  │                        │
  │          │ Renderer  │           │  present) │                        │
  ├──────────┼───────────┼───────────┼───────────┼────────────────────────┤
  │ API < 29 │ Software  │ OpenGL    │ TextureView│ 2                     │
  │ Kawase   │ Canvas    │ Kawase    │ Surface   │ sw capture + texImage2D│
  │          │ (2-5ms)   │ (GPU)     │ (GPU-GPU  │                        │
  │          │           │           │  present) │                        │
  └──────────┴───────────┴───────────┴───────────┴────────────────────────┘

  Before this branch: 4 crossings on ALL API levels
  After:              0 / 1 / 2 depending on API level
```
