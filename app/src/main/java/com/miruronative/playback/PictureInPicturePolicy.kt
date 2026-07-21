package com.miruronative.playback

import kotlin.math.roundToInt

internal const val PICTURE_IN_PICTURE_MIN_API = 26
internal const val PICTURE_IN_PICTURE_AUTO_ENTER_API = 31

internal data class PictureInPictureAspectRatio(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0)
    }

    companion object {
        val DEFAULT = PictureInPictureAspectRatio(16, 9)
    }
}

/** Screen-space video bounds used by Android to animate the correct source snapshot into PiP. */
internal data class PictureInPictureSourceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val isUsable: Boolean
        get() = right > left && bottom > top
}

/** Process-wide facts needed to make a PiP decision without consulting Compose navigation. */
internal data class PictureInPicturePlaybackSnapshot(
    val isPlaying: Boolean = false,
    val hasNativeSurface: Boolean = false,
    val playbackRoute: PlaybackRoute = PlaybackRoute.LOCAL,
    val aspectRatio: PictureInPictureAspectRatio = PictureInPictureAspectRatio.DEFAULT,
    val sourceRect: PictureInPictureSourceRect? = null,
)

internal enum class PictureInPictureEntryMode {
    DISABLED,
    USER_LEAVE_HINT,
    AUTO_ENTER,
}

internal fun isPictureInPictureEligible(
    snapshot: PictureInPicturePlaybackSnapshot,
    isTv: Boolean,
): Boolean = !isTv &&
    snapshot.hasNativeSurface &&
    snapshot.isPlaying &&
    snapshot.playbackRoute == PlaybackRoute.LOCAL

/**
 * Android 12+ owns the Home-gesture transition through auto-enter. Older supported versions need
 * an explicit entry from Activity.onUserLeaveHint().
 */
internal fun pictureInPictureEntryMode(
    snapshot: PictureInPicturePlaybackSnapshot,
    sdkInt: Int,
    isTv: Boolean,
    isAlreadyInPictureInPicture: Boolean,
): PictureInPictureEntryMode = when {
    sdkInt < PICTURE_IN_PICTURE_MIN_API ||
        isAlreadyInPictureInPicture ||
        !isPictureInPictureEligible(snapshot, isTv) -> PictureInPictureEntryMode.DISABLED
    sdkInt >= PICTURE_IN_PICTURE_AUTO_ENTER_API -> PictureInPictureEntryMode.AUTO_ENTER
    else -> PictureInPictureEntryMode.USER_LEAVE_HINT
}

/** A real PiP activity remains the visible owner of native playback and must not be paused. */
internal fun shouldPausePlaybackOnActivityStop(isActuallyInPictureInPicture: Boolean): Boolean =
    !isActuallyInPictureInPicture

/** Produces a safe Android PiP ratio, falling back when video metadata is absent or unsupported. */
internal fun pictureInPictureAspectRatio(
    videoWidth: Int,
    videoHeight: Int,
    pixelWidthHeightRatio: Float,
): PictureInPictureAspectRatio {
    if (
        videoWidth <= 0 ||
        videoHeight <= 0 ||
        !pixelWidthHeightRatio.isFinite() ||
        pixelWidthHeightRatio <= 0f
    ) {
        return PictureInPictureAspectRatio.DEFAULT
    }
    val adjustedWidth = (videoWidth.toDouble() * pixelWidthHeightRatio).roundToInt()
    if (adjustedWidth <= 0) return PictureInPictureAspectRatio.DEFAULT
    val ratio = adjustedWidth.toDouble() / videoHeight
    // Android rejects ratios outside [1 / 2.39, 2.39] with IllegalArgumentException.
    if (ratio < 1.0 / 2.39 || ratio > 2.39) return PictureInPictureAspectRatio.DEFAULT
    val divisor = greatestCommonDivisor(adjustedWidth, videoHeight)
    return PictureInPictureAspectRatio(adjustedWidth / divisor, videoHeight / divisor)
}

private tailrec fun greatestCommonDivisor(first: Int, second: Int): Int =
    if (second == 0) first else greatestCommonDivisor(second, first % second)
