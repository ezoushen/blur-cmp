package io.github.ezoushen.blur

/**
 * Strategy for the blur capture pipeline.
 *
 * Controls how view content is captured and uploaded to the GPU for blurring.
 * Use [AUTO] for best performance on each device, or force a specific strategy
 * for testing and debugging.
 *
 * - [RENDER_EFFECT]: API 31+ RenderEffect blur (no capture needed)
 * - [EGL_IMAGE]: API 29+ HardwareBuffer -> EGLImage -> GL_TEXTURE_2D (zero copy)
 * - [SURFACE_TEXTURE]: API 26+ Surface.lockHardwareCanvas -> SurfaceTexture -> GL_TEXTURE_EXTERNAL_OES (zero copy)
 * - [LEGACY]: Software bitmap capture -> texImage2D (2 CPU-GPU crossings)
 * - [AUTO]: Best available strategy for the current device
 */
enum class BlurPipelineStrategy {
    RENDER_EFFECT,
    EGL_IMAGE,
    SURFACE_TEXTURE,
    LEGACY,
    AUTO
}
