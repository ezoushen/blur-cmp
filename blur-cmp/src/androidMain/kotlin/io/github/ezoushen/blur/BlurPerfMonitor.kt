package io.github.ezoushen.blur

/**
 * Global performance monitor for blur timing data.
 * Controllers write timing, UI reads it for on-screen overlay.
 * Only used for debugging/profiling — no production impact when not read.
 */
object BlurPerfMonitor {
    @Volatile var lastCaptureUs: Long = 0
    @Volatile var lastBlurUs: Long = 0
    @Volatile var lastTotalUs: Long = 0
    @Volatile var lastStrategy: String = ""
    @Volatile var lastDimension: String = ""
    @Volatile var frameCount: Long = 0

    fun report(captureUs: Long, blurUs: Long, totalUs: Long, strategy: String, dim: String) {
        lastCaptureUs = captureUs
        lastBlurUs = blurUs
        lastTotalUs = totalUs
        lastStrategy = strategy
        lastDimension = dim
        frameCount++
    }
}
