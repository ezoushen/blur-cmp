package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun BlurOverlayHost(
    state: BlurOverlayState,
    modifier: Modifier,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
) = BlurOverlayHostCommon(state, modifier, background, content)
