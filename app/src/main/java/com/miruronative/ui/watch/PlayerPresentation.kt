package com.miruronative.ui.watch

/** Identity emitted by the UI only when a real native or embed player is in the composition. */
internal data class PlayerPresentation(
    val generation: Int,
    val anilistId: Int,
    val episodeNumber: Double,
    val provider: String,
    val category: String,
    val streamUrl: String,
)

/**
 * Rejects stale presentations and makes the accepted signal idempotent. A rejected signal is not
 * consumed: the same player can be accepted later once its matching resolution has completed.
 */
internal class PlayerPresentationGate {
    private val accepted = mutableSetOf<PlayerPresentation>()

    fun accept(
        presented: PlayerPresentation,
        current: PlayerPresentation?,
        resolutionPending: Boolean,
    ): Boolean {
        if (resolutionPending || presented != current) return false
        return accepted.add(presented)
    }
}
