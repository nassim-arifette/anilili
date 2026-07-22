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
      ${embedPlaybackMutationEpochJs()}
      var expectedMediaIdentity = ${expectedMediaIdentity.toJsStringLiteral()};
      var expectedMediaGeneration = $expectedMediaGeneration;
      var reported = false;
      var seekConfirmed = false;
      var bounded = null;
      var playbackMutationEpoch = null;
      function matches(video) {
        return !!video && findContentVideo() === video &&
          __aniliMediaIdentity(video) === expectedMediaIdentity &&
          __aniliMediaGeneration(video) === expectedMediaGeneration;
      }
      function remainsAtOrBeyondTarget(video) {
        return !!video && bounded !== null && isFinite(video.currentTime) &&
          video.currentTime + 1.5 >= bounded;
      }
      function stillOwnsPlaybackMutation(video) {
        return playbackMutationEpoch !== null &&
          __aniliPlaybackMutationIsCurrent(video, playbackMutationEpoch);
      }
      function report(success, video) {
        if (reported) return;
        reported = true;
        var position = video && isFinite(video.currentTime) ? video.currentTime : 0;
        var playing = !!video && !video.paused && !video.ended;
        success = success && matches(video) && stillOwnsPlaybackMutation(video) && playing &&
          remainsAtOrBeyondTarget(video);
        try {
          AniliProgress.onCommandResult(
            '$capabilityToken', '$navigationGeneration', '$commandId', success, position, playing,
            __aniliMediaIdentity(video), __aniliMediaGeneration(video)
          );
        } catch (e) { /* bridge detached */ }
      }
      function ensurePlaying(video) {
        if (reported || !matches(video) || !stillOwnsPlaybackMutation(video)) {
          report(false, video); return;
        }
        if (!video.paused && !video.ended) { report(true, video); return; }
        try {
          __aniliPauseCompetingMedia(video);
          var playResult = video.play();
          function settleResumePlay() {
            if (!matches(video) || !stillOwnsPlaybackMutation(video)) {
              __aniliReconcileStaleResumePlay(video, playbackMutationEpoch);
              report(false, video);
              return;
            }
            report(!video.paused && !video.ended, video);
          }
          if (playResult && typeof playResult.then === 'function') {
            playResult.then(settleResumePlay)
              .catch(function() { report(false, video); });
          } else {
            setTimeout(settleResumePlay, 0);
          }
        } catch (e) { report(false, video); }
      }
      try {
        var video = findContentVideo();
        if (!matches(video)) { report(false, video); return; }
        var target = $targetSec;
        bounded = isFinite(video.duration) && video.duration > 0
          ? Math.min(Math.max(0, target), video.duration)
          : Math.max(0, target);
        playbackMutationEpoch = __aniliBeginPlaybackMutation(video, true);
        function resumeAfterSeek() {
          if (reported || seekConfirmed) return;
          if (!matches(video) || !stillOwnsPlaybackMutation(video)) {
            report(false, video); return;
          }
          if (remainsAtOrBeyondTarget(video)) {
            seekConfirmed = true;
            ensurePlaying(video);
          }
        }
        if (remainsAtOrBeyondTarget(video)) {
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
