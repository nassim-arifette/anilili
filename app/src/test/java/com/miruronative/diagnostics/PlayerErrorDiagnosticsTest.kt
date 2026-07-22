package com.miruronative.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerErrorDiagnosticsTest {
    @Test
    fun `media3 codes map to controlled diagnostic categories`() {
        assertEquals("timeout", playerErrorDiagnosticCategory(1003))
        assertEquals("player", playerErrorDiagnosticCategory(1000))
        assertEquals("io", playerErrorDiagnosticCategory(2001))
        assertEquals("parsing", playerErrorDiagnosticCategory(3002))
        assertEquals("decoder", playerErrorDiagnosticCategory(4003))
        assertEquals("audio-output", playerErrorDiagnosticCategory(5001))
        assertEquals("drm", playerErrorDiagnosticCategory(6004))
        assertEquals("video-processing", playerErrorDiagnosticCategory(7001))
        assertEquals("unknown", playerErrorDiagnosticCategory(-1))
    }
}
