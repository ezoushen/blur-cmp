package io.github.ezoushen.blur.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.lang.ref.WeakReference

/**
 * Holds the currently-resumed demo [Activity] so [presentNativeChildScreen] can
 * launch a child Activity on top of the current task (the Android analogue of
 * iOS `presentViewController`). Set from [MainActivity].
 */
internal var demoActivityRef: WeakReference<Activity>? = null

/**
 * Launches [NativeChildActivity] on top of the current Activity — the Android
 * equivalent of the iOS present-on-top probe. A normal (opaque, top-of-stack)
 * Activity already renders above the blur overlay, so the expected result
 * matches iOS: the child fully covers the overlay.
 */
actual fun presentNativeChildScreen() {
    val activity = demoActivityRef?.get() ?: return
    activity.startActivity(Intent(activity, NativeChildActivity::class.java))
}

class NativeChildActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NativeChildScreen(onClose = { finish() })
        }
    }
}
