package com.miruronative.ui.watch

/** Stable categories for diagnostics; never derived from exception messages or remote content. */
internal fun playerErrorDiagnosticCategory(errorCode: Int): String = when {
    errorCode == MEDIA3_TIMEOUT_ERROR_CODE -> "timeout"
    errorCode in MEDIA3_IO_ERROR_CODES -> "io"
    errorCode in MEDIA3_PARSING_ERROR_CODES -> "parsing"
    errorCode in MEDIA3_DECODER_ERROR_CODES -> "decoder"
    errorCode in MEDIA3_AUDIO_OUTPUT_ERROR_CODES -> "audio-output"
    errorCode in MEDIA3_DRM_ERROR_CODES -> "drm"
    errorCode in MEDIA3_VIDEO_PROCESSING_ERROR_CODES -> "video-processing"
    errorCode in MEDIA3_PLAYER_ERROR_CODES -> "player"
    else -> "unknown"
}

// Media3 reserves each thousand-wide block for one stable error family.
private const val MEDIA3_TIMEOUT_ERROR_CODE = 1003
private val MEDIA3_PLAYER_ERROR_CODES = 1000..1999
private val MEDIA3_IO_ERROR_CODES = 2000..2999
private val MEDIA3_PARSING_ERROR_CODES = 3000..3999
private val MEDIA3_DECODER_ERROR_CODES = 4000..4999
private val MEDIA3_AUDIO_OUTPUT_ERROR_CODES = 5000..5999
private val MEDIA3_DRM_ERROR_CODES = 6000..6999
private val MEDIA3_VIDEO_PROCESSING_ERROR_CODES = 7000..7999
