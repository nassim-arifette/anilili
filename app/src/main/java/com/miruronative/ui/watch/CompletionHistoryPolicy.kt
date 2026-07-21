package com.miruronative.ui.watch

/**
 * Returns true only when the catalog and AniList total jointly prove that playback reached the
 * final episode of the series. The last episode currently available from a provider is not enough:
 * an airing series can have more episodes scheduled but not released yet.
 */
internal fun isConfirmedFinalSeriesEpisode(
    episodeNumber: Double,
    totalEpisodes: Int?,
    hasNextEpisode: Boolean,
): Boolean {
    if (hasNextEpisode || totalEpisodes == null || totalEpisodes <= 0 || !episodeNumber.isFinite()) {
        return false
    }
    val integralEpisode = episodeNumber.toInt()
    return episodeNumber == integralEpisode.toDouble() && integralEpisode == totalEpisodes
}
