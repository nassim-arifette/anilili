package com.miruronative.playback

import com.miruronative.data.model.StreamItem
import java.net.URI
import kotlin.math.abs

internal sealed interface CastSourceDecision {
    data class Ready(
        val stream: StreamItem,
        val switchesSource: Boolean,
    ) : CastSourceDecision

    data class Blocked(val reason: CastBlockReason) : CastSourceDecision
}

internal enum class CastBlockReason(val userMessage: String) {
    PLAYLIST_DECRYPTION(
        "Cast is unavailable for this source because its playlist is decrypted inside AniLili+.",
    ),
    REQUEST_HEADERS(
        "Cast is unavailable because the TV cannot send this source's required Referer/Origin headers.",
    ),
    UNSUPPORTED_URL(
        "Cast is unavailable because this source is not a public HTTP media URL.",
    ),
    NO_COMPATIBLE_SOURCE(
        "No Cast-compatible source is available for this episode. Try screen mirroring instead.",
    ),
}

internal fun chooseCastSource(
    active: StreamItem,
    candidates: List<StreamItem>,
): CastSourceDecision {
    if (active.isCastSafe()) {
        return CastSourceDecision.Ready(
            stream = active,
            switchesSource = false,
        )
    }

    val activeHeight = active.height ?: declaredCastHeight(active.quality)
    val alternate = candidates.asSequence()
        .filterNot(StreamItem::isEmbed)
        .distinctBy(StreamItem::url)
        .filter(StreamItem::isCastSafe)
        .sortedWith(
            compareBy<StreamItem> {
                val height = it.height ?: declaredCastHeight(it.quality)
                if (activeHeight == null || height == null) Int.MAX_VALUE else abs(activeHeight - height)
            }.thenByDescending { it.height ?: declaredCastHeight(it.quality) ?: 0 },
        )
        .firstOrNull()

    if (alternate != null) {
        return CastSourceDecision.Ready(
            stream = alternate,
            switchesSource = alternate.url != active.url,
        )
    }

    val reason = when {
        !active.playlistKey.isNullOrBlank() -> CastBlockReason.PLAYLIST_DECRYPTION
        !active.referer.isNullOrBlank() -> CastBlockReason.REQUEST_HEADERS
        !active.hasPublicMediaUrl() -> CastBlockReason.UNSUPPORTED_URL
        else -> CastBlockReason.NO_COMPATIBLE_SOURCE
    }
    return CastSourceDecision.Blocked(reason)
}

internal fun StreamItem.isCastSafe(): Boolean =
    !isEmbed && playlistKey.isNullOrBlank() && referer.isNullOrBlank() && hasPublicMediaUrl()

private fun StreamItem.hasPublicMediaUrl(): Boolean = runCatching {
    val uri = URI(url)
    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
        !uri.host.isNullOrBlank()
}.getOrDefault(false)

private fun declaredCastHeight(quality: String?): Int? =
    quality?.lowercase()?.let { Regex("(\\d{3,4})p?").find(it)?.groupValues?.get(1)?.toIntOrNull() }
