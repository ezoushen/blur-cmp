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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.doOnPreDraw
import io.github.ezoushen.blur.capture.BackdropCaptureSource
import java.util.WeakHashMap

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

internal data class AndroidBackdropStack(
    val captureSources: List<BackdropCaptureSource>,
    val currentRoot: View,
    val currentWindow: Window?,
    val depth: Int,
    val lowerLayers: List<AndroidBackdropLayer>,
    val currentLayer: AndroidBackdropLayer,
)

internal val LocalAndroidBackdropStack =
    staticCompositionLocalOf<AndroidBackdropStack?> { null }

private class BackdropWindowEntry(
    val layer: AndroidBackdropLayer,
) {
    var root: View? by mutableStateOf(null)
    var window: Window? by mutableStateOf(null)
}

private object BackdropWindowRegistry {
    private val entriesByBaseRoot =
        WeakHashMap<View, MutableList<BackdropWindowEntry>>()

    fun register(baseRoot: View, entry: BackdropWindowEntry) {
        entriesByBaseRoot
            .getOrPut(baseRoot) { mutableStateListOf() }
            .add(entry)
    }

    fun unregister(baseRoot: View, entry: BackdropWindowEntry) {
        entriesByBaseRoot[baseRoot]?.let { entries ->
            entries.remove(entry)
            if (entries.isEmpty()) entriesByBaseRoot.remove(baseRoot)
        }
    }

    fun previous(
        baseRoot: View,
        entry: BackdropWindowEntry,
    ): List<BackdropWindowEntry> {
        val entries = entriesByBaseRoot[baseRoot] ?: return emptyList()
        val index = entries.indexOf(entry)
        val preceding = if (index < 0) entries else entries.subList(0, index)
        return preceding.filter { it.root != null }
    }
}

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
    val registryEntry = remember { BackdropWindowEntry(layer) }
    if (parentStack == null) {
        DisposableEffect(lowerRoot, registryEntry) {
            BackdropWindowRegistry.register(lowerRoot, registryEntry)
            onDispose {
                BackdropWindowRegistry.unregister(lowerRoot, registryEntry)
            }
        }
    }

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
        val previousEntries = if (parentStack == null) {
            BackdropWindowRegistry.previous(lowerRoot, registryEntry)
        } else {
            emptyList()
        }
        val currentWindow = (view.parent as? DialogWindowProvider)?.window
        val captureSources = if (parentStack != null) {
            parentStack.captureSources + BackdropCaptureSource(
                parentStack.currentRoot,
                parentStack.currentWindow,
            )
        } else {
            listOf(BackdropCaptureSource(lowerRoot, activityWindow)) +
                previousEntries.mapNotNull { entry ->
                    entry.root?.let { BackdropCaptureSource(it, entry.window) }
                }
        }
        val stack = AndroidBackdropStack(
            captureSources = captureSources,
            currentRoot = view.rootView,
            currentWindow = currentWindow,
            depth = captureSources.size - 1,
            lowerLayers = parentStack?.let {
                it.lowerLayers + it.currentLayer
            } ?: previousEntries.map { it.layer },
            currentLayer = layer,
        )
        SideEffect {
            registryEntry.root = view.rootView
            registryEntry.window = currentWindow
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
