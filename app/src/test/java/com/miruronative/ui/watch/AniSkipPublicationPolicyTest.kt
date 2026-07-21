package com.miruronative.ui.watch

import com.miruronative.data.model.Category
import com.miruronative.data.model.SkipTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniSkipPublicationPolicyTest {
    private val request = PlaybackRequestToken(sessionGeneration = 4, requestGeneration = 12)
    private val current = AniSkipPublicationIdentity(
        request = request,
        animeId = 21,
        episodeNumber = 7.0,
        provider = "allanime",
        category = Category.SUB,
        sourceGeneration = 30,
    )

    @Test
    fun `late AniSkip fills only the provider range that is missing`() {
        val provider = SkipTimes(8.0, 92.0, null, null)
        val fallback = SkipTimes(12.0, 95.0, 1_320.0, 1_405.0)

        assertEquals(
            SkipTimes(8.0, 92.0, 1_320.0, 1_405.0),
            lateAniSkipUpdate(current, current, provider, fallback),
        )
    }

    @Test
    fun `late AniSkip never overwrites complete provider markers`() {
        val provider = SkipTimes(8.0, 92.0, 1_300.0, 1_390.0)
        val fallback = SkipTimes(12.0, 95.0, 1_320.0, 1_405.0)

        assertNull(lateAniSkipUpdate(current, current, provider, fallback))
    }

    @Test
    fun `late publication rejects a superseded request`() {
        val newerRequest = current.copy(
            request = request.copy(requestGeneration = request.requestGeneration + 1),
        )

        assertNull(lateAniSkipUpdate(current, newerRequest, null, usefulFallback()))
    }

    @Test
    fun `late publication rejects a different episode or anime`() {
        assertNull(
            lateAniSkipUpdate(current, current.copy(episodeNumber = 8.0), null, usefulFallback()),
        )
        assertNull(
            lateAniSkipUpdate(current, current.copy(animeId = 22), null, usefulFallback()),
        )
    }

    @Test
    fun `late publication rejects provider and category switches`() {
        assertNull(
            lateAniSkipUpdate(current, current.copy(provider = "hianime"), null, usefulFallback()),
        )
        assertNull(
            lateAniSkipUpdate(current, current.copy(category = Category.DUB), null, usefulFallback()),
        )
    }

    @Test
    fun `late publication rejects a replacement source generation`() {
        assertNull(
            lateAniSkipUpdate(
                current,
                current.copy(sourceGeneration = current.sourceGeneration + 1),
                null,
                usefulFallback(),
            ),
        )
    }

    @Test
    fun `empty or invalid late markers produce no update`() {
        assertNull(lateAniSkipUpdate(current, current, null, null))
        assertNull(
            lateAniSkipUpdate(
                current,
                current,
                null,
                SkipTimes(90.0, 10.0, 1_400.0, 1_300.0),
            ),
        )
    }

    private fun usefulFallback() = SkipTimes(0.0, 90.0, 1_300.0, 1_390.0)
}
