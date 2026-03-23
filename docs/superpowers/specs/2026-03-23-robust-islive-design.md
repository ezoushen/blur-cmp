# Robust isLive Support for Blur Pipeline

## Problem

The current `isLive` implementation has two defects:

1. **No guaranteed first frame.** When `isLive = false`, `onPreDraw` skips blur entirely.
   The view appears blank until someone calls `updateBlur()`.

2. **Redundant work on config changes.** When blur parameters change (radius animation),
   the entire pipeline runs including recapture, even though only the blur pass needs
   to re-run on the already-captured bitmap.

## Why Unconditional Recapture Is Necessary for isLive=true

A backdrop blur captures whatever is rendered at its screen position. The blur view does
not own or observe the content behind it — it could be any combination of views,
animations, and surfaces.

Hardware-accelerated property animations (`translationX`, `alpha`, `scaleX`, etc.) modify
RenderNode properties directly without calling `View.invalidate()`. HWUI's RenderThread
re-composites with the new transform — no UI thread draw traversal, no `OnDrawListener`
callback. But the visual content behind the blur HAS changed because elements moved.

`sourceView.draw(canvas)` correctly captures these changes because it reads the current
property values (`getTranslationX()`, etc.) at capture time. This only works if we
actually recapture.

Therefore: **`isLive=true` must recapture every frame.** Any change detection scheme
based on `OnDrawListener` or `View.isDirty()` will miss property animations, shared
element transitions, and window animations — causing stale blur content.

## Design

### Dirty Tracking Model

Replace the single `isDirty` boolean with two independent dirty signals:

| Signal | Set by | Meaning | Action |
|--------|--------|---------|--------|
| `configDirty` | `setConfig()`, `setGradient()` | Blur parameters changed (radius, gradient, overlay) | Re-blur cached capture (skip recapture) |
| `contentDirty` | `isLive=true` (every frame), initial attach, dimension change, downsample change, `invalidate()` | Backdrop pixels may have changed or capture size changed | Full recapture + reblur |

`update()` logic:
- Neither flag set → return false (0ms)
- `configDirty` only → re-blur the cached `captureBitmap` (skip capture, ~3ms saved)
- `contentDirty` (with or without `configDirty`) → full recapture + reblur

**Config changes that affect capture resolution:** If `downsampleFactor` changes, the
scaled capture dimensions change. The cached `captureBitmap` is at the old resolution.
The controller detects this by comparing `scaledWidth`/`scaledHeight` against the cached
bitmap's dimensions — if they differ, it promotes `configDirty` to `contentDirty` and
forces a recapture.

**Pre-blur tint changes:** `preBlurTintColor` / `preBlurBlendModeOrdinal` are applied to
`captureBitmap` before blurring. If these change via `configDirty`-only, re-blurring the
already-tinted bitmap would stack tints. Therefore, changes to pre-blur tint parameters
set `contentDirty` (not `configDirty`) to force a fresh capture with the new tint applied.

### isLive Behavior

**`isLive = true`:** The `onPreDraw` listener sets `contentDirty = true` every frame
before calling `update()`. This is the same behavior as today, but expressed through
the dirty flag model instead of calling `controller.invalidate()`.

**`isLive = false`:** After the first frame, `onPreDraw` skips unless a dirty flag is
pending. Config changes (e.g., `setBlurRadius()` while `isLive=false`) set `configDirty`
and the next `onPreDraw` processes it.

**`setIsLive(false -> true)`:** Sets `contentDirty = true` and calls `invalidate()` on
the view to schedule an immediate redraw, then resumes per-frame recapture.

### onPreDraw Logic

The `onPreDraw` listener gates on three conditions:

```kotlin
private var hasFirstFrame = false

private val preDrawListener = OnPreDrawListener {
    val needsFirstFrame = !hasFirstFrame
    val hasPendingWork = controller.hasPendingDirty()
    if (isBlurEnabled && (needsFirstFrame || hasPendingWork || (isLive && isShown))) {
        if (isLive) {
            controller.markContentDirty()
        }
        if (controller.update()) {
            hasFirstFrame = true
            invalidate()
        }
    }
    true
}
```

- `needsFirstFrame`: unconditional first capture regardless of `isLive`
- `hasPendingWork`: config changes while `isLive=false` (e.g., radius animation on static)
- `isLive && isShown`: continuous recapture every frame

This also handles size changes: `onSizeChanged` sets `contentDirty` via the controller,
`hasPendingWork` returns true, and `onPreDraw` processes it even when `isLive=false`.

### First-Frame Guarantee

`contentDirty` initializes to `true`. Combined with the `hasFirstFrame` gate:

- View attached with `isLive=false` → first frame captured, then static
- View attached with `isLive=true` → first frame captured, then continuous

`hasFirstFrame` resets to `false` in `onDetachedFromWindow()` so re-attachment
triggers a fresh first frame.

### Config-Only Re-blur (The Key Optimization)

When `configDirty` is true but `contentDirty` is false, the controller skips the
capture phase and re-runs only the blur algorithm on the previously captured bitmap.

This happens when:
- Radius animation on a static background (`isLive=false` + animated `setBlurRadius()`)
- Gradient changes while paused
- Overlay color animation (post-blur overlay, not pre-blur tint)

The capture cost (~1.8-4ms depending on device) is entirely avoided. Only the blur
phase runs (~2-3ms).

### Controller update() Flow

```
update():
  if (!configDirty && !contentDirty && !dimensionsChanged) return false

  if (dimensionsChanged) contentDirty = true
  if (scaledDimensionsChanged) contentDirty = true   // downsample factor changed

  if (contentDirty):
    capture(sourceView -> captureBitmap)
    applyPreBlurTint(captureBitmap)
    blur(captureBitmap -> blurredBitmap)
    contentDirty = false
    configDirty = false
  else if (configDirty):
    blur(captureBitmap -> blurredBitmap)   // re-blur cached capture
    configDirty = false

  return true

hasPendingDirty():
  return configDirty || contentDirty
```

### RenderNodeBlurController (API 31+)

`BlurView` uses `RenderNodeBlurController` on API 31+ instead of `BlurController`.
This path uses `RenderEffect` for blur, which has built-in compositor-level dirty
tracking — HWUI only re-applies the effect when the input RenderNode changes.

For this path:
- The `configDirty`/`contentDirty` split is not needed — HWUI handles it natively
- The `hasFirstFrame` first-frame guarantee applies (added to `BlurView`'s
  `preDrawListener` for the RenderNode branch, not just the BlurController branch)
- The per-frame `controller.invalidate()` in `preDrawListener` is removed for the
  RenderNode path — HWUI's compositor-level dirty tracking is sufficient
- Config changes call `renderNodeController.setConfig()` which internally invalidates
  the RenderNode, triggering HWUI to re-apply the effect

### View-Layer Config Setters

Under the new model, view-layer config setters should **not** call
`controller.invalidate()` — that sets `contentDirty` and forces an unnecessary
recapture. They should only call `controller.setConfig()` / `controller.setGradient()`,
which sets `configDirty` only.

Exception: setters that change `preBlurTintColor` or `preBlurBlendModeOrdinal` must
set `contentDirty` (see "Pre-blur tint changes" above).

`controller.invalidate()` remains as a public API for explicit "force full refresh".
Called by `updateBlur()` and available to callers who know the backdrop content changed
externally (e.g., SurfaceView/TextureView content updates).

### Changes Summary

**VariableBlurController / BlurController:**
- Replace `isDirty: Boolean` with `configDirty: Boolean` and `contentDirty: Boolean`
- `contentDirty` initializes to `true` (first-frame guarantee)
- Add `markContentDirty()` and `hasPendingDirty()` public methods
- `setConfig()` / `setGradient()` set `configDirty` only
- Pre-blur tint changes set `contentDirty`
- `invalidate()` sets both flags
- `update()`: skip if neither flag set; skip capture if only `configDirty`;
  promote to `contentDirty` if scaled dimensions changed
- Track `lastScaledWidth`/`lastScaledHeight` for downsample change detection

**BlurView (both BlurController and RenderNodeBlurController paths):**
- Add `hasFirstFrame` flag, reset in `onDetachedFromWindow()`
- `onPreDraw`: unconditional first frame, `hasPendingWork` check for `isLive=false`
  config changes, `markContentDirty()` only when `isLive=true`
- Remove `controller.invalidate()` from config setters
- RenderNode path: remove per-frame `invalidate()` from `onPreDraw`

**VariableBlurView:**
- Same changes as BlurView (non-RenderNode path only since VariableBlurView doesn't
  use RenderNodeBlurController)

**BlurOverlay / VariableBlurOverlay (Compose):**
- No changes needed — Compose wrappers call `setIsLive()` / `setBlurConfig()`

### Edge Cases

| Case | Behavior |
|------|----------|
| View attached with `isLive=false` | First frame captured (`hasFirstFrame` gate), then static |
| View attached with `isLive=true` | First frame + continuous recapture every frame |
| Radius animation, `isLive=false` | `configDirty` only → re-blur cached capture, no recapture |
| Radius animation, `isLive=true` | `contentDirty` dominates → full recapture (correct) |
| `setIsLive(false -> true)` | `contentDirty` + `invalidate()`, immediate refresh |
| `setIsLive(true -> false)` | Stops recapture, retains last frame |
| Dimension change (layout) | `contentDirty` set, recapture at new size |
| Dimension change (size change while `isLive=false`) | `contentDirty` + `hasPendingDirty` → processed in `onPreDraw` |
| Downsample factor change | Scaled dimensions differ → promoted to `contentDirty` |
| Pre-blur tint color change | Treated as `contentDirty` (prevents tint stacking) |
| Post-blur overlay color change | `configDirty` only (overlay applied after blur, safe) |
| `updateBlur()` while `isLive=false` | Sets both flags, forces one full recapture |
| Config change before first render | `contentDirty` already true, first render does full pipeline |
| TextureView/SurfaceView behind blur | Use `updateBlur()` when their content changes |
| Property animation behind blur, `isLive=true` | Captured correctly (per-frame recapture) |
| Property animation behind blur, `isLive=false` | NOT captured — expected (user chose static) |
| Multiple blur views in same hierarchy | Each independent, own `hasFirstFrame` and dirty state |

### Performance Impact

| Scenario | Before | After |
|----------|--------|-------|
| `isLive=true`, dynamic backdrop | 6ms/frame | 6ms/frame (same) |
| `isLive=true`, static backdrop | 6ms/frame | 6ms/frame (same — can't detect statically) |
| `isLive=false`, static | 0ms, no first frame | 0ms, guaranteed first frame |
| `isLive=false`, radius animation | N/A (not supported) | ~3ms (blur only, no capture) |
| `isLive=false`, size change | May miss update | Correctly recaptures via `hasPendingDirty` |

### Future: Optional Smart Change Detection

For callers who set a specific `blurredView` (not the DecorView), an opt-in
`OnDrawListener`-based change detection could skip recapture when the source view
tree hasn't been invalidated. This would NOT be safe for backdrop blur (DecorView)
due to property animation blindness, but would work for specific-view blurring where
the caller controls the content.

This is deferred — not part of this spec.
