package com.miruronative.data.library

import kotlinx.serialization.Serializable

/**
 * One history record per anime: the last episode watched plus an optional continuation target.
 *
 * [episodeWatchProgress] is deliberately sparse. Remote list services expose a single aggregate
 * episode number, but the device must not infer that opening episode 90 means episodes 1-89 were
 * actually played. Older saved JSON has no map and remains valid through the empty default.
 */
@Serializable
data class HistoryEntry(
    val anilistId: Int,
    val title: String,
    val cover: String?,
    val episodeNumber: Double,
    val episodeTitle: String? = null,
    val provider: String,
    val category: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /**
     * Episode to open after a naturally completed, non-final episode. The default keeps history
     * written by older app versions compatible and represents ordinary in-progress playback.
     */
    val continuationEpisodeNumber: Double? = null,
    /** Completed final episodes stay in viewing history but are hidden from Continue Watching. */
    val completed: Boolean = false,
    /** Actual locally observed progress, keyed by the canonical string form of an episode number. */
    val episodeWatchProgress: Map<String, Float> = emptyMap(),
    val updatedAt: Long = 0,
) {
    private val activeContinuationEpisodeNumber: Double?
        get() = continuationEpisodeNumber?.takeIf {
            !completed && it.isFinite() && it != episodeNumber
        }

    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    private val currentEpisodeWatchFraction: Float
        get() = if (completed || activeContinuationEpisodeNumber != null) 1f else progressFraction

    /** Locally observed progress for exactly [episodeNumber], without sequential inference. */
    fun watchFractionFor(episodeNumber: Double): Float {
        if (!episodeNumber.isFinite()) return 0f
        val recorded = episodeWatchProgress[episodeNumber.episodeProgressKey()]
            ?.normalizedWatchFraction()
            ?: 0f
        val current = if (this.episodeNumber == episodeNumber) currentEpisodeWatchFraction else 0f
        return maxOf(recorded, current)
    }

    /** Fraction of the whole series that this device has actually observed. */
    fun seriesWatchFraction(totalEpisodes: Int): Float {
        if (totalEpisodes <= 0) return 0f
        val observed = linkedMapOf<Double, Float>()
        episodeWatchProgress.forEach { (key, fraction) ->
            val episode = key.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return@forEach
            if (episode <= 0.0 || episode > totalEpisodes.toDouble()) return@forEach
            observed[episode] = maxOf(observed[episode] ?: 0f, fraction.normalizedWatchFraction())
        }
        if (episodeNumber > 0.0 && episodeNumber <= totalEpisodes.toDouble()) {
            observed[episodeNumber] = maxOf(
                observed[episodeNumber] ?: 0f,
                currentEpisodeWatchFraction,
            )
        }
        return (observed.values.sum() / totalEpisodes).coerceIn(0f, 1f)
    }

    val episodeLabel: String
        get() = episodeNumber.toEpisodeLabel()

    /** Episode and position represented by Continue Watching entry points. */
    val continueEpisodeNumber: Double
        get() = activeContinuationEpisodeNumber ?: episodeNumber

    val continueEpisodeLabel: String
        get() = continueEpisodeNumber.toEpisodeLabel()

    val continuePositionMs: Long
        get() = if (activeContinuationEpisodeNumber != null) 0L else positionMs

    val continueDurationMs: Long
        get() = if (activeContinuationEpisodeNumber != null) 0L else durationMs

    val continueProgressFraction: Float
        get() = if (activeContinuationEpisodeNumber != null) 0f else progressFraction

    val hasContinuationTarget: Boolean
        get() = activeContinuationEpisodeNumber != null

    val belongsInContinueWatching: Boolean
        get() = !completed

    fun resumePositionFor(episode: Double): Long? = when {
        completed -> null
        activeContinuationEpisodeNumber == episode -> 0L
        activeContinuationEpisodeNumber == null && episodeNumber == episode -> positionMs
        else -> null
    }
}

/**
 * Carries forward only progress that was actually observed on this device. The current resume
 * fields remain owned by [incoming]; the sparse map survives episode changes and records a
 * naturally completed episode as complete even when its final timestamp is slightly short.
 */
internal fun mergeHistoryEntry(existing: HistoryEntry?, incoming: HistoryEntry): HistoryEntry {
    val progress = linkedMapOf<String, Float>()

    fun record(episodeNumber: Double, fraction: Float) {
        if (!episodeNumber.isFinite()) return
        val normalized = fraction.normalizedWatchFraction()
        if (normalized <= 0f) return
        val key = episodeNumber.episodeProgressKey()
        progress[key] = maxOf(progress[key] ?: 0f, normalized)
    }

    fun recordSaved(entry: HistoryEntry) {
        entry.episodeWatchProgress.forEach { (key, fraction) ->
            key.toDoubleOrNull()?.let { record(it, fraction) }
        }
        record(entry.episodeNumber, entry.watchFractionFor(entry.episodeNumber))
    }

    existing?.takeIf { it.anilistId == incoming.anilistId }?.let(::recordSaved)
    recordSaved(incoming)
    return incoming.copy(episodeWatchProgress = progress)
}

private fun Double.episodeProgressKey(): String = toString()

private fun Float.normalizedWatchFraction(): Float =
    if (isFinite()) coerceIn(0f, 1f) else 0f

private fun Double.toEpisodeLabel(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

/** A saved series the user wants to watch. */
@Serializable
data class WatchlistEntry(
    val anilistId: Int,
    val title: String,
    val cover: String?,
    val format: String? = null,
    val averageScore: Int? = null,
    val addedAt: Long = 0,
)

internal fun mergeWatchlistEntries(
    local: List<WatchlistEntry>,
    fromAniList: List<WatchlistEntry>,
    addedAt: Long = System.currentTimeMillis(),
): List<WatchlistEntry> {
    if (fromAniList.isEmpty()) return local
    val remoteById = fromAniList.associateBy { it.anilistId }
    val localIds = local.mapTo(mutableSetOf()) { it.anilistId }
    return buildList {
        local.forEach { saved ->
            val remote = remoteById[saved.anilistId]
            add(remote?.copy(addedAt = saved.addedAt) ?: saved)
        }
        fromAniList.forEach { remote ->
            if (remote.anilistId !in localIds) add(remote.copy(addedAt = addedAt))
        }
    }.distinctBy { it.anilistId }
}
