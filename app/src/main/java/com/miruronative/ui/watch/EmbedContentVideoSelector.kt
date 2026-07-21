package com.miruronative.ui.watch

// Keep this aligned with EmbedEndPolicy's two-minute content plausibility floor.
internal const val MIN_EMBED_CONTENT_DURATION_SEC = 120.0
internal const val EMBED_VIDEO_SWITCH_MARGIN = 250.0

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

/** Keeps a valid content lock unless another candidate wins by a meaningful margin. */
internal fun selectEmbedContentVideo(
    candidates: List<EmbedVideoCandidate>,
    lockedId: String?,
): EmbedVideoCandidate? {
    val scored = candidates.mapNotNull { candidate ->
        embedVideoCandidateScore(candidate)?.let { score -> candidate to score }
    }
    val best = scored.maxByOrNull { it.second } ?: return null
    val locked = scored.firstOrNull { it.first.id == lockedId }
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
    function __aniliCollectVideos(root, out) {
      if (!root) return;
      var videos = root.querySelectorAll('video');
      for (var i = 0; i < videos.length; i++) out.push(videos[i]);
      var frames = root.querySelectorAll('iframe');
      for (var j = 0; j < frames.length; j++) {
        try { __aniliCollectVideos(frames[j].contentDocument, out); } catch (e) { /* cross-origin */ }
      }
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
      for (var i = 0; i < candidates.length; i++) {
        var score = __aniliVideoScore(candidates[i]);
        if (score !== null && score > bestScore) { best = candidates[i]; bestScore = score; }
      }
      var locked = window.__aniliContentVideo;
      var lockedScore = locked && candidates.indexOf(locked) >= 0 ? __aniliVideoScore(locked) : null;
      if (lockedScore !== null && (!best || bestScore <= lockedScore + $EMBED_VIDEO_SWITCH_MARGIN)) return locked;
      window.__aniliContentVideo = best;
      return best;
    }
""".trimIndent()
