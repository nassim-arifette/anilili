package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class WebProgressBridgeTest {

    @Test
    fun `progress carries the reported video identity and generation together`() {
        val received = mutableListOf<Pair<String, Long>>()
        val bridge = WebProgressBridge(
            expectedToken = "trusted-capability",
            onTickCallback = { _, _, _, _, _, _, mediaIdentity, mediaGeneration ->
                received += mediaIdentity to mediaGeneration
            },
            onVideoAvailableCallback = {},
            onEndedCallback = { _, _, _, _, _, _ -> },
            onCommandResultCallback = { _, _, _, _, _, _, _ -> },
        )

        bridge.onTick(
            "trusted-capability",
            "7",
            80.0,
            100.0,
            true,
            false,
            1.0,
            "https://cdn.example/video.m3u8|100000",
            2,
        )

        assertEquals(listOf("https://cdn.example/video.m3u8|100000" to 2L), received)
    }

    @Test
    fun `callbacks require the top frame capability`() {
        var ticks = 0
        var videos = 0
        val bridge = WebProgressBridge(
            expectedToken = "trusted-capability",
            onTickCallback = { _, _, _, _, _ -> ticks++ },
            onVideoAvailableCallback = { videos++ },
        )

        bridge.onTick("forged", 80.0, 100.0, true, false, 1.0)
        bridge.onVideoAvailable("forged")
        bridge.onTick(null, 80.0, 100.0, true, false, 1.0)
        bridge.onVideoAvailable(null)
        assertEquals(0, ticks)
        assertEquals(0, videos)

        bridge.onTick("trusted-capability", 80.0, 100.0, true, false, 1.0)
        bridge.onVideoAvailable("trusted-capability")
        assertEquals(1, ticks)
        assertEquals(1, videos)
    }

    @Test
    fun `command acknowledgements require the bridge capability`() {
        val acknowledged = mutableListOf<String>()
        val bridge = WebProgressBridge(
            expectedToken = "trusted-capability",
            onTickCallback = { _, _, _, _, _, _, _, _ -> },
            onVideoAvailableCallback = {},
            onEndedCallback = { _, _, _, _, _, _ -> },
            onCommandResultCallback = { _, commandId, _, _, _, _, _ -> acknowledged += commandId },
        )

        bridge.onCommandResult("forged", "7", "11", true, 42.0, true, "episode", 1)
        bridge.onCommandResult("trusted-capability", "7", "12", true, 42.0, true, "episode", 1)

        assertEquals(listOf("12"), acknowledged)
    }
}
