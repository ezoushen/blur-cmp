package io.github.ezoushen.blur.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ezoushen.blur.cmp.BlurOverlay
import io.github.ezoushen.blur.cmp.BlurOverlayConfig
import io.github.ezoushen.blur.cmp.rememberBlurOverlayState

/**
 * Presents a dialog hosted in **separated (window) mode** — the backdrop blur is
 * driven through `LocalBlurOverlayPlatformContext(contentWindow = ...)`, exactly
 * how stforestkit's `IosDialogManager` does it: a dedicated Alert-level `UIWindow`
 * holds a ComposeVC whose `BlurOverlay` adds its backdrop to that window.
 *
 * iOS only; Android is a no-op (window mode is an iOS-specific path).
 */
expect fun presentBlurWindowDialog()

/**
 * Whether the platform supports the separated (window) mode demo. iOS hosts the
 * dialog in a dedicated Alert-level `UIWindow`; Android has no equivalent (a second
 * GL backdrop blur cannot coexist with the screen's existing one — shared EGL
 * context), so the entry point is hidden there. See [presentBlurWindowDialog].
 */
expect val supportsWindowMode: Boolean

/**
 * Dialog content shown inside the separated window. No dark scrim — the area
 * around the card is left transparent so the blur (or absence of it) behind the
 * card is directly observable.
 */
@Composable
fun WindowModeDialogContent(onClose: () -> Unit) {
    val state = rememberBlurOverlayState(initialConfig = BlurOverlayConfig(radius = 24f))
    BlurOverlay(state = state, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF222831))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    "Window-mode Dialog",
                    style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(8.dp))
                BasicText(
                    "Hosted in a separate UIWindow (Alert level) via LocalBlurOverlayPlatformContext",
                    style = TextStyle(color = Color.White, fontSize = 13.sp),
                )
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    BasicText(
                        "Close",
                        style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}
