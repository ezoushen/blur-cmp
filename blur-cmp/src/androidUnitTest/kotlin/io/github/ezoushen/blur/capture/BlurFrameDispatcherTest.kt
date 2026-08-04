package io.github.ezoushen.blur.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class BlurFrameDispatcherTest {

    @Test
    fun multipleClientsShareOneFrameCallback() {
        var frameRequests = 0
        val dispatcher = BlurFrameDispatcher { frameRequests++ }
        var firstFrames = 0
        var secondFrames = 0
        val first = { firstFrames++; true }
        val second = { secondFrames++; true }

        dispatcher.register(first)
        dispatcher.register(second)

        assertEquals(1, frameRequests)
        dispatcher.dispatchFrame()
        assertEquals(1, firstFrames)
        assertEquals(1, secondFrames)
        assertEquals(2, frameRequests)
    }

    @Test
    fun registrationIsIdempotentAndClientsStopIndependently() {
        var frameRequests = 0
        val dispatcher = BlurFrameDispatcher { frameRequests++ }
        var continuingFrames = 0
        var stoppingFrames = 0
        val continuing = { continuingFrames++; true }
        val stopping = { stoppingFrames++; false }

        dispatcher.register(continuing)
        dispatcher.register(continuing)
        dispatcher.register(stopping)
        dispatcher.dispatchFrame()
        dispatcher.dispatchFrame()

        assertEquals(2, continuingFrames)
        assertEquals(1, stoppingFrames)
        assertEquals(3, frameRequests)

        dispatcher.unregister(continuing)
        dispatcher.dispatchFrame()
        assertEquals(3, frameRequests)
    }

    @Test
    fun epochReadsCoalesceWithLiveUpdates() {
        var frameRequests = 0
        val dispatcher = BlurFrameDispatcher { frameRequests++ }
        var liveFrames = 0
        val live = { liveFrames++; true }

        dispatcher.register(live)
        assertEquals(0L, dispatcher.currentEpoch())
        assertEquals(0L, dispatcher.currentEpoch())
        assertEquals(1, frameRequests)

        dispatcher.dispatchFrame()

        assertEquals(1L, dispatcher.currentEpoch())
        assertEquals(1, liveFrames)
        assertEquals(2, frameRequests)
    }

    @Test
    fun selfUnregisterDoesNotSkipOrRemoveTheNextClient() {
        val dispatcher = BlurFrameDispatcher {}
        val calls = mutableListOf<String>()
        lateinit var unregisteringTrue: () -> Boolean
        lateinit var unregisteringFalse: () -> Boolean
        unregisteringTrue = {
            calls += "true"
            dispatcher.unregister(unregisteringTrue)
            true
        }
        unregisteringFalse = {
            calls += "false"
            dispatcher.unregister(unregisteringFalse)
            false
        }
        val continuing = {
            calls += "continuing"
            true
        }

        dispatcher.register(unregisteringTrue)
        dispatcher.register(unregisteringFalse)
        dispatcher.register(continuing)
        dispatcher.dispatchFrame()
        dispatcher.dispatchFrame()

        assertEquals(
            listOf("true", "false", "continuing", "continuing"),
            calls,
        )
    }
}
