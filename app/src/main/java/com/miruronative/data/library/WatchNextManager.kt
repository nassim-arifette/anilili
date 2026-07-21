package com.miruronative.data.library

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.miruronative.data.AppGraph
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.nav.Routes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes the user's in-progress episode to the Android TV launcher's "Continue Watching"
 * (Watch Next) row — the TV-native counterpart of notifications. Selecting the card deep-links
 * straight back into the watch screen at the saved position.
 *
 * Only real Android TV / Google TV launchers read this provider; on phones this is skipped and
 * on Fire TV (which has no TvProvider) every call fails harmlessly inside runCatching.
 */
object WatchNextManager {
    private const val PREFS = "anilili_watch_next"
    private const val PUBLISH_THROTTLE_MS = 60_000L

    /** Progress saves arrive every few seconds; content changes must still reach the launcher. */
    private val lastPublished = ConcurrentHashMap<Int, WatchNextPublishState>()
    private val publishCoordinator = WatchNextPublishCoordinator()

    /**
     * Reserve the ordering ticket synchronously with the history save. Launching the provider I/O
     * on [kotlinx.coroutines.Dispatchers.IO] can otherwise reorder episode A and episode B before
     * [publish] is even entered.
     */
    internal fun preparePublish(entry: HistoryEntry): WatchNextPublishRequest = WatchNextPublishRequest(
        entry = entry,
        ticket = publishCoordinator.register(entry.anilistId),
    )

    internal fun publish(context: Context, request: WatchNextPublishRequest) {
        if (!AppGraph.isTv) return
        publishCoordinator.runIfLatest(request.ticket) {
            val entry = request.entry
            val throttleNowMs = SystemClock.elapsedRealtime()
            val content = entry.watchNextContent()
            val previous = lastPublished[entry.anilistId]
            if (!shouldPublishWatchNext(previous, content, throttleNowMs, PUBLISH_THROTTLE_MS)) {
                return@runIfLatest
            }

            if (publishProgram(context, entry, System.currentTimeMillis())) {
                lastPublished[entry.anilistId] = WatchNextPublishState(content, throttleNowMs)
            }
        }
    }

    // androidx.tvprovider annotates this builder as restricted even though it is the supported
    // integration point for publishing a program to an Android TV launcher's Watch Next row.
    @SuppressLint("RestrictedApi")
    private fun publishProgram(context: Context, entry: HistoryEntry, engagementAtMs: Long): Boolean =
        runCatching {
            val app = context.applicationContext
            val target = entry.watchNextProgramData()
            val watchIntent = Intent(app, com.miruronative.MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(
                    Routes.EXTRA_ROUTE,
                    Routes.watch(entry.anilistId, entry.provider, entry.category, target.episodeLabel),
                )
            }
            val program = WatchNextProgram.Builder()
                .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(engagementAtMs)
                .setTitle(entry.title)
                .setEpisodeTitle(target.episodeTitle)
                .setEpisodeNumber(target.episodeNumber.toInt())
                .setInternalProviderId(entry.anilistId.toString())
                .setIntentUri(Uri.parse(watchIntent.toUri(Intent.URI_INTENT_SCHEME)))
                .apply {
                    entry.cover?.let { setPosterArtUri(Uri.parse(it)) }
                    if (target.durationMs > 0) {
                        setDurationMillis(target.durationMs.toInt())
                        setLastPlaybackPositionMillis(target.positionMs.coerceAtLeast(0).toInt())
                    }
                }
                .build()

            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existingId = prefs.getLong(entry.anilistId.toString(), -1L)
            if (existingId > 0) {
                val updated = app.contentResolver.update(
                    TvContractCompat.buildWatchNextProgramUri(existingId),
                    program.toContentValues(),
                    null,
                    null,
                )
                if (updated > 0) return@runCatching true
            }
            val uri = app.contentResolver.insert(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                program.toContentValues(),
            )
            val id = uri?.lastPathSegment?.toLongOrNull() ?: return@runCatching false
            prefs.edit().putLong(entry.anilistId.toString(), id).apply()
            DiagnosticsLog.event("WatchNext published id=${entry.anilistId} programId=$id")
            true
        }.getOrDefault(false) // Fire TV has no TvProvider; retry on the next progress save.

    fun remove(context: Context, anilistId: Int) {
        if (!AppGraph.isTv) return
        publishCoordinator.cancelAndRun(anilistId) {
            lastPublished.remove(anilistId)
            runCatching {
                val app = context.applicationContext
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val existingId = prefs.getLong(anilistId.toString(), -1L)
                if (existingId > 0) {
                    app.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(existingId), null, null)
                    prefs.edit().remove(anilistId.toString()).apply()
                }
            }
        }
    }

    fun removeAll(context: Context) {
        if (!AppGraph.isTv) return
        publishCoordinator.cancelAllAndRun {
            lastPublished.clear()
            runCatching {
                val app = context.applicationContext
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.all.keys.toList().forEach { key ->
                    val id = prefs.getLong(key, -1L)
                    if (id > 0) {
                        app.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
                    }
                }
                prefs.edit().clear().apply()
            }
        }
    }
}

internal data class WatchNextPublishRequest(
    val entry: HistoryEntry,
    val ticket: WatchNextPublishTicket,
)

internal data class WatchNextPublishTicket(
    val anilistId: Int,
    val generation: Long,
)

/** Serializes TvProvider mutations and rejects work superseded before it gets the lock. */
internal class WatchNextPublishCoordinator {
    private val operationLock = Any()
    private val generations = ConcurrentHashMap<Int, AtomicLong>()

    fun register(anilistId: Int): WatchNextPublishTicket {
        val generation = generations.computeIfAbsent(anilistId) { AtomicLong() }.incrementAndGet()
        return WatchNextPublishTicket(anilistId, generation)
    }

    fun runIfLatest(ticket: WatchNextPublishTicket, operation: () -> Unit): Boolean =
        synchronized(operationLock) {
            if (generations[ticket.anilistId]?.get() != ticket.generation) {
                false
            } else {
                operation()
                true
            }
        }

    fun cancelAndRun(anilistId: Int, operation: () -> Unit) {
        synchronized(operationLock) {
            generations.computeIfAbsent(anilistId) { AtomicLong() }.incrementAndGet()
            operation()
        }
    }

    fun cancelAllAndRun(operation: () -> Unit) {
        synchronized(operationLock) {
            generations.values.forEach { it.incrementAndGet() }
            operation()
        }
    }
}

internal data class WatchNextContent(
    val episodeNumber: Double,
    val provider: String,
    val category: String,
    val isContinuationTarget: Boolean = false,
)

internal data class WatchNextProgramData(
    val episodeNumber: Double,
    val episodeLabel: String,
    val episodeTitle: String,
    val positionMs: Long,
    val durationMs: Long,
)

internal data class WatchNextPublishState(
    val content: WatchNextContent,
    val publishedAtMs: Long,
)

internal fun HistoryEntry.watchNextContent(): WatchNextContent = WatchNextContent(
    episodeNumber = continueEpisodeNumber,
    provider = provider,
    category = category,
    isContinuationTarget = hasContinuationTarget,
)

internal fun HistoryEntry.watchNextProgramData(): WatchNextProgramData = WatchNextProgramData(
    episodeNumber = continueEpisodeNumber,
    episodeLabel = continueEpisodeLabel,
    episodeTitle = episodeTitle.takeUnless { hasContinuationTarget } ?: "Episode $continueEpisodeLabel",
    positionMs = continuePositionMs,
    // The completed episode's duration does not describe the fresh continuation target.
    durationMs = continueDurationMs,
)

internal fun shouldPublishWatchNext(
    previous: WatchNextPublishState?,
    content: WatchNextContent,
    nowMs: Long,
    throttleMs: Long,
): Boolean = previous == null ||
    previous.content != content ||
    nowMs - previous.publishedAtMs >= throttleMs
