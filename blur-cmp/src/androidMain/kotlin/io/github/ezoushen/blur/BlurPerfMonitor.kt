package io.github.ezoushen.blur

import io.github.ezoushen.blur.cmp.BuildConfig

/**
 * Global performance monitor for blur pipeline timing.
 *
 * All writes are gated behind [BuildConfig.BLUR_PERF_ENABLED].
 * When disabled (default), all methods are no-ops with zero overhead
 * since the JIT eliminates the dead branches.
 *
 * Enable for profiling: `./gradlew assembleDebug -Pblur.perf.enabled=true`
 */
object BlurPerfMonitor {
    @JvmStatic val enabled: Boolean = BuildConfig.BLUR_PERF_ENABLED

    @Volatile var lastCaptureUs: Long = 0
    @Volatile var lastBlurUs: Long = 0
    @Volatile var lastTotalUs: Long = 0
    @Volatile var lastStrategy: String = ""
    @Volatile var lastDimension: String = ""
    @Volatile var frameCount: Long = 0

    @Volatile var lastUploadUs: Long = 0
    @Volatile var lastPyramidUs: Long = 0
    @Volatile var lastCompositeUs: Long = 0
    @Volatile var lastSwapUs: Long = 0

    inline fun report(captureUs: Long, blurUs: Long, totalUs: Long, strategy: String, dim: String) {
        if (!enabled) return
        lastCaptureUs = captureUs
        lastBlurUs = blurUs
        lastTotalUs = totalUs
        lastStrategy = strategy
        lastDimension = dim
        frameCount++
    }

    inline fun reportBlur(uploadUs: Long, pyramidUs: Long, compositeUs: Long, swapUs: Long) {
        if (!enabled) return
        lastUploadUs = uploadUs
        lastPyramidUs = pyramidUs
        lastCompositeUs = compositeUs
        lastSwapUs = swapUs
    }
}
