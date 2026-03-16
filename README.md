# blur-cmp

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
| Min version | API 23 | iOS 15 |
| Variable blur | OpenGL pyramid compositing | CAFilter variableBlur + mask |
| Performance | ~39 texture samples/pixel | Zero-cost compositor capture |

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

For Android, blur-cmp depends on `blur-core` for the native OpenGL blur engine:

```kotlin
// settings.gradle.kts — if using local source
includeBuild("../blur-android") {
    dependencySubstitution {
        substitute(module("io.github.ezoushen:blur-core")).using(project(":blur-core"))
    }
}
```

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

Color Dodge with tint creates a brightening bloom effect. The ordering is always: capture background → apply tint with blend mode → blur the result.

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

Uses `blur-core`'s `BlurView` / `VariableBlurView` hosted via `AndroidView`:

1. `DecorViewCapture` draws the DecorView to a downsampled bitmap
2. OpenGL Dual Kawase blur processes the bitmap on GPU (~6 passes, constant cost regardless of radius)
3. Blurred bitmap is drawn scaled-up behind the content
4. Content overlay is excluded from capture to prevent glow artifacts

### iOS

Uses `CABackdropLayer` extracted from `UIVisualEffectView`:

1. `CABackdropLayer` captures live window content at the GPU compositor level (zero-copy)
2. `CAFilter` (gaussianBlur or variableBlur) applies blur natively
3. Blur overlay is added to `rootViewController.view` above CMP's MetalView
4. Content renders in a separate transparent `UIWindow` via `ComposeUIViewController(opaque = false)`

## Requirements

- **Kotlin**: 2.2.20+
- **Compose Multiplatform**: 1.8.2+
- **Android**: API 23+ (minSdk 23)
- **iOS**: 15+

## License

Apache License 2.0
