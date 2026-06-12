# Feature Request: make "fully-integrated" the default iOS hosting model for `BlurOverlay`

- **Status:** Proposed
- **Area:** `blur-cmp` iOS (`BlurOverlayHost.ios.kt`), commonMain dispatcher (`BlurOverlayHost.kt`)
- **Type:** Behavior change (default) + cleanup (remove hybrid path)
- **Impact:** iOS only. Android already behaves as proposed.
- **Compat:** Behavior-breaking on iOS for consumers that rely on the content living in a separate `UIWindow`. Requires a minor/major version bump + changelog.

---

## 1. Summary

On iOS, `BlurOverlay` currently hosts its **content** in a brand-new `UIWindow`
at `windowLevel = .normal + 1`, while the **backdrop** blur view is inserted as a
sibling into the existing app window's `rootViewController.view`. This split
("hybrid") model is the sole reason a coordinator/view-controller presented from
inside a blur overlay renders **underneath** the overlay's content on iOS, and it
introduces a `keyWindow`-stacking fragility.

This request proposes:

1. **Replace the hybrid default with a "fully-integrated" model**: host the
   **backdrop and the content together inside a single plain-native container
   `UIViewController`**, added as **one child VC** of the host
   `rootViewController`. Inside that container: the backdrop is a native subview
   (placement/capture path unchanged from today), and the content is a nested
   `ComposeUIViewController` ordered **above** the backdrop. No second window.
2. **Keep the existing "fully-separated" model** (consumer-/lib-owned window via
   `LocalBlurOverlayPlatformContext`) as the explicit alternative for callers that
   need window isolation (this is what `PlatformDialogManager` in stforestkit uses).
3. **Delete the hybrid code path** (`DefaultBlurOverlay`'s separate content window
   + `makeKeyAndVisible`).

Net result: two coherent, orthogonal modes — *integrated* (default, no extra
window, single child VC owns both backdrop + content) and *separated* (opt-in,
isolated window) — both of which support presenting child screens on top of the
overlay.

---

## 2. Background — three structural states exist today

iOS `BlurOverlayHost` (`blur-cmp/src/iosMain/.../BlurOverlayHost.ios.kt`) branches
on `LocalBlurOverlayPlatformContext.current.contentWindow`:

| Mode | Backdrop lives in | Content lives in | Touches host VC tree | Present child on top |
|---|---|---|---|---|
| **Separated** (injected window) | the injected window | the same injected window (inline) | no | ✅ |
| **Hybrid** (current default) | **app window** (sibling subview) | **new `.normal + 1` window** | yes (backdrop) | ❌ |
| **Integrated** (this proposal) | container VC (native subview) | same container VC (nested ComposeVC, above backdrop) | yes (one child VC) | ✅ |

Reference: `docs/architecture.md` §3.1 documents the hybrid component stack
(Main `UIWindow` holding the backdrop, plus a `Content UIWindow` at `.normal + 1`).

Relevant history:

- `ccbc421` — "content-on-top via transparent ComposeUIViewController overlay window" (introduced the hybrid).
- `da8fc77`, `fa5b2bd` — attempts to embed the backdrop inline via `UIKitView` interop (single composition root, to fix moko-resources / Koin / CompositionLocal).
- `5136288` — **reverted** the inline-backdrop attempt: *"UIKitView interop didn't capture Metal-rendered content for CABackdropLayer blur."* Confirms the backdrop must remain a **real native view** (not inside a Compose/Metal surface).
- `2d1cce2` — added the injected-window (separated) mode.

This proposal is consistent with that history: the backdrop **stays a real native
view** (the part `5136288` proved is required) and is never nested inside a Compose
surface. It only relocates the **content** surface from a new window to a nested VC
in the same window, and groups backdrop + content under one container VC.

---

## 3. Problem

### 3.1 Children presented from inside a blur overlay render underneath it (iOS)

In the hybrid model the **presenting view controller** and the **content** live in
**different windows**:

- Content is in the `.normal + 1` window.
- The host composition (and therefore the view controller that performs a modal
  `presentViewController(...)`) is in the app window at `.normal`.

A full-screen modal presented from a `.normal`-window VC cannot cover a
`.normal + 1` window. The presented screen therefore appears **below** the overlay
content.

**Concrete repro (stforestkit):** `TreeTypeSelectionCoordinator` /
`MenuCoordinator` host a `BlurOverlay`-backed sheet/drawer, then present a child
coordinator (e.g. the store/subscription screens) while the sheet stays open. On
iOS the child renders under the sheet. On Android the same flow works because the
child is launched as a new `Activity` (top of the task stack).

Both clean modes (separated, integrated) put the presenting VC and the content in
the **same** window, so the modal covers the content correctly. The hybrid is the
only broken configuration.

### 3.2 `keyWindow` stacking fragility

`DefaultBlurOverlay` calls `contentWindow.makeKeyAndVisible()`, and
`findActiveWindowScene()` resolves its scene via `keyWindow`. After the first
overlay mounts, `keyWindow` is the overlay's content window. By construction, a
**second** overlay mounted on top resolves the wrong scene/root and would insert
its backdrop into the first overlay's content window rather than the app window —
so the second backdrop samples the wrong content. The integrated model performs no
`keyWindow` change and avoids this entirely.

### 3.3 The hybrid is the worst of both axes

It mutates the host window (like integrated) **and** spawns a second window (like
separated), earning neither isolation nor presentability.

---

## 4. Proposal — fully-integrated as the default

### 4.1 iOS layer hierarchy (target)

```
app UIWindow (.normal)                              ← unchanged; created by the host
└─ rootViewController.view
   ├─ [0] host CMP MetalView                        → background (e.g. Focus)   ← sampled by backdrop
   └─ [1] containerVC.view (plain UIView, clear)    ← ONE child VC added to rootVC
          ├─ [0] backdrop native view (CABackdrop)  → blurs the MetalView behind the (transparent) container
          └─ [1] content ComposeUIViewController.view → sharp, opaque = false; sibling ABOVE the backdrop
```

- **Container VC must be a plain `UIViewController`** whose `.view` is a plain
  (transparent) `UIView` — **not** a `ComposeUIViewController`. A
  `ComposeUIViewController`'s `.view` is itself a Metal surface; nesting the
  backdrop inside it re-triggers the exact `5136288` failure (backdrop inside a
  Compose/Metal surface does not capture).
- The backdrop is a **real native view** as today; it now lives one nesting level
  deeper, inside the transparent container, but still in the pure-native UIKit tree.
- The content `ComposeUIViewController` is a child VC of the **container** VC, its
  view inserted **above** the backdrop.
- A child screen presented from the host VC (same app window) is a full-screen
  modal over the app window → renders **on top** of the content. Bug resolved.

### 4.2 Critical implementation note — content sibling ABOVE the backdrop, NOT child of it

`IosBlurState.setupInView` / `setupAsBackdrop` set
`container.userInteractionEnabled = false`. On iOS that disables touch delivery for
the view **and all subviews**. Therefore the content VC's view must be a **sibling
above** the backdrop native view (both subviews of the container VC's view), **not**
a subview of the backdrop view — otherwise the content goes dead to touch.

This is precisely why backdrop and content cannot share one Compose composition:
the sharp/blurred split requires a native layer (the backdrop) physically between
two surfaces.

### 4.3 Capture-through-wrapper — the one open risk; spike before full impl

`5136288` proved a backdrop nested **inside a Compose/Metal surface** does not
capture. It did **not** test a backdrop nested inside a **plain transparent native
`UIView`** that is itself a sibling above the host Metal surface. In pure-native
UIKit, `CABackdropLayer` (and `UIVisualEffectView`) capture window content behind
them regardless of transparent native ancestors — so this is expected to work, but
it is unproven for this codebase.

**Gate:** a short spike must confirm the backdrop still captures the host MetalView
when nested inside the transparent container VC. If it does **not**, fall back to
hosting the backdrop directly on `rootViewController.view` (proven placement) and
the content as a second sibling child VC — i.e. two children of `rootVC` instead of
one container. Functionally identical for present-on-top; only teardown bookkeeping
differs.

### 4.4 iOS implementation sketch

A new internal path in `BlurOverlayHost.ios.kt` (replacing `DefaultBlurOverlay`):

```kotlin
@Composable
private fun IntegratedBlurOverlay(
    state: BlurOverlayState,
    modifier: Modifier,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val blurState = remember { IosBlurState() }
    val contentHolder = remember { ContentHolder() }
    contentHolder.content = content

    DisposableEffect(Unit) {
        val rootVC = findActiveWindowScene()?.keyWindow?.rootViewController
        val rootView = rootVC?.view
        if (rootView != null) {
            // ONE plain-native container VC; its view is a transparent sibling above the host MetalView.
            val containerVC = UIViewController()
            containerVC.view.backgroundColor = UIColor.clearColor
            containerVC.view.setOpaque(false)
            containerVC.view.setAutoresizingMask(FlexibleWidth or FlexibleHeight)
            containerVC.view.setFrame(rootView.bounds)

            rootVC.addChildViewController(containerVC)
            rootView.addSubview(containerVC.view)        // sibling above host MetalView
            containerVC.didMoveToParentViewController(rootVC)

            // Backdrop: real native view, now inside the container (NOT inside any Compose surface).
            blurState.setupInView(containerVC.view, state.config)

            // Content: nested ComposeVC, sibling ABOVE the backdrop within the container.
            val contentVC = ComposeUIViewController(configure = { opaque = false }) {
                Box(Modifier.fillMaxSize()) { contentHolder.content() }
            }
            contentVC.view.backgroundColor = UIColor.clearColor
            contentVC.view.setOpaque(false)
            contentVC.view.setAutoresizingMask(FlexibleWidth or FlexibleHeight)

            containerVC.addChildViewController(contentVC)
            containerVC.view.addSubview(contentVC.view)  // added last → above the backdrop
            contentVC.didMoveToParentViewController(containerVC)

            blurState.containerViewController = containerVC   // new field; replaces contentWindow
        }
        onDispose {
            blurState.cleanupIntegrated()   // tear down content VC + backdrop + container VC together
        }
    }

    LaunchedEffect(state.config) { blurState.applyConfig(state.config) }
    LaunchedEffect(state.alpha)  { blurState.applyAlpha(state.alpha) }

    Box(modifier) { background() }          // EmptyBackground for BlurOverlay
}
```

`IosBlurState` changes: drop `contentWindow` + `makeKeyAndVisible`, add
`containerViewController` + `cleanupIntegrated()` (removes nested content VC,
backdrop, then the container VC from its parent). No `keyWindow` mutation.

### 4.5 Android

Android already matches the integrated model for the common path:
`BlurOverlayHost.android.kt` hosts the `BlurView` (Kawase, DecorView capture) and
the content (`ContentOverlay`, `addExcludedView`) **inline in the host
`ComposeView`** — same window. No change required for the default.

(Separate note, out of scope: on Android `BlurOverlay(onDismissRequest != null)`
wraps content in `BackdropBlurDialog` — a separate `Dialog` window. That conflates
"handle dismissal" with "host in a separate window." If we want the two modes to be
explicitly selectable on both platforms, consider decoupling dismissal handling
from window hosting. Tracked separately.)

### 4.6 Keeping "separated" as the explicit alternative

The injected-window mode (`LocalBlurOverlayPlatformContext`) stays as-is and remains
the right choice when a caller needs true window isolation, a specific
`windowLevel`, or a composition root decoupled from the host VC tree
(`PlatformDialogManager` continues to use it at `UIWindowLevelAlert`).

---

## 5. Why integrated is a safe default (and the trade-off we accept)

- **No capture regression (pending spike §4.3):** the backdrop stays a real native
  view; only its nesting level changes (into a transparent native container).
- **Fixes present-on-top out of the box** for the common case (overlay that
  presents a child while staying open).
- **Removes the `keyWindow` stacking hazard.**
- **Single-VC ownership:** backdrop + content tear down together with the container
  VC — cleaner than the hybrid's window teardown and simpler than two-sibling
  bookkeeping.

Accepted assumption (documented): integrated mode parents a container VC into
`keyWindow.rootViewController` and relies on it being stable and addressable. For
hosts that swap their root VC or need isolation, use the **separated** mode. (Note
the hybrid already depended on `keyWindow` for its backdrop, so this is not a new
class of assumption — and the escape hatch is a first-class mode.)

Unchanged caveat: the content VC is still a separate composition root (as it was in
the hybrid), so DI / resources / `CompositionLocal` initialization is the caller's
responsibility for that VC (consumers already handle this; e.g. stforestkit's
`ForestComposeUIViewController`). Note this is a real difference from the
**separated** mode, which renders content **inline** in the caller's composition and
therefore preserves host `CompositionLocal`s — integrated does not. Integrated is
no-regression vs the hybrid on this axis, not strictly superior to separated.

---

## 6. API / migration

- No public API signature change required for the default flip; `BlurOverlay(...)`
  keeps its current signature.
- iOS behavior changes: content no longer lives in a separate window. Consumers who
  depended on that (e.g. relied on the overlay floating above app windows they
  didn't tell blur-cmp about, or on `keyWindow` becoming the overlay) must switch to
  the **separated** mode via `LocalBlurOverlayPlatformContext`.
- Version bump + `CHANGELOG`/`README`/`docs/architecture.md` §3.1 update (replace the
  two-window stack diagram with the single-window, single-container-VC integrated
  stack; move the two-window diagram under the "separated mode" section).

---

## 7. Acceptance criteria

iOS, on simulator + device:

0. **(Spike, gates the rest)** Backdrop nested inside the transparent container VC
   still captures the host MetalView — blur renders, not transparent/empty.
1. A `BlurOverlay`-backed screen that presents a full-screen child VC shows the
   child **on top** while the blurred overlay remains mounted underneath; on
   dismissal the overlay reappears unchanged.
2. Blur still renders correctly (radius, tint POST/PRE, variable/gradient), with no
   cold-mount black flash and no static-art regression.
3. Touches pass through transparent content regions to the underlying controls;
   interactive content remains interactive.
4. Two stacked overlays each sample the correct backdrop (no `keyWindow`
   mis-resolution).
5. No leaked VCs/views after dismissal (mirror the dialog teardown discipline:
   tear down on exit-animation completion).

Android:

6. Existing behavior unchanged; the common (no-dialog) path remains inline/integrated.

Reference repro to validate against: stforestkit `TreeTypeSelectionCoordinator`
presenting the store/subscription screens, and `MenuCoordinator` presenting a child,
on iOS. For in-repo verification, add a demo-app scenario that presents a native
child VC from inside a `BlurOverlay` (demo currently has none).

---

## 8. Alternatives considered

- **Two sibling child VCs on `rootVC`** (backdrop directly on `rootViewController.view`
  as today + a separate content child VC above it). This is the proposal's own
  fallback (§4.3) if the capture-through-wrapper spike fails. Backdrop placement is
  byte-identical to today (zero capture risk) at the cost of slightly more
  ordering/teardown bookkeeping (two things on `rootVC`). Chosen the single-container
  form as default for cleaner single-VC ownership, contingent on the spike.
- **Lib-owned fully-separated window as default** (auto-create one window holding
  *both* backdrop + content; route through the injected code path with a
  self-supplied window). Deletes the most code. Rejected as the **default**: a
  lib-owned window sits *above* the app window, so a modal presented from the
  app-window coordinator (the stforestkit repro) would land *under* it — i.e. it
  reintroduces the same present-on-top bug as the hybrid for that flow. Also adds an
  extra window, which we want to avoid for this consumer profile.
- **Add integrated as a third mode, keep hybrid.** Rejected: the hybrid is
  incoherent and the source of the bug + the `keyWindow` hazard; three modes where
  two overlap increases surface and test matrix for no benefit.
