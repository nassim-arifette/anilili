package com.miruronative.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** AniSkip v2's complete set of segment categories. */
@Serializable
enum class AniSkipType(val apiValue: String) {
    @SerialName("op")
    OP("op"),

    @SerialName("ed")
    ED("ed"),

    @SerialName("mixed-op")
    MIXED_OP("mixed-op"),

    @SerialName("mixed-ed")
    MIXED_ED("mixed-ed"),

    @SerialName("recap")
    RECAP("recap"),
    ;

    companion object {
        fun fromApiValue(value: String?): AniSkipType? = entries.firstOrNull { it.apiValue == value }
    }
}

@Serializable
data class AniSkipInterval(
    val startSeconds: Double,
    val endSeconds: Double,
)

/**
 * One contribution selected by AniSkip for the requested duration.
 *
 * [interval] is deliberately kept in the contributor's reference timeline. Consumers should use
 * [AniSkipPlaybackSegment] after the duration offset has been validated and applied.
 */
@Serializable
data class AniSkipSegment(
    val type: AniSkipType,
    val interval: AniSkipInterval,
    val referenceDurationSeconds: Double,
    val skipId: String,
)

/** A raw AniSkip segment paired with its safe interval in the active stream's timeline. */
@Serializable
data class AniSkipPlaybackSegment(
    val source: AniSkipSegment,
    val interval: AniSkipInterval,
) {
    val type: AniSkipType get() = source.type
    val referenceDurationSeconds: Double get() = source.referenceDurationSeconds
    val skipId: String get() = source.skipId
}

@Serializable
data class AniSkipRelationRange(
    val start: Int,
    val end: Int? = null,
)

@Serializable
data class AniSkipRelationTarget(
    val malId: Int,
    val start: Int,
    val end: Int? = null,
)

@Serializable
data class AniSkipRelationRule(
    val from: AniSkipRelationRange,
    val to: AniSkipRelationTarget,
)

/** MAL id and episode after applying a relation rule. Episode numbers remain decimal. */
data class AniSkipEpisodeReference(
    val malId: Int,
    val episode: Double,
)
