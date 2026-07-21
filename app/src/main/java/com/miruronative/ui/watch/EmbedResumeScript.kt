package com.miruronative.ui.watch

private const val EMBED_RESUME_RETRY_INTERVAL_MS = 250
private const val EMBED_RESUME_TIMEOUT_MS = 60_000

/**
 * Waits for a directly accessible HTML5 video, seeks to the saved position, then starts it.
 *
 * Embed pages often finish loading before their player or same-origin iframe exists. Keep retrying
 * for a bounded period, and re-check the navigation generation and revocation state before every
 * attempt so a timer created by a replaced document cannot control the new playback session.
 */
internal fun embedResumeWhenReadyJs(
    targetSec: Double,
    navigationGeneration: Long,
): String = """
    (function() {
      ${embedNavigationJsGuard(navigationGeneration)}
      var deadline = Date.now() + $EMBED_RESUME_TIMEOUT_MS;
      function isCurrentNavigation() {
        return window.__aniliNavigationToken === '$navigationGeneration' &&
          window.__aniliNavigationRevoked !== true;
      }
      ${embedContentVideoSelectorJs()}
      function scheduleRetry() {
        if (Date.now() >= deadline) return;
        setTimeout(attemptResume, $EMBED_RESUME_RETRY_INTERVAL_MS);
      }
      function attemptResume() {
        if (!isCurrentNavigation()) return;
        if (Date.now() >= deadline) return;
        try {
          var video = findContentVideo();
          if (video && video.readyState >= 1) {
            var target = $targetSec;
            video.currentTime = isFinite(video.duration) && video.duration > 0
              ? Math.min(target, video.duration)
              : target;
            var playResult = video.play();
            if (playResult && typeof playResult.catch === 'function') {
              playResult.catch(function() { /* autoplay may require a user gesture */ });
            }
            return;
          }
        } catch (e) { /* player still initializing */ }
        scheduleRetry();
      }
      attemptResume();
    })();
""".trimIndent()
