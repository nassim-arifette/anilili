package com.miruronative.ui.watch

internal enum class OutroSkipAction {
    NONE,
    SEEK_TO_END,
    NEXT_EPISODE,
}

/** Keeps the independent auto-skip and autoplay settings from silently changing each other. */
internal fun outroSkipAction(
    autoSkip: Boolean,
    autoplay: Boolean,
    hasNextEpisode: Boolean,
    isPlaying: Boolean,
    alreadyHandled: Boolean,
    positionMs: Long,
    startMs: Long?,
    endMs: Long?,
): OutroSkipAction {
    val start = startMs ?: return OutroSkipAction.NONE
    val end = endMs ?: return OutroSkipAction.NONE
    if (!autoSkip || !isPlaying || alreadyHandled || end <= start || positionMs !in start until end) {
        return OutroSkipAction.NONE
    }
    return if (autoplay && hasNextEpisode) {
        OutroSkipAction.NEXT_EPISODE
    } else {
        OutroSkipAction.SEEK_TO_END
    }
}
