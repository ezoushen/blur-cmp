# Configurable Tint Order: Pre-Blur vs Post-Blur

## Problem

Tint application order is inconsistent across platforms and code paths. Some apply
tint before blur (blended into content), some after (on top of blurred result).
The visual difference matters for non-Normal blend modes (ColorDodge, Screen, etc.).

## Design

Make tint order configurable via a `TintOrder` enum, defaulting to `POST_BLUR`.
Both orders are supported on all platforms.

### TintOrder Enum (common)

```kotlin
enum class TintOrder {
    PRE_BLUR,   // Tint blended with content BEFORE blur (current non-Normal behavior)
    POST_BLUR   // Tint applied AFTER blur (Apple UIVisualEffectView style)
}
```

Added to `BlurOverlayConfig`:
```kotlin
data class BlurOverlayConfig(
    val radius: Float = 25f,
    val tintColorValue: Long = 0L,
    val tintBlendMode: BlurBlendMode = BlurBlendMode.Normal,
    val tintOrder: TintOrder = TintOrder.POST_BLUR,  // NEW
    // ... existing fields
)
```

### Current State vs Target

| Platform / Path | Current | Target (POST_BLUR default) |
|-----------------|---------|----------------------------|
| Android Kawase (BlurController) | Normal: post-blur via `overlayColor`, Non-Normal: pre-blur via `preBlurTintColor` | Unified: tint order controlled by `tintOrder` field |
| Android Kawase (VariableBlurController) | Same split + overlay in GL shader | Same unified approach, overlay moved from GL shader to `draw()` |
| Android RenderNode | Pre-blur tint + overlay via chained RenderEffects | Tint order controlled by `tintOrder` field |
| iOS | Normal: post-blur via tintLayer, Non-Normal: pre-blur via preBlendBackdropView | Tint order controlled by `tintOrder` field |

### Android: BlurConfig Changes

Replace the split `overlayColor` / `preBlurTintColor` / `preBlurBlendModeOrdinal`
with unified fields:

```kotlin
data class BlurConfig(
    val radius: Float = 16f,
    val tintColor: Int? = null,             // replaces both overlayColor and preBlurTintColor
    val tintBlendModeOrdinal: Int? = null,  // replaces preBlurBlendModeOrdinal, null = SRC_OVER
    val tintOrder: TintOrder = TintOrder.POST_BLUR,  // NEW
    val downsampleFactor: Float = 4f,
    val pipelineStrategy: BlurPipelineStrategy = BlurPipelineStrategy.AUTO
)
```

The `overlayColor` field is removed. `BlurConfig.Light/Medium/Heavy` presets migrate
to `tintColor` with no blend mode (SRC_OVER, same visual as old `overlayColor`).

### Android: GradientMapper Changes

`AndroidGradientMapper.toBlurConfig()` no longer splits Normal vs non-Normal. It
maps directly:

```kotlin
fun toBlurConfig(config: BlurOverlayConfig): BlurConfig {
    val hasTint = config.tintColorValue != 0L
    return BlurConfig(
        radius = config.radius,
        tintColor = if (hasTint) config.tintColorValue.toInt() else null,
        tintBlendModeOrdinal = if (hasTint && config.tintBlendMode != BlurBlendMode.Normal)
            AndroidBlendModeMapper.toAndroidBlendMode(config.tintBlendMode)?.ordinal
        else null,
        tintOrder = config.tintOrder.toAndroidTintOrder(),
        downsampleFactor = config.downsampleFactor,
    )
}
```

### Android: BlurController Pipeline

**POST_BLUR path (default):**
```
capture → blur → draw(canvas): drawBitmap + drawTint(tintColor, blendMode)
```

**PRE_BLUR path:**
```
capture → applyTint(bitmap, tintColor, blendMode) → blur → draw(canvas): drawBitmap
```

In `updateLegacy()`:
```kotlin
if (config.tintOrder == TintOrder.PRE_BLUR) {
    applyTint(captureOutput)
}
blurredBitmap = algorithm.blur(captureOutput, scaledRadius)
```

In `draw()`:
```kotlin
canvas.drawBitmap(blurred, srcRect, dstRect, paint)
if (config.tintOrder == TintOrder.POST_BLUR && config.tintColor != null) {
    drawTint(canvas, config.tintColor, config.tintBlendModeOrdinal)
}
```

The `applyTint()` method (renamed from `applyPreBlurTint()`) remains for PRE_BLUR.
A new `drawTint()` method draws the tint rect with blend mode for POST_BLUR.

### Android: VariableBlurController Pipeline

Same pattern as BlurController. Additionally:
- Remove `algorithm.setOverlayColor()` calls — overlay was in the GL shader
- Remove `uOverlayColor`/`uHasOverlay` from the composite shader
- Apply tint in `draw()` for POST_BLUR, or `applyTint(bitmap)` for PRE_BLUR

The GL shader outputs raw blurred content. Tint is always applied at the Canvas level
(either pre-blur on the capture bitmap or post-blur in `draw()`). This simplifies the
shader and unifies tint handling between BlurController and VariableBlurController.

**Note:** Variable blur tint will be applied uniformly (not gradient-following) in both
PRE_BLUR and POST_BLUR modes. The previous gradient-following overlay in the GL shader
was removed for simplicity. If gradient-following tint is needed in the future, it can
be re-added as a separate feature.

### Android: RenderNodeBlurController

`applyRenderEffect()` currently reads `preBlurTintColor`, `preBlurBlendModeOrdinal`,
and `overlayColor` to build chained RenderEffects. Update to use unified fields:

**POST_BLUR:** `createBlurEffect()` only. Tint applied in `draw()` via Canvas, same
as the Kawase path.

**PRE_BLUR:** `createBlurEffect()` chained with color filter for tint (current approach
but using the new `tintColor`/`tintBlendModeOrdinal` fields).

### Android: BlurOverlayHost.android.kt

`buildBlurRenderEffect()` (the RenderEffect path for API 31+ with explicit background)
also builds pre-blur tint chains. Update to respect `tintOrder`:
- POST_BLUR: blur only in RenderEffect, tint via Compose `drawWithContent`
- PRE_BLUR: tint + blur chained in RenderEffect (current behavior)

### Android: Public API Migration

Since this is pre-1.0 (v0.4.0), make a clean break:

**BlurConfig:**
- Remove: `overlayColor`, `preBlurTintColor`, `preBlurBlendModeOrdinal`
- Add: `tintColor`, `tintBlendModeOrdinal`, `tintOrder`
- Update presets: `Light`/`Medium`/`Heavy` use `tintColor` instead of `overlayColor`

**BlurView / VariableBlurView:**
- `setOverlayColor(color: Int?)` → rename to `setTintColor(color: Int?)`
- XML attribute `blurOverlayColor` → add `blurTintColor` (keep old as deprecated alias)

**Compose APIs:**
- `BlurSurface(overlayColor=)` → `BlurSurface(tintColor=)` (breaking, pre-1.0)
- `VariableBlurSurface(overlayColor=)` → same rename
- `BlurModifier.blurBehind()` → already deprecated, update internal usage

### iOS Changes

`applyTint()` in `IosBlurState` respects `tintOrder`:

**POST_BLUR (default):**
Apply tint + compositingFilter on `tintLayer` (already after backdrop). Remove
`setupPreBlend()`/`teardownPreBlend()` call for this path.

**PRE_BLUR:**
Use `preBlendBackdropView` + `preBlendTintLayer` (existing machinery). Keep
`setupPreBlend()`/`teardownPreBlend()` for this path.

```kotlin
private fun applyTint(config: BlurOverlayConfig) {
    val hasTint = config.tintColorValue != 0L
    if (!hasTint) {
        teardownPreBlend()
        tintLayer?.backgroundColor = null
        return
    }

    val color = argbToUIColor(config.tintColorValue)
    val filterName = IosBlendModeMapper.toCompositingFilterName(config.tintBlendMode)

    if (config.tintOrder == TintOrder.PRE_BLUR) {
        if (!isBeforeBlurActive) setupPreBlend()
        preBlendTintLayer?.backgroundColor = color?.CGColor
        preBlendTintLayer?.compositingFilter = filterName
        tintLayer?.backgroundColor = null
    } else {
        teardownPreBlend()
        tintLayer?.backgroundColor = color?.CGColor
        tintLayer?.compositingFilter = filterName
    }
}
```

Same change applies to `RealTimeVariableBlurView` on iOS (has identical pre-blend
machinery).

### Dirty Tracking Impact

**POST_BLUR tint changes:** `configDirty` only. Tint doesn't touch `captureBitmap`,
no recapture needed. Just need to call `draw()` again.

**PRE_BLUR tint changes:** `contentDirty` (same as current). Tint mutates the capture
bitmap, requires recapture to avoid stacking.

**`tintOrder` changes:** `contentDirty` (switching order requires recapture to clear
any pre-blur tint from the cached bitmap).

In `setConfig()`:
```kotlin
if (this.config.tintOrder != config.tintOrder) {
    contentDirty = true  // order change requires recapture
} else if (config.tintOrder == TintOrder.PRE_BLUR &&
    (this.config.tintColor != config.tintColor ||
     this.config.tintBlendModeOrdinal != config.tintBlendModeOrdinal)) {
    contentDirty = true  // pre-blur tint change requires recapture
} else {
    configDirty = true   // post-blur tint change or other config
}
```

### Changes Summary

**Common (commonMain):**
- Add `TintOrder` enum to `BlurOverlayConfig`
- Add `tintOrder` field to `BlurOverlayConfig`, default `POST_BLUR`

**BlurConfig (Android):**
- Remove `overlayColor`, `preBlurTintColor`, `preBlurBlendModeOrdinal`
- Add `tintColor`, `tintBlendModeOrdinal`, `tintOrder`
- Update presets

**BlurController / VariableBlurController:**
- Rename `applyPreBlurTint()` → `applyTint()`, call only when `PRE_BLUR`
- Add `drawTint()` for POST_BLUR in `draw()`
- Remove `algorithm.setOverlayColor()` calls
- Update dirty tracking: POST_BLUR tint = `configDirty`, PRE_BLUR tint = `contentDirty`

**VariableOpenGLBlur:**
- Remove `uOverlayColor`, `uHasOverlay`, `setOverlayColor()`, `setOverlayUniforms()`
- Remove overlay from composite shader
- Remove related cached uniform locations

**RenderNodeBlurController:**
- Update `applyRenderEffect()` to use `tintColor`/`tintBlendModeOrdinal`/`tintOrder`
- POST_BLUR: blur-only RenderEffect, tint in draw()
- PRE_BLUR: chained RenderEffect with tint (current approach with new field names)

**BlurOverlayHost.android.kt:**
- Update `buildBlurRenderEffect()` for tintOrder
- Update `RenderEffectBlurOverlay` composable

**GradientMapper:**
- Simplify to unified `tintColor`/`tintBlendModeOrdinal`/`tintOrder` mapping

**BlurView / VariableBlurView:**
- Rename `setOverlayColor()` → `setTintColor()`
- Add `blurTintColor` XML attribute

**Compose APIs (BlurSurface, VariableBlurSurface):**
- Rename `overlayColor` → `tintColor` parameter

**iOS:**
- `applyTint()`: route to post-blur tintLayer or pre-blur preBlendBackdropView based
  on `tintOrder`
- Both `IosBlurState` and variable blur view updated

### Edge Cases

| Case | Behavior |
|------|----------|
| POST_BLUR + Normal blend | Same visual as old `overlayColor` (SRC_OVER) |
| POST_BLUR + ColorDodge | Tint on blurred result — Apple-like style |
| PRE_BLUR + ColorDodge | Tint blended with content then blurred — current behavior |
| Switch POST→PRE at runtime | `contentDirty` triggered, recaptures to clear stale tint |
| Switch PRE→POST at runtime | `contentDirty` triggered, clean recapture |
| POST_BLUR tint animation, isLive=false | `configDirty` only — redraw without capture |
| PRE_BLUR tint animation, isLive=false | `contentDirty` — full recapture each change |
| Variable blur + POST_BLUR tint | Uniform tint over variable-blurred content |
| Variable blur + PRE_BLUR tint | Uniform tint blended into content before variable blur |
| API < 29 + non-Normal blend | Falls back to SRC_OVER (no BlendMode support) |
| No tint | No draw, no overhead, regardless of tintOrder |
