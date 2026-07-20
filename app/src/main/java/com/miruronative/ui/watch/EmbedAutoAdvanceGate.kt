package com.miruronative.ui.watch

/**
 * Every web player ultimately drives an HTML5 <video>. Poll it (and any same-origin iframe's)
 * every second while playing and report position/duration to the Kotlin bridge. Cross-origin
 * iframes are unreachable by design, so those hosts cannot report progress or completion.
 */
internal fun progressPollJs(navigationGeneration: Long): String = """
    (function() {
      if (window.__aniliProgressHooked) return;
      window.__aniliProgressHooked = true;
      var navigationToken = '$navigationGeneration';
      function findVideo() {
        var v = document.querySelector('video');
        if (v) return v;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) {
              var fv = d.querySelector('video');
              if (fv) return fv;
            }
          } catch (e) { /* cross-origin */ }
        }
        return null;
      }
      function reportEnded(video) {
        if (window.__aniliEndedReportedVideo === video) return;
        if (!isFinite(video.duration) || video.duration <= 0 || video.currentTime < 0) return;
        window.__aniliEndedReportedVideo = video;
        try {
          AniliProgress.onEnded(navigationToken, video.currentTime, video.duration);
        } catch (e) { /* bridge detached */ }
      }
      function observeEnded(video) {
        if (window.__aniliObservedVideo === video) return;
        try {
          if (window.__aniliObservedVideo && window.__aniliEndedHandler) {
            window.__aniliObservedVideo.removeEventListener('ended', window.__aniliEndedHandler);
          }
        } catch (e) { /* old iframe navigated */ }
        window.__aniliObservedVideo = video;
        window.__aniliEndedHandler = function() { reportEnded(video); };
        video.addEventListener('ended', window.__aniliEndedHandler);
        if (video.ended) reportEnded(video);
      }
      setInterval(function() {
        try {
          var v = findVideo();
          if (v) observeEnded(v);
          if (v && !window.__aniliVideoReported) {
            window.__aniliVideoReported = true;
            AniliProgress.onVideoAvailable(navigationToken);
          }
          if (v && isFinite(v.duration) && v.duration > 0 && v.currentTime >= 0) {
            AniliProgress.onTick(navigationToken, v.currentTime, v.duration, !v.paused, v.muted, v.volume);
            // Some players replace or suppress the DOM ended event. Polling `ended` keeps the
            // transition reliable without guessing from a near-duration timestamp.
            if (v.ended) reportEnded(v);
          }
        } catch (e) { /* bridge detached */ }
      }, 1000);
    })();
""".trimIndent()

/** Exactly-once gate shared by natural completion and metadata-driven outro auto-advance. */
internal class EmbedAutoAdvanceGate {
    private var handledNavigationToken: String? = null

    fun hasAdvanced(navigationToken: String): Boolean = handledNavigationToken == navigationToken

    fun tryAdvance(navigationToken: String, autoplay: Boolean, hasNextEpisode: Boolean): Boolean {
        if (navigationToken.isBlank() || !autoplay || !hasNextEpisode) return false
        if (handledNavigationToken == navigationToken) return false
        handledNavigationToken = navigationToken
        return true
    }
}
