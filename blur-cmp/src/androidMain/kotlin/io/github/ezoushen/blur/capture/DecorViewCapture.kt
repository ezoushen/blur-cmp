package io.github.ezoushen.blur.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View

/**
 * Captures view content by drawing the DecorView to a scaled bitmap.
 */
class DecorViewCapture : ContentCapture {

    private val sourceLocation = IntArray(2)
    private val blurViewLocation = IntArray(2)

    @Volatile
    private var isCapturing = false

    private val excludedViews = mutableListOf<View>()

    fun isCurrentlyCapturing(): Boolean = isCapturing

    fun addExcludedView(view: View) {
        if (view !in excludedViews) {
            excludedViews.add(view)
        }
    }

    fun removeExcludedView(view: View) {
        excludedViews.remove(view)
    }

    override fun capture(
        blurView: View,
        sourceView: View,
        output: Bitmap,
        downsampleFactor: Float
    ): Boolean {
        if (blurView.width == 0 || blurView.height == 0) {
            return false
        }

        val hiddenViews = mutableListOf<View>()

        try {
            isCapturing = true

            for (view in excludedViews) {
                if (view.visibility == View.VISIBLE) {
                    view.visibility = View.INVISIBLE
                    hiddenViews.add(view)
                }
            }

            sourceView.getLocationOnScreen(sourceLocation)
            blurView.getLocationOnScreen(blurViewLocation)

            val offsetX = blurViewLocation[0] - sourceLocation[0]
            val offsetY = blurViewLocation[1] - sourceLocation[1]

            val canvas = Canvas(output)
            val scaleX = output.width.toFloat() / blurView.width
            val scaleY = output.height.toFloat() / blurView.height

            val saveCount = canvas.save()

            try {
                canvas.scale(scaleX, scaleY)
                canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
                sourceView.background?.draw(canvas)
                sourceView.draw(canvas)
            } finally {
                canvas.restoreToCount(saveCount)
            }

            return true
        } catch (e: StopCaptureException) {
            return true
        } catch (e: Exception) {
            return false
        } finally {
            for (view in hiddenViews) {
                view.visibility = View.VISIBLE
            }
            isCapturing = false
        }
    }

    override fun release() {}

    override fun isAvailable(): Boolean = true

    class StopCaptureException : RuntimeException()

    companion object {
        val STOP_EXCEPTION = StopCaptureException()
    }
}
