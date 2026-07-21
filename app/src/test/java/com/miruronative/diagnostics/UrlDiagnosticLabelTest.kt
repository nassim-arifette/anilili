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
        assertTrue(
            Regex("host=cdn\\.example fingerprint=sha256:[0-9a-f]{64}").matches(label),
        )
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

    @Test
    fun `userinfo unicode and ipv6 hosts are normalized without leaking credentials`() {
        val credentialed = privacySafeUrlDiagnosticLabel(
            "https://alice:password@CDN.Example:443/private?token=secret",
        )
        val unicode = privacySafeUrlDiagnosticLabel("https://m\u00FCnich.example/private")
        val ipv6 = privacySafeUrlDiagnosticLabel("https://[2001:DB8::1]/private")

        assertTrue(credentialed.startsWith("host=cdn.example fingerprint=sha256:"))
        assertFalse(credentialed.contains("alice"))
        assertFalse(credentialed.contains("password"))
        assertFalse(credentialed.contains("private"))
        assertFalse(credentialed.contains("secret"))
        assertTrue(unicode.startsWith("host=xn--mnich-kva.example fingerprint=sha256:"))
        assertFalse(unicode.contains("m\u00FCnich"))
        assertTrue(ipv6.startsWith("host=2001:db8::1 fingerprint=sha256:"))
    }

    @Test
    fun `control characters and oversized hosts cannot enter the diagnostic label`() {
        val injected = privacySafeUrlDiagnosticLabel(
            "https://cdn.example/video\r\nInjected: token=secret",
        )
        val oversizedHost = "a".repeat(260) + ".example"
        val oversized = privacySafeUrlDiagnosticLabel("https://$oversizedHost/private")

        assertTrue(injected.startsWith("host=unknown fingerprint=sha256:"))
        assertFalse(injected.contains('\r'))
        assertFalse(injected.contains('\n'))
        assertFalse(injected.contains("Injected"))
        assertFalse(injected.contains("secret"))
        assertTrue(oversized.startsWith("host=unknown fingerprint=sha256:"))
        assertFalse(oversized.contains(oversizedHost))
    }
}
