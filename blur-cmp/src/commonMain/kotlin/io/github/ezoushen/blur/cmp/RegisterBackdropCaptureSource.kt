package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable

/**
 * Makes a source-only custom dialog window available to blur overlays presented above it.
 *
 * [BlurOverlay] and [BackdropBlurDialog] register their own windows automatically. Call this
 * only from a separate platform window that does not contain its own [BlurOverlay].
 */
@Composable
expect fun RegisterBackdropCaptureSource()
