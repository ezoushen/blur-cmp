# Android Blur Zero-Copy Optimization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate all CPU↔GPU roundtrips in the Android blur pipeline for the most common case (API 31+, uniform blur) by using RenderEffect, saving 2-5ms/frame from software DecorView capture.

**Architecture:** On API 31+ with uniform blur and Normal blend mode tint, `BlurOverlayHost` inserts a new branch inside its existing `if (state.isEnabled && config.radius > 0f)` block. This branch uses `RenderEffect.createBlurEffect(...).asComposeRenderEffect()` on a `graphicsLayer` wrapping the background composable. Content is rendered as a normal Compose sibling. The existing code structure is preserved for all other paths.

**Tech Stack:** Compose Multiplatform, `android.graphics.RenderEffect` (API 31+), `asComposeRenderEffect()`, Kotlin

---

## Review-Informed Decisions

Shaped by 3 reviews + bug research (2 reviewer iterations):

- **Feasibility:** EGL HardwareBuffer→FBO needs NDK/JNI — not pure Kotlin. Deferred.
- **Performance:** `glReadPixels` at 4x downsample costs ~0.2ms. The real bottleneck is software `sourceView.draw()` at 2-5ms. HardwareBuffer FBO saves ~0.3ms for ~500 LOC + 4-6MB RAM — premature.
- **Architecture:** No `BlurStrategy` abstraction. Simple `if` branch. Don't restructure existing code. `background()` stays unconditional at line 49.
- **Bug research:** `RenderEffect(0,0)` crashes (Google #241546169). API 31 RenderNode stale rendering. EGLImage crashes on Adreno/Mali.
- **Review iteration 2:** Fixed wrong API class (`BlurEffect` → `RenderEffect.createBlurEffect().asComposeRenderEffect()`), fixed `TileMode` (`Shader.TileMode.CLAMP`), moved tint overlay outside blurred layer.

**Scoped out:** HardwareBuffer FBO output, RenderNode capture, controller duplication refactor (separate PRs).

---

## Known Bugs to Guard Against

| Bug | Guard |
|---|---|
| `RenderEffect.createBlurEffect(0f, 0f)` crashes (Google #241546169) | Set `renderEffect = null` when radius ≤ 0 |
| `graphicsLayer` forces offscreen compositing | Background composable must fill its bounds |
| Lambda vs non-lambda `graphicsLayer` | MUST use lambda form for animated radius |
| Tint inside blurred layer gets blurred | Tint is a SEPARATE sibling Box, outside the `graphicsLayer` with RenderEffect |

---

## File Structure

```
MODIFY: blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/cmp/BlurOverlayHost.android.kt
  - Add RenderEffect branch inside existing if-else
  - Add RenderEffectBlurOverlay private composable
  - Add imports

READ ONLY (reference):
  blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/compose/BlurModifier.kt:40-48  ← correct API pattern
  blur-cmp/src/commonMain/kotlin/io/github/ezoushen/blur/cmp/BlurColorExtensions.kt:8  ← tintColor exists
```

---

### Task 1: Add RenderEffect blur path for BlurOverlayHost

**Files:**
- Modify: `blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/cmp/BlurOverlayHost.android.kt`

- [ ] **Step 1: Add imports to BlurOverlayHost.android.kt**

Add after existing imports:

```kotlin
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
```

- [ ] **Step 2: Insert RenderEffect gate inside existing if-else block**

In `BlurOverlayHost`, inside the `if (state.isEnabled && config.radius > 0f)` block (line 51), add a new branch BEFORE the existing `if (gradient != null)` check (line 55). The `background()` call at line 49 stays untouched.

```kotlin
        if (state.isEnabled && config.radius > 0f) {
            val gradient = config.gradient

            // Tier 1: API 31+ uniform blur with Normal blend mode tint.
            // Bypasses entire View-based pipeline — pure Compose graphicsLayer.
            val hasNonNormalTint = config.tintColorValue != 0L &&
                config.tintBlendMode != BlurBlendMode.Normal
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                gradient == null && !hasNonNormalTint
            ) {
                RenderEffectBlurOverlay(state, config, background)
                content()
                return@Box
            }

            // Existing Kawase paths below (unchanged) ...
            var prevAlpha by remember { mutableFloatStateOf(state.alpha) }
```

- [ ] **Step 3: Add RenderEffectBlurOverlay composable**

Add after `BlurOverlayHost`, before `ContentOverlay`:

```kotlin
/**
 * API 31+ blur overlay using RenderEffect on graphicsLayer.
 *
 * Eliminates all CPU-GPU roundtrips: no software capture, no OpenGL, no readback.
 * The background composable is rendered inside a graphicsLayer with RenderEffect blur.
 * Tint overlay is a separate sibling (outside the blur) so it stays crisp.
 *
 * background() is invoked here AND as an unblurred sibling at the Box root.
 * When alpha=1, only the blurred version is visible. When alpha=0, the blurred
 * layer is invisible and the sharp background shows through. This matches the
 * existing BlurView behavior. The duplicate composition is a known tradeoff.
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun RenderEffectBlurOverlay(
    state: BlurOverlayState,
    config: BlurOverlayConfig,
    background: @Composable () -> Unit,
) {
    // Blurred background layer.
    // Lambda graphicsLayer: updates properties without recomposition.
    // Pattern matches BlurModifier.kt:41-48.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Guard: RenderEffect.createBlurEffect(0,0) crashes on API 31+
                // (Google Issue Tracker #241546169 — unfixed upstream).
                val r = config.radius
                renderEffect = if (r > 0f) {
                    RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                } else {
                    null
                }
                alpha = state.alpha
            }
    ) {
        background()
    }

    // Tint overlay — SEPARATE from the blurred graphicsLayer so tint stays crisp.
    // Matches BlurModifier.kt:50-55 pattern (tint drawn AFTER blur, on top).
    val tintColor = config.tintColor
    if (tintColor != null && state.alpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.alpha }
                .drawBehind { drawRect(tintColor) }
        )
    }
}
```

- [ ] **Step 4: Build and verify compilation**

Run: `./gradlew :blur-cmp:compileKotlinAndroid`

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Install demo app on API 31+ Android emulator and test**

Run: `./gradlew :demoApp:installDebug`

Test checklist:
- [ ] Uniform blur tab: blur visible, no crash
- [ ] Radius slider to 0: blur disappears, no crash (validates radius=0 guard)
- [ ] Radius slider back up: blur reappears smoothly
- [ ] Transition tab: fade in/out works without background freeze
- [ ] Variable blur tab: still uses Kawase pipeline (gradient pattern visible)
- [ ] Tint overlay renders as crisp color, not blurred

- [ ] **Step 6: Install on API 30 emulator to verify fallback**

Run: `./gradlew :demoApp:installDebug` (API 30 emulator)

Verify all blur modes work identically to before. RenderEffect path must NOT activate.

- [ ] **Step 7: Commit**

```bash
git add blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/cmp/BlurOverlayHost.android.kt
git commit -m "$(cat <<'EOF'
feat: RenderEffect blur path for API 31+ uniform blur

On API 31+ with uniform blur and Normal blend mode tint, bypass the
entire View-based pipeline (BlurView, BlurController, OpenGLBlur,
DecorViewCapture) using Compose graphicsLayer with RenderEffect.

Eliminates all 4 CPU-GPU boundary crossings for the most common case.
Guards against RenderEffect(0,0) crash (Google #241546169).
Uses Shader.TileMode.CLAMP matching existing BlurModifier.kt.
Tint overlay is a separate sibling outside the blurred layer.

Falls through to existing Kawase pipeline for:
- Gradient/variable blur
- Non-Normal blend mode tint
- API < 31
EOF
)"
```

---

### Task 2: Update documentation

**Files:**
- Modify: `docs/architecture.md`
- Modify: `docs/android-blur-optimization.md`

- [ ] **Step 1: Add RenderEffect path to architecture.md**

After "2. Android Pipeline" heading, add:

```markdown
### 2.0 RenderEffect Fast Path (API 31+, uniform blur)

When conditions are met (API 31+, no gradient, Normal blend mode tint),
BlurOverlayHost bypasses the entire View-based pipeline:

  background()                    <- unblurred (always rendered at Box root)
  Box(graphicsLayer {
    renderEffect = BlurEffect     <- GPU blur on RenderThread
    alpha = state.alpha           <- GPU alpha modulation
  }) {
    background()                  <- re-rendered inside blurred layer
  }
  Box(tint overlay)               <- crisp tint, outside blur layer
  content()                       <- sharp, on top

  CPU-GPU crossings: 0
  CPU involvement: zero pixel access

Falls through to Kawase pipeline (Section 2.1+) when:
- API < 31
- Gradient/variable blur configured
- Non-Normal blend mode tint (requires pre-blur bitmap access)
```

- [ ] **Step 2: Update android-blur-optimization.md**

Add a "Status" section noting Tier 2/3 deferred with rationale.

- [ ] **Step 3: Commit**

```bash
git add docs/
git commit -m "docs: add RenderEffect fast path to architecture docs"
```

---

## Summary

| Task | Files | Risk | Lines |
|---|---|---|---|
| 1. RenderEffect blur path | `BlurOverlayHost.android.kt` | Low — additive branch | ~60 |
| 2. Documentation | `docs/*.md` | None | ~30 |

**What changes:** A single new `if` branch + one private composable.

**What does NOT change:** The entire existing Kawase pipeline. All existing code paths. Variable blur. API < 31 behavior. No new dependencies, no NDK, no JNI.

**Known tradeoff:** `background()` composable is invoked twice in the RenderEffect path (once unblurred, once blurred). The unblurred instance is hidden behind the blurred overlay. This creates a duplicate composition subtree but is functionally correct. Could be optimized later with `GraphicsLayer.record{}` capture if profiling shows it matters.
