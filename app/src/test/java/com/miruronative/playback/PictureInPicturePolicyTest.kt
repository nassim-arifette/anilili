package com.miruronative.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureInPicturePolicyTest {
    private val eligiblePlayback = PictureInPicturePlaybackSnapshot(
        isPlaying = true,
        hasNativeSurface = true,
        playbackRoute = PlaybackRoute.LOCAL,
    )

    @Test
    fun `only playing native local video on a non-TV device is eligible`() {
        assertTrue(isPictureInPictureEligible(eligiblePlayback, isTv = false))
        assertFalse(isPictureInPictureEligible(eligiblePlayback.copy(isPlaying = false), isTv = false))
        assertFalse(isPictureInPictureEligible(eligiblePlayback.copy(hasNativeSurface = false), isTv = false))
        assertFalse(
            isPictureInPictureEligible(
                eligiblePlayback.copy(playbackRoute = PlaybackRoute.REMOTE),
                isTv = false,
            ),
        )
        assertFalse(isPictureInPictureEligible(eligiblePlayback, isTv = true))
    }

    @Test
    fun `Android 12 and newer use auto-enter only while eligible`() {
        assertEquals(
            PictureInPictureEntryMode.AUTO_ENTER,
            pictureInPictureEntryMode(
                snapshot = eligiblePlayback,
                sdkInt = 31,
                isTv = false,
                isAlreadyInPictureInPicture = false,
            ),
        )
        assertEquals(
            PictureInPictureEntryMode.DISABLED,
            pictureInPictureEntryMode(
                snapshot = eligiblePlayback.copy(playbackRoute = PlaybackRoute.REMOTE),
                sdkInt = 35,
                isTv = false,
                isAlreadyInPictureInPicture = false,
            ),
        )
    }

    @Test
    fun `Android 26 through 30 enter only from user-leave hint`() {
        (26..30).forEach { sdkInt ->
            assertEquals(
                PictureInPictureEntryMode.USER_LEAVE_HINT,
                pictureInPictureEntryMode(
                    snapshot = eligiblePlayback,
                    sdkInt = sdkInt,
                    isTv = false,
                    isAlreadyInPictureInPicture = false,
                ),
            )
        }
        assertEquals(
            PictureInPictureEntryMode.DISABLED,
            pictureInPictureEntryMode(
                snapshot = eligiblePlayback,
                sdkInt = 25,
                isTv = false,
                isAlreadyInPictureInPicture = false,
            ),
        )
    }

    @Test
    fun `entry is disabled after the activity is already in PiP`() {
        assertEquals(
            PictureInPictureEntryMode.DISABLED,
            pictureInPictureEntryMode(
                snapshot = eligiblePlayback,
                sdkInt = 35,
                isTv = false,
                isAlreadyInPictureInPicture = true,
            ),
        )
    }

    @Test
    fun `activity stop pauses ordinary background playback but not real PiP`() {
        assertTrue(shouldPausePlaybackOnActivityStop(isActuallyInPictureInPicture = false))
        assertFalse(shouldPausePlaybackOnActivityStop(isActuallyInPictureInPicture = true))
    }

    @Test
    fun `video dimensions produce reduced PiP aspect ratios`() {
        assertEquals(
            PictureInPictureAspectRatio(16, 9),
            pictureInPictureAspectRatio(1920, 1080, 1f),
        )
        assertEquals(
            PictureInPictureAspectRatio(4, 3),
            pictureInPictureAspectRatio(720, 576, 16f / 15f),
        )
    }

    @Test
    fun `missing and Android-invalid dimensions use the safe default ratio`() {
        assertEquals(
            PictureInPictureAspectRatio.DEFAULT,
            pictureInPictureAspectRatio(0, 0, 1f),
        )
        assertEquals(
            PictureInPictureAspectRatio.DEFAULT,
            pictureInPictureAspectRatio(4000, 200, 1f),
        )
    }

    @Test
    fun `source rectangle rejects empty snapshot bounds`() {
        assertTrue(PictureInPictureSourceRect(10, 20, 1010, 620).isUsable)
        assertFalse(PictureInPictureSourceRect(10, 20, 10, 620).isUsable)
        assertFalse(PictureInPictureSourceRect(10, 20, 1010, 20).isUsable)
    }
}
