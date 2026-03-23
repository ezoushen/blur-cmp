# Robust isLive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace single `isDirty` flag with `configDirty`/`contentDirty` split, add first-frame guarantee, and config-only re-blur path that skips capture.

**Architecture:** Two dirty flags in controllers (`configDirty` for blur params, `contentDirty` for backdrop). Views use `hasFirstFrame` gate for first-frame guarantee. `onPreDraw` checks `hasPendingDirty()` to handle config changes while `isLive=false`. Config setters no longer force content recapture.

**Tech Stack:** Kotlin, Android View system, OpenGL ES 2.0

**Spec:** `docs/superpowers/specs/2026-03-23-robust-islive-design.md`

---

### File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `blur-cmp/.../blur/VariableBlurController.kt` | Modify | Replace `isDirty` with `configDirty`/`contentDirty`, add `markContentDirty()`, `hasPendingDirty()`, config-only re-blur path |
| `blur-cmp/.../blur/BlurController.kt` | Modify | Same dirty flag changes as VariableBlurController |
| `blur-cmp/.../blur/view/BlurView.kt` | Modify | `hasFirstFrame` gate, new `onPreDraw` logic, remove `invalidate()` from config setters |
| `blur-cmp/.../blur/view/VariableBlurView.kt` | Modify | Same view-layer changes as BlurView (non-RenderNode path) |

Base path: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen`

---

### Task 1: Two-Flag Dirty Tracking in VariableBlurController

**Files:**
- Modify: `blur/VariableBlurController.kt`

- [ ] **Step 1: Replace `isDirty` with `configDirty` and `contentDirty`**

Replace at line 74:
```kotlin
// Before:
private var isDirty = true

// After:
private var configDirty = false
private var contentDirty = true  // first-frame guarantee
```

Update `init()` (line 91):
```kotlin
// Before:
this.isDirty = true

// After:
this.contentDirty = true
```

- [ ] **Step 2: Update `setConfig()` — set `configDirty` only, promote tint changes to `contentDirty`**

At `setConfig()` (line 121):
```kotlin
fun setConfig(config: BlurConfig) {
    if (this.config != config) {
        if (this.config.pipelineStrategy != config.pipelineStrategy) {
            resolvedStrategy = null
        }
        // Pre-blur tint changes must recapture (prevents tint stacking)
        if (this.config.preBlurTintColor != config.preBlurTintColor ||
            this.config.preBlurBlendModeOrdinal != config.preBlurBlendModeOrdinal) {
            contentDirty = true
        } else {
            configDirty = true
        }
        this.config = config
        algorithm.setOverlayColor(config.overlayColor)
    }
}
```

- [ ] **Step 3: Update `setGradient()` — set `configDirty`**

At `setGradient()` (line 103):
```kotlin
fun setGradient(gradient: BlurGradient) {
    if (this.gradient != gradient) {
        this.gradient = gradient
        algorithm.setGradient(gradient)
        configDirty = true
    }
}
```

- [ ] **Step 4: Update `invalidate()` — set both flags**

At `invalidate()` (line 142):
```kotlin
fun invalidate() {
    configDirty = true
    contentDirty = true
}
```

- [ ] **Step 5: Add `markContentDirty()` and `hasPendingDirty()`**

Add after `invalidate()`:
```kotlin
fun markContentDirty() {
    contentDirty = true
}

fun hasPendingDirty(): Boolean =
    configDirty || contentDirty
```

- [ ] **Step 6: Rewrite `update()` with two-flag logic and config-only path**

The `update()` method (line 201) becomes:
```kotlin
fun update(): Boolean {
    if (!isInitialized) return false

    val view = blurView ?: return false
    val source = sourceView ?: return false
    val currentGradient = gradient ?: return false

    if (view.width == 0 || view.height == 0) return false

    val dimensionsChanged = view.width != lastWidth || view.height != lastHeight
    if (dimensionsChanged) contentDirty = true

    if (!configDirty && !contentDirty) return false

    // ... (rest of update: compute effectiveDownsample, scaledWidth/Height) ...

    // Promote configDirty to contentDirty if scaled dimensions changed
    if (captureBitmap?.width != scaledWidth || captureBitmap?.height != scaledHeight) {
        contentDirty = true
    }

    // ... algorithm.prepare() ...

    val strategy = resolveStrategy()
    if (contentDirty) {
        // Full recapture + reblur
        val success = when (strategy) {
            BlurPipelineStrategy.SURFACE_TEXTURE -> updateSurfaceTexture(...)
            else -> updateLegacy(...)
        }
        if (!success) return false
    } else {
        // Config-only: re-blur cached capture (skip recapture)
        blurredBitmap = algorithm.blur(captureBitmap!!, scaledMaxRadius)
    }

    // ... perf logging ...

    lastWidth = view.width
    lastHeight = view.height
    configDirty = false
    contentDirty = false
    return true
}
```

- [ ] **Step 7: Update `release()` — reset to `contentDirty = true`**

In `release()`, replace `isDirty = true` with:
```kotlin
configDirty = false
contentDirty = true  // next init triggers fresh first frame
```

- [ ] **Step 8: Build and verify compilation**

Run: `./gradlew :blur-cmp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/VariableBlurController.kt
git commit -m "refactor: two-flag dirty tracking in VariableBlurController"
```

---

### Task 2: Two-Flag Dirty Tracking in BlurController

**Files:**
- Modify: `blur/BlurController.kt`

Apply the same pattern as Task 1 to `BlurController`:

- [ ] **Step 1: Replace `isDirty` with `configDirty`/`contentDirty`**

Same changes: `isDirty = true` → `configDirty = false` + `contentDirty = true`.

- [ ] **Step 2: Update `setConfig()` with tint promotion**

`BlurController.setConfig()` (line 97) — same tint-promotion pattern as VariableBlurController. **Note:** `BlurController.setConfig()` does NOT call `algorithm.setOverlayColor()` — overlay is drawn in `draw()` via `canvas.drawColor()`. Do not copy that line from VariableBlurController's snippet.

- [ ] **Step 3: Update `invalidate()`, add `markContentDirty()`, `hasPendingDirty()`**

Same as VariableBlurController.

- [ ] **Step 4: Rewrite `update()` with config-only path**

Same two-flag logic. `BlurController` doesn't have `setGradient()` but has the same `updateLegacy`/`updateSurfaceTexture` dispatch. The config-only path calls `algorithm.blur(captureBitmap!!, scaledRadius)`.

- [ ] **Step 5: Update `release()` — reset to `contentDirty = true`**

Same pattern as VariableBlurController: replace `isDirty = true` with `configDirty = false` + `contentDirty = true`.

- [ ] **Step 6: Build and verify**

Run: `./gradlew :blur-cmp:compileDebugKotlinAndroid`

- [ ] **Step 7: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/BlurController.kt
git commit -m "refactor: two-flag dirty tracking in BlurController"
```

---

### Task 3: View-Layer Changes — BlurView

**Files:**
- Modify: `blur/view/BlurView.kt`

- [ ] **Step 1: Add `hasFirstFrame` field**

After `private var isRendering = false` (line 67):
```kotlin
private var hasFirstFrame = false
```

- [ ] **Step 2: Rewrite `preDrawListener` with three-gate logic**

Replace the current `preDrawListener` (lines 91-112):
```kotlin
private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (isBlurEnabled) {
        val needsFirstFrame = !hasFirstFrame
        if (useRenderNode) {
            val controller = renderNodeController
            if (controller != null && (needsFirstFrame || (isLive && isShown))) {
                // RenderNode path: HWUI handles dirty tracking natively.
                // No per-frame invalidate() — only config changes via setConfig().
                if (controller.update()) {
                    hasFirstFrame = true
                    invalidate()
                }
            }
        } else {
            val controller = blurController
            if (controller != null) {
                val hasPendingWork = controller.hasPendingDirty()
                if (needsFirstFrame || hasPendingWork || (isLive && isShown)) {
                    if (isLive) {
                        controller.markContentDirty()
                    }
                    if (controller.update()) {
                        hasFirstFrame = true
                        invalidate()
                    }
                }
            }
        }
    }
    true
}
```

- [ ] **Step 3: Remove `controller.invalidate()` from config setters**

In `setBlurConfig()`, `setBlurRadius()`, `setOverlayColor()`, `setDownsampleFactor()` — remove the `blurController?.invalidate()` and `renderNodeController?.invalidate()` calls. Keep only `setConfig()` + `invalidate()` (view invalidation for redraw).

**IMPORTANT:** Do NOT remove `blurController?.invalidate()` from `updateBlur()` — that method is the explicit "force full refresh" API and must set both dirty flags.

Example for `setBlurConfig()`:
```kotlin
fun setBlurConfig(config: BlurConfig) {
    blurConfig = config
    if (useRenderNode) {
        renderNodeController?.setConfig(config)
    } else {
        blurController?.setConfig(config)
    }
    invalidate()
}
```

Same pattern for `setBlurRadius()`, `setOverlayColor()`, `setDownsampleFactor()`.

- [ ] **Step 4: Update `setIsLive()` — set contentDirty on false→true transition**

In `setIsLive()` (line 290):
```kotlin
fun setIsLive(live: Boolean) {
    if (isLive != live) {
        isLive = live
        if (live) {
            blurController?.markContentDirty()
            renderNodeController?.invalidate()
            invalidate()
        }
    }
}
```

- [ ] **Step 5: Reset `hasFirstFrame` in `onDetachedFromWindow()`**

In `onDetachedFromWindow()` (line 419), add:
```kotlin
hasFirstFrame = false
```

- [ ] **Step 6: Update `onSizeChanged()` — set contentDirty instead of invalidate**

In `onSizeChanged()` (line 432):
```kotlin
override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (useRenderNode) {
        renderNodeController?.invalidate()
    } else {
        blurController?.markContentDirty()
    }
}
```

- [ ] **Step 7: Build and verify**

Run: `./gradlew :blur-cmp:compileDebugKotlinAndroid`

- [ ] **Step 8: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/view/BlurView.kt
git commit -m "feat: hasFirstFrame gate and config-only dirty tracking in BlurView"
```

---

### Task 4: View-Layer Changes — VariableBlurView

**Files:**
- Modify: `blur/view/VariableBlurView.kt`

- [ ] **Step 1: Add `hasFirstFrame` field**

After `private var isRendering = false` (line 70):
```kotlin
private var hasFirstFrame = false
```

- [ ] **Step 2: Rewrite `preDrawListener`**

Replace the current listener (lines 94-107):
```kotlin
private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
    if (isBlurEnabled) {
        val controller = blurController
        if (controller != null) {
            val needsFirstFrame = !hasFirstFrame
            val hasPendingWork = controller.hasPendingDirty()
            if (needsFirstFrame || hasPendingWork || (isLive && isShown)) {
                if (isLive) {
                    controller.markContentDirty()
                }
                if (controller.update()) {
                    hasFirstFrame = true
                    invalidate()
                }
            }
        }
    }
    true
}
```

- [ ] **Step 3: Remove `controller.invalidate()` from config setters**

In `setBlurGradient()`, `setBlurConfig()`, `setOverlayColor()`, `setDownsampleFactor()` — remove `blurController?.invalidate()`. Keep only the `setConfig()`/`setGradient()` call + `invalidate()`.

**IMPORTANT:** Do NOT remove `blurController?.invalidate()` from `updateBlur()` — that method must set both dirty flags.

- [ ] **Step 4: Update `setIsLive()` — set contentDirty on false→true**

```kotlin
fun setIsLive(live: Boolean) {
    if (isLive != live) {
        isLive = live
        if (live) {
            blurController?.markContentDirty()
            invalidate()
        }
    }
}
```

- [ ] **Step 5: Reset `hasFirstFrame` in `onDetachedFromWindow()` and update `onSizeChanged()`**

```kotlin
// onDetachedFromWindow:
hasFirstFrame = false

// onSizeChanged:
override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    blurController?.markContentDirty()
}
```

- [ ] **Step 6: Build and verify**

Run: `./gradlew :blur-cmp:compileDebugKotlinAndroid`

- [ ] **Step 7: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/view/VariableBlurView.kt
git commit -m "feat: hasFirstFrame gate and config-only dirty tracking in VariableBlurView"
```

---

### Task 5: Emulator Verification

**Files:** None (testing only)

- [ ] **Step 1: Build the demo app**

Run: `./gradlew :demoApp:assembleDebug -Pblur.perf.enabled=true`

- [ ] **Step 2: Install and launch on emulator**

```bash
adb install -r demoApp/build/outputs/apk/debug/demoApp-debug.apk
adb shell am start -n io.github.ezoushen.blur.demo/.MainActivity
```

- [ ] **Step 3: Verify Variable blur tab works (isLive=true)**

Navigate to Variable blur tab. Blur should render immediately (first-frame guarantee) and update continuously. Check logcat for `BlurPerf` logs showing `contentDirty` path.

- [ ] **Step 4: Verify Uniform blur tab works**

Navigate to Uniform blur tab. Same behavior.

- [ ] **Step 5: Verify all gradient modes**

Tap through: Top→Bottom, Bottom→Top, Left→Right, Right→Left, Spotlight. Each should render blur correctly.

- [ ] **Step 6: Take screenshot to verify visual correctness**

```bash
adb exec-out screencap -p > /tmp/islive_test.png
```

- [ ] **Step 7: Commit verification**

No code changes — just confirmation that the implementation works.
