package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable

/**
 * Makes the current custom dialog window available to blur overlays presented above it.
 *
 * [BackdropBlurDialog] registers its own windows automatically. Call this only from a custom
 * dialog or sheet implementation that creates a separate platform window.
 */
@Composable
expect fun RegisterBackdropCaptureSource()
