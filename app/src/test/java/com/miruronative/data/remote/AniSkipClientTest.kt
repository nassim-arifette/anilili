package com.miruronative.data.remote

import com.miruronative.data.model.AniSkipType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AniSkipClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `v2 URL requests every type with decimal episode and real duration`() {
        val url = aniSkipTimesUrl(
            malId = 11_021,
            episode = 12.5,
            episodeLengthSeconds = 1_439.257,
        )

        assertEquals(listOf("v2", "skip-times", "11021", "12.5"), url.pathSegments)
        assertEquals(
            listOf("op", "ed", "mixed-op", "mixed-ed", "recap"),
            url.queryParameterValues("types[]"),
        )
        assertEquals("1439.257", url.queryParameter("episodeLength"))
    }

    @Test
    fun `v2 URL refuses duration-less lookups`() {
        try {
            aniSkipTimesUrl(malId = 1, episode = 1.0, episodeLengthSeconds = 0.0)
            fail("A zero duration must not reach AniSkip")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `parser preserves every typed segment field`() {
        val payload = """
            {
              "found": true,
              "results": [
                {"interval":{"startTime":1.25,"endTime":91.5},"skipType":"op","skipId":"op-id","episodeLength":1439.257},
                {"interval":{"startTime":1300.0,"endTime":1390.0},"skipType":"ed","skipId":"ed-id","episodeLength":1439.257},
                {"interval":{"startTime":2.0,"endTime":80.0},"skipType":"mixed-op","skipId":"mop-id","episodeLength":1439.257},
                {"interval":{"startTime":1310.0,"endTime":1400.0},"skipType":"mixed-ed","skipId":"med-id","episodeLength":1439.257},
                {"interval":{"startTime":0.0,"endTime":45.0},"skipType":"recap","skipId":"recap-id","episodeLength":1439.257}
              ]
            }
        """.trimIndent()

        val segments = parseAniSkipSegments(json, payload)

        assertEquals(ANI_SKIP_V2_TYPES, segments.map { it.type })
        assertEquals("op-id", segments.first().skipId)
        assertEquals(1.25, segments.first().interval.startSeconds, 0.0)
        assertEquals(91.5, segments.first().interval.endSeconds, 0.0)
        assertEquals(1_439.257, segments.first().referenceDurationSeconds, 0.0)
    }

    @Test
    fun `parser ignores unknown and unsafe contributions`() {
        val payload = """
            {
              "found": true,
              "results": [
                {"interval":{"startTime":0,"endTime":10},"skipType":"preview","skipId":"unknown","episodeLength":1400},
                {"interval":{"startTime":20,"endTime":10},"skipType":"op","skipId":"backwards","episodeLength":1400},
                {"interval":{"startTime":0,"endTime":10},"skipType":"ed","skipId":"","episodeLength":1400},
                {"interval":{"startTime":0,"endTime":10},"skipType":"recap","skipId":"valid","episodeLength":1400}
              ]
            }
        """.trimIndent()

        val segments = parseAniSkipSegments(json, payload)

        assertEquals(1, segments.size)
        assertEquals(AniSkipType.RECAP, segments.single().type)
    }

    @Test
    fun `relation parser accepts episode zero and skips malformed ranges`() {
        val payload = """
            {
              "found": true,
              "rules": [
                {"from":{"start":0,"end":0},"to":{"malId":100,"start":1,"end":1}},
                {"from":{"start":20,"end":10},"to":{"malId":200,"start":1}},
                {"from":{"start":4,"end":null},"to":{"malId":300,"start":2,"end":null}}
              ]
            }
        """.trimIndent()

        val rules = parseAniSkipRelationRules(json, payload)

        assertEquals(2, rules.size)
        assertEquals(0, rules.first().from.start)
        assertEquals(100, rules.first().to.malId)
        assertTrue(rules.last().from.end == null)
    }
}
