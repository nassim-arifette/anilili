package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class WebProgressBridgeTest {

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
}
