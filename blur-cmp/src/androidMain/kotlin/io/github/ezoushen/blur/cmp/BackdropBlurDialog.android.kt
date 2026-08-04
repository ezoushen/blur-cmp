package io.github.ezoushen.blur.cmp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.doOnPreDraw
import io.github.ezoushen.blur.capture.BackdropCapturePrefix
import io.github.ezoushen.blur.capture.BackdropCaptureSource
import io.github.ezoushen.blur.capture.WindowPixelCopyCoordinator

internal class AndroidBackdropLayer {
    var captureView: View? by mutableStateOf(null)
    private var claimedByBlurHost by mutableStateOf(false)
    private var frameReady by mutableStateOf(false)
    var contentReady by mutableStateOf(false)

    var isReady: Boolean
        get() = contentReady && (!claimedByBlurHost || frameReady)
        set(value) {
            frameReady = value
        }

    fun claim() {
        claimedByBlurHost = true
    }

    fun release() {
        claimedByBlurHost = false
        frameReady = false
    }
}

internal class AndroidBackdropReadiness(
    private val parent: AndroidBackdropReadiness?,
    private val layer: AndroidBackdropLayer,
) {
    private val ready = derivedStateOf(structuralEqualityPolicy()) {
        (parent?.isReady ?: true) && layer.isReady
    }

    val isReady: Boolean
        get() = ready.value
}

internal data class AndroidBackdropStack(
    val capturePrefix: BackdropCapturePrefix,
    val pixelCopyCoordinator: WindowPixelCopyCoordinator?,
    val currentRoot: View,
    val currentWindow: Window?,
    val depth: Int,
    val lowerReadiness: AndroidBackdropReadiness?,
    val readiness: AndroidBackdropReadiness,
    val currentLayer: AndroidBackdropLayer,
)

internal val LocalAndroidBackdropStack =
    staticCompositionLocalOf<AndroidBackdropStack?> { null }

/**
 * Android implementation of [BackdropBlurDialog].
 *
 * Each logical backdrop layer owns a physical Dialog window. Its blur captures
 * all lower windows from the Activity upward, while Android window ordering keeps
 * every lower TextureView and SurfaceView below the complete upper overlay.
 */
@Composable
actual fun BackdropBlurDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val parentStack = LocalAndroidBackdropStack.current
    val lowerRoot = LocalView.current.rootView
    val activityWindow = LocalContext.current.findActivity()?.window
    val layer = remember { AndroidBackdropLayer() }

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
        DisposableEffect(view.rootView, layer) {
            layer.contentReady = false
            val firstDraw = view.rootView.doOnPreDraw {
                layer.contentReady = true
            }
            onDispose {
                firstDraw.removeListener()
                layer.contentReady = false
            }
        }
        val currentWindow = view.findDialogWindow()
        val registeredCapture = registeredBackdropCapture(
            activity = LocalContext.current.findActivity(),
            currentView = view,
        )
        val pixelCopyCoordinator = parentStack?.pixelCopyCoordinator
            ?: registeredBackdropCoordinator(LocalContext.current.findActivity())
        val activityPrefix = remember(lowerRoot, activityWindow, pixelCopyCoordinator) {
            BackdropCapturePrefix(
                parent = null,
                source = BackdropCaptureSource(
                    view = lowerRoot,
                    window = activityWindow,
                    pixelCopyCoordinator = pixelCopyCoordinator,
                ),
            )
        }
        val capturePrefix = if (parentStack != null) {
            remember(
                parentStack.capturePrefix,
                parentStack.currentRoot,
                parentStack.currentWindow,
                pixelCopyCoordinator,
            ) {
                BackdropCapturePrefix(
                    parent = parentStack.capturePrefix,
                    source = BackdropCaptureSource(
                        view = parentStack.currentRoot,
                        window = parentStack.currentWindow,
                        pixelCopyCoordinator = pixelCopyCoordinator,
                    ),
                )
            }
        } else registeredCapture?.capturePrefix ?: activityPrefix
        DisposableEffect(pixelCopyCoordinator, capturePrefix) {
            onDispose {
                pixelCopyCoordinator?.invalidatePreparedTopology()
            }
        }
        val lowerReadiness = parentStack?.readiness ?: registeredCapture?.lowerReadiness
        val readiness = remember(lowerReadiness, layer) {
            AndroidBackdropReadiness(lowerReadiness, layer)
        }
        val stack = AndroidBackdropStack(
            capturePrefix = capturePrefix,
            pixelCopyCoordinator = pixelCopyCoordinator,
            currentRoot = view.rootView,
            currentWindow = currentWindow,
            depth = capturePrefix.size - 1,
            lowerReadiness = lowerReadiness,
            readiness = readiness,
            currentLayer = layer,
        )
        RegisterBackdropCaptureSource(readiness)
        SideEffect {
            val window = currentWindow ?: return@SideEffect
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setWindowAnimations(0)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.decorView.requestApplyInsets()
        }
        CompositionLocalProvider(LocalAndroidBackdropStack provides stack) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
