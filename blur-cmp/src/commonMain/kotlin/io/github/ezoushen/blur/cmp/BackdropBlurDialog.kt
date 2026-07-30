package io.github.ezoushen.blur.cmp

import androidx.compose.runtime.Composable

/**
 * Edge-to-edge transparent dialog host suitable for displaying a
 * [BlurOverlay] backdrop on top of an existing screen.
 *
 * On Android every host opens an edge-to-edge transparent Compose `Dialog`.
 * Each upper host composites every lower window from the Activity upward,
 * including each lower layer's blur output and sharp content. This preserves
 * Android window composition order while supporting an arbitrary number of
 * layers and partial overlay bounds.
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
 * @param content The composable tree to draw inside the dialog or current
 * overlay layer.
 */
@Composable
expect fun BackdropBlurDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
)
