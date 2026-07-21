package com.miruronative.data.library

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.miruronative.data.reminder.ReleaseSyncScheduler
import com.miruronative.data.AppGraph
import com.miruronative.data.LatestMutationTracker
import com.miruronative.data.MutationTicket
import com.miruronative.data.auth.AccountService
import com.miruronative.data.auth.AccountSessionIdentity
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.auth.MalAuthManager
import com.miruronative.data.auth.currentAccountSession
import com.miruronative.data.auth.isCurrentAccountSession
import com.miruronative.data.auth.jwtSubject
import com.miruronative.data.settings.SettingsStore
import com.miruronative.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * On-device library: watch history (continue-watching + resume position) and watchlist.
 * Persisted as JSON in SharedPreferences; exposed as StateFlows so the UI reacts. No login.
 */
object LibraryStore {
    private const val KEY_HISTORY = "history"
    private const val KEY_WATCHLIST = "watchlist"
    private const val MAX_HISTORY = 100

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchlistSyncVersions = LatestMutationTracker<Int>()
    private val watchlistLock = Any()
    private val remoteSyncQueue = Channel<RemoteWatchlistCommand>(Channel.UNLIMITED)
    private val remoteSyncStartLock = Any()
    private var remoteSyncStarted = false

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history = _history.asStateFlow()

    private val _watchlist = MutableStateFlow<List<WatchlistEntry>>(emptyList())
    val watchlist = _watchlist.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("miruro_library", Context.MODE_PRIVATE)
        _history.value = decodeList(prefs.getString(KEY_HISTORY, null), HistoryEntry.serializer())
        _watchlist.value = decodeList(prefs.getString(KEY_WATCHLIST, null), WatchlistEntry.serializer())
        synchronized(remoteSyncStartLock) {
            if (!remoteSyncStarted) {
                remoteSyncStarted = true
                scope.launch { processRemoteSyncQueue() }
            }
        }
    }

    // ---- history ----

    /** Insert/replace the anime's record (keeps one per anime, most-recent first). */
    fun upsertHistory(entry: HistoryEntry) {
        upsertHistoryInternal(entry, commitToDisk = false)
    }

    /**
     * Persists a history entry to disk before returning. Terminal playback events use this path so
     * an immediate autoplay transition cannot outrun SharedPreferences' asynchronous apply().
     */
    fun upsertHistoryDurably(entry: HistoryEntry): Boolean =
        upsertHistoryInternal(entry, commitToDisk = true)

    private fun upsertHistoryInternal(entry: HistoryEntry, commitToDisk: Boolean): Boolean {
        val stamped = entry.copy(updatedAt = System.currentTimeMillis())
        val updated = buildList {
            add(stamped)
            addAll(_history.value.filter { it.anilistId != entry.anilistId })
        }.take(MAX_HISTORY)
        if (commitToDisk) {
            if (!persist(KEY_HISTORY, updated, HistoryEntry.serializer(), commitToDisk = true)) {
                return false
            }
            _history.value = updated
        } else {
            _history.value = updated
            persist(KEY_HISTORY, updated, HistoryEntry.serializer())
        }
        // TV launchers surface only in-progress titles. remove() invalidates any older queued
        // publish ticket, so an asynchronous progress write cannot resurrect a completed title.
        if (stamped.completed) {
            scope.launch { WatchNextManager.remove(appContext, stamped.anilistId) }
        } else {
            val watchNextRequest = WatchNextManager.preparePublish(stamped)
            scope.launch { WatchNextManager.publish(appContext, watchNextRequest) }
        }
        return true
    }

    fun updateProgress(anilistId: Int, episodeNumber: Double, positionMs: Long, durationMs: Long) {
        val existing = _history.value.firstOrNull { it.anilistId == anilistId } ?: return
        if (existing.episodeNumber != episodeNumber) return
        upsertHistory(existing.copy(positionMs = positionMs, durationMs = durationMs))
    }

    /** Atomically commits a terminal position, continuation target, and series completion state. */
    fun updateProgressDurably(
        anilistId: Int,
        episodeNumber: Double,
        positionMs: Long,
        durationMs: Long,
        continuationEpisodeNumber: Double?,
        completed: Boolean,
    ): Boolean {
        val existing = _history.value.firstOrNull { it.anilistId == anilistId } ?: return false
        if (existing.episodeNumber != episodeNumber) return false
        return upsertHistoryInternal(
            existing.copy(
                positionMs = positionMs,
                durationMs = durationMs,
                continuationEpisodeNumber = continuationEpisodeNumber.takeUnless { completed },
                completed = completed,
            ),
            commitToDisk = true,
        )
    }

    fun historyFor(anilistId: Int): HistoryEntry? = _history.value.firstOrNull { it.anilistId == anilistId }

    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
        scope.launch { WatchNextManager.removeAll(appContext) }
    }

    // ---- watchlist ----

    fun isInWatchlist(anilistId: Int): Boolean =
        synchronized(watchlistLock) { _watchlist.value.any { it.anilistId == anilistId } }

    fun toggleWatchlist(entry: WatchlistEntry) {
        val updated = synchronized(watchlistLock) {
            val current = _watchlist.value
            val next = if (current.any { it.anilistId == entry.anilistId }) {
                current.filter { it.anilistId != entry.anilistId }
            } else {
                listOf(entry.copy(addedAt = System.currentTimeMillis())) + current
            }
            _watchlist.value = next
            persist(KEY_WATCHLIST, next, WatchlistEntry.serializer())
            next
        }
        ReleaseSyncScheduler.runNow(appContext)
        val session = currentAccountSession()
        if (session != null && SettingsStore.syncSavedToAniList.value) {
            val saved = updated.any { it.anilistId == entry.anilistId }
            val ticket = watchlistSyncVersions.begin(listOf(entry.anilistId))
            val command = RemoteWatchlistCommand.Item(
                session = session,
                ticket = ticket,
                anilistId = entry.anilistId,
                saved = saved,
            )
            if (remoteSyncQueue.trySend(command).isFailure) {
                watchlistSyncVersions.complete(ticket)
                DiagnosticsLog.event("${session.service.label} saved sync queue unavailable")
            }
        }
    }

    /** Push the whole device watchlist to whichever list service is signed in. */
    fun syncSavedToRemote() {
        val session = currentAccountSession() ?: return
        if (!SettingsStore.syncSavedToAniList.value) return
        val savedIds = synchronized(watchlistLock) { _watchlist.value.map { it.anilistId } }
        if (remoteSyncQueue.trySend(RemoteWatchlistCommand.Full(session, savedIds)).isFailure) {
            DiagnosticsLog.event("${session.service.label} watchlist sync queue unavailable")
        }
    }

    /** Merge AniList Planning into this device without deleting device-only saves. */
    fun hydrateWatchlistFromAniList(entries: List<WatchlistEntry>) {
        val changed = synchronized(watchlistLock) {
            val current = _watchlist.value
            val merged = mergeWatchlistEntries(current, entries)
            if (merged == current) {
                false
            } else {
                _watchlist.value = merged
                persist(KEY_WATCHLIST, merged, WatchlistEntry.serializer())
                true
            }
        }
        if (!changed) return
        ReleaseSyncScheduler.runNow(appContext)
    }

    private suspend fun processRemoteSyncQueue() {
        for (command in remoteSyncQueue) {
            when (command) {
                is RemoteWatchlistCommand.Item -> processRemoteItemSync(command)
                is RemoteWatchlistCommand.Full -> processRemoteFullSync(command)
            }
        }
    }

    private suspend fun processRemoteItemSync(command: RemoteWatchlistCommand.Item) {
        val shouldContinue = {
            SettingsStore.syncSavedToAniList.value &&
                watchlistSyncVersions.isLatest(command.ticket) &&
                isCurrentAccountSession(command.session)
        }
        try {
            if (!shouldContinue()) return
            when (command.session.service) {
                AccountService.ANILIST -> {
                    val token = AuthManager.tokenForSession(command.session.generation) ?: return
                    AppGraph.repository.syncSavedAnimeForSession(
                        mediaId = command.anilistId,
                        saved = command.saved,
                        authenticationToken = token,
                        shouldContinue = shouldContinue,
                    )
                }
                AccountService.MAL -> {
                    val token = MalAuthManager.freshAccessToken(command.session.generation) ?: return
                    AppGraph.repository.malSyncSavedAnimeForSession(
                        anilistId = command.anilistId,
                        saved = command.saved,
                        accessToken = token,
                        shouldContinue = shouldContinue,
                    )
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            DiagnosticsLog.throwable(
                "${command.session.service.label} saved sync failed " +
                    "id=${command.anilistId} saved=${command.saved}",
                error,
            )
        } finally {
            watchlistSyncVersions.complete(command.ticket)
        }
    }

    private suspend fun processRemoteFullSync(command: RemoteWatchlistCommand.Full) {
        val shouldContinue = {
            SettingsStore.syncSavedToAniList.value && isCurrentAccountSession(command.session)
        }
        try {
            if (!shouldContinue()) return
            when (command.session.service) {
                AccountService.ANILIST -> {
                    val token = AuthManager.tokenForSession(command.session.generation) ?: return
                    val viewerId = jwtSubject(token) ?: return
                    AppGraph.repository.syncSavedAnimeForSession(
                        mediaIds = command.savedIds,
                        viewerId = viewerId,
                        authenticationToken = token,
                        shouldContinue = shouldContinue,
                    )
                }
                AccountService.MAL -> {
                    val token = MalAuthManager.freshAccessToken(command.session.generation) ?: return
                    AppGraph.repository.malSyncSavedAnimeForSession(
                        anilistIds = command.savedIds,
                        accessToken = token,
                        shouldContinue = shouldContinue,
                    )
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            DiagnosticsLog.throwable(
                "${command.session.service.label} watchlist sync failed " +
                    "(${command.savedIds.size} titles)",
                error,
            )
        }
    }

    // ---- persistence ----

    @SuppressLint("ApplySharedPref") // Terminal events deliberately require commit() before Next.
    private fun <T> persist(
        key: String,
        list: List<T>,
        serializer: kotlinx.serialization.KSerializer<T>,
        commitToDisk: Boolean = false,
    ): Boolean {
        val encoded = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer), list)
        val editor = prefs.edit().putString(key, encoded)
        return if (commitToDisk) editor.commit() else {
            editor.apply()
            true
        }
    }

    private fun <T> decodeList(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(serializer), raw)
        }.getOrDefault(emptyList())
    }

    private sealed interface RemoteWatchlistCommand {
        val session: AccountSessionIdentity

        data class Item(
            override val session: AccountSessionIdentity,
            val ticket: MutationTicket<Int>,
            val anilistId: Int,
            val saved: Boolean,
        ) : RemoteWatchlistCommand

        data class Full(
            override val session: AccountSessionIdentity,
            val savedIds: List<Int>,
        ) : RemoteWatchlistCommand
    }
}
