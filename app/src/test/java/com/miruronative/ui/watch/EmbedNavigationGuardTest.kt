package com.miruronative.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedNavigationGuardTest {
    @Test
    fun logicalEpisodeChangesNavigationIdentityWhenUrlAndRefererAreReused() {
        val sharedUrl = "https://player.example/reused"
        val sharedReferer = "https://catalog.example/"
        val episodeA = EmbedNavigationIdentity(
            playbackKey = EmbedPlaybackKey(
                animeId = 21,
                provider = "provider-a",
                category = "sub",
                episodeNumber = "1",
            ),
            streamUrl = sharedUrl,
            referer = sharedReferer,
            usesIframeShell = false,
        )
        val episodeB = episodeA.copy(
            playbackKey = episodeA.playbackKey.copy(episodeNumber = "2"),
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
    fun injectedProgressAndCommandsCarryAndRecheckTheNavigationCapability() {
        val progressScript = progressPollJs(42L)
        val commandGuard = embedNavigationJsGuard(42L)

        assertTrue(progressScript.contains("window.__aniliNavigationToken = navigationToken"))
        assertTrue(progressScript.contains("AniliProgress.onTick("))
        assertTrue(progressScript.contains("navigationToken,"))
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
