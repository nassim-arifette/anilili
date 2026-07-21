package com.miruronative.data.remote

import com.miruronative.data.model.AniSkipInterval
import com.miruronative.data.model.AniSkipRelationRange
import com.miruronative.data.model.AniSkipRelationRule
import com.miruronative.data.model.AniSkipRelationTarget
import com.miruronative.data.model.AniSkipSegment
import com.miruronative.data.model.AniSkipType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniSkipResolverTest {
    @Test
    fun `canonical episodes retain zero and fractions without integer truncation`() {
        assertEquals("0", canonicalAniSkipEpisode(0.0))
        assertEquals("12.5", canonicalAniSkipEpisode(12.5))
        assertEquals("90", canonicalAniSkipEpisode(90.0))
    }

    @Test
    fun `relation mapping keeps target start and fractional offset`() {
        val rule = AniSkipRelationRule(
            from = AniSkipRelationRange(start = 23, end = 44),
            to = AniSkipRelationTarget(malId = 9_999, start = 3, end = 24),
        )

        val mapped = resolveAniSkipEpisode(malId = 1_111, episode = 23.5, rules = listOf(rule))

        assertEquals(9_999, mapped.malId)
        assertEquals(3.5, mapped.episode, 0.0)
    }

    @Test
    fun `open relation range maps episode zero and later fractional episodes`() {
        val rule = AniSkipRelationRule(
            from = AniSkipRelationRange(start = 0),
            to = AniSkipRelationTarget(malId = 2_222, start = 1),
        )

        assertEquals(1.0, resolveAniSkipEpisode(1_111, 0.0, listOf(rule)).episode, 0.0)
        assertEquals(5.5, resolveAniSkipEpisode(1_111, 4.5, listOf(rule)).episode, 0.0)
    }

    @Test
    fun `relation outside source or target bounds leaves episode unchanged`() {
        val rule = AniSkipRelationRule(
            from = AniSkipRelationRange(start = 10, end = 20),
            to = AniSkipRelationTarget(malId = 2_222, start = 1, end = 5),
        )

        val beforeRange = resolveAniSkipEpisode(1_111, 9.5, listOf(rule))
        val beyondTarget = resolveAniSkipEpisode(1_111, 16.0, listOf(rule))

        assertEquals(1_111, beforeRange.malId)
        assertEquals(9.5, beforeRange.episode, 0.0)
        assertEquals(1_111, beyondTarget.malId)
        assertEquals(16.0, beyondTarget.episode, 0.0)
    }

    @Test
    fun `duration resolver applies official offset and clamps to active media`() {
        val segments = listOf(
            segment(AniSkipType.OP, start = 5.0, end = 90.0, referenceDuration = 1_420.0),
            segment(AniSkipType.ED, start = 1_410.0, end = 1_425.0, referenceDuration = 1_420.0),
        )

        val resolved = resolveAniSkipSegments(segments, actualDurationSeconds = 1_430.0)

        assertEquals(15.0, resolved[0].interval.startSeconds, 0.0)
        assertEquals(100.0, resolved[0].interval.endSeconds, 0.0)
        assertEquals(1_420.0, resolved[1].interval.startSeconds, 0.0)
        assertEquals(1_430.0, resolved[1].interval.endSeconds, 0.0)
        assertEquals(segments[0], resolved[0].source)
    }

    @Test
    fun `duration tolerance is inclusive at twenty seconds and rejects larger drift`() {
        val atLimit = segment(AniSkipType.OP, 30.0, 90.0, referenceDuration = 1_400.0)
        val overLimit = segment(AniSkipType.ED, 1_300.0, 1_390.0, referenceDuration = 1_399.999)

        val resolved = resolveAniSkipSegments(
            listOf(atLimit, overLimit),
            actualDurationSeconds = 1_420.0,
        )

        assertEquals(listOf(AniSkipType.OP), resolved.map { it.type })
    }

    @Test
    fun `negative offset that collapses a range is discarded`() {
        val collapsed = segment(AniSkipType.RECAP, 2.0, 8.0, referenceDuration = 1_420.0)

        assertTrue(resolveAniSkipSegments(listOf(collapsed), 1_410.0).isEmpty())
    }

    @Test
    fun `cache key is scoped and does not expose signed source identity`() {
        val signedUrl = "https://video.example/episode.m3u8?token=secret"
        val base = aniSkipSegmentsCacheKey(100, 12.5, 1_439_257L, signedUrl)

        assertNotEquals(base, aniSkipSegmentsCacheKey(100, 12.0, 1_439_257L, signedUrl))
        assertNotEquals(base, aniSkipSegmentsCacheKey(100, 12.5, 1_440_000L, signedUrl))
        assertNotEquals(base, aniSkipSegmentsCacheKey(100, 12.5, 1_439_257L, "other-source"))
        assertFalse(base.contains("secret"))
        assertFalse(base.contains("video.example"))
        assertTrue(base.contains(":12.5:1439257:"))
    }

    private fun segment(
        type: AniSkipType,
        start: Double,
        end: Double,
        referenceDuration: Double,
    ) = AniSkipSegment(
        type = type,
        interval = AniSkipInterval(startSeconds = start, endSeconds = end),
        referenceDurationSeconds = referenceDuration,
        skipId = "${type.apiValue}-id",
    )
}
