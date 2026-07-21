package com.miruronative.ui.watch

// Keep this aligned with EmbedEndPolicy's two-minute content plausibility floor.
internal const val MIN_EMBED_CONTENT_DURATION_SEC = 120.0
internal const val EMBED_VIDEO_SWITCH_MARGIN = 250.0
internal const val MIN_EMBED_VISUAL_OVERLAP_RATIO = 0.65

internal data class EmbedVideoCandidate(
    val id: String,
    val durationSec: Double,
    val pixelArea: Long,
    val visibleArea: Long,
    val readyState: Int,
    val hasSource: Boolean,
    val isPlaying: Boolean,
    val looksLikeAd: Boolean,
)

internal data class EmbedVideoBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

/** Only a second plausible long-form video covering most of the selected video is hidden. */
internal fun shouldSuppressCompetingEmbedVideo(
    candidate: EmbedVideoCandidate,
    selectedBounds: EmbedVideoBounds,
    candidateBounds: EmbedVideoBounds,
    sameOwnerDocument: Boolean = true,
    selectionVerified: Boolean = true,
): Boolean = selectionVerified && sameOwnerDocument && embedVideoCandidateScore(candidate) != null &&
    embedVideoOverlapRatio(selectedBounds, candidateBounds) >= MIN_EMBED_VISUAL_OVERLAP_RATIO

private fun embedVideoOverlapRatio(first: EmbedVideoBounds, second: EmbedVideoBounds): Double {
    val firstWidth = (first.right - first.left).coerceAtLeast(0.0)
    val firstHeight = (first.bottom - first.top).coerceAtLeast(0.0)
    val secondWidth = (second.right - second.left).coerceAtLeast(0.0)
    val secondHeight = (second.bottom - second.top).coerceAtLeast(0.0)
    val smallerArea = minOf(firstWidth * firstHeight, secondWidth * secondHeight)
    if (smallerArea <= 0.0) return 0.0
    val overlapWidth = (minOf(first.right, second.right) - maxOf(first.left, second.left))
        .coerceAtLeast(0.0)
    val overlapHeight = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top))
        .coerceAtLeast(0.0)
    return (overlapWidth * overlapHeight / smallerArea).coerceIn(0.0, 1.0)
}

internal fun embedVideoCandidateScore(candidate: EmbedVideoCandidate): Double? {
    if (!candidate.durationSec.isFinite() || candidate.durationSec < MIN_EMBED_CONTENT_DURATION_SEC) {
        return null
    }
    if (!candidate.hasSource || candidate.looksLikeAd) return null
    return candidate.durationSec.coerceAtMost(7_200.0) +
        candidate.pixelArea.coerceAtMost(2_073_600L) / 1_000.0 +
        candidate.visibleArea.coerceAtMost(2_073_600L) / 2_000.0 +
        candidate.readyState.coerceIn(0, 4) * 75.0 +
        if (candidate.isPlaying) 200.0 else 0.0
}

/** Keeps an observed-playing lock absolute; only an unverified initial guess may be replaced. */
internal fun selectEmbedContentVideo(
    candidates: List<EmbedVideoCandidate>,
    lockedId: String?,
    lockedVerified: Boolean = true,
): EmbedVideoCandidate? {
    val scored = candidates.mapNotNull { candidate ->
        embedVideoCandidateScore(candidate)?.let { score -> candidate to score }
    }
    val best = scored.maxByOrNull { it.second } ?: return null
    val locked = scored.firstOrNull { it.first.id == lockedId }
    val bestPlaying = scored.filter { it.first.isPlaying }.maxByOrNull { it.second }
    if (locked != null && lockedVerified) return locked.first
    if (locked != null && !lockedVerified && bestPlaying != null) return bestPlaying.first
    return if (locked == null || best.second > locked.second + EMBED_VIDEO_SWITCH_MARGIN) {
        best.first
    } else {
        locked.first
    }
}

/**
 * Shared browser-side selector installed into telemetry and every content command. It considers
 * every reachable frame, rejects short/ad-labelled media and retains a stable lock to prevent a
 * newly inserted preroll from stealing controls from the episode.
 */
internal fun embedContentVideoSelectorJs(): String = """
    function __aniliCollectMedia(root, selector, out) {
      if (!root) return;
      var media = root.querySelectorAll(selector);
      for (var i = 0; i < media.length; i++) out.push(media[i]);
      var frames = root.querySelectorAll('iframe');
      for (var j = 0; j < frames.length; j++) {
        try { __aniliCollectMedia(frames[j].contentDocument, selector, out); } catch (e) { /* cross-origin */ }
      }
    }
    function __aniliCollectVideos(root, out) {
      __aniliCollectMedia(root, 'video', out);
    }
    function __aniliLooksLikeAd(video) {
      var source = video.currentSrc || video.src || '';
      var marker = [video.id, video.className, video.getAttribute('data-ad'), source]
        .join(' ').toLowerCase();
      return /(^|[^a-z])(ad|ads|advert|advertisement|preroll|pre-roll|vast|ima)([^a-z]|$)/.test(marker);
    }
    function __aniliMediaIdentity(video) {
      if (!video) return '';
      var source = video.currentSrc || video.src || '';
      var duration = isFinite(video.duration) ? Math.round(video.duration * 1000) : 'unknown';
      return source + '|' + duration;
    }
    function __aniliVideoScore(video) {
      var duration = Number(video.duration);
      var source = video.currentSrc || video.src || '';
      if (!isFinite(duration) || duration < $MIN_EMBED_CONTENT_DURATION_SEC || !source || __aniliLooksLikeAd(video)) {
        return null;
      }
      var rect = video.getBoundingClientRect ? video.getBoundingClientRect() : { width: 0, height: 0 };
      var pixels = Math.min(2073600, Math.max(0, video.videoWidth * video.videoHeight));
      var visible = Math.min(2073600, Math.max(0, rect.width * rect.height));
      return Math.min(7200, duration) + pixels / 1000 + visible / 2000 +
        Math.max(0, Math.min(4, video.readyState || 0)) * 75 + (video.paused ? 0 : 200);
    }
    function findContentVideo() {
      var candidates = [];
      __aniliCollectVideos(document, candidates);
      var best = null;
      var bestScore = -Infinity;
      var bestPlaying = null;
      var bestPlayingScore = -Infinity;
      for (var i = 0; i < candidates.length; i++) {
        var score = __aniliVideoScore(candidates[i]);
        if (score !== null && score > bestScore) { best = candidates[i]; bestScore = score; }
        if (score !== null && !candidates[i].paused && score > bestPlayingScore) {
          bestPlaying = candidates[i]; bestPlayingScore = score;
        }
      }
      var locked = window.__aniliContentVideo;
      var lockedScore = locked && candidates.indexOf(locked) >= 0 ? __aniliVideoScore(locked) : null;
      var lockedVerified = window.__aniliContentVideoVerified === true;
      if (lockedScore !== null && lockedVerified) return locked;
      if (lockedScore !== null && !lockedVerified && bestPlaying) {
        window.__aniliContentVideo = bestPlaying;
        window.__aniliContentVideoVerified = true;
        return bestPlaying;
      }
      if (lockedScore !== null && (!best || bestScore <= lockedScore + $EMBED_VIDEO_SWITCH_MARGIN)) {
        if (!locked.paused) window.__aniliContentVideoVerified = true;
        return locked;
      }
      window.__aniliContentVideo = best;
      window.__aniliContentVideoVerified = !!best && !best.paused;
      return best;
    }
    function __aniliPauseAllMedia() {
      var media = [];
      __aniliCollectMedia(document, 'video,audio', media);
      for (var i = 0; i < media.length; i++) {
        try { media[i].pause(); } catch (e) { /* detached media */ }
      }
    }
    function __aniliVisualOverlapRatio(first, second) {
      if (!first || !second || !first.getBoundingClientRect || !second.getBoundingClientRect) return 0;
      if (first.ownerDocument !== second.ownerDocument) return 0;
      var a = first.getBoundingClientRect();
      var b = second.getBoundingClientRect();
      var areaA = Math.max(0, a.width) * Math.max(0, a.height);
      var areaB = Math.max(0, b.width) * Math.max(0, b.height);
      var smaller = Math.min(areaA, areaB);
      if (smaller <= 0) return 0;
      var width = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
      var height = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
      return width * height / smaller;
    }
    function __aniliSetCompetingVideoSuppressed(video, suppressed) {
      if (!video || !video.style) return;
      if (suppressed) {
        if (!video.__aniliOriginalCompetingStyle) {
          video.__aniliOriginalCompetingStyle = {
            visibility: video.style.getPropertyValue('visibility'),
            visibilityPriority: video.style.getPropertyPriority('visibility'),
            pointerEvents: video.style.getPropertyValue('pointer-events'),
            pointerEventsPriority: video.style.getPropertyPriority('pointer-events')
          };
        }
        video.style.setProperty('visibility', 'hidden', 'important');
        video.style.setProperty('pointer-events', 'none', 'important');
      } else if (video.__aniliOriginalCompetingStyle) {
        var original = video.__aniliOriginalCompetingStyle;
        if (original.visibility) video.style.setProperty('visibility', original.visibility, original.visibilityPriority);
        else video.style.removeProperty('visibility');
        if (original.pointerEvents) video.style.setProperty('pointer-events', original.pointerEvents, original.pointerEventsPriority);
        else video.style.removeProperty('pointer-events');
        delete video.__aniliOriginalCompetingStyle;
      }
    }
    function __aniliPauseCompetingMedia(content) {
      var media = [];
      __aniliCollectMedia(document, 'video,audio', media);
      var selectionVerified = window.__aniliContentVideo === content &&
        window.__aniliContentVideoVerified === true;
      for (var i = 0; i < media.length; i++) {
        var candidate = media[i];
        if (candidate === content || !content) {
          __aniliSetCompetingVideoSuppressed(candidate, false);
          continue;
        }
        if (!selectionVerified) {
          __aniliSetCompetingVideoSuppressed(candidate, false);
          continue;
        }
        try {
          candidate.pause();
          var plausibleOverlappingVideo = candidate.tagName &&
            candidate.tagName.toLowerCase() === 'video' &&
            __aniliVideoScore(candidate) !== null &&
            __aniliVisualOverlapRatio(content, candidate) >= $MIN_EMBED_VISUAL_OVERLAP_RATIO;
          __aniliSetCompetingVideoSuppressed(candidate, plausibleOverlappingVideo);
          if (!candidate.__aniliCompetingMediaGuard) {
            candidate.__aniliCompetingMediaGuard = true;
            candidate.addEventListener('play', function(event) {
              var started = event.currentTarget;
              var selected = findContentVideo();
              if (selected && started !== selected) started.pause();
            }, true);
          }
        } catch (e) { /* detached media */ }
      }
    }
""".trimIndent()
