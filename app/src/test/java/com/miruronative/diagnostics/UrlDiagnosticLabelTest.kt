package com.miruronative.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlDiagnosticLabelTest {
    @Test
    fun `signed playback url exposes only normalized host and stable fingerprint`() {
        val signedUrl = "https://CDN.Example/video.m3u8?token=secret#private-fragment"

        val label = privacySafeUrlDiagnosticLabel(signedUrl)

        assertTrue(label.contains("host=cdn.example"))
        assertTrue(label.contains("fingerprint=sha256:"))
        assertFalse(label.contains("/video"))
        assertFalse(label.contains("m3u8"))
        assertFalse(label.contains("token"))
        assertFalse(label.contains("secret"))
        assertFalse(label.contains("private-fragment"))
        assertEquals(label, privacySafeUrlDiagnosticLabel(signedUrl))
        assertNotEquals(
            label,
            privacySafeUrlDiagnosticLabel("https://cdn.example/video.m3u8?token=other"),
        )
    }

    @Test
    fun `missing and malformed urls never echo their input`() {
        val malformed = "not a URL / signed-path?token=secret"

        assertEquals("host=none fingerprint=none", privacySafeUrlDiagnosticLabel(null))
        val malformedLabel = privacySafeUrlDiagnosticLabel(malformed)
        assertTrue(malformedLabel.contains("host=unknown"))
        assertTrue(malformedLabel.contains("fingerprint=sha256:"))
        assertFalse(malformedLabel.contains("signed-path"))
        assertFalse(malformedLabel.contains("secret"))
    }
}
