# Configurable Tint Order Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace split overlayColor/preBlurTint with unified tintColor + configurable TintOrder (PRE_BLUR/POST_BLUR), defaulting to POST_BLUR on all platforms.

**Architecture:** Add `TintOrder` enum to common config. On Android, unify `overlayColor` + `preBlurTintColor` into `tintColor` + `tintBlendModeOrdinal` + `tintOrder` in BlurConfig. Route tint application based on tintOrder in controllers. Remove overlay from GL shader. On iOS, route `applyTint()` to pre-blend or post-blur tintLayer based on tintOrder.

**Tech Stack:** Kotlin Multiplatform, Android View/Compose, OpenGL ES 2.0, iOS UIKit/CALayer

**Spec:** `docs/superpowers/specs/2026-03-24-post-blur-tint-design.md`

---

### File Map

**Common (commonMain):**
| File | Action | Change |
|------|--------|--------|
| `cmp/BlurOverlayConfig.kt` | Modify | Add `tintOrder: TintOrder` field |
| `cmp/TintOrder.kt` | Create | `TintOrder` enum (PRE_BLUR, POST_BLUR) |

**Android core:**
| File | Action | Change |
|------|--------|--------|
| `blur/BlurConfig.kt` | Modify | Replace overlayColor/preBlurTint with tintColor/tintBlendModeOrdinal/tintOrder |
| `blur/BlurController.kt` | Modify | Route tint by tintOrder in updateLegacy/draw, update dirty tracking |
| `blur/VariableBlurController.kt` | Modify | Same as BlurController + remove algorithm.setOverlayColor |
| `blur/algorithm/VariableOpenGLBlur.kt` | Modify | Remove overlay from composite shader + cached uniforms |
| `blur/RenderNodeBlurController.kt` | Modify | Update applyRenderEffect for new field names + tintOrder |
| `blur/cmp/GradientMapper.android.kt` | Modify | Simplify to unified tint mapping |

**Android views/compose:**
| File | Action | Change |
|------|--------|--------|
| `blur/view/BlurView.kt` | Modify | Rename setOverlayColor→setTintColor |
| `blur/view/VariableBlurView.kt` | Modify | Same rename |
| `blur/compose/BlurSurface.kt` | Modify | Rename overlayColor→tintColor param |
| `blur/compose/VariableBlurSurface.kt` | Modify | Same rename |
| `blur/compose/BlurModifier.kt` | Modify | Update deprecated APIs |
| `res/values/attrs.xml` | Modify | Add blurTintColor attr |
| `blur/cmp/BlurOverlayHost.android.kt` | Modify | Update RenderEffect path for tintOrder |

**iOS:**
| File | Action | Change |
|------|--------|--------|
| `blur/cmp/BlurOverlayHost.ios.kt` | Modify | Route applyTint by tintOrder |

**Base path:** `blur-cmp/src/{commonMain,androidMain,iosMain}/kotlin/io/github/ezoushen`

---

### Task 1: Add TintOrder Enum + BlurOverlayConfig Field

**Files:**
- Create: `blur-cmp/src/commonMain/kotlin/io/github/ezoushen/blur/cmp/TintOrder.kt`
- Modify: `blur-cmp/src/commonMain/kotlin/io/github/ezoushen/blur/cmp/BlurOverlayConfig.kt`

- [ ] **Step 1: Create TintOrder enum**

```kotlin
// TintOrder.kt
package io.github.ezoushen.blur.cmp

enum class TintOrder {
    PRE_BLUR,
    POST_BLUR
}
```

- [ ] **Step 2: Add tintOrder to BlurOverlayConfig**

Add field with default POST_BLUR:
```kotlin
val tintOrder: TintOrder = TintOrder.POST_BLUR,
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew :blur-cmp:compileDebugKotlinAndroid`

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: add TintOrder enum and tintOrder to BlurOverlayConfig"
```

---

### Task 2: Migrate BlurConfig Fields

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/BlurConfig.kt`

- [ ] **Step 1: Replace fields**

Remove `overlayColor`, `preBlurTintColor`, `preBlurBlendModeOrdinal`. Add:
```kotlin
@ColorInt
val tintColor: Int? = null,
val tintBlendModeOrdinal: Int? = null,
val tintOrder: TintOrder = TintOrder.POST_BLUR,
```

Import `TintOrder` from common. Keep `pipelineStrategy`.

- [ ] **Step 2: Update presets**

```kotlin
val Light = BlurConfig(radius = 10f, tintColor = 0x40FFFFFF.toInt())
val Medium = BlurConfig(radius = 20f, tintColor = 0x60FFFFFF.toInt())
val Heavy = BlurConfig(radius = 50f, tintColor = 0x80FFFFFF.toInt())
```

- [ ] **Step 3: Expect compilation errors — do NOT build yet**

This will break 80+ references. Tasks 3-5 fix them incrementally. **The project
will not compile until Task 6 Step 5.** Tasks 2-5 are WIP commits.

- [ ] **Step 4: Commit (WIP)**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/BlurConfig.kt
git commit -m "refactor(wip): migrate BlurConfig to tintColor/tintOrder"
```

---

### Task 3: Update GradientMapper

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/cmp/GradientMapper.android.kt`

- [ ] **Step 1: Simplify toBlurConfig()**

Replace the Normal/non-Normal split with unified mapping:
```kotlin
fun toBlurConfig(config: BlurOverlayConfig): BlurConfig {
    val hasTint = config.tintColorValue != 0L
    return BlurConfig(
        radius = config.radius,
        tintColor = if (hasTint) config.tintColorValue.toInt() else null,
        tintBlendModeOrdinal = if (hasTint && config.tintBlendMode != BlurBlendMode.Normal)
            AndroidBlendModeMapper.toAndroidBlendMode(config.tintBlendMode)?.ordinal
        else null,
        tintOrder = config.tintOrder,
        downsampleFactor = config.downsampleFactor,
    )
}
```

Remove `overlayArgb`, `preBlurTint`, `preBlurBlendOrdinal` local variables.

- [ ] **Step 2: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/cmp/GradientMapper.android.kt
git commit -m "refactor: simplify GradientMapper to unified tint mapping"
```

---

### Task 4: Update BlurController — Tint Routing

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/BlurController.kt`

- [ ] **Step 1: Rename applyPreBlurTint → applyTint**

Keep the method but only call it when `config.tintOrder == TintOrder.PRE_BLUR`. Update it to read `config.tintColor` and `config.tintBlendModeOrdinal` instead of the old field names.

- [ ] **Step 2: Add drawTint() for POST_BLUR**

```kotlin
private fun drawTint(canvas: Canvas) {
    val tintColor = config.tintColor ?: return
    tintPaint.color = tintColor
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val ordinal = config.tintBlendModeOrdinal
        tintPaint.blendMode = if (ordinal != null) {
            try { android.graphics.BlendMode.values()[ordinal] } catch (_: Exception) { null }
        } else null
    }
    canvas.drawRect(0f, 0f, (blurView?.width ?: 0).toFloat(), (blurView?.height ?: 0).toFloat(), tintPaint)
}
```

- [ ] **Step 3: Update draw()**

Replace `overlayColor` drawing with tintOrder routing:
```kotlin
fun draw(canvas: Canvas) {
    val blurred = blurredBitmap ?: return
    val view = blurView ?: return
    srcRect.set(0, 0, blurred.width, blurred.height)
    dstRect.set(0, 0, view.width, view.height)
    canvas.drawBitmap(blurred, srcRect, dstRect, paint)
    if (config.tintOrder == TintOrder.POST_BLUR) {
        drawTint(canvas)
    }
}
```

- [ ] **Step 4: Update updateLegacy() — route tint by tintOrder**

```kotlin
// In updateLegacy, after capture and before blur:
if (config.tintOrder == TintOrder.PRE_BLUR) {
    applyTint(captureOutput)
}
```

Remove the unconditional `applyPreBlurTint(captureOutput)` call.

- [ ] **Step 5: Update dirty tracking in setConfig()**

Replace the preBlurTintColor check with tintOrder-aware logic:
```kotlin
if (this.config.tintOrder != config.tintOrder) {
    contentDirty = true
} else if (config.tintOrder == TintOrder.PRE_BLUR &&
    (this.config.tintColor != config.tintColor ||
     this.config.tintBlendModeOrdinal != config.tintBlendModeOrdinal)) {
    contentDirty = true
} else {
    configDirty = true
}
```

- [ ] **Step 6: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/BlurController.kt
git commit -m "refactor: route tint by tintOrder in BlurController"
```

---

### Task 5: Update VariableBlurController — Tint Routing + Remove Overlay

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/VariableBlurController.kt`

- [ ] **Step 1: Apply all Task 4 changes to VariableBlurController**

Specifically:
1. Rename `applyPreBlurTint()` → `applyTint()`, read from `config.tintColor`/`config.tintBlendModeOrdinal`
2. Add `drawTint(canvas)` method (same as BlurController)
3. In `updateLegacy()`: call `applyTint()` only when `config.tintOrder == TintOrder.PRE_BLUR`
4. In `updateSurfaceTexture()`: same routing
5. In `draw()`: call `drawTint(canvas)` when `config.tintOrder == TintOrder.POST_BLUR`
6. In `setConfig()`: update dirty tracking with tintOrder-aware logic (same as Task 4 Step 5)

- [ ] **Step 2: Remove algorithm.setOverlayColor() calls**

Remove from `init()` and `setConfig()`:
```kotlin
// Remove these lines:
algorithm.setOverlayColor(config.overlayColor)
```

- [ ] **Step 3: Add drawTint in draw()**

Same as BlurController — draw tint after blurredBitmap when POST_BLUR.

- [ ] **Step 4: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/VariableBlurController.kt
git commit -m "refactor: route tint by tintOrder in VariableBlurController"
```

---

### Task 6: Remove Overlay from VariableOpenGLBlur Shader

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/algorithm/VariableOpenGLBlur.kt`

- [ ] **Step 1: Remove overlay from composite shader**

In `COMPOSITE_FRAGMENT_SHADER`, remove:
- `uniform vec4 uOverlayColor;`
- `uniform float uHasOverlay;`
- The overlay blending block in `main()` (the `if (uHasOverlay > 0.5)` block)

- [ ] **Step 2: Remove setOverlayColor() method and currentOverlayColor field**

- [ ] **Step 3: Remove setOverlayUniforms() method**

- [ ] **Step 4: Remove cached uniform locations**

Remove `compHasOverlayLoc`, `compOverlayColorLoc` fields and their lines in `cacheUniformLocations()`. Remove the `setOverlayUniforms()` call from `compositeWithGradient()`.

- [ ] **Step 5: Build and verify**

Run: `./gradlew :blur-cmp:compileDebugKotlinAndroid`
This should now compile if Tasks 2-5 are all done.

- [ ] **Step 6: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/algorithm/VariableOpenGLBlur.kt
git commit -m "refactor: remove overlay from GL composite shader"
```

---

### Task 7: Update RenderNodeBlurController

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/RenderNodeBlurController.kt`

- [ ] **Step 1: Update applyRenderEffect()**

Replace `config.preBlurTintColor`/`config.preBlurBlendModeOrdinal`/`config.overlayColor` references with `config.tintColor`/`config.tintBlendModeOrdinal`/`config.tintOrder`.

For POST_BLUR: build blur-only RenderEffect (no tint chain). Tint applied in draw().
For PRE_BLUR: chain tint color filter + blur (current approach with new field names).

- [ ] **Step 2: Add drawTint() and update draw()**

Same pattern as BlurController — draw tint rect with blend mode after the blurred bitmap when POST_BLUR.

- [ ] **Step 3: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/RenderNodeBlurController.kt
git commit -m "refactor: update RenderNodeBlurController for tintOrder"
```

---

### Task 8: Update Views + XML Attrs

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/view/BlurView.kt`
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/view/VariableBlurView.kt`
- Modify: `blur-cmp/src/androidMain/res/values/attrs.xml`

- [ ] **Step 1: In BlurView — rename setOverlayColor → setTintColor**

Update the method to use `tintColor` in `BlurConfig.copy()`. Update `parseAttributes()` to read `blurTintColor` (fall back to `blurOverlayColor` for compat).

- [ ] **Step 2: In VariableBlurView — same rename**

- [ ] **Step 3: In attrs.xml — add blurTintColor attr**

Add `blurTintColor` alongside existing `blurOverlayColor` (keep old for XML compat).

- [ ] **Step 4: Build and verify**

Run: `./gradlew :blur-cmp:compileDebugKotlinAndroid`

- [ ] **Step 5: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/view/ blur-cmp/src/androidMain/res/
git commit -m "refactor: rename setOverlayColor to setTintColor in views"
```

---

### Task 9: Update Compose APIs

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/compose/BlurSurface.kt`
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/compose/VariableBlurSurface.kt`
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/compose/BlurModifier.kt`
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/cmp/BlurOverlayHost.android.kt`

- [ ] **Step 1: BlurSurface — rename overlayColor → tintColor**

Rename the parameter and update `BlurConfig` construction.

- [ ] **Step 2: VariableBlurSurface — same rename**

- [ ] **Step 3: BlurModifier — update deprecated blurBehind**

Update internal `config.overlayColor` references to `config.tintColor`.

- [ ] **Step 4: BlurOverlayHost.android.kt — update RenderEffect path**

In `buildBlurRenderEffect()` / `RenderEffectBlurOverlay`: route by tintOrder.
POST_BLUR: blur-only RenderEffect, tint via `drawWithContent`.
PRE_BLUR: chained RenderEffect (current approach with new field names).

- [ ] **Step 5: Build full project**

Run: `./gradlew :demoApp:assembleDebug`
Expected: BUILD SUCCESSFUL (all references migrated)

- [ ] **Step 6: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/compose/ blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/cmp/
git commit -m "refactor: rename overlayColor to tintColor in Compose APIs"
```

---

### Task 10: Update iOS — Route applyTint by TintOrder

**Files:**
- Modify: `blur-cmp/src/iosMain/kotlin/io/github/ezoushen/blur/cmp/BlurOverlayHost.ios.kt`
- Check: `blur-cmp/src/iosMain/swift/RealTimeBlurView.swift` (has own `blendOrder: TintBlendOrder`)
- Check: `blur-cmp/src/iosMain/swift/RealTimeVariableBlurView.swift` (has own `blendOrder: TintBlendOrder`)

- [ ] **Step 1: Update applyTint()**

Route by `config.tintOrder`:
- POST_BLUR: teardown pre-blend, apply tint + compositingFilter on tintLayer
- PRE_BLUR: setup pre-blend, apply tint on preBlendTintLayer

```kotlin
private fun applyTint(config: BlurOverlayConfig) {
    val hasTint = config.tintColorValue != 0L
    if (!hasTint) {
        teardownPreBlend()
        tintLayer?.backgroundColor = null
        tintLayer?.compositingFilter = null
        return
    }
    val color = argbToUIColor(config.tintColorValue)
    val filterName = IosBlendModeMapper.toCompositingFilterName(config.tintBlendMode)

    if (config.tintOrder == TintOrder.PRE_BLUR) {
        if (!isBeforeBlurActive) setupPreBlend()
        preBlendTintLayer?.backgroundColor = color?.CGColor
        preBlendTintLayer?.compositingFilter = filterName
        tintLayer?.backgroundColor = null
        tintLayer?.compositingFilter = null
    } else {
        teardownPreBlend()
        tintLayer?.backgroundColor = color?.CGColor
        tintLayer?.compositingFilter = filterName
    }
}
```

- [ ] **Step 2: Bridge TintOrder to Swift views' blendOrder**

The Swift views (`RealTimeBlurView`, `RealTimeVariableBlurView`) have their own
`blendOrder: TintBlendOrder` property (`.auto`, `.beforeBlur`, `.afterBlur`).
Find where the Kotlin layer configures these views and map:
- `TintOrder.PRE_BLUR` → `.beforeBlur`
- `TintOrder.POST_BLUR` → `.afterBlur`

If the Swift views are only used via `IosBlurState` (the CABackdropLayer extraction
path), and NOT through direct Kotlin→Swift bridge calls, then no bridging is needed —
the `applyTint()` routing in Step 1 handles it entirely. Verify by searching for
`RealTimeBlurView` / `RealTimeVariableBlurView` usage in Kotlin code.

- [ ] **Step 3: Build iOS**

Run: `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -configuration Debug build`

- [ ] **Step 4: Commit**

```bash
git add blur-cmp/src/iosMain/
git commit -m "feat: route iOS tint by TintOrder"
```

---

### Task 11: Emulator + Simulator Verification

**Files:** None (testing only)

- [ ] **Step 1: Build Android demo with profiler**

Run: `./gradlew :demoApp:assembleDebug -Pblur.perf.enabled=true`

- [ ] **Step 2: Install on Android emulator, verify all tabs**

Verify Uniform, Variable, ColorDodge tabs. Toggle isLive. Check blur renders correctly with default POST_BLUR tint.

- [ ] **Step 3: Build and run iOS**

Verify blur renders on iOS simulator. Check tint appears post-blur.

- [ ] **Step 4: Screenshot both platforms**

```bash
adb exec-out screencap -p > /tmp/android_tint_verify.png
xcrun simctl io booted screenshot /tmp/ios_tint_verify.png
```
