package com.miruronative.data.remote

import com.miruronative.data.model.SourceCompleteness
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListCompletenessParserTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `present media is decoded`() {
        val result = parseAnimeInfoCompleteness("""{"data":{"Media":{"id":42}}}""", json)

        assertTrue(result is SourceCompleteness.Present)
        assertEquals(42, (result as SourceCompleteness.Present).value.id)
    }

    @Test
    fun `explicit media null is definitive absence`() {
        val result = parseAnimeInfoCompleteness("""{"data":{"Media":null}}""", json)

        assertTrue(result === SourceCompleteness.DefinitiveAbsence)
    }

    @Test
    fun `missing media field is incomplete rather than absence`() {
        val result = parseAnimeInfoCompleteness("""{"data":{}}""", json)

        assertTrue(result is SourceCompleteness.Incomplete)
        assertEquals("AniList response omitted data.Media", (result as SourceCompleteness.Incomplete).reason)
    }

    @Test
    fun `null data envelope is incomplete rather than absence`() {
        val result = parseAnimeInfoCompleteness("""{"data":null}""", json)

        assertTrue(result is SourceCompleteness.Incomplete)
        assertEquals("AniList response data is null", (result as SourceCompleteness.Incomplete).reason)
    }

    @Test
    fun `GraphQL error is incomplete even when data is present`() {
        val result = parseAnimeInfoCompleteness(
            """{"data":{"Media":{"id":42}},"errors":[{"message":"partial response"}]}""",
            json,
        )

        assertTrue(result is SourceCompleteness.Incomplete)
        assertEquals(
            "AniList GraphQL error: partial response",
            (result as SourceCompleteness.Incomplete).reason,
        )
    }

    @Test
    fun `malformed present media is incomplete`() {
        val result = parseAnimeInfoCompleteness("""{"data":{"Media":{"title":"wrong shape"}}}""", json)

        assertTrue(result is SourceCompleteness.Incomplete)
    }

    @Test
    fun `present user list is decoded while explicit null is authoritative`() {
        val present = parseUserAnimeListCompleteness(
            """{"data":{"MediaListCollection":{"lists":[]}}}""",
            json,
        )
        val absent = parseUserAnimeListCompleteness(
            """{"data":{"MediaListCollection":null}}""",
            json,
        )

        assertTrue(present is SourceCompleteness.Present)
        assertEquals(emptyList<Any>(), (present as SourceCompleteness.Present).value.lists)
        assertTrue(absent === SourceCompleteness.DefinitiveAbsence)
    }
}
