package io.github.ezoushen.blur.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.IntDef

/**
 * Utility for detecting device performance characteristics.
 *
 * Used to select appropriate blur algorithms and parameters
 * based on the device's capabilities.
 */
object DeviceCapability {

    @IntDef(TIER_LOW, TIER_MID, TIER_HIGH)
    @Retention(AnnotationRetention.SOURCE)
    annotation class PerformanceTier

    const val TIER_LOW = 0
    const val TIER_MID = 1
    const val TIER_HIGH = 2

    private var cachedTier: Int? = null
    private var cachedIsLowRam: Boolean? = null

    /**
     * Detects the device's performance tier.
     *
     * @param context Application context
     * @return Performance tier (TIER_LOW, TIER_MID, or TIER_HIGH)
     */
    @PerformanceTier
    fun getPerformanceTier(context: Context): Int {
        cachedTier?.let { return it }

        val tier = calculatePerformanceTier(context)
        cachedTier = tier
        return tier
    }

    /**
     * Checks if the device is a low-RAM device.
     *
     * @param context Application context
     * @return true if this is a low-RAM device
     */
    fun isLowRamDevice(context: Context): Boolean {
        cachedIsLowRam?.let { return it }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = activityManager?.isLowRamDevice ?: false
        cachedIsLowRam = isLowRam
        return isLowRam
    }

    /**
     * Returns recommended downsample factor based on device tier.
     *
     * Higher factor = less work but lower quality.
     */
    fun getRecommendedDownsampleFactor(context: Context): Float {
        return when (getPerformanceTier(context)) {
            TIER_HIGH -> 4f
            TIER_MID -> 6f
            TIER_LOW -> 8f
            else -> 4f
        }
    }

    /**
     * Returns recommended max blur radius based on device tier.
     */
    fun getRecommendedMaxRadius(context: Context): Float {
        return when (getPerformanceTier(context)) {
            TIER_HIGH -> 25f
            TIER_MID -> 20f
            TIER_LOW -> 15f
            else -> 25f
        }
    }

    /**
     * Checks if RenderEffect API is available (API 31+).
     */
    fun supportsRenderEffect(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /**
     * Checks if AGSL/RuntimeShader is available (API 33+).
     */
    fun supportsAGSL(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Checks if HardwareBuffer is available (API 26+).
     */
    fun supportsHardwareBuffer(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    /**
     * Checks if RenderScript is available.
     * RenderScript was deprecated in API 31, but can still be used
     * with degraded performance (no hardware acceleration).
     * Always returns true since minSdk is 23.
     */
    fun supportsRenderScript(): Boolean = true

    /**
     * Returns the number of available CPU cores.
     */
    fun getAvailableCores(): Int {
        return Runtime.getRuntime().availableProcessors()
    }

    private fun calculatePerformanceTier(context: Context): Int {
        // Check if explicitly marked as low-RAM device
        if (isLowRamDevice(context)) {
            return TIER_LOW
        }

        // Use API level and core count as heuristics
        val coreCount = getAvailableCores()
        val apiLevel = Build.VERSION.SDK_INT

        return when {
            // Modern device with many cores
            apiLevel >= Build.VERSION_CODES.S && coreCount >= 6 -> TIER_HIGH
            // Mid-range device
            apiLevel >= Build.VERSION_CODES.N && coreCount >= 4 -> TIER_MID
            // Older or lower-end device
            else -> TIER_LOW
        }
    }

    /**
     * Returns a human-readable description of the device's blur capabilities.
     *
     * Useful for debugging and logging.
     */
    fun getCapabilityDescription(context: Context): String {
        return buildString {
            appendLine("Device Capability Report:")
            appendLine("  Performance Tier: ${getTierName(getPerformanceTier(context))}")
            appendLine("  Low RAM Device: ${isLowRamDevice(context)}")
            appendLine("  CPU Cores: ${getAvailableCores()}")
            appendLine("  API Level: ${Build.VERSION.SDK_INT}")
            appendLine("  Supports RenderEffect: ${supportsRenderEffect()}")
            appendLine("  Supports AGSL: ${supportsAGSL()}")
            appendLine("  Supports HardwareBuffer: ${supportsHardwareBuffer()}")
            appendLine("  Recommended Downsample: ${getRecommendedDownsampleFactor(context)}")
            appendLine("  Recommended Max Radius: ${getRecommendedMaxRadius(context)}")
        }
    }

    private fun getTierName(tier: Int): String {
        return when (tier) {
            TIER_HIGH -> "HIGH"
            TIER_MID -> "MID"
            TIER_LOW -> "LOW"
            else -> "UNKNOWN"
        }
    }

    /**
     * Clears cached values. Call this if device state might have changed.
     */
    fun clearCache() {
        cachedTier = null
        cachedIsLowRam = null
    }
}
