# Known Android API Bugs for Blur Pipeline

Compiled from GitHub issues in Dimezis/BlurView, chrisbanes/haze, Flutter, React Native,
Coil, and Google Issue Tracker. These bugs directly affect the APIs used in blur-cmp.

---

## 1. RenderEffect.createBlurEffect() (API 31+)

### BUG: Radius=0 Crash — `IllegalArgumentException: nativePtr is null`
- **Issues:** [Google #241546169](https://issuetracker.google.com/issues/241546169), [BlurView #247](https://github.com/Dimezis/BlurView/issues/247), [React Native #49447](https://github.com/facebook/react-native/pull/49447)
- **Affected:** API 31+ (confirmed API 31, 32)
- **Root cause:** `createBlurEffect()` passes 0 to native code → null pointer → `IllegalArgumentException`
- **Workaround:** Never pass radius <= 0. Use `setRenderEffect(null)` to remove blur.
- **Status:** Unfixed upstream ("Not started")

### BUG: Sub-pixel radius values crash
- **Issue:** [React Native PR #49447](https://github.com/facebook/react-native/pull/49447)
- **Root cause:** `sigmaToRadius` conversion can return negative/zero from valid inputs due to DIP rounding
- **Workaround:** Validate radius > 0 AFTER unit conversion. Values < 0.5 → remove effect.

### GOTCHA: Dynamic radius animation
- **Source:** [Chet Haase blog](https://medium.com/androiddevelopers/blurring-the-lines-4fd33821b83c)
- Guard against animation reaching exactly 0. API 31 flickering during rapid radius changes.

---

## 2. RenderNode Recording + Drawing

### BUG: API 31 — RenderNode fails to refresh if position unchanged
- **Issue:** [Haze #77](https://github.com/chrisbanes/haze/issues/77)
- **Affected:** Android 12 (API 31) ONLY. Fixed on API 32+.
- **Root cause:** RenderNode JNI layer skips re-rendering if `setPosition()` hasn't changed
- **Workaround:** Re-apply `setRenderEffect()` every frame on API 31 to force redraw.

### BUG: `drawRenderNode` fails on software Canvas
- **Issues:** [BlurView #223](https://github.com/Dimezis/BlurView/issues/223), [BlurView #185](https://github.com/Dimezis/BlurView/issues/185)
- **Root cause:** `Canvas.drawRenderNode()` throws `IllegalArgumentException: Software rendering doesn't support drawRenderNode`
- **Workaround:** Fall back to software blur on software canvas.

### LIMITATION: RecordingCanvas bitmap size cap
- **Limit:** 150 MB default. Error: `RuntimeException: Canvas: trying to draw too large bitmap`

---

## 3. HardwareRenderer + ImageReader

### BUG: HardwareRenderer breakage on Android 14 under memory pressure
- **Issue:** [Flutter #147578](https://github.com/flutter/flutter/issues/147578)
- **Affected:** Android 14 (API 34)
- **Root cause:** `trimMemory` breaks `HardwareRenderer` pipeline. Views stay interactive but stop rendering.
- **Workaround:** None documented at app level.

### BUG: HardwareRenderer ANR on Mali GPUs
- **Issue:** [Google #274207636](https://issuetracker.google.com/issues/274207636)
- **Root cause:** `nSyncAndDrawFrame()` blocks main thread in native `ConditionVariable::WaitHoldingLocks`
- **Status:** No fix or workaround.

### BUG: ImageReader image leak when onDraw() not called
- **Issue:** [Flutter Engine PR #24272](https://github.com/flutter/engine/pull/24272)
- **Affected:** Huawei P30, Mate 30 PRO, devices skipping `onDraw()` in sleep mode
- **Root cause:** `acquireLatestImage()` needs `maxImages >= 2`. Images accumulate if not closed.
- **Workaround:** Close previous image immediately. Set `maxImages >= 2`.

---

## 4. Bitmap.wrapHardwareBuffer() (API 29+)

### BUG: Hardware bitmap + software Canvas incompatibility
- **Issues:** [BlurView #109](https://github.com/Dimezis/BlurView/issues/109), [BlurView #66](https://github.com/Dimezis/BlurView/issues/66)
- **Root cause:** `IllegalArgumentException: Software rendering doesn't support hardware bitmaps`
- **Workaround:** Never draw hardware bitmaps to software Canvas. Use `.copy(ARGB_8888, true)`.

### BUG: A8 hardware bitmap decode failure on Android 14
- **Issue:** [Coil #2094](https://github.com/coil-kt/coil/issues/2094)
- **Affected:** API 34 with OpenGL (not Vulkan)
- **Status:** Fixed in API 35.

---

## 5. Compose graphicsLayer { renderEffect = ... }

### BUG: BlurEffect(0f, 0f) crashes
- Same as RenderEffect radius=0 bug above.
- **Workaround:** Set `renderEffect = null` when radius is 0.

### GOTCHA: Forces offscreen compositing
- Content clipped to bounds. Shadows/glow extending beyond will be cut off.

### GOTCHA: Use lambda form for animation
- `graphicsLayer { }` (lambda) updates without recomposition — correct for animation.
- Non-lambda `graphicsLayer()` causes full recomposition every frame.

---

## 6. TextureView

### GOTCHA: Requires hardware-accelerated window
- `TextureView` with `isOpaque = false` requires HW acceleration on parent window.
- Default for all Activities since API 14, but dialogs with cleared flags may fail.

### GOTCHA: Triple buffering memory overhead
- TextureView uses ~3x surface size in GPU memory.
- 1-3 frames extra latency vs direct rendering.

---

## 7. API Version Summary

| API | Known Issues |
|-----|-------------|
| 31 | RenderNode stale refresh, RenderEffect(0) crash, blur flickering |
| 32 | RenderEffect(0) crash |
| 33 | Software Canvas suppresses ripple |
| 34 | HardwareRenderer memory pressure break, A8 HW bitmap bug |
| 35 | A8 bug fixed |

---

## 8. Required Guards in Our Implementation

| Bug | Guard Required | Applied In |
|-----|---------------|------------|
| RenderEffect radius=0 crash | `if (r > 0f) createBlurEffect() else null` | RenderNodeBlurController, BlurOverlayHost |
| API 31 RenderNode stale | Re-apply `setRenderEffect()` on API 31 | RenderNodeBlurController |
| HW Bitmap on software Canvas | `hardwareBitmap.copy(ARGB_8888, true)` | HardwareBufferCapture |
| ImageReader maxImages | `maxImages = 2` | HardwareBufferCapture, RenderNodeBlurController |
| ImageReader image leak | Close image in `finally` block | HardwareBufferCapture, RenderNodeBlurController |
| TextureView HW accel | Document constraint | docs |
