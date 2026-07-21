package com.miruronative.ui.watch

import com.miruronative.data.model.AniSkipInterval
import com.miruronative.data.model.AniSkipPlaybackSegment
import com.miruronative.data.model.AniSkipSegment
import com.miruronative.data.model.AniSkipType
import com.miruronative.data.model.SkipTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniSkipPlaybackPolicyTest {
    @Test
    fun `provider auto skip stays blocked while a late mixed lookup is pending`() {
        val provider = SkipTimes(0.0, 90.0, null, null)
        val pending = buildPlaybackSkipPlan(
            providerSkip = provider,
            aniSkipSegments = emptyList(),
            aniSkipLookupStatus = AniSkipLookupStatus.LOADING,
        )

        assertNull(pending.automaticOpening)
        assertEquals("Skip Intro", pending.actionAt(20_000L, hasNextEpisode = false)?.label)
        assertNull(
            buildPlaybackSkipPlan(
                providerSkip = provider,
                aniSkipSegments = emptyList(),
                aniSkipLookupStatus = AniSkipLookupStatus.AWAITING_DURATION,
            ).automaticOpening,
        )

        val resolvedMixed = buildPlaybackSkipPlan(
            providerSkip = provider,
            aniSkipSegments = listOf(segment(AniSkipType.MIXED_OP, 12.0, 84.0)),
            aniSkipLookupStatus = AniSkipLookupStatus.COMPLETE,
        )
        assertNull(resolvedMixed.automaticOpening)
        assertEquals(
            "Skip Mixed Opening",
            resolvedMixed.actionAt(20_000L, hasNextEpisode = false)?.label,
        )

        val failedOrEmpty = buildPlaybackSkipPlan(
            providerSkip = provider,
            aniSkipSegments = emptyList(),
            aniSkipLookupStatus = AniSkipLookupStatus.COMPLETE,
        )
        assertEquals(PlaybackSkipOrigin.PROVIDER, failedOrEmpty.automaticOpening?.origin)
    }

    @Test
    fun `mixed opening suppresses provider auto skip for that family`() {
        val provider = SkipTimes(0.0, 90.0, 1_320.0, 1_410.0)
        val plan = buildPlaybackSkipPlan(
            providerSkip = provider,
            aniSkipSegments = listOf(segment(AniSkipType.MIXED_OP, 12.0, 84.0)),
        )

        assertEquals(PlaybackSkipKind.MIXED_OPENING, plan.opening?.kind)
        assertEquals(PlaybackSkipOrigin.ANISKIP, plan.opening?.origin)
        assertFalse(checkNotNull(plan.opening).autoSkipEligible)
        assertNull(plan.automaticOpening)
        assertEquals(PlaybackSkipOrigin.PROVIDER, plan.automaticEnding?.origin)
        assertEquals("Skip Mixed Opening", plan.actionAt(20_000L, hasNextEpisode = true)?.label)
    }

    @Test
    fun `mixed typed marker wins over pure typed marker regardless of response order`() {
        val plan = buildPlaybackSkipPlan(
            providerSkip = null,
            aniSkipSegments = listOf(
                segment(AniSkipType.OP, 0.0, 90.0),
                segment(AniSkipType.MIXED_OP, 8.0, 82.0),
                segment(AniSkipType.ED, 1_300.0, 1_390.0),
                segment(AniSkipType.MIXED_ED, 1_320.0, 1_400.0),
            ),
        )

        assertEquals(PlaybackSkipKind.MIXED_OPENING, plan.opening?.kind)
        assertEquals(PlaybackSkipKind.MIXED_ENDING, plan.ending?.kind)
        assertNull(plan.automaticOpening)
        assertNull(plan.automaticEnding)
    }

    @Test
    fun `pure AniSkip markers remain eligible for existing automatic settings`() {
        val plan = buildPlaybackSkipPlan(
            providerSkip = SkipTimes(4.0, 94.0, 1_310.0, 1_400.0),
            aniSkipSegments = listOf(
                segment(AniSkipType.OP, 10.0, 80.0),
                segment(AniSkipType.ED, 1_325.0, 1_405.0),
            ),
        )

        assertTrue(checkNotNull(plan.automaticOpening).autoSkipEligible)
        assertTrue(checkNotNull(plan.automaticEnding).autoSkipEligible)
        assertEquals(10_000L, plan.automaticOpening?.startMs)
        assertEquals(1_405_000L, plan.automaticEnding?.endMs)
        assertEquals(PlaybackSkipOrigin.ANISKIP, plan.opening?.origin)
    }

    @Test
    fun `provider markers remain the fallback when typed family is absent`() {
        val plan = buildPlaybackSkipPlan(
            providerSkip = SkipTimes(5.0, 95.0, 1_300.0, 1_390.0),
            aniSkipSegments = listOf(segment(AniSkipType.RECAP, 0.0, 45.0)),
        )

        assertEquals(PlaybackSkipOrigin.PROVIDER, plan.opening?.origin)
        assertEquals(PlaybackSkipOrigin.PROVIDER, plan.ending?.origin)
        assertEquals(PlaybackSkipKind.RECAP, plan.recap?.kind)
        assertEquals("Skip Recap", plan.actionAt(20_000L, hasNextEpisode = true)?.label)
    }

    @Test
    fun `invalid provider placeholders are not exposed as actions or automatic ranges`() {
        val plan = buildPlaybackSkipPlan(
            providerSkip = SkipTimes(90.0, 10.0, 1_400.0, 1_300.0),
            aniSkipSegments = emptyList(),
            aniSkipLookupStatus = AniSkipLookupStatus.COMPLETE,
        )

        assertNull(plan.opening)
        assertNull(plan.ending)
        assertNull(plan.actionAt(50_000L, hasNextEpisode = true))
    }

    @Test
    fun `recap overlap cannot be auto skipped through another marker`() {
        val plan = buildPlaybackSkipPlan(
            providerSkip = null,
            aniSkipSegments = listOf(
                segment(AniSkipType.RECAP, 0.0, 45.0),
                segment(AniSkipType.OP, 10.0, 80.0),
            ),
        )

        assertNull(plan.automaticOpening)
        assertEquals("Skip Recap", plan.actionAt(20_000L, hasNextEpisode = false)?.label)
    }

    @Test
    fun `touching ranges do not overlap and actions end before their seek target`() {
        val plan = buildPlaybackSkipPlan(
            providerSkip = null,
            aniSkipSegments = listOf(
                segment(AniSkipType.RECAP, 0.0, 45.0),
                segment(AniSkipType.OP, 45.0, 90.0),
            ),
        )

        assertEquals(45_000L, plan.automaticOpening?.startMs)
        assertEquals("Skip Recap", plan.actionAt(44_999L, hasNextEpisode = false)?.label)
        assertEquals("Skip Intro", plan.actionAt(45_000L, hasNextEpisode = false)?.label)
        assertNull(plan.actionAt(90_000L, hasNextEpisode = false))
    }

    @Test
    fun `mixed ending is manual seek and never next episode`() {
        val plan = buildPlaybackSkipPlan(
            providerSkip = SkipTimes(null, null, 1_300.0, 1_390.0),
            aniSkipSegments = listOf(segment(AniSkipType.MIXED_ED, 1_320.0, 1_400.0)),
        )

        val action = checkNotNull(plan.actionAt(1_350_000L, hasNextEpisode = true))
        assertEquals("Skip Mixed Ending", action.label)
        assertEquals(1_400_000L, action.seekTargetMs)
        assertFalse(action.advanceToNextEpisode)
    }

    @Test
    fun `pure ending keeps next episode behavior`() {
        val plan = buildPlaybackSkipPlan(
            providerSkip = null,
            aniSkipSegments = listOf(segment(AniSkipType.ED, 1_320.0, 1_400.0)),
        )

        val withNext = checkNotNull(plan.actionAt(1_350_000L, hasNextEpisode = true))
        assertEquals("Next Episode", withNext.label)
        assertTrue(withNext.advanceToNextEpisode)
        assertNull(withNext.seekTargetMs)

        val finalEpisode = checkNotNull(plan.actionAt(1_350_000L, hasNextEpisode = false))
        assertEquals("Skip Outro", finalEpisode.label)
        assertEquals(1_400_000L, finalEpisode.seekTargetMs)
    }

    private fun segment(type: AniSkipType, start: Double, end: Double): AniSkipPlaybackSegment {
        val interval = AniSkipInterval(startSeconds = start, endSeconds = end)
        return AniSkipPlaybackSegment(
            source = AniSkipSegment(
                type = type,
                interval = interval,
                referenceDurationSeconds = 1_440.0,
                skipId = "$type-$start-$end",
            ),
            interval = interval,
        )
    }
}
