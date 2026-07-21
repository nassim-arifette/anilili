package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedNavigationGuardTest {
    @Test
    fun firstDurationBearingVideoActivatesBeforeItsProgressIsAccepted() {
        val playback = EmbedPlaybackKey(21, "allanime", "sub", 12.0, sourceGeneration = 8)
        val document = EmbedMediaIdentity(
            playbackKey = playback,
            navigationGeneration = 4,
            documentMediaId = "https://embed.example/episode-12",
        )
        val firstVideo = document.copy(
            videoIdentity = EmbedVideoIdentity(
                "https://cdn.example/episode-12.m3u8?token=first|1440000",
                generation = 1,
            ),
        )

        assertEquals(
            EmbedMediaHandoffDecision.ACTIVATE_AND_RESET_ANISKIP,
            planEmbedMediaHandoff(document, firstVideo, playback),
        )
        // The production handler performs the activation synchronously and then publishes this
        // exact same sample, rather than requiring (and potentially never receiving) a second tick.
        assertTrue(acceptsEmbedMediaCallback(firstVideo, firstVideo, playback))
    }

    @Test
    fun sameDocumentVideoReplacementUsesGenerationEvenAtTheSameRoundedDuration() {
        val playback = EmbedPlaybackKey(21, "allanime", "sub", 12.0, sourceGeneration = 8)
        val videoA = EmbedMediaIdentity(
            playbackKey = playback,
            navigationGeneration = 4,
            documentMediaId = "https://embed.example/episode-12",
            videoIdentity = EmbedVideoIdentity(
                "https://cdn-a.example/episode.m3u8?token=a|1440000",
                generation = 1,
            ),
        )
        val videoB = videoA.copy(
            videoIdentity = EmbedVideoIdentity(
                "https://cdn-b.example/episode.m3u8?token=b|1440000",
                generation = 2,
            ),
        )
        val indistinguishableReplacement = videoA.copy(
            videoIdentity = checkNotNull(videoA.videoIdentity).copy(generation = 2),
        )

        assertEquals(
            EmbedMediaHandoffDecision.ACTIVATE_AND_RESET_ANISKIP,
            planEmbedMediaHandoff(videoA, videoB, playback),
        )
        assertEquals(
            EmbedMediaHandoffDecision.ACTIVATE_AND_RESET_ANISKIP,
            planEmbedMediaHandoff(videoA, indistinguishableReplacement, playback),
        )
        assertNotEquals(videoA.aniSkipSourceIdentity(), videoB.aniSkipSourceIdentity())
        assertNotEquals(
            videoA.aniSkipSourceIdentity(),
            indistinguishableReplacement.aniSkipSourceIdentity(),
        )
        assertNotEquals(videoA.mediaInstanceId, videoB.mediaInstanceId)
        assertTrue(acceptsEmbedMediaCallback(videoB, videoB, playback))

        // A queued A sample cannot reactivate the former video after B became current.
        assertEquals(
            EmbedMediaHandoffDecision.REJECT,
            planEmbedMediaHandoff(videoB, videoA, playback),
        )
        assertFalse(acceptsEmbedMediaCallback(videoA, videoB, playback))
    }

    @Test
    fun navigationAndQualityReplacementRejectTheFormerConcreteVideo() {
        val playback = EmbedPlaybackKey(21, "allanime", "sub", 12.0, sourceGeneration = 8)
        val quality720 = EmbedMediaIdentity(
            playbackKey = playback,
            navigationGeneration = 40,
            documentMediaId = "https://embed.example/episode-12-720",
            videoIdentity = EmbedVideoIdentity("blob:https://embed.example/a|1440000", 1),
        )
        val quality1080 = quality720.copy(
            navigationGeneration = 41,
            documentMediaId = "https://embed.example/episode-12-1080",
            videoIdentity = EmbedVideoIdentity("blob:https://embed.example/b|1440000", 1),
        )

        assertEquals(
            EmbedMediaHandoffDecision.ACTIVATE_AND_RESET_ANISKIP,
            planEmbedMediaHandoff(quality720, quality1080, playback),
        )
        assertEquals(
            EmbedMediaHandoffDecision.REJECT,
            planEmbedMediaHandoff(quality1080, quality720, playback),
        )
        assertFalse(acceptsEmbedMediaCallback(quality720, quality1080, playback))
    }

    @Test
    fun navigationGenerationsRemainOrderedAcrossGuardRecreation() {
        val first = EmbedNavigationGuard().begin(request("https://player.example/episode-a"))
        val replacement = EmbedNavigationGuard().begin(request("https://player.example/episode-a"))

        assertTrue(replacement.generation > first.generation)
    }

    @Test
    fun concreteMediaDiagnosticsAndCacheScopeDoNotExposeSignedIds() {
        val signedDocument = "https://embed.example/watch?signature=document-secret"
        val signedVideo = "https://cdn.example/video.m3u8?token=video-secret|1440000"
        val identity = EmbedMediaIdentity(
            playbackKey = EmbedPlaybackKey(21, "allanime", "sub", 12.0, sourceGeneration = 8),
            navigationGeneration = 40,
            documentMediaId = signedDocument,
            videoIdentity = EmbedVideoIdentity(signedVideo, 1),
        )
        val sourceIdentity = checkNotNull(identity.aniSkipSourceIdentity())
        val playbackIdentity = checkNotNull(identity.playbackProgressIdentity())
        val diagnostic = identity.toString()

        assertTrue(sourceIdentity.startsWith("embed-sha256:"))
        assertFalse(sourceIdentity.contains("document-secret"))
        assertFalse(sourceIdentity.contains("video-secret"))
        assertFalse(diagnostic.contains(signedDocument))
        assertFalse(diagnostic.contains(signedVideo))
        assertFalse(identity.videoIdentity.toString().contains("video-secret"))
        assertEquals(signedDocument, playbackIdentity.mediaId)
        assertEquals(sourceIdentity, playbackIdentity.sourceIdentityForAniSkipLookup())
        assertFalse(playbackIdentity.toString().contains("document-secret"))
        assertFalse(playbackIdentity.toString().contains(sourceIdentity))
    }

    @Test
    fun logicalEpisodeChangesNavigationIdentityWhenUrlAndRefererAreReused() {
        val sharedUrl = "https://player.example/reused"
        val sharedReferer = "https://catalog.example/"
        val episodeA = EmbedNavigationIdentity(
            playbackKey = EmbedPlaybackKey(
                animeId = 21,
                provider = "provider-a",
                category = "sub",
                episodeNumber = 1.0,
            ),
            streamUrl = sharedUrl,
            referer = sharedReferer,
            usesIframeShell = false,
        )
        val episodeB = episodeA.copy(
            playbackKey = episodeA.playbackKey.copy(episodeNumber = 2.0),
        )

        assertFalse(episodeA == episodeB)
        assertTrue(episodeA.streamUrl == episodeB.streamUrl)
        assertTrue(episodeA.referer == episodeB.referer)

        val guard = EmbedNavigationGuard()
        val navigationA = guard.begin(request(sharedUrl))
        val navigationB = guard.begin(request(sharedUrl))
        assertFalse(guard.isCurrent(navigationA))
        assertTrue(guard.isCurrent(navigationB))
    }

    @Test
    fun callbackIdentityCannotBeRelabelledAsTheNewEpisode() {
        val episodeA = EmbedPlaybackKey(21, "provider-a", "sub", 1.0, sourceGeneration = 3)
        val episodeB = episodeA.copy(episodeNumber = 2.0, sourceGeneration = 4)

        assertTrue(acceptsEmbedPlaybackCallback(episodeA, episodeA))
        assertFalse(acceptsEmbedPlaybackCallback(episodeA, episodeB))
    }

    @Test
    fun qualityUrlHandoffActivatesAndResetsDurationBoundMarkers() {
        val playback = EmbedPlaybackKey(21, "allanime", "sub", 12.0, sourceGeneration = 8)
        val quality720 = EmbedMediaIdentity(
            playbackKey = playback,
            navigationGeneration = 4,
            documentMediaId = "https://embed.example/episode-12-720",
        )
        val quality1080 = quality720.copy(
            navigationGeneration = 5,
            documentMediaId = "https://embed.example/episode-12-1080",
        )

        assertTrue(
            planEmbedMediaHandoff(null, quality720, playback) ==
                EmbedMediaHandoffDecision.ACTIVATE_AND_RESET_ANISKIP,
        )
        assertTrue(
            planEmbedMediaHandoff(quality720, quality1080, playback) ==
                EmbedMediaHandoffDecision.ACTIVATE_AND_RESET_ANISKIP,
        )
        assertTrue(
            planEmbedMediaHandoff(quality1080, quality1080, playback) ==
                EmbedMediaHandoffDecision.KEEP,
        )
    }

    @Test
    fun staleQualityProgressIsRejectedAfterActiveUrlHandoff() {
        val playback = EmbedPlaybackKey(21, "allanime", "sub", 12.0, sourceGeneration = 8)
        val oldQuality = EmbedMediaIdentity(
            playbackKey = playback,
            navigationGeneration = 4,
            documentMediaId = "https://embed.example/episode-12-720",
            videoIdentity = EmbedVideoIdentity("https://cdn.example/episode-12.m3u8|1440000", 1),
        )
        val activeQuality = oldQuality.copy(
            navigationGeneration = 5,
            documentMediaId = "https://embed.example/episode-12-1080",
        )

        assertFalse(acceptsEmbedMediaCallback(oldQuality, activeQuality, playback))
        assertTrue(acceptsEmbedMediaCallback(activeQuality, activeQuality, playback))
        assertFalse(
            acceptsEmbedMediaCallback(
                activeQuality,
                activeQuality,
                playback.copy(sourceGeneration = 9),
            ),
        )
    }

    @Test
    fun replacementNavigationCanReloadMarkersOnlyForItsOwnMediaIdentity() {
        val playback = EmbedPlaybackKey(21, "allanime", "sub", 12.0, sourceGeneration = 8)
        val oldQuality = EmbedMediaIdentity(
            playbackKey = playback,
            navigationGeneration = 4,
            documentMediaId = "https://embed.example/episode-12-720",
            videoIdentity = EmbedVideoIdentity("https://cdn.example/episode-12-720.m3u8|1440000", 1),
        )
        val replacement = oldQuality.copy(
            navigationGeneration = 5,
            documentMediaId = "https://embed.example/episode-12-1080",
            videoIdentity = EmbedVideoIdentity("https://cdn.example/episode-12-1080.m3u8|1440000", 1),
        )

        assertTrue(
            planEmbedMediaHandoff(oldQuality, replacement, playback) ==
                EmbedMediaHandoffDecision.ACTIVATE_AND_RESET_ANISKIP,
        )
        assertFalse(acceptsEmbedMediaCallback(oldQuality, replacement, playback))
        assertTrue(acceptsEmbedMediaCallback(replacement, replacement, playback))
    }

    @Test
    fun beginningNavigationBImmediatelyRejectsBridgeAndClientCallbacksFromA() {
        val guard = EmbedNavigationGuard()
        val navigationA = guard.begin(request("https://player.example/episode-a"))
        assertTrue(guard.acceptsBridgeToken(navigationA.bridgeToken))
        assertTrue(
            guard.acceptPageStarted(
                navigationA,
                "https://player.example/episode-a",
                "https://player.example/episode-a",
            ),
        )

        val navigationB = guard.begin(request("https://player.example/episode-b"))

        assertFalse(guard.acceptsBridgeToken(navigationA.bridgeToken))
        assertTrue(guard.acceptsBridgeToken(navigationB.bridgeToken))
        assertFalse(
            guard.acceptPageFinished(
                navigationA,
                "https://player.example/episode-a",
                "https://player.example/episode-a",
            ),
        )
        assertFalse(
            guard.acceptMainFrameError(
                navigationA,
                "https://player.example/episode-a",
                "https://player.example/episode-a",
            ),
        )
    }

    @Test
    fun currentClientRejectsLateSameHostUrlsUntilExpectedDocumentStarts() {
        val guard = EmbedNavigationGuard()
        val navigation = guard.begin(request("https://player.example/episode-b"))

        assertFalse(
            guard.acceptPageStarted(
                navigation,
                "https://player.example/episode-a",
                "https://player.example/episode-a",
            ),
        )
        assertFalse(
            guard.acceptMainFrameError(
                navigation,
                "https://player.example/episode-a",
                "https://player.example/episode-a",
            ),
        )
        assertTrue(
            guard.acceptMainFrameError(
                navigation,
                "https://player.example/episode-b",
                "https://player.example/episode-a",
            ),
        )
        assertTrue(
            guard.acceptPageStarted(
                navigation,
                "https://player.example/episode-b#player",
                // WebView.getUrl() may still expose the committed A document while B is
                // provisional; the exact expected callback URL is the capability proof here.
                "https://player.example/episode-a",
            ),
        )
        assertFalse(
            guard.acceptPageFinished(
                navigation,
                "https://player.example/episode-a",
                "https://player.example/episode-a",
            ),
        )
        assertTrue(
            guard.acceptPageFinished(
                navigation,
                "https://player.example/episode-b",
                "https://player.example/episode-b",
            ),
        )
        assertFalse(
            guard.acceptPageFinished(
                navigation,
                "https://player.example/episode-b",
                "https://player.example/episode-b",
            ),
        )
        assertFalse(
            guard.acceptMainFrameError(
                navigation,
                "https://player.example/episode-b",
                "https://player.example/episode-b",
            ),
        )
    }

    @Test
    fun onlyTrackedSameHostRedirectsAreAcceptedWhileMainFrameIsLoading() {
        val guard = EmbedNavigationGuard()
        val navigation = guard.begin(request("https://player.example/start"))

        assertFalse(guard.allowsMainFrameNavigation(navigation, "https://player.example/redirect"))
        assertTrue(
            guard.acceptPageStarted(
                navigation,
                "https://player.example/start",
                "https://player.example/start",
            ),
        )
        assertFalse(guard.allowsMainFrameNavigation(navigation, "https://ads.example.net/takeover"))
        assertTrue(guard.allowsMainFrameNavigation(navigation, "https://cdn.player.example/final"))
        assertTrue(
            guard.acceptPageFinished(
                navigation,
                "https://cdn.player.example/final",
                "https://cdn.player.example/final",
            ),
        )
        assertFalse(guard.allowsMainFrameNavigation(navigation, "https://player.example/late"))
    }

    @Test
    fun explicitInvalidationRejectsLateResultsBeforeReplacementSessionExists() {
        val guard = EmbedNavigationGuard()
        val navigation = guard.begin(request("https://player.example/episode-a"))

        assertTrue(guard.invalidate(navigation))
        assertFalse(guard.isCurrent(navigation))
        assertFalse(guard.acceptsBridgeToken(navigation.bridgeToken))
        assertFalse(guard.invalidate(navigation))
    }

    @Test
    fun replacementDocumentWaitsForItsOwnBlankCommit() {
        val navigationGuard = EmbedNavigationGuard()
        val transitionGate = EmbedDocumentTransitionGate()
        val navigationA = navigationGuard.begin(request("https://player.example/episode-a"))
        transitionGate.begin(navigationA)
        val navigationB = navigationGuard.begin(request("https://player.example/episode-b"))
        transitionGate.begin(navigationB)

        // A blank started by A may finish after B becomes current. B must not treat it as its own
        // teardown until B has actually requested a blank navigation.
        assertFalse(
            transitionGate.acceptBlankFinished(
                navigationB,
                EMBED_BLANK_DOCUMENT_URL,
                EMBED_BLANK_DOCUMENT_URL,
            ),
        )
        assertFalse(transitionGate.markBlankRequested(navigationA))
        assertTrue(transitionGate.markBlankRequested(navigationB))
        assertFalse(
            transitionGate.acceptBlankFinished(
                navigationA,
                EMBED_BLANK_DOCUMENT_URL,
                EMBED_BLANK_DOCUMENT_URL,
            ),
        )
        assertFalse(
            transitionGate.acceptBlankFinished(
                navigationB,
                "https://player.example/episode-a",
                "https://player.example/episode-a",
            ),
        )
        assertTrue(
            transitionGate.acceptBlankFinished(
                navigationB,
                EMBED_BLANK_DOCUMENT_URL,
                EMBED_BLANK_DOCUMENT_URL,
            ),
        )
        assertFalse(
            transitionGate.acceptBlankFinished(
                navigationB,
                EMBED_BLANK_DOCUMENT_URL,
                EMBED_BLANK_DOCUMENT_URL,
            ),
        )
    }

    @Test
    fun injectedProgressAndCommandsCarryAndRecheckTheNavigationCapability() {
        val progressScript = progressPollJs(42L)
        val commandGuard = embedNavigationJsGuard(42L)

        assertTrue(progressScript.contains("window.__aniliNavigationToken = navigationToken"))
        assertTrue(progressScript.contains("AniliProgress.onTick("))
        assertTrue(progressScript.contains("navigationToken,"))
        assertTrue(progressScript.contains("observedMediaGeneration"))
        assertTrue(progressScript.contains("__aniliBindMediaGeneration(video)"))
        assertTrue(progressScript.contains("AniliProgress.onVideoAvailable(navigationToken)"))
        assertTrue(progressScript.contains("window.__aniliNavigationRevoked"))
        assertTrue(progressScript.contains("clearInterval(timer)"))
        assertTrue(commandGuard.contains("window.__aniliNavigationToken !== '42'"))
        assertTrue(REVOKE_EMBED_NAVIGATION_JS.contains("__aniliNavigationRevoked = true"))
    }

    private fun request(url: String): EmbedNavigationRequest = EmbedNavigationRequest(
        streamUrl = url,
        documentUrl = url,
        allowedMainFrameHost = "player.example",
        resumePositionMs = 12_000L,
    )
}
