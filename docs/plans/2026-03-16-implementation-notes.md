# blur-cmp Implementation Notes

## Architecture Overview

blur-cmp provides native real-time backdrop blur for Compose Multiplatform on Android and iOS.

### Android
- **Engine**: blur-core's `BlurView` / `VariableBlurView` via `AndroidView`
- **Blur algorithm**: OpenGL ES 2.0 Dual Kawase (GPU-accelerated, ~39 texture samples per pixel regardless of radius)
- **Capture**: `DecorViewCapture` draws the DecorView to a downsampled bitmap
- **Variable blur**: Per-pixel radius via OpenGL pyramid compositing (6 pre-blurred levels, single-pass composite shader)
- **Blend modes**: `TintOverlayView` for non-Normal blend ordering (background → tint+dodge → blur)

### iOS
- **Engine**: `CABackdropLayer` (private API extracted from `UIVisualEffectView`)
- **Blur algorithm**: Native GPU compositor gaussian/variable blur via `CAFilter`
- **Capture**: `CABackdropLayer` captures live window content at the GPU compositor level
- **Variable blur**: `CAFilter(variableBlur)` with `inputMaskImage` CGImage gradient mask
- **Blend modes**: `compositingFilter` on `CALayer` + two-backdrop-layer architecture for beforeBlur mode

## Key Technical Findings

### Android: StopCaptureException Fix

**Problem**: blur-core's `BlurView.draw()` threw `StopCaptureException` during `DecorViewCapture.capture()` to prevent recursive drawing. When hosted inside Compose via `AndroidView`, this exception propagated through Compose's `RenderNode.beginRecording()`/`endRecording()` lifecycle, corrupting the RenderNode state and crashing the app.

**Solution**: Replace `throw STOP_EXCEPTION` with `return` in `BlurView.draw()` and `VariableBlurView.draw()`. The exception was an optimization (stop drawing siblings above BlurView), not a correctness requirement. Returning early skips drawing during capture without propagating through Compose's rendering pipeline.

### iOS: CABackdropLayer Instantiation

**Problem**: Creating `CABackdropLayer` via `NSObjectMeta.new()` in Kotlin/Native produces a layer that doesn't render. The layer is created and configured (groupName, allowsInPlaceFiltering, filters) but shows black — no content captured.

**Solution**: Extract the `_UIVisualEffectBackdropView` from a temporary `UIVisualEffectView`. This gives a fully system-initialized backdrop view with a working `UICABackdropLayer`. Custom blur filters can then be applied via KVC on the extracted view's layer.

```kotlin
val effectView = UIVisualEffectView(
    effect = UIBlurEffect.effectWithStyle(UIBlurEffectStyleLight)
)
// Find subview with class name ending in "BackdropView"
val backdropView = effectView.subviews.firstOrNull {
    NSStringFromClass(object_getClass(it)!!).contains("BackdropView")
} as? UIView
backdropView?.removeFromSuperview()
// backdropView.layer is now a fully functional UICABackdropLayer
```

### iOS: BackdropLayerProvider K/N Interop Fixes

**Problem 1 — Class method dispatch**: Swift stores `AnyClass` and calls `layerClass.init()` directly. The original K/N port stored class name strings, used `NSClassFromString()`, and cast to `NSObject` for `performSelector("new")`. This is semantically wrong — class objects aren't NSObject instances in K/N's type system.

**Fix**: Use `NSObjectMeta` for class method dispatch:
```kotlin
val cls = object_getClass(view.layer) as? NSObjectMeta
// cls.new() correctly dispatches +[CABackdropLayer new]
```

**Problem 2 — Filter copy memory**: Swift uses `.takeRetainedValue()` on `performSelector("copy")` return. K/N's `performSelector` doesn't have retained/unretained semantics, causing potential double-retain.

**Fix**: Use `NSObject.copy()` directly (correctly annotated `@ReturnsRetained` in K/N klib):
```kotlin
val copied = template.copy() as? NSObject
```

**Problem 3 — CGImageRef KVC bridging**: In Swift, `CGImage` toll-free bridges to NSObject for `setValue(forKey:)`. In K/N, `CGImageRef` is a raw `CPointer<CGImage>` that doesn't auto-bridge.

**Fix**: Use `interpretObjCPointerOrNull` to convert the raw pointer:
```kotlin
val maskAsId = interpretObjCPointerOrNull<NSObject>(maskImage.rawValue)
filter.setValue(maskAsId, forKey = "inputMaskImage")
```

**Problem 4 — Scale override breaks rendering**: Calling `setValue(1.0, forKey = "scale")` on the extracted backdrop view stops it from rendering. The extracted view has its own default scale configured by UIKit.

**Fix**: Don't override the scale property on extracted backdrop views.

### iOS: CMP View Hierarchy and CABackdropLayer

**Problem**: CABackdropLayer captures content from views BELOW it in the UIKit view hierarchy. CMP renders all Compose content into a single `CAMetalLayer` (MetalView). When using `UIKitViewController` interop, CMP places the interop view BELOW MetalView:

```
ComposeView
  UserInputView
    InteropWrappingView  ← index 0 (BOTTOM)
    MetalView            ← index 1 (ON TOP)
    TransparentContainer ← index 2
```

CABackdropLayer in the interop view sees nothing below it → renders black.

**Attempted approaches**:

1. **View reordering** — Move InteropWrappingView above MetalView via `insertSubview(atIndex:)`. Result: the z-ordering changes correctly but CMP's MetalView stops rendering background content (renders white). CMP's rendering pipeline is coupled to its view hierarchy and breaks when views are reordered.

2. **Window-level overlay** — Add blur view directly to `UIWindow` via `addSubview`. Result: SwiftUI's UIHostingController rejects custom subviews added to its view. Adding to window directly also didn't render (possibly due to SwiftUI scene management).

3. **Separate UIWindow overlay** — Create a new `UIWindow` at higher `windowLevel` for the blur. Result: CABackdropLayer in a separate window captured nothing (CABackdropLayer works within the same window's layer tree, not across windows).

4. **rootViewController.view overlay** — Add blur view to `rootViewController.view` (SwiftUI hosting view). Result: **Works!** The blur view is above CMP's MetalView in the same view tree. CABackdropLayer captures Metal-rendered content.

### iOS: Content-on-Top Architecture

**Problem**: The blur overlay on rootVC.view covers ALL CMP content (both background and controls). Controls need to render sharply above the blur.

**Attempted approaches**:

1. **UIKitViewController + view reordering** — Place blur between MetalView and TransparentContainerView. Result: CMP stops rendering background correctly when view order is manipulated.

2. **Separate UIWindow for content** with `ComposeUIViewController(opaque = false)` — **Works!** CMP 1.8+ supports transparent Metal rendering via the `opaque` configuration flag. Content window sits above the blur window, renders only the UI controls with transparent background.

**Final working architecture**:
```
UIWindow (main, windowLevel = Normal)
  SwiftUI HostingView
    ComposeView / MetalView  ← renders background()
    Blur overlay (plain UIView with extracted CABackdropLayer)

UIWindow (content, windowLevel = Normal + 1)
  ComposeUIViewController(opaque = false)  ← renders content()
```

## Current Roadblocks

### 1. Separate UIWindow for iOS Content

The blur overlay must be on `rootViewController.view` for CABackdropLayer to capture CMP content. CMP's UIKitViewController interop places views BELOW MetalView, and reordering breaks CMP's rendering. The only reliable approach is a separate `UIWindow` for content rendering.

**Impact**: Two UIWindows instead of a single unified render tree. The overhead is minimal (both share the same GPU context), but it's architecturally less clean than a single-tree approach.

**Potential future fix**: If CMP adds an API to control interop view z-ordering (placing interop views ABOVE MetalView), or supports custom `CALayer` sublayer injection into the ComposeView's layer tree, a single-window approach would be possible.

### 2. NSObjectMeta.new() Doesn't Produce Working CABackdropLayer

Layers created via `NSObjectMeta.new()` on the extracted `UICABackdropLayer` class don't render, even though the class is correct and the layer object is created. This forces us to extract `_UIVisualEffectBackdropView` from UIVisualEffectView for each blur instance.

**Impact**: Creates and discards a `UIVisualEffectView` per blur view instance. Minor performance cost during setup.

**Root cause**: Unknown. Possibly the `UICABackdropLayer` requires internal initialization that only happens when created as part of `UIVisualEffectView`'s setup (e.g., connection to the window server's compositor, internal state that `+new` doesn't configure).

### 3. CMP Compose State Sharing Across Windows

The content `@Composable` lambda is shared between the main composition (where `BlurOverlayHost` lives) and the content UIWindow's `ComposeUIViewController` via a `ContentHolder` with `mutableStateOf`. This works for state objects (MutableState, StateFlow) but may have edge cases with Compose-specific constructs (CompositionLocal, remember scopes).

**Impact**: Content composables that rely on CompositionLocals from the parent might not work. Need to re-provide them in the content window's composition.

## What Works Now

| Feature | Android | iOS |
|---------|---------|-----|
| Uniform blur | Native OpenGL Dual Kawase | Native CABackdropLayer gaussian |
| Variable blur | Native OpenGL pyramid | Native CAFilter variableBlur + mask |
| Real-time backdrop capture | DecorView capture | GPU compositor capture |
| Sharp content on top | Same CMP render tree | Separate UIWindow (opaque=false) |
| Color dodge blend mode | TintOverlayView + BlurView | Two-backdrop-layer + compositingFilter |
| API level support | Android 23+ (API 23) | iOS 15+ |
| Glow-free variable blur | Per-pixel via GPU shader | Per-pixel via compositor mask |
