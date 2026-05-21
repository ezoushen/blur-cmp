package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable

/**
 * iOS implementation of [BackdropBlurDialog]. Passthrough — the native
 * modal presentation chain already supplies an edge-to-edge transparent
 * surface, so no additional wrapper window is needed.
 */
@Composable
actual fun BackdropBlurDialog(
    @Suppress("UNUSED_PARAMETER") onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    content()
}
