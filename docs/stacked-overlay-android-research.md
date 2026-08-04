# Android stacked-backdrop capture research

Research date: 2026-07-29

## Root cause

The original Android failure was a window-boundary problem, not a Kawase blur
problem.

Before this fix, `BackdropBlurDialog` created a separate Android `Dialog`
window for every overlay while a `BlurView` with no explicit source resolved
the Activity decor. Both Overlay A and Overlay B therefore captured the
Activity window; B could not see A's dialog content and produced effectively
the same backdrop as A.

This matches Android's rendering model: a `Window` owns its own top-level decor
view, and view drawing walks that decor's view tree. It does not replay another
window's decor merely because that window is visually behind it
([Window](https://developer.android.com/reference/android/view/Window),
[View drawing](https://developer.android.com/guide/topics/ui/how-android-draws)).

## What each Android capture primitive contains

| Primitive | What it captures | Does it include a transparent window's visual backdrop? |
| --- | --- | --- |
| `sourceView.draw(canvas)` | The selected view and descendants in that view tree. A decor root therefore covers the ordinary views in one window. | No. Other top-level windows are not descendants. |
| `PixelCopy.request(Window, ...)` | The most recently queued buffer in the specified window's surface. | No. Transparent pixels remain pixels from that source buffer; the already-composited windows behind it are not part of that buffer. This follows from the API's source-buffer contract. |
| `PixelCopy.request(Surface, ...)` | The most recently queued buffer in that one surface. | No. It captures that producer surface, not the final display composition. |
| `MediaProjection` | A selected app window or the display composition. | Yes, subject to system capture rules, but Android requires user consent for every session. |
| Internal SurfaceFlinger layer capture | A compositor layer subtree or display. | Potentially, but it is hidden platform API and unavailable to a normal SDK library. |

The repository's API 26+ path locks a hardware canvas and invokes
`sourceView.draw(canvas)`
([source](../blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/capture/SurfaceTextureCapture.kt#L81-L155)).
Its fallback invokes the same method on a software `Bitmap` canvas
([source](../blur-cmp/src/androidMain/kotlin/io/github/ezoushen/blur/capture/DecorViewCapture.kt#L32-L89)).
Changing the canvas destination does not broaden the source beyond the chosen
view tree.

The relevant platform contracts are:

- [`PixelCopy`](https://developer.android.com/reference/android/view/PixelCopy)
  explicitly copies the latest queued buffer of the supplied `Window` or
  `Surface`. It is not a SurfaceFlinger screenshot API.
- [`MediaProjection`](https://developer.android.com/media/grow/media-projection)
  is the public API for capturing an app window or display, and requires user
  consent before every projection session.
- The public [`SurfaceControl`](https://developer.android.com/reference/android/view/SurfaceControl)
  API exposes a compositor-layer handle but no capture operation. AOSP's
  compositor capture implementation is marked `@hide`
  ([AOSP `ScreenCapture`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/window/ScreenCapture.java),
  [AOSP hidden `captureLayers`](https://android.googlesource.com/platform/frameworks/base/+/540e22284684/core/java/android/view/SurfaceControl.java)).

I found no public, consent-free SDK API that returns the final SurfaceFlinger
composition of multiple app windows.

## Implemented result

The Android host creates one transparent edge-to-edge dialog per visible
backdrop layer. Each upper layer captures and composites every lower window in
order, from the Activity through the preceding dialogs, including each lower
dialog's TextureView blur output and sharp UI. Native window ordering keeps an
on-top SurfaceView in a lower dialog behind every upper overlay without
mutating the consumer's SurfaceView configuration. This does not use Android's
built-in blur.

Each layer's `isLive` and `requestUpdate()` state stays independent.

The demo exposes **Reproduce Stacked Overlays**, and
`demoApp/src/androidInstrumentedTest/.../StackedBlurOverlayTest.kt` verifies:

1. a third overlay captures colored UI from both lower overlays; and
2. a non-live upper layer remains frozen while a live lower layer changes,
   then exposes that change after its own one-shot update.

## View-technology boundaries

### Ordinary Views and Compose

These are capturable when they belong to the selected source hierarchy.
Compose hosted in another dialog window is still outside that hierarchy.

### `TextureView`

[`TextureView`](https://developer.android.com/reference/android/view/TextureView)
behaves as a regular view and does not create a separate window, but it only
renders in a hardware-accelerated window. Android explicitly states that it
draws nothing under software rendering.

This repository copies non-protected `TextureView` content with
`TextureView.getBitmap()` and composites it at the view's transformed bounds.
The Android instrumentation suite verifies that a nested blur contains those
pixels.

### `SurfaceView`

[`SurfaceView`](https://developer.android.com/reference/android/view/SurfaceView)
owns a dedicated compositor surface and punches a hole in the containing
window. Replaying the containing view tree is therefore not a capture of the
producer pixels in that surface.

A non-protected `SurfaceView` is copied asynchronously at the blur's
downsampled resolution with `PixelCopy`, then positioned and composited using
the view hierarchy's transforms, clipping, alpha, and Z order. Bounded retries
handle a surface whose first producer buffer has not arrived yet, and the last
successful frame remains available between asynchronous copies. Hardware-
protected sources return `PixelCopy.ERROR_SOURCE_INVALID`, and secure content
is intentionally excluded from screenshots
([PixelCopy errors](https://developer.android.com/reference/android/view/PixelCopy),
[`FLAG_SECURE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)).

Android 36 exposes each `SurfaceView` compositor sublayer through
`getCompositionOrder()`. Android 24–35 does not expose that state through the
public SDK, and `Window` PixelCopy leaves SurfaceView pixels transparent.
When overlapping pre-36 SurfaceViews use compositor order that differs from
their View hierarchy order, call
`SurfaceView.registerBlurCaptureCompositionOrder()` with the matching
SurfaceFlinger sublayer. Regular, media-overlay, and on-top surfaces normally
use `-2`, `-1`, and `1`, respectively. This explicit registration avoids
blocked hidden-API reflection while preserving exact replay order.

Therefore “regardless of view technology” cannot be an unconditional Android
contract: ordinary Views, Compose, non-protected `TextureView`, and
non-protected `SurfaceView` are supported, while secure/DRM surfaces are
impossible to include by design.

## N-layer design without platform blur

The implementation keeps one physical dialog per logical overlay. For each
layer it builds an ordered source list containing the Activity window and every
preceding overlay window, composites those buffers at the blur's requested
bounds and resolution, then runs that layer's custom Kawase/OpenGL blur. This
preserves native window ordering for `SurfaceView` content while allowing each
layer to own its `isLive` and one-shot update policy.

Layers in the same stack with the same capture bounds share raw `Window`
PixelCopy results and build one canonical cumulative prefix per source window.
For N live layers with common bounds this requires N physical window copies and
N raw-plane additions per frame instead of N(N+1)/2. A consumer with different
output dimensions receives one resize of the nearest cumulative prefix. Layers
with different capture bounds use separate batches to preserve exact sampling
without allocating a full-resolution union buffer. Each layer still performs
its own blur pass
because its bounds, liveness, radius, scrim, and transition can differ. Capture
and prefix bitmaps are reused across frames and are released with the stack.

This architecture preserves the device-independent custom blur result and does
not depend on a hidden compositor capture API. With all N layers live, N blur
passes remain unavoidable; capture and prefix composition scale linearly with
stack depth when capture bounds match. Different bounds remain supported but
can require repeated source copies, so performance must still be verified at
the maximum supported depth, bounds, and resolution.

## Recommended verification contract

Before changing production code, the demo should prove:

1. Overlay A contains high-contrast animated UI.
2. Overlay B appears above A and its blur visibly contains A's UI silhouette,
   not only the Activity background.
3. Three or more layers preserve the same rule.
4. Toggling each layer's `isLive` affects only that layer.
5. The requested blur bounds, including fullscreen system-bar coverage, align
   with the lower composite.
6. Ordinary Views, Compose, non-protected `TextureView`, and non-protected
   `SurfaceView` are covered. Secure/DRM content remains excluded by Android.

## Android instrumented Compose tests in this repository

This repository currently uses the legacy `com.android.library` plus
`kotlin { androidTarget() }` arrangement, not the newer
`com.android.kotlin.multiplatform.library` plugin. For this exact arrangement,
the Android device-test Kotlin source set is `androidInstrumentedTest`. The
tests belong to the demo module because they exercise the public demo flow:

```text
demoApp/src/androidInstrumentedTest/kotlin
```

Kotlin's official source-layout guide documents that name and path. It also
states that `androidInstrumentedTest` no longer depends on `commonTest`
automatically, so shared tests need an explicit relationship
([Kotlin Android source-set layout](https://kotlinlang.org/docs/multiplatform/multiplatform-android-layout.html)):

```kotlin
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation("org.jetbrains.compose.ui:ui-test:1.11.1")
        }
        androidInstrumentedTest {
            dependsOn(commonTest)
            dependencies {
                implementation("androidx.test.ext:junit:1.3.0")
                implementation("androidx.test:runner:1.7.0")
                implementation("androidx.compose.ui:ui-test-junit4-android:1.11.1")
            }
        }
    }
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    add(
        "debugImplementation",
        "androidx.compose.ui:ui-test-manifest:1.11.1",
    )
}
```

The Compose Multiplatform test guide documents the common
`org.jetbrains.compose.ui:ui-test:1.11.1` dependency, Android instrumentation
runner, Android JUnit bridge, test manifest, and `connectedAndroidTest` task
([Compose Multiplatform UI testing](https://kotlinlang.org/docs/multiplatform/compose-test.html)).
The AndroidX Test release page gives the current stable
`ext:junit:1.3.0` and `runner:1.7.0` versions
([AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test)).
For this repository specifically, Gradle dependency insight resolves Compose
Multiplatform 1.11.0's Android `androidx.compose.ui` artifacts to 1.11.1, so the
two Android Compose test artifacts above should use 1.11.1 rather than copying
the 1.11.2 examples from the newer documentation.

The exact compile and device tasks used by this repository are:

```shell
./gradlew :demoApp:compileDebugAndroidTestKotlinAndroid
./gradlew :demoApp:connectedDebugAndroidTest
```

The demo module also exposes the aggregate
`:demoApp:connectedAndroidTest` task.

Do not create `androidDeviceTest` or add `withDeviceTestBuilder` unless the
module is deliberately migrated to `com.android.kotlin.multiplatform.library`.
Those are APIs of the newer plugin, whose official migration guide explicitly
distinguishes it from legacy `com.android.library`
([Android-KMP plugin](https://developer.android.com/kotlin/multiplatform/plugin)).
That migration is unrelated to reproducing this blur defect and should not be
bundled with the test.

## Exact `TextureView.draw(Canvas)` behavior

The earlier compatibility statement can be made definitive:

- On an attached hierarchy drawn to a **hardware-accelerated canvas**,
  `TextureView` participates. AOSP's final `TextureView.draw(Canvas)` checks
  `canvas.isHardwareAccelerated()`, obtains its `TextureLayer`, updates its
  current image and transform, and calls
  `RecordingCanvas.drawTextureLayer(layer)`.
- On a **software canvas**, that branch is skipped and `TextureView` contributes
  no pixels.

This is both the public contract—“When rendered in software, TextureView will
draw nothing”—and the platform implementation
([`TextureView` API](https://developer.android.com/reference/android/view/TextureView),
[AOSP `TextureView.draw`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/TextureView.java#419)).

The primary stacked-window path does not depend on this draw behavior:
`PixelCopy.request(Window, ...)` captures each window's `TextureView` pixels,
so `DecorViewCapture` deliberately skips manual `TextureView` replay there.
Only the software `View.draw(Canvas)` fallback uses `getBitmap()` and
composites those pixels explicitly.

## Smallest screenshot assertion that covers stacked dialogs and textures

Use the instrumentation process's display-wide
`InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()`,
then crop and inspect the known blur region. This is API 18+, requires no
MediaProjection consent, and is the smallest platform mechanism here that
observes the final displayed stack rather than one selected view or window
([`UiAutomation.takeScreenshot()`](https://developer.android.com/reference/android/app/UiAutomation#takeScreenshot())).
AOSP shows that the no-argument method captures the full display through
`ScreenCapture`; it therefore observes the Activity, all visible Dialog
windows, and displayed `TextureView` layers in their composed positions
([AOSP `UiAutomation.takeScreenshot`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/UiAutomation.java#1264)).

Do not use the API 34 `takeScreenshot(window)` overload for this assertion: its
contract captures one window's `SurfaceControl`, so it recreates the same
single-window boundary that this regression must detect
([window overload](https://developer.android.com/reference/android/app/UiAutomation#takeScreenshot(android.view.Window))).
Compose's `captureToImage()` is useful for targeted Compose assertions and,
from API 28, explicitly supports multiple roots plus Dialogs. Its contract is
the selected semantics-node surface, however; it does not promise arbitrary
embedded view technologies such as `TextureView`
([Compose `captureToImage`](https://developer.android.com/reference/kotlin/androidx/compose/ui/test/package-summary#captureToImage(androidx.compose.ui.test.SemanticsNodeInteraction))).

A robust non-golden regression should:

1. Put a unique high-contrast marker and motion inside Overlay A.
2. Wait on the blur renderer's first-frame-ready signal (registered as a
   Compose `IdlingResource`), not a fixed sleep
   ([Compose synchronization](https://developer.android.com/develop/ui/compose/testing/synchronization)).
3. Take the full-display screenshot off the main thread and crop Overlay B's
   blur bounds.
4. Assert that the crop contains a softened contribution from A's unique marker
   and differs from the corresponding Activity-only capture. Use regional
   color/edge statistics rather than a whole-screen golden so system bars,
   font rasterization, and emulator density do not dominate the test.

The test should run on a fixed API 28+ emulator because Compose's Dialog
cross-root capture contract starts there, even though the chosen
`UiAutomation` API itself starts at API 18. `FLAG_SECURE` or protected/DRM
content remains intentionally uncapturable and must be excluded from this
success criterion.
