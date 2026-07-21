package com.miruronative.data.remote

import com.miruronative.data.model.AniSkipInterval
import com.miruronative.data.model.AniSkipRelationRange
import com.miruronative.data.model.AniSkipRelationRule
import com.miruronative.data.model.AniSkipRelationTarget
import com.miruronative.data.model.AniSkipSegment
import com.miruronative.data.model.AniSkipType
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** AniSkip v2 community segments and MAL episode relation rules. */
class AniSkipClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    /**
     * Returns the best community contribution for every AniSkip v2 category.
     *
     * A real, known [episodeLengthSeconds] is mandatory. Querying with zero asks AniSkip for all
     * encodes and can silently select markers from an incompatible cut of the episode.
     */
    fun skipTimes(
        malId: Int,
        episode: Double,
        episodeLengthSeconds: Double,
    ): List<AniSkipSegment> {
        val request = Request.Builder()
            .url(aniSkipTimesUrl(malId, episode, episodeLengthSeconds))
            .header("Accept", "application/json")
            .build()
        val payload = client.newCall(request).execute().use { response ->
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) error("AniSkip HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        return parseAniSkipSegments(json, payload)
    }

    /** Episode-number redirects used for split cours, specials, and continuous numbering. */
    fun relationRules(malId: Int): List<AniSkipRelationRule> {
        require(malId > 0) { "MAL id must be positive" }
        val request = Request.Builder()
            .url("$ANI_SKIP_API_BASE/relation-rules/$malId")
            .header("Accept", "application/json")
            .build()
        val payload = client.newCall(request).execute().use { response ->
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) error("AniSkip relation rules HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        return parseAniSkipRelationRules(json, payload)
    }
}

internal val ANI_SKIP_V2_TYPES: List<AniSkipType> = listOf(
    AniSkipType.OP,
    AniSkipType.ED,
    AniSkipType.MIXED_OP,
    AniSkipType.MIXED_ED,
    AniSkipType.RECAP,
)

internal fun aniSkipTimesUrl(
    malId: Int,
    episode: Double,
    episodeLengthSeconds: Double,
): HttpUrl {
    require(malId > 0) { "MAL id must be positive" }
    require(episode.isFinite() && episode >= 0.0) { "Episode must be a finite non-negative number" }
    require(episodeLengthSeconds.isFinite() && episodeLengthSeconds > 0.0) {
        "A positive, finite episode length is required"
    }
    return "$ANI_SKIP_API_BASE/skip-times/$malId/${canonicalAniSkipEpisode(episode)}"
        .toHttpUrl()
        .newBuilder()
        .apply {
            ANI_SKIP_V2_TYPES.forEach { addQueryParameter("types[]", it.apiValue) }
            addQueryParameter("episodeLength", String.format(Locale.US, "%.3f", episodeLengthSeconds))
        }
        .build()
}

internal fun parseAniSkipSegments(json: Json, payload: String): List<AniSkipSegment> {
    val root = json.parseToJsonElement(payload) as? JsonObject ?: return emptyList()
    if ((root["found"] as? JsonPrimitive)?.booleanOrNull != true) return emptyList()
    return (root["results"] as? JsonArray).orEmpty().mapNotNull { element ->
        val result = element as? JsonObject ?: return@mapNotNull null
        val interval = result["interval"] as? JsonObject ?: return@mapNotNull null
        val start = (interval["startTime"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val end = (interval["endTime"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val type = AniSkipType.fromApiValue(
            (result["skipType"] as? JsonPrimitive)?.contentOrNull,
        ) ?: return@mapNotNull null
        val skipId = (result["skipId"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val referenceDuration = (result["episodeLength"] as? JsonPrimitive)?.doubleOrNull
            ?: return@mapNotNull null
        if (!start.isFinite() || !end.isFinite() || start < 0.0 || end <= start) return@mapNotNull null
        if (!referenceDuration.isFinite() || referenceDuration <= 0.0) return@mapNotNull null
        AniSkipSegment(
            type = type,
            interval = AniSkipInterval(startSeconds = start, endSeconds = end),
            referenceDurationSeconds = referenceDuration,
            skipId = skipId,
        )
    }
}

internal fun parseAniSkipRelationRules(json: Json, payload: String): List<AniSkipRelationRule> {
    val root = json.parseToJsonElement(payload) as? JsonObject ?: return emptyList()
    if ((root["found"] as? JsonPrimitive)?.booleanOrNull != true) return emptyList()
    return (root["rules"] as? JsonArray).orEmpty().mapNotNull { element ->
        val rule = element as? JsonObject ?: return@mapNotNull null
        val from = rule["from"] as? JsonObject ?: return@mapNotNull null
        val to = rule["to"] as? JsonObject ?: return@mapNotNull null
        val fromStart = (from["start"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val fromEnd = when (val value = from["end"]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.intOrNull ?: return@mapNotNull null
            else -> return@mapNotNull null
        }
        val toMalId = (to["malId"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val toStart = (to["start"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val toEnd = when (val value = to["end"]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.intOrNull ?: return@mapNotNull null
            else -> return@mapNotNull null
        }
        if (fromStart < 0 || fromEnd != null && fromEnd < fromStart) return@mapNotNull null
        if (toMalId <= 0 || toStart < 0 || toEnd != null && toEnd < toStart) return@mapNotNull null
        AniSkipRelationRule(
            from = AniSkipRelationRange(start = fromStart, end = fromEnd),
            to = AniSkipRelationTarget(malId = toMalId, start = toStart, end = toEnd),
        )
    }
}

private const val ANI_SKIP_API_BASE = "https://api.aniskip.com/v2"
