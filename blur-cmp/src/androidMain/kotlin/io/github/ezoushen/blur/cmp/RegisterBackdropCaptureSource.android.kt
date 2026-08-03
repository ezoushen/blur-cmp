package io.github.ezoushen.blur.cmp

import android.app.Activity
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

private object RegisteredBackdropCaptureSources {
    private val sourcesByActivity =
        WeakHashMap<Activity, SnapshotStateList<AndroidBlurOverlayCaptureSource>>()
    private var version by mutableIntStateOf(0)

    fun register(activity: Activity, source: AndroidBlurOverlayCaptureSource) {
        sourcesByActivity.getOrPut(activity, ::mutableStateListOf).add(source)
        version++
    }

    fun unregister(activity: Activity, source: AndroidBlurOverlayCaptureSource) {
        sourcesByActivity[activity]?.remove(source)
        version++
    }

    fun below(
        activity: Activity,
        currentWindow: Window?,
    ): List<AndroidBlurOverlayCaptureSource> {
        version
        if (currentWindow === activity.window) return emptyList()

        val registered = sourcesByActivity[activity].orEmpty()
        val currentIndex = registered.indexOfFirst { it.window === currentWindow }
        val lowerSources = if (currentIndex >= 0) {
            registered.subList(0, currentIndex)
        } else {
            registered
        }

        return buildList {
            lowerSources.forEach { source ->
                if (none {
                        it.view === source.view && it.window === source.window
                    }
                ) {
                    add(source)
                }
            }
        }
    }
}

@Composable
actual fun RegisterBackdropCaptureSource() {
    val activity = LocalContext.current.findActivity() ?: return
    val view = LocalView.current
    val root = view.rootView
    val window = view.findDialogWindow() ?: return

    DisposableEffect(activity, root, window) {
        val source = AndroidBlurOverlayCaptureSource(root, window)
        RegisteredBackdropCaptureSources.register(activity, source)
        onDispose {
            RegisteredBackdropCaptureSources.unregister(activity, source)
        }
    }
}

internal data class RegisteredBackdropCapture(
    val sources: List<AndroidBlurOverlayCaptureSource>,
)

internal fun registeredBackdropCapture(
    activity: Activity?,
    currentView: View,
): RegisteredBackdropCapture? {
    activity ?: return null
    val currentWindow = currentView.findDialogWindow()
    if (currentWindow == null && currentView.rootView === activity.window.decorView) return null
    val registered = RegisteredBackdropCaptureSources.below(activity, currentWindow)
    return RegisteredBackdropCapture(
        sources = registered,
    )
}

internal fun View.findDialogWindow(): Window? =
    (rootView.parent as? DialogWindowProvider)?.window
        ?: (parent as? DialogWindowProvider)?.window
