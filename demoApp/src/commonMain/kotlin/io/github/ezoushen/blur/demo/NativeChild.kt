package io.github.ezoushen.blur.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
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

/**
 * Presents an opaque, full-screen native child screen from the host view
 * controller (iOS: `presentViewController`; Android: a new Activity / no-op).
 *
 * Acceptance probe for present-on-top: the child is fully opaque, so if it
 * renders *under* the blur overlay (the old hybrid bug) the overlay content
 * shows on top of it; if it renders *on top* (integrated, fixed) it covers
 * the overlay entirely.
 */
expect fun presentNativeChildScreen()

/** Shared content of the presented child screen. */
@Composable
fun NativeChildScreen(onClose: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                "NATIVE CHILD SCREEN",
                style = TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                "Presented via presentViewController from the host VC",
                style = TextStyle(color = Color.White, fontSize = 14.sp),
            )
            Spacer(Modifier.height(24.dp))
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
