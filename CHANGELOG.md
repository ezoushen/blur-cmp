# Changelog

## [Unreleased]

## [0.10.1] — 2026-08-01

- fix: expose backdrop readiness on every platform
- fix(android): preserve capture sources for stacked overlay composition
- fix(android): keep blur geometry stable while the radius animates

## [0.10.0] — 2026-07-30

- Raise Android minSdk to 24 so every supported device has reliable
  `SurfaceView` capture through `PixelCopy`.
- fix(android): compose sibling and nested blur overlays from every lower layer
- fix(android): retain SurfaceView frames until asynchronous window capture completes
- feat(android): register pre-36 SurfaceView compositor order for exact replay
- test(android): cover independent bounds, three-layer composition, and per-layer liveness

## [0.9.3] — 2026-07-23

- fix(ios): balance nested ComposeVC appearance transitions
- feat(ios): touch pass-through via BlurOverlayState.isInteractionEnabled
- fix(demo): hide window-mode dialog on Android — iOS-only

## [0.9.2] — 2026-06-18

- fix: layout constraints on API < 31

## [0.9.1] — 2026-06-16

- fix(android): exclude overlay content via alpha, not visibility, so IME keeps focus
- test(demo): window-mode (separated) dialog scenario

## [0.9.0] — 2026-06-12

- feat(ios): integrated single-window BlurOverlay (replaces hybrid window)
- test(demo): present-on-top scenarios for integrated BlurOverlay
- docs: update iOS architecture to single-window integrated stack

## [0.8.2] — 2026-05-21

- feat: add `BackdropBlurDialog` + auto-wrap `BlurOverlay`

## [0.8.1] — 2026-05-21

- fix: hide BlurView until first GL frame to avoid cold-mount black flash

## [0.8.0] — 2026-05-20

- chore: migrate to Kotlin 2.3 / Compose Multiplatform 1.11

## [0.7.2] — 2026-05-21

- Backport of BackdropBlurDialog on 0.7.x line

## [0.7.1] — 2026-05-21

- fix: hide BlurView until first GL frame to avoid cold-mount black flash

## [0.7.0] — 2026-05-06

- perf: AUTO -> SURFACE_TEXTURE capture when API 26+ and OES extension available
- feat: persist GL program binaries across launches (`GL_OES_get_program_binary`)
- fix: backdrop blur lifecycle, EGL context loss, shader prewarm

## [0.6.0] — 2026-03-29

- feat: add `BlurOverlayPlatformContext` CompositionLocal for platform-specific config
- feat(ios): support injected window via `LocalBlurOverlayPlatformContext`

## [0.5.4] — 2026-03-28

- fix: revert to UIWindow approach for proper CABackdropLayer blur

## [0.5.3] — 2026-03-28

- fix: use UIKitView interop for iOS blur instead of separate UIWindow

## [0.5.2] — 2026-03-27

- fix: remove radius check from iOS early return to prevent crash

## [0.5.1] — 2026-03-26

- fix: use Kawase pipeline for backdrop blur to avoid ANR on complex hierarchies

## [0.5.0] — 2026-03-24

- feat: add TintOrder (POST_BLUR / PRE_BLUR) to BlurOverlayConfig
- feat: route iOS tint by TintOrder
- refactor: rename overlayColor → tintColor across all APIs
- docs: rewrite performance-analysis.md as final project report

## [0.4.0] — 2026-03-23

- perf: shared downsample chain and direct pyramid write (38% faster)
- feat: BrowserStack E2E test scripts for real device profiling
- feat: RenderNode + RenderEffect blur for API 31+ (zero-copy GPU)
- feat: SurfaceTexture GPU capture (API 26+)
- feat: TextureView output for TBDR GPUs (PowerVR)
- feat: `isLive` toggle and build-config-gated perf instrumentation
- refactor: two-flag dirty tracking (configDirty / contentDirty)

## [0.3.0] — 2026-03-17

- feat: animate both radius and tint alpha in Transition demo
- fix: scale downsample factor with blur radius for smooth transitions

## [0.2.5] — 2026-03-17

- Fixes and polish for alpha transitions on both platforms

## [0.2.4] — 2026-03-17

- Minor bugfixes for alpha fade animation

## [0.2.3] — 2026-03-17

- Additional alpha transition fixes

## [0.2.2] — 2026-03-17

- feat: add alpha property to BlurOverlayState for cross-platform fade animation
- fix: pre-blur tint pipeline and alpha transition support
- perf: publish to Maven Central and GitHub Packages in single invocation

## [0.2.1] — 2026-03-17

- fix: inline blur-core into androidMain to eliminate unresolvable transitive dependency

## [0.2.0] — 2026-03-17

- feat: add GitHub Packages publishing alongside Maven Central
- fix: add developer email and fix SCM connection URL for Maven Central validation

## [0.1.0] — 2026-03-16

- Initial release
- Real-time backdrop blur via OpenGL Dual Kawase (Android) and CABackdropLayer (iOS)
- Uniform and variable blur with linear/radial gradients
- 12 blend modes for tint compositing
- Maven Central publishing
