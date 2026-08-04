# blur-cmp

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ezoushen/blur-cmp?color=blue)](https://central.sonatype.com/artifact/io.github.ezoushen/blur-cmp)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.20-purple?logo=kotlin)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose_Multiplatform-1.11.0-8E2DE2)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20iOS-brightgreen)](#)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![CI](https://github.com/ezoushen/blur-cmp/actions/workflows/check.yml/badge.svg)](https://github.com/ezoushen/blur-cmp/actions/workflows/check.yml)

Real-time native backdrop blur for Compose Multiplatform (Android + iOS).

Blurs whatever is behind it in the view hierarchy — like iOS `UIVisualEffectView` or CSS `backdrop-filter`, but cross-platform with a single Compose API.

## Features

- **True backdrop blur** — captures and blurs live content behind the overlay, not its own children
- **Uniform blur** — constant radius across the entire surface
- **Variable blur** — per-pixel radius controlled by linear or radial gradients
- **Blend modes** — 12 blend modes including Color Dodge, Multiply, Screen, Overlay
- **Color tint** — tint the blurred content with any color + blend mode
- **Native GPU performance** — OpenGL Dual Kawase on Android, CABackdropLayer on iOS

## Platform Details

| | Android | iOS |
|---|---|---|
| Blur engine | OpenGL ES 2.0 Dual Kawase | CABackdropLayer (GPU compositor) |
| Min version | API 24 | iOS 15 |
| Published targets | Android release | iosArm64, iosSimulatorArm64 |
| Variable blur | OpenGL pyramid compositing | CAFilter variableBlur + mask |
| Performance | ~39 texture samples/pixel | Zero-cost compositor capture |

Android captures non-protected `TextureView` and `SurfaceView` content without
using the platform blur API. On API 24–35, overlapping SurfaceViews with a
custom compositor sublayer should call
`registerBlurCaptureCompositionOrder(order)`; API 36+ discovers the order
directly. Secure and DRM surfaces cannot be captured.

## Installation

Add the dependency to your KMP module:

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.ezoushen:blur-cmp:<version>")
        }
    }
}
```

The blur engine is bundled — no additional dependencies needed.

## Quick Start

### BlurOverlay — backdrop blur (recommended)

Blurs whatever is behind it. Place it on top of any content:

```kotlin
@Composable
fun MyScreen() {
    val blurState = rememberBlurOverlayState(
        initialConfig = BlurOverlayConfig(radius = 20f)
    )

    Box(Modifier.fillMaxSize()) {
        // Your scene — this gets blurred
        MyContent()

        // Blur overlay — blurs everything behind it
        BlurOverlay(state = blurState) {
            // Sharp controls on top
            Text("Hello", color = Color.White)
        }
    }
}
```

### BlurOverlayHost — managed background

If you want the blur composable to manage both background and foreground:

```kotlin
@Composable
fun MyScreen() {
    val blurState = rememberBlurOverlayState()

    BlurOverlayHost(
        state = blurState,
        background = { PhotoGallery() },
        content = { OverlayControls() },
    )
}
```

## Configuration

### BlurOverlayConfig

```kotlin
BlurOverlayConfig(
    radius = 20f,              // blur radius in logical pixels (0 = no blur)
    tintBlendMode = BlurBlendMode.Normal,  // blend mode for tint
    tintOrder = TintOrder.POST_BLUR,       // POST_BLUR (default) or PRE_BLUR
    downsampleFactor = 4f,     // Android only: higher = faster, lower quality
    gradient = null,           // null = uniform blur, or BlurGradientType
    isLive = true,             // true = updates every frame
)
```

### Presets

```kotlin
BlurOverlayConfig.Default  // radius 16, no tint
BlurOverlayConfig.Light    // radius 10, white tint 25%
BlurOverlayConfig.Dark     // radius 20, black tint 40%
BlurOverlayConfig.Heavy    // radius 50, white tint 50%
```

### Tint Color

Use the `withTint` extension to set a Compose Color:

```kotlin
val config = BlurOverlayConfig(radius = 20f)
    .withTint(Color.White.copy(alpha = 0.2f))
```

Read it back:
```kotlin
val tintColor: Color? = config.tintColor
```

## Variable Blur

Variable blur lets the blur intensity vary across the surface using a gradient.

### Linear Gradient

```kotlin
// Top-to-bottom: full blur at top, clear at bottom
BlurOverlayConfig(
    radius = 30f,
    gradient = BlurGradientType.Linear(
        startX = 0.5f, startY = 0f,   // top center
        endX = 0.5f, endY = 1f,       // bottom center
        startIntensity = 1f,            // full blur
        endIntensity = 0f,              // no blur
    ),
)

// Convenience factory
BlurOverlayConfig(
    radius = 30f,
    gradient = BlurGradientType.verticalTopToBottom(),
)
```

### Radial Gradient (Spotlight)

```kotlin
// Sharp center, blurred edges
BlurOverlayConfig(
    radius = 25f,
    gradient = BlurGradientType.Radial(
        centerX = 0.5f, centerY = 0.4f,
        radius = 0.4f,
        centerIntensity = 0f,   // sharp
        edgeIntensity = 1f,     // blurred
    ),
)

// Convenience factory
BlurOverlayConfig(
    radius = 25f,
    gradient = BlurGradientType.spotlight(centerX = 0.5f, centerY = 0.4f, radius = 0.4f),
)
```

### Multi-Stop Gradient

```kotlin
BlurOverlayConfig(
    radius = 30f,
    gradient = BlurGradientType.Linear(
        startX = 0.5f, startY = 0f,
        endX = 0.5f, endY = 1f,
        stops = listOf(
            BlurGradientType.Stop(0.0f, 1.0f),   // full blur at top
            BlurGradientType.Stop(0.3f, 0.0f),   // clear zone
            BlurGradientType.Stop(0.7f, 0.0f),   // clear zone
            BlurGradientType.Stop(1.0f, 1.0f),   // full blur at bottom
        ),
    ),
)
```

## Blend Modes

12 blend modes for tint compositing:

```kotlin
BlurOverlayConfig(
    radius = 15f,
    tintBlendMode = BlurBlendMode.ColorDodge,
).withTint(Color.White.copy(alpha = 0.2f))
```

Available modes: `Normal`, `ColorDodge`, `ColorBurn`, `Multiply`, `Screen`, `Overlay`, `SoftLight`, `HardLight`, `Darken`, `Lighten`, `Difference`, `Exclusion`

Color Dodge with tint creates a brightening bloom effect.

### Tint Order

By default, tint is applied **after** blur (`TintOrder.POST_BLUR`), matching Apple's `UIVisualEffectView` and CSS `backdrop-filter`. This produces a sharp, uniform tint over the blurred result.

For a softer look where the tint gets diffused by the blur, use `TintOrder.PRE_BLUR`:

```kotlin
BlurOverlayConfig(
    radius = 15f,
    tintBlendMode = BlurBlendMode.ColorDodge,
    tintOrder = TintOrder.PRE_BLUR,  // tint blended into content before blur
).withTint(Color.White.copy(alpha = 0.2f))
```

## Runtime Control

```kotlin
val blurState = rememberBlurOverlayState()

// Update config dynamically
blurState.config = BlurOverlayConfig(radius = newRadius)

// Toggle blur on/off
blurState.isEnabled = false

// Convenience setters
blurState.setRadius(25f)
blurState.setTintColor(Color.Blue.copy(alpha = 0.1f))
blurState.setGradient(BlurGradientType.spotlight())

// Force update when isLive = false
blurState.requestUpdate()
```

## Architecture

### Android

Uses `BlurView` / `VariableBlurView` hosted via `AndroidView`:

1. **Capture**: SurfaceTexture GPU capture via `lockHardwareCanvas` (API 26+), or software canvas fallback
2. **Blur**: OpenGL ES 2.0 Dual Kawase with shared downsample chain (~5ms on Pixel 9)
3. **Output**: `glReadPixels` → `canvas.drawBitmap`, or TextureView for TBDR GPUs
4. **Content exclusion**: overlay content hidden during capture to prevent glow artifacts
5. **API 31+**: `RenderNodeBlurController` uses `RenderEffect` for uniform blur (zero-copy GPU path)

Backdrop `BlurOverlay`s always use the custom Kawase path. Each simultaneous
sibling or nested modal overlay owns a transparent edge-to-edge dialog and
composites every lower window from the activity upward, including each lower
layer's blur and sharp UI. Each layer keeps its own live/frozen update policy.

`BlurOverlay` and `BackdropBlurDialog` register their hosting windows automatically.
Call `RegisterBackdropCaptureSource` only for a source-only custom dialog or sheet
that must appear in blur overlays above it but does not contain its own `BlurOverlay`:

```kotlin
Dialog(onDismissRequest = onDismissRequest) {
    RegisterBackdropCaptureSource()
    SheetContent()
}
```

Manual source injection remains available from an Android source set for hosts that
cannot register from Compose:

```kotlin
CompositionLocalProvider(
    LocalBlurOverlayPlatformContext provides BlurOverlayPlatformContext(
        captureSources = listOf(
            AndroidBlurOverlayCaptureSource(activity.window.decorView, activity.window),
            AndroidBlurOverlayCaptureSource(sheetView.rootView, sheetWindow),
        ),
    ),
) {
    BlurOverlay(state = blurState) { OverlayContent() }
}
```

Sources are composited from back to front. Keep the list aligned with the
currently mounted windows; an empty list falls back to the hosting activity.

### iOS

Uses `CABackdropLayer` extracted from `UIVisualEffectView`:

1. `CABackdropLayer` captures live window content at the GPU compositor level (zero-copy)
2. `CAFilter` (gaussianBlur or variableBlur) applies blur natively
3. Blur overlay is added to `rootViewController.view` above CMP's MetalView
4. Content renders in a separate transparent `UIWindow` via `ComposeUIViewController(opaque = false)`

## Requirements

- **Kotlin**: 2.3.20+
- **Compose Multiplatform**: 1.11.0+
- **Android Gradle Plugin**: 8.13.2+ recommended for Kotlin 2.3 Android metadata
- **Android**: API 24+ (minSdk 24)
- **iOS**: 15+ on iosArm64 and iosSimulatorArm64

Compose Multiplatform 1.11.0 no longer publishes Apple x86_64 artifacts. Projects that need the Intel iOS simulator target must stay on the last 0.7.x blur-cmp line or maintain a separate legacy build.

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

- [Bug reports](.github/ISSUE_TEMPLATE/bug_report.yml) — reproduce and fix issues
- [Feature requests](.github/ISSUE_TEMPLATE/feature_request.yml) — suggest improvements
- [Pull requests](https://github.com/ezoushen/blur-cmp/pulls) — code contributions

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for version history.

## License

Apache License 2.0
