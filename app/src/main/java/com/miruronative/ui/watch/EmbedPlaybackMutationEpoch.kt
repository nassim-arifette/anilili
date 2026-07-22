package com.miruronative.ui.watch

/**
 * Browser-side epoch shared by every app-directed playback mutation in one document.
 *
 * A resume command can install asynchronous `seeked`, timer, and play-promise callbacks. Starting
 * any later seek/toggle/resume advances this epoch, allowing those callbacks to prove they still
 * own the selected concrete video before they are allowed to resume it.
 */
internal fun embedPlaybackMutationEpochJs(): String = """
    function __aniliBeginPlaybackMutation(video, desiredPlaying) {
      var previous = window.__aniliPlaybackMutationState;
      var nextEpoch = previous && isFinite(previous.epoch) ? previous.epoch + 1 : 1;
      window.__aniliPlaybackMutationState = {
        epoch: nextEpoch,
        mediaIdentity: __aniliMediaIdentity(video),
        mediaGeneration: __aniliMediaGeneration(video),
        desiredPlaying: desiredPlaying === true
      };
      return nextEpoch;
    }
    function __aniliPlaybackMutationIsCurrent(video, epoch) {
      var current = window.__aniliPlaybackMutationState;
      return !!video && !!current && current.epoch === epoch &&
        current.mediaIdentity === __aniliMediaIdentity(video) &&
        current.mediaGeneration === __aniliMediaGeneration(video);
    }
    function __aniliReconcileStaleResumePlay(video, staleEpoch) {
      if (!video) return;
      var current = window.__aniliPlaybackMutationState;
      var selected = findContentVideo();
      var newerExactMutationWantsPlaying = !!current && current.epoch > staleEpoch &&
        current.desiredPlaying === true &&
        current.mediaIdentity === __aniliMediaIdentity(video) &&
        current.mediaGeneration === __aniliMediaGeneration(video);
      if (selected === video && newerExactMutationWantsPlaying) return;
      try { video.pause(); } catch (e) { /* detached or replaced media */ }
    }
""".trimIndent()
