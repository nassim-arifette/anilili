package com.miruronative.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlixcloudBridgeSecurityTest {

    @Test
    fun `accepts HLS provenance from the trusted player origin`() {
        assertTrue(isTrustedFlixcloudRequest(mapOf("Origin" to "https://flixcloud.cc")))
        assertTrue(
            isTrustedFlixcloudRequest(
                mapOf("origin" to "https://embed.flixcloud.cc"),
            ),
        )
    }

    @Test
    fun `rejects referer inherited by a third party iframe`() {
        assertFalse(
            isTrustedFlixcloudRequest(
                mapOf("Referer" to "https://flixcloud.cc/player/episode"),
            ),
        )
        assertFalse(
            isTrustedFlixcloudRequest(
                mapOf(
                    "Origin" to "https://attacker.example",
                    "Referer" to "https://flixcloud.cc/player/episode",
                ),
            ),
        )
    }

    @Test
    fun `rejects missing malformed ambiguous and third party origins`() {
        assertFalse(isTrustedFlixcloudRequest(emptyMap()))
        assertFalse(isTrustedFlixcloudRequest(mapOf("Origin" to "not a URL")))
        assertFalse(isTrustedFlixcloudRequest(mapOf("Origin" to "https://flixcloud.cc.attacker.example")))
        assertFalse(isTrustedFlixcloudRequest(mapOf("Origin" to "https://flixcloud.cc:8443")))
        assertFalse(
            isTrustedFlixcloudRequest(
                mapOf(
                    "Origin" to "https://flixcloud.cc",
                    "origin" to "https://attacker.example",
                ),
            ),
        )
    }
}
