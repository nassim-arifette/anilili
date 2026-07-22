package com.miruronative.ui.watch

/**
 * Identity-scoped saved-position seek that also preserves the former resume-and-start behavior.
 * The native command coordinator accepts completion only from this exact video and generation.
 */
internal fun resumeVideoCommandJs(
    targetSec: Double,
    navigationGeneration: Long,
    capabilityToken: String,
    commandId: Long,
    expectedMediaIdentity: String,
    expectedMediaGeneration: Long,
): String = """
    (function() {
      ${embedNavigationJsGuard(navigationGeneration)}
      ${embedContentVideoSelectorJs()}
      var expectedMediaIdentity = ${expectedMediaIdentity.toJsStringLiteral()};
      var expectedMediaGeneration = $expectedMediaGeneration;
      var reported = false;
      var seekConfirmed = false;
      function matches(video) {
        return !!video && findContentVideo() === video &&
          __aniliMediaIdentity(video) === expectedMediaIdentity &&
          __aniliMediaGeneration(video) === expectedMediaGeneration;
      }
      function report(success, video) {
        if (reported) return;
        reported = true;
        var position = video && isFinite(video.currentTime) ? video.currentTime : 0;
        var playing = !!video && !video.paused && !video.ended;
        success = success && matches(video) && playing;
        try {
          AniliProgress.onCommandResult(
            '$capabilityToken', '$navigationGeneration', '$commandId', success, position, playing,
            __aniliMediaIdentity(video), __aniliMediaGeneration(video)
          );
        } catch (e) { /* bridge detached */ }
      }
      function ensurePlaying(video) {
        if (reported || !matches(video)) { report(false, video); return; }
        if (!video.paused && !video.ended) { report(true, video); return; }
        try {
          __aniliPauseCompetingMedia(video);
          var playResult = video.play();
          if (playResult && typeof playResult.then === 'function') {
            playResult.then(function() { report(!video.paused && !video.ended, video); })
              .catch(function() { report(false, video); });
          } else {
            setTimeout(function() { report(!video.paused && !video.ended, video); }, 0);
          }
        } catch (e) { report(false, video); }
      }
      try {
        var video = findContentVideo();
        if (!matches(video)) { report(false, video); return; }
        var target = $targetSec;
        var bounded = isFinite(video.duration) && video.duration > 0
          ? Math.min(Math.max(0, target), video.duration)
          : Math.max(0, target);
        function resumeAfterSeek() {
          if (reported || seekConfirmed) return;
          if (!matches(video)) { report(false, video); return; }
          if (Math.abs(video.currentTime - bounded) <= 1.5) {
            seekConfirmed = true;
            ensurePlaying(video);
          }
        }
        if (Math.abs(video.currentTime - bounded) <= 1.5) {
          seekConfirmed = true;
          ensurePlaying(video);
        } else {
          video.addEventListener('seeked', resumeAfterSeek, { once: true });
          video.currentTime = bounded;
          setTimeout(resumeAfterSeek, 150);
        }
        setTimeout(function() { report(false, video); }, 1800);
      } catch (e) { report(false, null); }
    })();
""".trimIndent()
