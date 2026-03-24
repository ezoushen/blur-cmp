# blur-cmp: Dynamic Blur Architecture Report

## 1. High-Level Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    commonMain (KMP)                      │
│                                                         │
│  BlurOverlayState ──► BlurOverlayConfig                 │
│    ├── alpha: Float      ├── radius: Float              │
│    ├── isEnabled: Bool   ├── tintColorValue: Long       │
│    └── config ───────────├── tintBlendMode: BlurBlendMode│
│                          ├── tintOrder: TintOrder        │
│                          ├── downsampleFactor: Float     │
│                          ├── gradient: BlurGradientType? │
│                          └── isLive: Boolean             │
│                                                         │
│  expect fun BlurOverlayHost(state, modifier, bg, content)│
└────────────────┬──────────────────────┬─────────────────┘
                 │                      │
        ┌────────▼────────┐    ┌────────▼────────┐
        │   androidMain   │    │     iosMain      │
        │  SurfaceTexture │    │  GPU Compositor  │
        │  + OpenGL Blur  │    │  CABackdropLayer │
        └─────────────────┘    └──────────────────┘
```

The two platforms use **fundamentally different strategies**:
- **Android**: GPU capture (SurfaceTexture/lockHardwareCanvas) → Kawase blur (OpenGL ES 2.0) → glReadPixels → View draw. API 31+: RenderNode + RenderEffect path.
- **iOS**: Zero-copy GPU compositor capture via private `CABackdropLayer`

---

## 2. Android Pipeline

### 2.0 RenderNode + RenderEffect Path (API 31+, uniform blur)

On API 31+, uniform blur uses `RenderNodeBlurController` which replaces
the software capture + OpenGL Kawase pipeline with hardware-accelerated
RenderNode recording + RenderEffect blur:

```
  ┌─── BlurView.onPreDraw (RenderNodeBlurController.update) ─────┐
  │                                                               │
  │  1. CAPTURE via RecordingCanvas (NOT software canvas)         │
  │     captureNode.beginRecording(w, h) → RecordingCanvas        │
  │     decorView.draw(recordingCanvas)                           │
  │       └─ each child: drawRenderNode(child.renderNode)         │
  │          records POINTERS to existing display lists (~0ms)    │
  │     captureNode.endRecording()                                │
  │                                                               │
  │  2. BLUR via RenderEffect (GPU, on RenderThread)              │
  │     captureNode.setRenderEffect(                              │
  │       RenderEffect.createBlurEffect(r, r, CLAMP)             │
  │     )                                                         │
  │     Tint via createChainEffect (controlled by TintOrder):      │
  │       POST_BLUR: tint(blur(source)) — tint after blur (default)│
  │       PRE_BLUR:  blur(tint(source)) — tint before blur         │
  │                                                               │
  │  3. RASTERIZE via HardwareRenderer → HardwareBuffer           │
  │     Severs the RenderNode graph (prevents circular ref)       │
  │     Bitmap.wrapHardwareBuffer() → GPU-resident bitmap         │
  │                                                               │
  └───────────────────────────────────────────────────────────────┘

  ┌─── BlurView.onDraw ──────────────────────────────────────────┐
  │  canvas.drawBitmap(hardwareBitmap)                            │
  │  HWUI draws GPU bitmap without re-upload                      │
  └───────────────────────────────────────────────────────────────┘

  CPU-GPU crossings: 0 for capture, 0 for blur, 0 for render
  Software rasterization: eliminated (was 2-5ms, now ~0ms)
```

**Performance evidence (Pixel 8 Pro API 35 emulator):**

```
  ┌─────────────────────────┬───────────┬───────────┐
  │ Metric                  │ Old Kawase│ RenderNode│
  ├─────────────────────────┼───────────┼───────────┤
  │ Draw→Complete avg       │ 23.88 ms  │ 12.85 ms  │
  │ Draw→Complete min       │ 17.06 ms  │  6.75 ms  │
  │ Janky frames            │ 33.21%    │  2.54%    │
  │ Slow UI thread frames   │ 93        │ 16        │
  │ 50th percentile         │ 48 ms     │ 25 ms     │
  │ 90th percentile         │ 48 ms     │ 32 ms     │
  └─────────────────────────┴───────────┴───────────┘
  ~46% faster draw time, ~13x fewer janky frames
```

**BlurOverlayHost with explicit background** also gets an optimization:
uses Compose `graphicsLayer { renderEffect = ... }` directly — no View
pipeline at all. Supports all blend modes via `createChainEffect()`.

Falls through to Kawase pipeline (Section 2.1+) when:
- API < 31
- Gradient/variable blur (no RenderEffect mask support)

### 2.1 Component Stack

```
┌──────────────────────────────────────────────────────┐
│  Compose Box (BlurOverlayHost)                       │
│  ┌────────────────────────────────────────────────┐  │
│  │  background()  ← user's composable             │  │
│  ├────────────────────────────────────────────────┤  │
│  │  AndroidView(BlurView)                         │  │
│  │    ├── ViewTreeObserver.OnPreDrawListener       │  │
│  │    │     → BlurController.update()              │  │
│  │    └── onDraw(canvas)                           │  │
│  │          → BlurController.draw(canvas)          │  │
│  ├────────────────────────────────────────────────┤  │
│  │  AndroidView(FrameLayout > ComposeView)        │  │
│  │    └── content()  ← sharp overlay               │  │
│  │    (excluded from capture to prevent glow)      │  │
│  └────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

### 2.2 Per-Frame Pipeline

```
       VSYNC / PreDraw
            │
            ▼
  ┌──────────────────┐
  │ isLive && isShown │──No──► skip (hold last frame)
  └────────┬─────────┘
           │ Yes
           ▼
  ┌────────────────────────────────────────────────────┐
  │ 1. CAPTURE (DecorViewCapture)                      │
  │                                                    │
  │    DecorView                                       │
  │    ┌──────────────────────────┐                    │
  │    │  StatusBar               │                    │
  │    │  ComposeContent          │                    │
  │    │    ├─ background()       │  ◄── captured      │
  │    │    ├─ BlurView           │  ◄── skipped       │
  │    │    │   (isCapturing=true │      (returns in   │
  │    │    │    → draw() returns)│       draw())      │
  │    │    └─ ContentOverlay     │  ◄── hidden        │
  │    │       (INVISIBLE during  │      (excludedView)│
  │    │        capture)          │                    │
  │    └──────────────────────────┘                    │
  │                                                    │
  │    Canvas setup:                                   │
  │      scale(1/downsample, 1/downsample)             │
  │      translate(-blurViewX, -blurViewY)             │
  │      sourceView.draw(softwareCanvas)               │
  │                                                    │
  │    Output: downsampled Bitmap (e.g. 270×585 @ 4x) │
  └────────────────────┬───────────────────────────────┘
                       │
                       ▼
  ┌────────────────────────────────────────────────────┐
  │ 2. ADAPTIVE DOWNSAMPLE                             │
  │                                                    │
  │    effectiveDownsample = lerp(1.0, config.factor,  │
  │                               radius / 16.0)      │
  │                                                    │
  │    radius=0  → factor=1.0 (sharp, no pixelation)   │
  │    radius=8  → factor=2.5 (half-scaled)            │
  │    radius=16 → factor=4.0 (full downsample)        │
  │    radius>16 → factor=4.0 (clamped)                │
  └────────────────────┬───────────────────────────────┘
                       │
                       ▼
  ┌────────────────────────────────────────────────────┐
  │ 3. TINT (configurable via TintOrder)              │
  │                                                    │
  │    POST_BLUR (default): tint applied in draw()     │
  │      after blurred bitmap — matches Apple style    │
  │    PRE_BLUR: tint baked into capture bitmap before │
  │      blur — creates softer diffused look           │
  └────────────────────┬───────────────────────────────┘
                       │
                       ▼
  ┌────────────────────────────────────────────────────┐
  │ 4. BLUR (OpenGL ES 2.0 — Dual Kawase)             │
  │                                                    │
  │    Bitmap → GL texture upload                      │
  │                                                    │
  │    ┌─── Downsample passes ───┐                     │
  │    │  tex[0] ──5-tap──► tex[1]  (W/2 × H/2)      │
  │    │  tex[1] ──5-tap──► tex[2]  (W/4 × H/4)      │
  │    │  tex[2] ──5-tap──► tex[3]  (W/8 × H/8)      │
  │    │         ...N iterations                       │
  │    └──────────────────────────┘                     │
  │    ┌─── Upsample passes ────┐                      │
  │    │  tex[N] ──9-tap──► tex[N-1]  (upscale)       │
  │    │  tex[N-1]──9-tap──► tex[N-2]                  │
  │    │         ...back to tex[0]                     │
  │    └──────────────────────────┘                     │
  │                                                    │
  │    glReadPixels → output Bitmap                    │
  └────────────────────┬───────────────────────────────┘
                       │
                       ▼
  ┌────────────────────────────────────────────────────┐
  │ 5. RENDER (BlurView.onDraw)                        │
  │                                                    │
  │    canvas.drawBitmap(blurred, srcRect, dstRect)    │
  │    if (POST_BLUR) drawTint(canvas)  ← tint on top │
  └────────────────────────────────────────────────────┘
```

### 2.3 Dual Kawase Blur Detail

```
  Downsample shader: 5 taps weighted average
  ┌───────────────────────────┐
  │       ○                   │    ○ = sample point
  │     ○ ● ○                 │    ● = center (weight 4x)
  │       ○                   │
  │  sum = center*4 + 4 corners│
  │  gl_FragColor = sum / 8   │
  └───────────────────────────┘

  Upsample shader: 9 taps (8 surrounding + implied center)
  ┌───────────────────────────┐
  │   ○   ○   ○               │
  │     ◉   ◉                 │    ◉ = weight 2x
  │   ○   ●   ○               │    ○ = weight 1x
  │     ◉   ◉                 │
  │   ○   ○   ○               │
  │  gl_FragColor = sum / 12  │
  └───────────────────────────┘

  Iteration count from radius (logarithmic):
    floatIter = log2(radius / BASE_SIGMA)
    iterations = floor(floatIter)  ← clamped [1, 8]
    offset = radius / 2^iterations ← clamped [0.5, 2.0]

  Example: radius=20
    log2(20) ≈ 4.32
    iterations = 4  (16x downsample + 16x upsample)
    offset = 20/16 = 1.25  (interpolates within level)
```

### 2.4 Alpha Transition Strategy

```
  ┌─────────────────────────────────────────────────┐
  │     FADE-IN (alpha 0→1): isLive = ON            │
  │                                                 │
  │     Blur covers background → dirty flag         │
  │     side-effect is invisible to user.           │
  │     Fresh frames captured each tick.            │
  │                                                 │
  │     FADE-OUT (alpha 1→0): isLive = OFF          │
  │                                                 │
  │     Blur holds last captured frame.             │
  │     Background Compose animations stay live     │
  │     because sourceView.draw() is NOT called.    │
  │                                                 │
  │     Root cause of the freeze bug:               │
  │     sourceView.draw(softwareCanvas) clears      │
  │     PFLAG_DIRTY_MASK on all child Views.        │
  │     View.setAlpha() only calls                  │
  │     damageInParent() — it does NOT set           │
  │     PFLAG_INVALIDATED. So HWUI never            │
  │     re-records display lists → animations       │
  │     appear frozen.                              │
  └─────────────────────────────────────────────────┘

  Timeline:
  alpha:  1.0 ──────────► 0.5 ──────────► 0.0 ──► 0.5 ──► 1.0
  isLive: ON              OFF             OFF      ON       ON
  frame:  [live capture]  [holds frame]   [off]    [live]   [live]
               ◄── fade out ──►               ◄── fade in ──►
```

---

## 3. iOS Pipeline

### 3.1 Component Stack

```
  UIWindowScene
  ┌──────────────────────────────────────────────────┐
  │  Main UIWindow (windowLevel = Normal)            │
  │  ┌────────────────────────────────────────────┐  │
  │  │  RootVC.view                               │  │
  │  │  ┌──────────────────────────────────────┐  │  │
  │  │  │  CMP MetalView                       │  │  │
  │  │  │    └── background()                  │  │  │
  │  │  ├──────────────────────────────────────┤  │  │
  │  │  │  Blur Container (UIView, alpha-ctrl) │  │  │
  │  │  │  ┌──────────────────────────────┐    │  │  │
  │  │  │  │ (opt) Pre-blend BackdropView │    │  │  │
  │  │  │  │   └── tintLayer (ColorDodge) │    │  │  │
  │  │  │  ├──────────────────────────────┤    │  │  │
  │  │  │  │ BackdropView                 │    │  │  │
  │  │  │  │   ├── CABackdropLayer        │    │  │  │
  │  │  │  │   │   └── filters: [         │    │  │  │
  │  │  │  │   │       gaussianBlur(r=16) │    │  │  │
  │  │  │  │   │     ]                    │    │  │  │
  │  │  │  │   └── tintLayer (Normal)     │    │  │  │
  │  │  │  └──────────────────────────────┘    │  │  │
  │  │  └──────────────────────────────────────┘  │  │
  │  └────────────────────────────────────────────┘  │
  ├──────────────────────────────────────────────────┤
  │  Content UIWindow (windowLevel = Normal + 1)     │
  │  ┌────────────────────────────────────────────┐  │
  │  │  ComposeUIViewController (opaque=false)    │  │
  │  │    └── content()  ← sharp overlay           │  │
  │  └────────────────────────────────────────────┘  │
  └──────────────────────────────────────────────────┘
```

### 3.2 How CABackdropLayer Works

```
  GPU Compositor (WindowServer / backboardd)
  ┌────────────────────────────────────────────────┐
  │                                                │
  │  Layer tree compositing order (back to front): │
  │                                                │
  │  1. MetalView surface (background)             │
  │        ↓ rendered pixels                       │
  │  2. CABackdropLayer                            │
  │        ↓ captures layers BELOW it              │
  │        ↓ applies CAFilter pipeline             │
  │        ↓ gaussianBlur(inputRadius=16)          │
  │        ↓ outputs blurred pixels                │
  │  3. tintLayer (post-blur color overlay)        │
  │  4. Content UIWindow (sharp overlay)           │
  │                                                │
  │  ALL happens in the GPU compositor.            │
  │  Zero CPU copies. Zero bitmap allocations.     │
  │  Real-time at 120fps with no cost.             │
  └────────────────────────────────────────────────┘
```

### 3.3 BackdropLayer Extraction

```
  UIVisualEffectView(UIBlurEffect(.light))
  ┌──────────────────────────────────────┐
  │  subviews:                           │
  │    [0] _UIVisualEffectBackdropView   │ ◄── extracted
  │         .layer = CABackdropLayer     │
  │         .layer.filters = [           │
  │           gaussianBlur(inputRadius)  │ ◄── template copied
  │         ]                            │
  │    [1] _UIVisualEffectSubview        │
  │    [2] _UIVisualEffectContentView    │
  └──────────────────────────────────────┘

  IosBackdropLayerProvider:
    1. Create throwaway UIVisualEffectView
    2. Walk subviews → find "BackdropView"
    3. Extract gaussianBlur CAFilter template
    4. Store filterClass (NSObjectMeta) for variable blur
    5. removeFromSuperview() → re-parent into our container
```

### 3.4 Tint Modes (controlled by TintOrder)

```
  POST_BLUR (default — matches Apple UIVisualEffectView):
  ┌──────────────────────────────┐
  │  BackdropView (blurred)      │
  │    └── tintLayer             │  ← tint drawn AFTER blur
  │         .backgroundColor     │
  │         .compositingFilter   │
  └──────────────────────────────┘

  PRE_BLUR (opt-in — softer diffused look):
  ┌──────────────────────────────┐
  │  Pre-blend BackdropView      │  ← 2nd extracted backdrop
  │    .filters = [blur(r=0)]    │     (captures raw, no blur)
  │    └── tintLayer             │  ← tint composited on raw pixels
  │         .compositingFilter   │     THEN the main backdrop blurs
  │              = "colorDodge"  │     the already-tinted result
  ├──────────────────────────────┤
  │  Main BackdropView (blurred) │
  │    .filters = [blur(r=16)]   │
  └──────────────────────────────┘
```

---

## 4. Platform Comparison

```
  ┌─────────────────────┬──────────────────────┬──────────────────────┐
  │                     │      ANDROID         │        iOS           │
  ├─────────────────────┼──────────────────────┼──────────────────────┤
  │ Capture method      │ sourceView.draw()    │ CABackdropLayer      │
  │                     │ (software canvas)    │ (GPU compositor)     │
  ├─────────────────────┼──────────────────────┼──────────────────────┤
  │ Blur engine         │ OpenGL ES 2.0        │ CAFilter (Metal)     │
  │                     │ Dual Kawase shader   │ gaussianBlur /       │
  │                     │                      │ variableBlur         │
  ├─────────────────────┼──────────────────────┼──────────────────────┤
  │ CPU copies          │ 2 (capture + read)   │ 0 (zero-copy)       │
  ├─────────────────────┼──────────────────────┼──────────────────────┤
  │ Content overlay     │ ComposeView in       │ Separate UIWindow    │
  │                     │ FrameLayout          │ (windowLevel + 1)    │
  │                     │ (excludedView)       │ (opaque = false)     │
  ├─────────────────────┼──────────────────────┼──────────────────────┤
  │ Tint (POST_BLUR)    │ drawTint(canvas)     │ tintLayer on top of  │
  │                     │ after blur draw      │ backdrop sublayer    │
  ├─────────────────────┼──────────────────────┼──────────────────────┤
  │ Tint (PRE_BLUR)     │ applyTint(bitmap)    │ 2nd BackdropView     │
  │                     │ before blur pass     │ with compositingFilter│
  │                     │                      │ before main blur     │
  ├─────────────────────┼──────────────────────┼──────────────────────┤
  │ Alpha control       │ view.alpha on        │ container.setAlpha() │
  │                     │ BlurView + fade-     │ on blur container    │
  │                     │ direction gating     │ (no freeze issue)    │
  ├─────────────────────┼──────────────────────┼──────────────────────┤
  │ Variable blur       │ VariableBlurView     │ variableBlur CAFilter│
  │                     │ + VariableOpenGLBlur │ + CGImage mask       │
  ├─────────────────────┼──────────────────────┼──────────────────────┤
  │ Frame trigger       │ ViewTreeObserver     │ Automatic (GPU       │
  │                     │ .OnPreDrawListener   │ compositor, always   │
  │                     │                      │ live)                │
  └─────────────────────┴──────────────────────┴──────────────────────┘
```

---

## 5. Data Flow Summary

```
  ┌────────────┐    state.config    ┌───────────────────┐
  │ User Code  │ ─────────────────► │ BlurOverlayState  │
  │            │    state.alpha     │  .config           │
  │            │ ─────────────────► │  .alpha            │
  └────────────┘                    │  .isEnabled        │
                                    └────────┬──────────┘
                                             │
                          ┌──────────────────┼──────────────────┐
                          │                  │                  │
                     ANDROID             commonMain            iOS
                          │                  │                  │
                          ▼                  │                  ▼
                 ┌────────────────┐          │         ┌───────────────┐
                 │ GradientMapper │          │         │ applyConfig() │
                 │  → BlurConfig  │          │         │  → CAFilter   │
                 │  → BlurGradient│          │         │  → tintLayer  │
                 └───────┬────────┘          │         └───────┬───────┘
                         │                   │                 │
                         ▼                   │                 ▼
                 ┌───────────────┐           │         ┌───────────────┐
                 │ BlurView      │           │         │CABackdropLayer│
                 │  .setBlurConfig│          │         │  .filters =   │
                 │  .setIsLive   │           │         │  [gaussianBlur│
                 └───────┬───────┘           │         │   (radius)]   │
                         │                   │         └───────────────┘
                         ▼                   │
                 ┌───────────────┐           │
                 │BlurController │           │
                 │ .capture()    │           │
                 │ .blur()       │           │
                 │ .draw()       │           │
                 │  + drawTint() │           │
                 └───────────────┘           │
```

The key takeaway: iOS gets blur "for free" at the GPU compositor level via private API extraction from `UIVisualEffectView`, while Android implements the full capture→blur→render pipeline using SurfaceTexture GPU capture (API 26+) or software canvas fallback, OpenGL ES 2.0 Dual Kawase shaders with shared downsample chain optimization, and two-flag dirty tracking (`configDirty`/`contentDirty`) to avoid redundant work. Tint order (`TintOrder.POST_BLUR`/`PRE_BLUR`) is configurable on both platforms, defaulting to post-blur (industry standard, matching Apple's UIVisualEffectView).
