package io.github.ezoushen.blur.view

import android.view.SurfaceView
import io.github.ezoushen.blur.capture.SurfaceCapture

/**
 * Supplies the SurfaceFlinger sublayer used when this SurfaceView is replayed
 * into a blur capture on Android 24–35.
 *
 * Android 36 exposes this value through `SurfaceView.getCompositionOrder()`.
 * Earlier releases do not, so overlapping SurfaceViews whose compositor order
 * differs from their View hierarchy order must register the same order they
 * pass to their SurfaceControl transaction (normally -2 for a regular surface,
 * -1 for a media overlay, or 1 for an on-top surface).
 */
fun SurfaceView.registerBlurCaptureCompositionOrder(order: Int) {
    SurfaceCapture.registerCompositionOrder(this, order)
}
