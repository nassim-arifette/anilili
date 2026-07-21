package com.miruronative.ui.watch

/**
 * Seeks a directly accessible HTML5 video without changing its play/pause state. Initial resume
 * has a separate script that deliberately starts playback; user seeks and skip actions do not.
 */
internal fun embedSeekJs(targetSec: Double): String = """
    (function() {
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
      try {
        var v = findVideo();
        if (!v) return false;
        var target = $targetSec;
        v.currentTime = isFinite(v.duration) && v.duration > 0 ? Math.min(target, v.duration) : target;
        return true;
      } catch (e) {
        return false;
      }
    })();
""".trimIndent()
