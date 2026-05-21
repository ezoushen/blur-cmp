package io.github.ezoushen.blur.cmp

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Android implementation of [BackdropBlurDialog]. Hosts the [content] in a
 * Compose `Dialog` so a [BlurOverlay] mounted inside captures a true
 * edge-to-edge backdrop:
 *
 * - `decorFitsSystemWindows = false` and `usePlatformDefaultWidth = false`
 *   make the dialog window draw across the status and navigation bar
 *   insets, so the overlay's backdrop blur reaches both bars.
 * - The window background is forced transparent and window animations are
 *   suppressed, so the cold-mount path doesn't show a single black frame
 *   from the framework's compositor handover.
 */
@Composable
actual fun BackdropBlurDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler(onBack = onDismissRequest)
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setWindowAnimations(0)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.decorView.requestApplyInsets()
        }
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
