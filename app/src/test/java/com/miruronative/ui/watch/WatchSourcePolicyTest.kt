package com.miruronative.ui.watch

import android.view.KeyEvent
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.ProviderData
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSourcePolicyTest {

    @Test
    fun `playback speeds include fine adjustments around normal speed`() {
        assertTrue(PlaybackSpeeds.containsAll(listOf(1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f)))
        assertEquals(PlaybackSpeeds.sorted(), PlaybackSpeeds)
        assertEquals(PlaybackSpeeds.distinct(), PlaybackSpeeds)
        assertEquals("1.15x", 1.15f.formatPlaybackSpeed())
    }
    @Test
    fun `kiwi uses embed because fixed quality CDN streams require page state`() {
        val hls = stream("https://vault.example/1080.m3u8", "hls", "1080p", active = true)
        val embed = stream("https://kwik.example/e/id", "embed", "1080p", active = true)

        assertEquals(embed, pickProviderStream("kiwi", sources(hls, embed)))
    }

    @Test
    fun `ally uses progressive file before unreliable HLS mirror`() {
        val direct = stream("https://files.example/video.mp4", "video", "1080p")
        val hls = stream("https://mirror.example/master.m3u8", "hls", "auto", active = true)

        assertEquals(direct, pickProviderStream("ally", sources(direct, hls)))
    }

    @Test
    fun `other providers retain active HLS preference`() {
        val direct = stream("https://files.example/video.mp4", "video", "1080p")
        val hls = stream("https://cdn.example/master.m3u8", "hls", "auto", active = true)

        assertEquals(hls, pickProviderStream("bonk", sources(direct, hls)))
    }

    @Test
    fun `allanime orders every stream before provider fallback`() {
        val mp4 = stream("https://files.example/video.mp4", "mp4", "AllAnime 1080p Yt-mp4", active = true)
        val primaryHls = stream("https://primary.example/master.m3u8", "hls", "AllAnime Ac")
        val backupHls = stream("https://backup.example/master.m3u8", "hls", "AllAnime Luf-hls")
        val embed = stream("https://embed.example/e/id", "embed", "AllAnime Ok")
        val result = sources(mp4, primaryHls, backupHls, embed)

        assertEquals(
            listOf(primaryHls, backupHls, mp4, embed),
            providerStreamOrder("allanime", result),
        )
    }

    @Test
    fun `allanime advances through untried streams and stops when exhausted`() {
        val primaryHls = stream("https://primary.example/master.m3u8", "hls", "AllAnime Ac", active = true)
        val backupHls = stream("https://backup.example/master.m3u8", "hls", "AllAnime Luf-hls")
        val mp4 = stream("https://files.example/video.mp4", "mp4", "AllAnime 1080p Yt-mp4")
        val embed = stream("https://embed.example/e/id", "embed", "AllAnime Ok")
        val result = sources(primaryHls, backupHls, mp4, embed)

        assertEquals(
            backupHls,
            nextProviderStream("allanime", result, primaryHls.url, setOf(primaryHls.url)),
        )
        assertEquals(
            mp4,
            nextProviderStream("allanime", result, backupHls.url, setOf(primaryHls.url, backupHls.url)),
        )
        assertEquals(
            embed,
            nextProviderStream("allanime", result, mp4.url, setOf(primaryHls.url, backupHls.url, mp4.url)),
        )
        assertNull(
            nextProviderStream(
                "allanime",
                result,
                embed.url,
                setOf(primaryHls.url, backupHls.url, mp4.url, embed.url),
            ),
        )
        assertEquals(
            primaryHls,
            nextProviderStream(
                "allanime",
                sources(primaryHls, backupHls),
                backupHls.url,
                setOf(backupHls.url),
            ),
        )
    }

    @Test
    fun `saved provider overrides each watch route`() {
        assertEquals("allanime", preferredProviderForWatch("allanime", "bonk"))
        assertEquals("allanime", preferredProviderForWatch(" AllAnime ", "kiwi"))
    }

    @Test
    fun `first watch keeps route provider until user chooses a global provider`() {
        assertEquals("bonk", preferredProviderForWatch("auto", "bonk"))
        assertEquals("kiwi", preferredProviderForWatch(null, " Kiwi "))
    }

    @Test
    fun `blank watch provider falls back to automatic selection`() {
        assertEquals("auto", preferredProviderForWatch("auto", " "))
    }

    @Test
    fun `tv controls keep progress display out of remote focus order`() {
        assertEquals(
            listOf(
                TvPlayerControl.PREVIOUS,
                TvPlayerControl.REWIND,
                TvPlayerControl.PLAY_PAUSE,
                TvPlayerControl.FORWARD,
                TvPlayerControl.NEXT,
                TvPlayerControl.VOLUME_DOWN,
                TvPlayerControl.MUTE,
                TvPlayerControl.VOLUME_UP,
                TvPlayerControl.SETTINGS,
                TvPlayerControl.FULLSCREEN,
            ),
            tvPlayerControlOrder(hasSettings = true, hasFullscreen = true),
        )
    }

    @Test
    fun `direction and confirm keys open tv controls`() {
        assertTrue(opensTvPlayerControls(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(opensTvPlayerControls(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(opensTvPlayerControls(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(opensTvPlayerControls(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(opensTvPlayerControls(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(false, opensTvPlayerControls(KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    @Test
    fun `quality height is recovered from provider label`() {
        assertEquals(1080, declaredVideoHeight("AllAnime 1080p Yt-mp4"))
        assertEquals(720, declaredVideoHeight("720P"))
        assertEquals(null, declaredVideoHeight("AllAnime auto"))
    }

    @Test
    fun `auto skip seeks past outro when autoplay is disabled`() {
        assertEquals(
            OutroSkipAction.SEEK_TO_END,
            outroSkipAction(true, false, true, true, false, 1_305_000, 1_300_000, 1_390_000),
        )
    }

    @Test
    fun `auto skip advances only when autoplay has a next episode`() {
        assertEquals(
            OutroSkipAction.NEXT_EPISODE,
            outroSkipAction(true, true, true, true, false, 1_305_000, 1_300_000, 1_390_000),
        )
        assertEquals(
            OutroSkipAction.SEEK_TO_END,
            outroSkipAction(true, true, false, true, false, 1_305_000, 1_300_000, 1_390_000),
        )
    }

    @Test
    fun `outro policy ignores disabled handled and out of range cases`() {
        assertEquals(
            OutroSkipAction.NONE,
            outroSkipAction(false, true, true, true, false, 1_305_000, 1_300_000, 1_390_000),
        )
        assertEquals(
            OutroSkipAction.NONE,
            outroSkipAction(true, true, true, true, true, 1_305_000, 1_300_000, 1_390_000),
        )
        assertEquals(
            OutroSkipAction.NONE,
            outroSkipAction(true, true, true, true, false, 1_200_000, 1_300_000, 1_390_000),
        )
    }

    @Test
    fun `paused playback never triggers automatic outro behavior`() {
        assertEquals(
            OutroSkipAction.NONE,
            outroSkipAction(true, true, true, false, false, 1_305_000, 1_300_000, 1_390_000),
        )
    }

    @Test
    fun `navigation spine removes duplicate rows and orders episode numbers`() {
        val preferred = ProviderData("bonk", listOf(episode(1), episode(2)), emptyList())
        val noisy = ProviderData("hop", listOf(episode(2), episode(1), episode(2), episode(3)), emptyList())

        val spine = pickNavigationSpine(EpisodesResult(listOf(preferred, noisy)), "bonk", Category.SUB)

        assertEquals(listOf(1.0, 2.0, 3.0), spine.map(EpisodeItem::number))
    }

    @Test
    fun `navigation spine keeps preferred provider on a normalized tie`() {
        val preferredEpisode = episode(1, id = "preferred")
        val preferred = ProviderData("bonk", listOf(preferredEpisode, episode(2)), emptyList())
        val other = ProviderData("hop", listOf(episode(1), episode(2), episode(2)), emptyList())

        val spine = pickNavigationSpine(EpisodesResult(listOf(preferred, other)), "bonk", Category.SUB)

        assertEquals("preferred", spine.first().pipeId)
    }

    @Test
    fun `navigation spine unions non-contiguous provider catalogs`() {
        val preferred = ProviderData(
            "bonk",
            listOf(episode(1, id = "preferred-1"), episode(3, id = "preferred-3")),
            emptyList(),
        )
        val longer = ProviderData(
            "hop",
            listOf(episode(1, id = "other-1"), episode(2), episode(4)),
            emptyList(),
        )

        val spine = pickNavigationSpine(EpisodesResult(listOf(preferred, longer)), "bonk", Category.SUB)

        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), spine.map(EpisodeItem::number))
        assertEquals("preferred-1", spine.first().pipeId)
        assertEquals(2, navigationEpisodeIndex(spine, 3.0))
    }

    @Test
    fun `missing navigation episode has no fallback index`() {
        assertEquals(null, navigationEpisodeIndex(listOf(episode(1)), 3.0))
    }

    @Test
    fun `late catalog publishes a direct-link episode while initial resolution is loading`() {
        val rebuilt = listOf(episode(1), episode(12))

        val publication = catalogSpinePublication(rebuilt, visibleEpisodeNumber = null)!!

        assertEquals(rebuilt, publication.episodes)
        assertNull(publication.currentIndex)
        assertEquals(1, navigationEpisodeIndex(publication.episodes, 12.0))
    }

    @Test
    fun `late catalog publication keeps visible episode and index in one snapshot`() {
        val rebuilt = listOf(episode(1), episode(2), episode(3))

        val publication = catalogSpinePublication(rebuilt, visibleEpisodeNumber = 2.0)!!

        assertEquals(1, publication.currentIndex)
        assertEquals(2.0, publication.episodes[publication.currentIndex!!].number, 0.0)
    }

    @Test
    fun `late catalog publication rejects a spine that would orphan visible episode`() {
        assertNull(catalogSpinePublication(listOf(episode(1), episode(3)), visibleEpisodeNumber = 2.0))
    }

    @Test
    fun `unresolved source retries only after catalog generation changes`() {
        assertTrue(shouldRetryAfterCatalogMerge(false, 2L, 3L))
        assertEquals(false, shouldRetryAfterCatalogMerge(false, 3L, 3L))
        assertEquals(false, shouldRetryAfterCatalogMerge(true, 2L, 3L))
    }

    @Test
    fun `unresolved capped pass always gets one exhaustive fallback`() {
        assertTrue(
            shouldRunExhaustiveProviderFallback(
                hasResolvedSource = false,
                exhaustiveResolutionAttempted = false,
            ),
        )
    }

    @Test
    fun `resolved or already exhaustive pass is not repeated`() {
        assertFalse(
            shouldRunExhaustiveProviderFallback(
                hasResolvedSource = true,
                exhaustiveResolutionAttempted = false,
            ),
        )
        assertFalse(
            shouldRunExhaustiveProviderFallback(
                hasResolvedSource = false,
                exhaustiveResolutionAttempted = true,
            ),
        )
    }

    @Test
    fun `navigation union is preserved for a switched audio category`() {
        val preferred = ProviderData(
            "bonk",
            sub = emptyList(),
            dub = listOf(episode(1, id = "preferred-1"), episode(3, id = "preferred-3")),
        )
        val fallback = ProviderData(
            "hop",
            sub = listOf(episode(99)),
            dub = listOf(episode(1, id = "other-1"), episode(2)),
        )

        val spine = pickNavigationSpine(
            EpisodesResult(listOf(preferred, fallback)),
            "bonk",
            Category.DUB,
        )

        assertEquals(listOf(1.0, 2.0, 3.0), spine.map(EpisodeItem::number))
        assertEquals("preferred-1", spine.first().pipeId)
    }

    @Test
    fun `provider and AniSkip chapter markers merge by range`() {
        val provider = SkipTimes(10.0, 95.0, null, null)
        val aniSkip = SkipTimes(12.0, 96.0, 1_320.0, 1_410.0)

        assertEquals(
            SkipTimes(10.0, 95.0, 1_320.0, 1_410.0),
            mergeSkipTimes(provider, aniSkip),
        )
    }

    @Test
    fun `empty provider skip object does not suppress AniSkip`() {
        val emptyProvider = SkipTimes(null, null, null, null)
        val aniSkip = SkipTimes(0.0, 90.0, 1_300.0, 1_390.0)

        assertEquals(aniSkip, mergeSkipTimes(emptyProvider, aniSkip))
        assertEquals(false, hasCompleteSkipTimes(emptyProvider))
    }

    @Test
    fun `invalid chapter placeholders are discarded`() {
        val invalid = SkipTimes(90.0, 10.0, 1_400.0, 1_300.0)

        assertNull(mergeSkipTimes(invalid, null))
    }

    @Test
    fun `anilist progress waits until most of a full integer episode was watched`() {
        assertTrue(shouldSyncAniListProgress(3.0, positionMs = 1_200_000, durationMs = 1_440_000))
        assertEquals(false, shouldSyncAniListProgress(3.0, positionMs = 600_000, durationMs = 1_440_000))
        assertEquals(false, shouldSyncAniListProgress(3.5, positionMs = 1_300_000, durationMs = 1_440_000))
        assertEquals(false, shouldSyncAniListProgress(3.0, positionMs = 45_000, durationMs = 50_000))
    }

    private fun sources(vararg streams: StreamItem) = SourcesResult(streams.toList(), emptyList(), null, null)

    private fun stream(url: String, type: String, quality: String, active: Boolean = false) = StreamItem(
        url = url,
        type = type,
        quality = quality,
        audio = null,
        referer = null,
        isActive = active,
        width = null,
        height = null,
    )

    private fun episode(number: Int, id: String = "episode-$number") = EpisodeItem(
        pipeId = id,
        number = number.toDouble(),
        title = null,
        image = null,
        filler = false,
    )
}
