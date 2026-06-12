package io.github.ezoushen.blur.demo

/**
 * Android present-on-top is not part of this scenario: the equivalent flow
 * launches a new Activity (top of the task stack), which already renders above
 * the overlay. No-op here; the iOS actual carries the acceptance probe.
 */
actual fun presentNativeChildScreen() {
    // no-op on Android (see KDoc)
}
