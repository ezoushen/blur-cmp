package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable

/**
 * Edge-to-edge transparent dialog host suitable for displaying a
 * [BlurOverlay] backdrop on top of an existing screen.
 *
 * On Android a `Compose Dialog` is opened with `decorFitsSystemWindows =
 * false` and `usePlatformDefaultWidth = false`; its `Window` background is
 * forced transparent and window animations are disabled so the first paint
 * of the dialog is the compose tree itself — there is no opaque
 * `windowBackground` frame between `WindowManager.addView` and the BlurView
 * coming online. The result is a backdrop blur that can extend under status
 * + navigation bars without a cold-mount black flash, even when the dialog
 * is opened from inside another overlay window (e.g. a parent
 * `DialogFragment`).
 *
 * On iOS this composable is a passthrough; the native modal presentation
 * path (`UIViewController.presentViewController`) already supplies the
 * equivalent edge-to-edge transparent surface, so wrapping the content in
 * a second window would add no value.
 *
 * Compose `Dialog` and `Popup` semantics differ — only the Dialog form
 * extends behind system bars regardless of the activity's content-insets
 * configuration, which is why this helper is built around it.
 *
 * @param onDismissRequest Called when the user attempts to dismiss the
 * dialog (back press on Android). On iOS, dismissal is owned by the
 * surrounding navigation host and this callback is ignored.
 * @param content The composable tree to draw inside the dialog. Backdrop
 * captures here will see whatever sits *behind* the dialog window on
 * screen.
 */
@Composable
expect fun BackdropBlurDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
)
