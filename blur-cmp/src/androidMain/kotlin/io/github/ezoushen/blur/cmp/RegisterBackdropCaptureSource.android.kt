package io.github.ezoushen.blur.cmp

import android.app.Activity
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import io.github.ezoushen.blur.capture.BackdropCapturePrefix
import io.github.ezoushen.blur.capture.BackdropCaptureSource
import io.github.ezoushen.blur.capture.WindowPixelCopyCoordinator
import java.util.WeakHashMap

private class RegisteredBackdropCaptureSource(
    val source: AndroidBlurOverlayCaptureSource,
    var capturePrefix: BackdropCapturePrefix,
    readiness: State<AndroidBackdropReadiness?>,
) {
    val readinessOwners = mutableListOf(readiness)

    val readiness: AndroidBackdropReadiness?
        get() = readinessOwners.asReversed().firstNotNullOfOrNull { it.value }
}

private class RegisteredBackdropCaptureLease(
    val source: RegisteredBackdropCaptureSource,
    val readiness: State<AndroidBackdropReadiness?>,
)

private class RegisteredBackdropCaptureGroup(activity: Activity) {
    val coordinator = WindowPixelCopyCoordinator()
    val activityPrefix = BackdropCapturePrefix(
        parent = null,
        source = BackdropCaptureSource(
            view = activity.window.decorView,
            window = activity.window,
            pixelCopyCoordinator = coordinator,
        ),
    )
    val sources = mutableStateListOf<RegisteredBackdropCaptureSource>()
}

private object RegisteredBackdropCaptureSources {
    private val groupsByActivity =
        WeakHashMap<Activity, RegisteredBackdropCaptureGroup>()
    private var version by mutableIntStateOf(0)

    fun coordinator(activity: Activity): WindowPixelCopyCoordinator =
        groupsByActivity.getOrPut(activity) { RegisteredBackdropCaptureGroup(activity) }.coordinator

    fun register(
        activity: Activity,
        source: AndroidBlurOverlayCaptureSource,
        readiness: State<AndroidBackdropReadiness?>,
    ): RegisteredBackdropCaptureLease {
        val group = groupsByActivity.getOrPut(activity) { RegisteredBackdropCaptureGroup(activity) }
        group.sources.firstOrNull {
            it.source.view === source.view && it.source.window === source.window
        }?.let { registered ->
            registered.readinessOwners += readiness
            version++
            return RegisteredBackdropCaptureLease(registered, readiness)
        }
        val registered = RegisteredBackdropCaptureSource(
            source = source,
            capturePrefix = BackdropCapturePrefix(
                parent = group.sources.lastOrNull()?.capturePrefix ?: group.activityPrefix,
                source = BackdropCaptureSource(
                    view = source.view,
                    window = source.window,
                    pixelCopyCoordinator = group.coordinator,
                ),
            ),
            readiness = readiness,
        )
        group.sources.add(registered)
        version++
        return RegisteredBackdropCaptureLease(registered, readiness)
    }

    fun unregister(activity: Activity, lease: RegisteredBackdropCaptureLease) {
        val group = groupsByActivity[activity] ?: return
        val source = lease.source
        val ownerIndex = source.readinessOwners.indexOfFirst { it === lease.readiness }
        if (ownerIndex < 0) return
        source.readinessOwners.removeAt(ownerIndex)
        if (source.readinessOwners.isNotEmpty()) {
            version++
            return
        }
        val removedIndex = group.sources.indexOf(source)
        if (removedIndex < 0) return
        group.sources.removeAt(removedIndex)
        rebuildPrefixesAfter(group, removedIndex)
        group.coordinator.invalidatePreparedTopology()
        if (group.sources.isEmpty()) {
            groupsByActivity.remove(activity)
            group.coordinator.close()
        }
        version++
    }

    private fun rebuildPrefixesAfter(group: RegisteredBackdropCaptureGroup, startIndex: Int) {
        for (index in startIndex until group.sources.size) {
            val registered = group.sources[index]
            registered.capturePrefix = BackdropCapturePrefix(
                parent = group.sources.getOrNull(index - 1)?.capturePrefix
                    ?: group.activityPrefix,
                source = BackdropCaptureSource(
                    view = registered.source.view,
                    window = registered.source.window,
                    pixelCopyCoordinator = group.coordinator,
                ),
            )
        }
    }

    fun below(
        activity: Activity,
        currentWindow: Window?,
    ): List<RegisteredBackdropCaptureSource> {
        version
        if (currentWindow === activity.window) return emptyList()

        val registered = groupsByActivity[activity]?.sources.orEmpty()
        val currentIndex = registered.indexOfFirst { it.source.window === currentWindow }
        val lowerSources = if (currentIndex >= 0) {
            registered.subList(0, currentIndex)
        } else {
            registered
        }

        return buildList {
            lowerSources.forEach { source ->
                if (none {
                        it.source.view === source.source.view &&
                            it.source.window === source.source.window
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
    RegisterBackdropCaptureSource(readiness = null)
}

@Composable
internal fun RegisterBackdropCaptureSource(readiness: AndroidBackdropReadiness?) {
    val view = LocalView.current
    val root = view.rootView
    val window = view.findDialogWindow() ?: return
    RegisterBackdropCaptureSource(readiness, root, window)
}

@Composable
internal fun RegisterBackdropCaptureSource(
    readiness: AndroidBackdropReadiness?,
    root: View,
    window: Window,
) {
    val activity = LocalContext.current.findActivity() ?: return
    val currentReadiness = rememberUpdatedState(readiness)

    DisposableEffect(activity, root, window) {
        val source = AndroidBlurOverlayCaptureSource(root, window)
        val registered = RegisteredBackdropCaptureSources.register(activity, source, currentReadiness)
        onDispose {
            RegisteredBackdropCaptureSources.unregister(activity, registered)
        }
    }
}

internal data class RegisteredBackdropCapture(
    val capturePrefix: BackdropCapturePrefix,
    val sources: List<BackdropCaptureSource>,
    val lowerReadiness: AndroidBackdropReadiness?,
)

internal fun registeredBackdropCapture(
    activity: Activity?,
    currentView: View,
    currentWindow: Window? = currentView.findDialogWindow(),
): RegisteredBackdropCapture? {
    activity ?: return null
    if (currentWindow == null && currentView.rootView === activity.window.decorView) return null
    val registered = RegisteredBackdropCaptureSources.below(activity, currentWindow)
    val top = registered.lastOrNull() ?: return null
    return RegisteredBackdropCapture(
        capturePrefix = top.capturePrefix,
        sources = buildList {
            val coordinator = top.capturePrefix.source.pixelCopyCoordinator
            add(
                BackdropCaptureSource(
                    view = activity.window.decorView,
                    window = activity.window,
                    pixelCopyCoordinator = coordinator,
                ),
            )
            registered.forEach {
                add(
                    BackdropCaptureSource(
                        view = it.source.view,
                        window = it.source.window,
                        pixelCopyCoordinator = coordinator,
                    ),
                )
            }
        },
        lowerReadiness = registered.lastOrNull { it.readiness != null }?.readiness,
    )
}

internal fun registeredBackdropCoordinator(activity: Activity?): WindowPixelCopyCoordinator? =
    activity?.let(RegisteredBackdropCaptureSources::coordinator)

internal fun View.findDialogWindow(): Window? =
    (rootView.parent as? DialogWindowProvider)?.window
        ?: (parent as? DialogWindowProvider)?.window
