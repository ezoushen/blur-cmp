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

        val dimmedViews = mutableListOf<Pair<View, Float>>()

        try {
            isCapturing = true

            for (view in excludedViews) {
                // Exclude via alpha, NOT visibility. Setting an excluded view INVISIBLE clears its
                // focus and tears down the IME input connection on every capture frame, so a focused
                // TextField inside the excluded content can never hold focus or receive keystrokes.
                // alpha=0 keeps it out of the captured bitmap while leaving focus untouched.
                if (view.alpha > 0f) {
                    dimmedViews.add(view to view.alpha)
                    view.alpha = 0f
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
            for ((view, originalAlpha) in dimmedViews) {
                view.alpha = originalAlpha
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
