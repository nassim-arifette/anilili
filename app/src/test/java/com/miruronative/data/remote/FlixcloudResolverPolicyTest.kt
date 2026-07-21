package com.miruronative.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlixcloudResolverPolicyTest {

    @Test
    fun `capture url disables every spelling of autoplay`() {
        val result = flixcloudCaptureUrl(
            "https://flixcloud.cc/embed/episode?autoPlay=true&autoplay=1&quality=1080",
            timestampMs = 1234L,
        ).toHttpUrl()

        val autoplayNames = result.queryParameterNames.filter { it.equals("autoplay", ignoreCase = true) }
        assertEquals(listOf("autoPlay"), autoplayNames)
        assertEquals("false", result.queryParameter("autoPlay"))
        assertNull(result.queryParameter("autoplay"))
        assertEquals("1080", result.queryParameter("quality"))
    }

    @Test
    fun `capture url adds resolver defaults without replacing existing values`() {
        val result = flixcloudCaptureUrl(
            "https://flixcloud.cc/embed/episode?skI=true&kuudere_ts=99",
            timestampMs = 1234L,
        ).toHttpUrl()

        assertEquals("false", result.queryParameter("autoPlay"))
        assertEquals("true", result.queryParameter("skI"))
        assertEquals("false", result.queryParameter("skO"))
        assertEquals("99", result.queryParameter("kuudere_ts"))
    }
}
