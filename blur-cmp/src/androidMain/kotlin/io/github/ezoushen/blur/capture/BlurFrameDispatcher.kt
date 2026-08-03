package io.github.ezoushen.blur.capture

import android.view.Choreographer

internal class BlurFrameDispatcher(
    private val requestFrame: () -> Unit,
) {
    private val clients = mutableListOf<() -> Boolean>()
    private var frameRequested = false
    private var epoch = 0L

    fun register(client: () -> Boolean) {
        if (clients.any { it === client }) return
        clients += client
        scheduleFrame()
    }

    fun unregister(client: () -> Boolean) {
        val index = clients.indexOfFirst { it === client }
        if (index >= 0) clients.removeAt(index)
    }

    fun currentEpoch(): Long {
        scheduleFrame()
        return epoch
    }

    internal fun dispatchFrame() {
        frameRequested = false
        epoch++
        var index = 0
        while (index < clients.size) {
            val client = clients[index]
            val keepRegistered = client()
            if (clients.getOrNull(index) !== client) {
                continue
            }
            if (keepRegistered) {
                index++
            } else {
                clients.removeAt(index)
            }
        }
        if (clients.isNotEmpty()) scheduleFrame()
    }

    private fun scheduleFrame() {
        if (frameRequested) return
        frameRequested = true
        requestFrame()
    }
}

internal object AndroidBlurFrameDispatcher {
    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback {
        dispatcher.dispatchFrame()
    }
    private val dispatcher: BlurFrameDispatcher = BlurFrameDispatcher {
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun register(client: () -> Boolean) = dispatcher.register(client)

    fun unregister(client: () -> Boolean) = dispatcher.unregister(client)

    fun currentEpoch(): Long = dispatcher.currentEpoch()
}
