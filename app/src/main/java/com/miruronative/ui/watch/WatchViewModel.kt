package com.miruronative.ui.watch

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.ProviderCatalog
import com.miruronative.data.SourceResolutionMode
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.auth.AccountService
import com.miruronative.data.settings.SettingsStore
import com.miruronative.data.settings.DEFAULT_PREFERRED_PROVIDER
import com.miruronative.data.model.AniSkipPlaybackSegment
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.remote.KonohaEpisode
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.playback.PlaybackService
import com.miruronative.ui.UiState
import com.miruronative.ui.detail.mergeEpisodeMetadata
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the initial load waits on the Miruro pipe before proceeding with the fast Anivexa
 * catalog alone. A healthy warm pipe answers well under this; a Cloudflare-dead one takes 15 s+,
 * which used to be dead time on the loading screen. Miruro still merges in if it answers later.
 */
private const val MIRURO_CATALOG_WAIT_MS = 8_000L

data class WatchData(
    val episodes: List<EpisodeItem>,
    val currentIndex: Int,
    val provider: String,
    val category: Category,
    val sourceOptions: List<WatchSourceOption>,
    val anilistId: Int,
    val sources: SourcesResult,
    /** Duration-adjusted AniSkip markers for the exact active media session. */
    val aniSkipSegments: List<AniSkipPlaybackSegment> = emptyList(),
    val aniSkipLookupStatus: AniSkipLookupStatus = AniSkipLookupStatus.AWAITING_DURATION,
    val chosenStream: StreamItem?,
    val seriesTitle: String,
    val artworkUrl: String?,
    val seriesFormat: String? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val description: String? = null,
    val totalEpisodes: Int? = null,
    val startPositionMs: Long = 0,
    val playbackGeneration: Int = 0,
    val preferredProvider: String = DEFAULT_PREFERRED_PROVIDER,
    val isResolving: Boolean = false,
    /** Non-null while source resolution is waiting for WatchScreen to tear down the old player. */
    val playbackTeardownGeneration: Int? = null,
    /** The fast Miruro sources are shown; the slower Anivexa servers are still loading in. */
    val isLoadingMoreSources: Boolean = false,
    val notice: String? = null,
) {
    val current: EpisodeItem get() = episodes[currentIndex]
    val hasNext: Boolean get() = currentIndex < episodes.lastIndex
    val hasPrev: Boolean get() = currentIndex > 0
}

data class WatchSourceOption(
    val provider: String,
    val category: Category,
    val hasCurrentEpisode: Boolean,
    val episodeCount: Int,
)

internal enum class ResolutionFailurePolicy {
    /** A user-requested Next/source/audio transition may return to the previously healthy media. */
    RESTORE_PREVIOUS,

    /** Playback-error recovery must never recreate the stream that just failed. */
    TERMINAL,
}

/**
 * Once natural completion has advanced history from A toward B, A is no longer a safe rollback.
 * Recreating it at its terminal position would emit ENDED again and loop A -> missing B forever.
 */
internal fun effectiveResolutionFailurePolicy(
    requestedPolicy: ResolutionFailurePolicy,
    previousEpisodeNumber: Double?,
    requestedEpisodeNumber: Double,
    historyEpisodeNumber: Double?,
    historyContinuationEpisodeNumber: Double?,
    historyCompleted: Boolean,
): ResolutionFailurePolicy {
    if (requestedPolicy == ResolutionFailurePolicy.TERMINAL) return requestedPolicy
    val continuation = historyContinuationEpisodeNumber
    val followsCompletedPrevious = previousEpisodeNumber != null &&
        historyEpisodeNumber == previousEpisodeNumber &&
        !historyCompleted &&
        continuation != null &&
        continuation.isFinite() &&
        continuation != historyEpisodeNumber &&
        continuation == requestedEpisodeNumber
    return if (followsCompletedPrevious) ResolutionFailurePolicy.TERMINAL else requestedPolicy
}

internal data class PlaybackRoutingState(
    val preferred: String,
    val category: Category,
    val spine: List<EpisodeItem>,
    val globalPreferredProvider: String,
    val failedProviders: Set<String>,
)

internal fun routingAfterResolutionFailure(
    policy: ResolutionFailurePolicy,
    rollback: PlaybackRoutingState?,
    current: PlaybackRoutingState,
): PlaybackRoutingState = if (policy == ResolutionFailurePolicy.RESTORE_PREVIOUS && rollback != null) {
    rollback
} else {
    current
}

/** Persist a user-selected provider only if that provider was the one that actually resolved. */
internal fun preferredProviderCommitAfterResolution(
    requestedCommit: String?,
    resolvedProvider: String,
): String? = requestedCommit?.takeIf { it == resolvedProvider }

/** Every concrete media id that the currently visible native or embed session may report. */
internal fun WatchData.playbackTarget(): ActivePlaybackTarget = ActivePlaybackTarget(
    animeId = anilistId,
    episodeNumber = current.number,
    generation = playbackGeneration,
    mediaIds = buildSet {
        chosenStream?.url?.let(::add)
        sources.streams.forEach { add(it.url) }
    },
)

/**
 * A failed replacement explicitly recreates the retained stream under the fresh transition
 * generation. Clearing the ticket without changing the generation could revive callbacks from
 * the player that was just destroyed.
 */
internal fun WatchData.rollbackAfterFailedResolution(
    refreshedSourceOptions: List<WatchSourceOption>,
    resumePositionMs: Long,
    failureNotice: String,
): WatchData = copy(
    sourceOptions = refreshedSourceOptions,
    isResolving = false,
    playbackTeardownGeneration = null,
    startPositionMs = resumePositionMs,
    notice = failureNotice,
)

internal fun WatchData?.watchDataAfterFailedResolution(
    policy: ResolutionFailurePolicy,
    refreshedSourceOptions: List<WatchSourceOption>,
    resumePositionMs: Long,
    failureNotice: String,
): WatchData? = takeIf { policy == ResolutionFailurePolicy.RESTORE_PREVIOUS }
    ?.rollbackAfterFailedResolution(
        refreshedSourceOptions = refreshedSourceOptions,
        resumePositionMs = resumePositionMs,
        failureNotice = failureNotice,
    )

private data class EpisodeSourceKey(
    val episode: Double,
    val provider: String,
    val category: Category,
)

private data class PendingResolution(
    val token: PlaybackRequestToken,
    val key: EpisodeResolutionKey,
)

private data class ProgressSyncKey(
    val animeId: Int,
    val episode: Int,
    val generation: Int,
    val service: AccountService,
)

class WatchViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<WatchData>>(UiState.Loading)
    val state = _state.asStateFlow()

    /** Live status line for the initial loading screen ("main source is down, trying others…"). */
    private val _loadingStatus = MutableStateFlow<String?>(null)
    val loadingStatus = _loadingStatus.asStateFlow()

    private var anilistId = 0
    private var category = Category.SUB
    private var globalPreferredProvider = DEFAULT_PREFERRED_PROVIDER
    private var preferred = ""
    private var spine: List<EpisodeItem> = emptyList()
    private var startedKey: String? = null
    private var seriesTitle = "Anime"
    private var artworkUrl: String? = null
    private var seriesFormat: String? = null
    private var averageScore: Int? = null
    private var popularity: Int? = null
    private var description: String? = null
    private var totalEpisodes: Int? = null
    private val syncedProgressEpisodes = mutableSetOf<ProgressSyncKey>()
    private var lastRequestedNumber = 1.0
    private var failedProviders = mutableSetOf<String>()
    private val unavailableSources = mutableSetOf<EpisodeSourceKey>()
    private val confirmedSources = mutableSetOf<EpisodeSourceKey>()
    private val failedStreamUrls = mutableSetOf<String>()
    /** Konoha's episode titles and stills, kept so every spine rebuild carries them. */
    private var episodeMeta: List<KonohaEpisode> = emptyList()
    private var resolveJob: Job? = null
    private var episodeMetaJob: Job? = null
    private var anivexaMergeJob: Job? = null
    private var miruroLateMergeJob: Job? = null
    private var sourceValidationJob: Job? = null
    private var aniSkipLookupIdentity: AniSkipLookupIdentity? = null
    private var aniSkipLookupJob: Job? = null
    private var mergedIncludesAnivexa = false
    private var mergedEpisodes = EpisodesResult(emptyList())
    private var catalogGeneration = 0L
    private val requestGate = PlaybackRequestGate()
    private val playbackTransitionBarrier = PlaybackTransitionBarrier()
    private var pendingResolution: PendingResolution? = null
    private var playbackGenerationCounter = 0

    private fun playbackRoutingState(): PlaybackRoutingState = PlaybackRoutingState(
        preferred = preferred,
        category = category,
        spine = spine,
        globalPreferredProvider = globalPreferredProvider,
        failedProviders = failedProviders.toSet(),
    )

    private fun restorePlaybackRouting(snapshot: PlaybackRoutingState) {
        preferred = snapshot.preferred
        category = snapshot.category
        spine = snapshot.spine
        globalPreferredProvider = snapshot.globalPreferredProvider
        failedProviders = snapshot.failedProviders.toMutableSet()
    }

    internal fun isStartedFor(
        id: Int,
        providerName: String,
        categoryApi: String,
        episodeNumber: String,
    ): Boolean = startedKey == "$id/$providerName/$categoryApi/$episodeNumber"

    fun start(id: Int, providerName: String, categoryApi: String, episodeNumber: String) {
        val key = "$id/$providerName/$categoryApi/$episodeNumber"
        if (key == startedKey && _state.value is UiState.Success) {
            DiagnosticsLog.event("Watch start ignored duplicate key=$key")
            return
        }
        val previous = (_state.value as? UiState.Success)?.data
        val request = withProgressFlushedBeforeTransition(previous) {
            requestGate.startSession()
        }
        DiagnosticsLog.event("Watch start key=$key")
        startedKey = key
        activeNativePlaybackIdentity = null
        committedNativePlaybackIdentity = null
        anilistId = id
        category = Category.from(categoryApi)
        preferred = providerName
        totalEpisodes = null
        seriesTitle = "Anime"
        artworkUrl = null
        seriesFormat = null
        averageScore = null
        popularity = null
        description = null
        syncedProgressEpisodes.clear()
        failedProviders.clear()
        unavailableSources.clear()
        confirmedSources.clear()
        mergedIncludesAnivexa = false
        catalogGeneration = 0L
        episodeMeta = emptyList()
        resetProgressSession()

        invalidatePendingResolution()
        resolveJob?.cancel()
        cancelAniSkipWork()
        episodeMetaJob?.cancel()
        anivexaMergeJob?.cancel()
        miruroLateMergeJob?.cancel()
        sourceValidationJob?.cancel()
        // WatchScreen has already committed a surface-free frame. Publish Loading synchronously
        // before returning so authorizing this route cannot recompose the retained old WatchData.
        _state.value = UiState.Loading
        _loadingStatus.value = null
        // Runs beside source resolution: providers hand back bare numbered lists, so without this
        // the episode list here reads "Episode 5" where the Anime page shows the real title.
        episodeMetaJob = viewModelScope.launch { loadEpisodeMetadata(id, request) }
        resolveJob = viewModelScope.launch {
            try {
                SettingsStore.awaitLoaded()
                ensureCurrentRequest(request)
                val storedPreferred = SettingsStore.preferredProvider.value
                globalPreferredProvider = storedPreferred
                preferred = preferredProviderForWatch(storedPreferred, providerName)
                DiagnosticsLog.event(
                    "Watch preferred server route=$providerName stored=$storedPreferred selected=$preferred",
                )
                DiagnosticsLog.event("Watch episodes load start id=$id")
                // Fast path: both catalogs race from the start. The fast Anivexa subset (plus the
                // preferred server) is already in flight if the Miruro pipe turns out to be slow
                // or down, and the Miruro wait is capped so a dead pipe (Cloudflare timeout)
                // can't hold the loading screen hostage — a late Miruro answer merges in behind.
                // The remaining slow Anivexa scrapers fold in via launchAnivexaMerge.
                val fastCatalog = async {
                    val result = runCatching { repo.fastAnivexaEpisodes(id, setOf(preferred)) }
                        .onFailure {
                            it.rethrowIfCancellation()
                            DiagnosticsLog.throwable("Watch fast anivexa episodes failed id=$id", it)
                        }
                        .getOrDefault(EpisodesResult(emptyList()))
                    ensureCurrentRequest(request)
                    result
                }
                val miruroDeferred = async {
                    val result = runCatching { repo.miruroEpisodes(id) }
                        .onFailure { it.rethrowIfCancellation() }
                    ensureCurrentRequest(request)
                    result
                }
                val miruroResult = withTimeoutOrNull(MIRURO_CATALOG_WAIT_MS) { miruroDeferred.await() }
                ensureCurrentRequest(request)
                miruroResult?.exceptionOrNull()?.let {
                    DiagnosticsLog.throwable("Watch miruro episodes failed id=$id", it)
                }
                if (miruroResult == null) {
                    DiagnosticsLog.event(
                        "Watch miruro episodes still pending after ${MIRURO_CATALOG_WAIT_MS}ms id=$id",
                    )
                }
                val miruro = miruroResult?.getOrNull() ?: EpisodesResult(emptyList())
                if (miruro.isEmpty) {
                    _loadingStatus.value =
                        "The main source looks down right now. Trying other sources…"
                }
                val preferredIsAnivexa =
                    ProviderCatalog.sourceOf(preferred) == ProviderCatalog.Source.ANIVEXA
                val merged = if (!miruro.isEmpty && !preferredIsAnivexa) {
                    // The full Anivexa catalog loads via launchAnivexaMerge anyway; drop the
                    // now-redundant fast subset instead of hitting those providers twice.
                    fastCatalog.cancel()
                    miruro
                } else {
                    val fast = fastCatalog.await()
                    ensureCurrentRequest(request)
                    if (fast.isEmpty) {
                        val allEpisodes = repo.episodes(id)
                        ensureCurrentRequest(request)
                        mergedIncludesAnivexa = true
                        allEpisodes
                    } else {
                        DiagnosticsLog.event(
                            "Watch fast catalog id=$id providers=" + fast.providerNames.joinToString(),
                        )
                        repo.mergeProviders(miruro, fast)
                    }
                }
                DiagnosticsLog.event(
                    "Watch episodes load success id=$id providers=" +
                        merged.providers.joinToString { provider ->
                            "${provider.name}:sub=${provider.sub.size},dub=${provider.dub.size}"
                        },
                )
                mergedEpisodes = merged
                catalogGeneration++
                // Prefer dub: launches carrying category=sub (typically history saved before the
                // setting was enabled) upgrade to dub when the catalog actually has the start
                // episode dubbed. In-player category switches are unaffected — this only runs on
                // screen entry — and when no dub exists the launch stays sub instead of erroring.
                if (category == Category.SUB && SettingsStore.preferDub.value) {
                    val startNumber = episodeNumber.toDoubleOrNull()
                    val dubAvailable = merged.providers.any { provider ->
                        provider.dub.isNotEmpty() &&
                            (startNumber == null || provider.dub.any { it.number == startNumber })
                    }
                    if (dubAvailable) {
                        category = Category.DUB
                        DiagnosticsLog.event("Watch category upgraded to dub (prefer dub) id=$id")
                    }
                }
                val info = repo.animeInfo(id)
                ensureCurrentRequest(request)
                info?.let {
                    seriesTitle = it.title.preferred
                    artworkUrl = it.coverImage.best
                    seriesFormat = it.format
                    averageScore = it.averageScore
                    popularity = it.popularity
                    description = it.description
                    totalEpisodes = it.episodes
                    DiagnosticsLog.event("Watch animeInfo success id=$id title=${seriesTitle.take(80)}")
                }
                spine = pickSpine(merged)
                DiagnosticsLog.event(
                    "Watch spine picked size=${spine.size} preferred=$preferred category=${category.api} " +
                        "first=${spine.firstOrNull()?.displayNumber ?: "none"} last=${spine.lastOrNull()?.displayNumber ?: "none"}",
                )
                if (spine.isEmpty()) error("No episodes for this title")
                val startNumber = episodeNumber.toDoubleOrNull() ?: spine.first().number
                // Launched before the first resolve so a miss on the partial catalog can await
                // the remaining servers instead of reporting "no source" prematurely.
                if (miruroResult == null) launchMiruroLateMerge(id, miruroDeferred, request)
                if (!mergedIncludesAnivexa) launchAnivexaMerge(id, request)
                resolveAndPlay(startNumber, request)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                if (!requestGate.isCurrentRequest(request)) return@launch
                requestGate.finishRequest(request)
                DiagnosticsLog.throwable("Watch start failed key=$key", e)
                _loadingStatus.value = null
                _state.value = UiState.Error(e.message ?: "Failed to load episode")
            }
        }
    }

    /**
     * When the Miruro pipe outlived its initial wait, keep listening: if it eventually answers,
     * fold its providers into the catalog so they become selectable, without disturbing playback
     * that already started on a fast source.
     */
    private fun launchMiruroLateMerge(
        id: Int,
        deferred: kotlinx.coroutines.Deferred<Result<EpisodesResult>>,
        session: PlaybackRequestToken,
    ) {
        miruroLateMergeJob?.cancel()
        miruroLateMergeJob = viewModelScope.launch {
            val lateResult = runCatching { deferred.await() }
                .onFailure { it.rethrowIfCancellation() }
                .getOrNull()
            ensureCurrentSession(session)
            val late = lateResult?.getOrNull() ?: return@launch
            if (late.isEmpty) return@launch
            mergedEpisodes = repo.mergeProviders(late, mergedEpisodes)
            catalogGeneration++
            val rebuiltSpine = pickSpine(mergedEpisodes)
            DiagnosticsLog.event(
                "Watch miruro late merge applied id=$id providers=" + late.providerNames.joinToString(),
            )
            publishMergedSpine(rebuiltSpine, mergeLabel = "miruro")
        }
    }

    /**
     * Fold the slower Anivexa providers in once they arrive, without disturbing playback that
     * already started on a Miruro source. Refreshes the navigation spine and the source list so
     * the extra servers become selectable; the active stream and resolving flag are left as-is.
     */
    private fun launchAnivexaMerge(id: Int, session: PlaybackRequestToken) {
        anivexaMergeJob?.cancel()
        anivexaMergeJob = viewModelScope.launch {
            val anivexa = runCatching { repo.anivexaEpisodes(id) }
                .onFailure {
                    it.rethrowIfCancellation()
                    DiagnosticsLog.throwable("Watch anivexa merge failed id=$id", it)
                }
                .getOrNull()
            ensureCurrentSession(session)
            val rebuiltSpine = if (anivexa != null && !anivexa.isEmpty) {
                mergedEpisodes = repo.mergeProviders(mergedEpisodes, anivexa)
                catalogGeneration++
                DiagnosticsLog.event(
                    "Watch anivexa merge applied id=$id providers=" + mergedEpisodes.providerNames.joinToString(),
                )
                pickSpine(mergedEpisodes)
            } else {
                spine
            }
            mergedIncludesAnivexa = true
            // Reflect completion whether or not Anivexa added anything: the extra servers become
            // selectable and the "loading more servers" hint clears.
            publishMergedSpine(
                rebuiltSpine = rebuiltSpine,
                mergeLabel = "anivexa",
                isLoadingMoreSources = false,
            )
        }
    }

    /**
     * Publish a late catalog even while a source request is pending. [spine] must not move on its
     * own while [WatchData] still points at the old list: next/previous navigation reads both.
     * The snapshot policy therefore either preserves the visible episode at its rebuilt index or
     * rejects the whole publication. Loading states have no visible episode and can publish the
     * rebuilt spine immediately, which is what lets a direct link resolve from a slow catalog.
     */
    private fun publishMergedSpine(
        rebuiltSpine: List<EpisodeItem>,
        mergeLabel: String,
        isLoadingMoreSources: Boolean? = null,
    ) {
        val data = (_state.value as? UiState.Success)?.data
        val visibleNumber = data?.current?.number
        val publication = catalogSpinePublication(rebuiltSpine, visibleNumber)
        if (publication == null) {
            DiagnosticsLog.event(
                "Watch $mergeLabel merge ignored: active episode=${visibleNumber?.let(::fmt) ?: "none"} " +
                    "missing from rebuilt spine",
            )
            return
        }

        spine = publication.episodes
        if (data != null) {
            val number = data.current.number
            _state.value = UiState.Success(
                data.copy(
                    episodes = publication.episodes,
                    currentIndex = checkNotNull(publication.currentIndex),
                    sourceOptions = sourceOptions(number),
                    isLoadingMoreSources = isLoadingMoreSources ?: data.isLoadingMoreSources,
                ),
            )
            if (!requestGate.hasPendingRequest()) {
                launchSourceValidation(number, requestGate.currentRequest())
            }
        }
    }

    /**
     * Navigation spine: the union of every provider episode list, so a longer but incomplete
     * catalog cannot hide episodes carried by another provider. When providers overlap, the
     * preferred provider supplies the row metadata; source resolution still falls back per
     * episode independently.
     */
    private fun pickSpine(merged: EpisodesResult): List<EpisodeItem> {
        val spine = pickNavigationSpine(merged, preferred, category)
        // Applied on every rebuild, not just the first paint: a provider failover swaps the spine
        // and would otherwise drop the titles back to bare numbers mid-episode.
        if (spine.isEmpty() || episodeMeta.isEmpty()) return spine
        return mergeEpisodeMetadata(spine, episodeMeta, anilistId)
    }

    /**
     * Konoha's episode titles and stills — the same overlay the Anime page applies. Cosmetic and
     * rate-limit-free, so any failure just leaves the provider's numbered list as it was.
     */
    private suspend fun loadEpisodeMetadata(id: Int, session: PlaybackRequestToken) {
        val meta = runCatching { repo.konohaEpisodes(id) }
            .onFailure {
                it.rethrowIfCancellation()
                DiagnosticsLog.throwable("Watch episode metadata failed id=$id", it)
            }
            .getOrDefault(emptyList())
        ensureCurrentSession(session)
        if (meta.isEmpty() || id != anilistId) return
        episodeMeta = meta
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.episodes.isEmpty()) return
        _state.value = UiState.Success(
            data.copy(episodes = mergeEpisodeMetadata(data.episodes, meta, id)),
        )
    }

    private suspend fun resolveAndPlay(
        number: Double,
        request: PlaybackRequestToken,
        failurePolicy: ResolutionFailurePolicy = ResolutionFailurePolicy.RESTORE_PREVIOUS,
        rollbackRouting: PlaybackRoutingState? = null,
        preferredProviderToCommit: String? = null,
    ): Unit = coroutineScope {
        ensureCurrentRequest(request)
        _loadingStatus.value = null
        failedStreamUrls.clear()
        val requestAnimeId = anilistId
        val requestedProvider = preferred
        val requestedCategory = category
        val excludedProviders = failedProviders.toSet()
        DiagnosticsLog.event(
            "Watch resolve start id=$requestAnimeId episode=${fmt(number)} preferred=$requestedProvider " +
                "category=${requestedCategory.api} excluded=${excludedProviders.joinToString()}",
        )
        lastRequestedNumber = number
        // AniSkip v2 requires the real media duration. Lookup therefore starts only after a
        // validated native/embed playback callback reports it; provider markers remain usable in
        // the meantime and inaccessible cross-origin embeds fail open without a bogus length=0.
        cancelAniSkipWork()
        val previous = (_state.value as? UiState.Success)?.data
        _state.value = previous?.let { UiState.Success(it.copy(isResolving = true, notice = null)) }
            ?: UiState.Loading
        var resolvedAgainstCatalogGeneration = catalogGeneration
        var resolution = repo.resolveSources(
            anilistId = requestAnimeId,
            number = number,
            preferred = requestedProvider,
            category = requestedCategory,
            episodes = mergedEpisodes,
            excludedProviders = excludedProviders,
        )
        ensureCurrentRequest(request)
        val unavailableThisResolve = resolution.unavailableProviders.toMutableSet()
        var resolved = resolution.resolved
        var exhaustiveResolutionAttempted = false
        suspend fun retryAfterCatalogMerge(label: String, mergeJob: Job?) {
            if (resolved != null || mergeJob == null) return
            if (mergeJob.isActive) {
                DiagnosticsLog.event(
                    "Watch resolve waiting for $label catalog id=$requestAnimeId episode=${fmt(number)}",
                )
                _loadingStatus.value = "Still looking — checking the remaining servers…"
            }
            mergeJob.join()
            ensureCurrentRequest(request)
            if (!shouldRetryAfterCatalogMerge(resolved != null, resolvedAgainstCatalogGeneration, catalogGeneration)) {
                return
            }
            DiagnosticsLog.event(
                "Watch resolve retry after $label catalog id=$requestAnimeId episode=${fmt(number)} " +
                    "generation=$catalogGeneration",
            )
            // Once a late catalog adds providers, try all of them. The normal attempt cap bounds
            // mid-playback fallback latency, but it must not hide a directly requested episode.
            resolvedAgainstCatalogGeneration = catalogGeneration
            exhaustiveResolutionAttempted = true
            resolution = repo.resolveSources(
                anilistId = requestAnimeId,
                number = number,
                preferred = requestedProvider,
                category = requestedCategory,
                episodes = mergedEpisodes,
                excludedProviders = excludedProviders,
                maxAttempts = Int.MAX_VALUE,
            )
            ensureCurrentRequest(request)
            unavailableThisResolve += resolution.unavailableProviders
            resolved = resolution.resolved
        }

        retryAfterCatalogMerge("anivexa", anivexaMergeJob)
        retryAfterCatalogMerge("miruro", miruroLateMergeJob)
        if (shouldRunExhaustiveProviderFallback(resolved != null, exhaustiveResolutionAttempted)) {
            DiagnosticsLog.event(
                "Watch resolve exhaustive fallback id=$requestAnimeId episode=${fmt(number)}",
            )
            _loadingStatus.value = "Still looking — checking every available server…"
            resolution = repo.resolveSources(
                anilistId = requestAnimeId,
                number = number,
                preferred = requestedProvider,
                category = requestedCategory,
                episodes = mergedEpisodes,
                // The capped pass already proved these providers unusable for this request. Skip
                // them so the final pass reaches providers that were previously hidden by the cap.
                excludedProviders = excludedProviders + unavailableThisResolve,
                maxAttempts = Int.MAX_VALUE,
            )
            ensureCurrentRequest(request)
            unavailableThisResolve += resolution.unavailableProviders
            resolved = resolution.resolved
        }
        if (resolved == null) {
            cancelAniSkipWork()
            ensureCurrentRequest(request)
            unavailableThisResolve.forEach { provider ->
                unavailableSources += EpisodeSourceKey(number, provider, requestedCategory)
            }
            _loadingStatus.value = null
            val message = "No playable source for episode ${fmt(number)} on any server"
            DiagnosticsLog.event("Watch resolve no source id=$requestAnimeId episode=${fmt(number)}")
            requestGate.finishRequest(request)
            val history = LibraryStore.historyFor(requestAnimeId)
            val effectiveFailurePolicy = effectiveResolutionFailurePolicy(
                requestedPolicy = failurePolicy,
                previousEpisodeNumber = previous?.current?.number,
                requestedEpisodeNumber = number,
                historyEpisodeNumber = history?.episodeNumber,
                historyContinuationEpisodeNumber = history?.continuationEpisodeNumber,
                historyCompleted = history?.completed == true,
            )
            val currentRouting = playbackRoutingState()
            restorePlaybackRouting(
                routingAfterResolutionFailure(
                    policy = effectiveFailurePolicy,
                    rollback = rollbackRouting,
                    current = currentRouting,
                ),
            )
            val rollbackPosition = previous?.let {
                history?.resumePositionFor(it.current.number)
                    ?: it.startPositionMs
            } ?: 0L
            val rollbackData = previous.watchDataAfterFailedResolution(
                policy = effectiveFailurePolicy,
                refreshedSourceOptions = previous?.let { sourceOptions(it.current.number) }.orEmpty(),
                resumePositionMs = rollbackPosition,
                failureNotice = message,
            )
            _state.value = rollbackData?.let {
                UiState.Success(
                    it,
                )
            } ?: UiState.Error(message)
            return@coroutineScope
        }
        val playable = checkNotNull(resolved)
        val fallbackNotice = if (playable.provider != requestedProvider) {
            "${ProviderCatalog.label(requestedProvider)} is unavailable for this episode. " +
                "Playing ${ProviderCatalog.label(playable.provider)} instead."
        } else {
            null
        }
        if (fallbackNotice != null) {
            DiagnosticsLog.event(
                "Watch provider fallback requested=$requestedProvider actual=${playable.provider} " +
                    "episode=${fmt(number)}",
            )
        }
        ensureCurrentRequest(request)
        unavailableThisResolve.forEach { provider ->
            unavailableSources += EpisodeSourceKey(number, provider, requestedCategory)
        }
        // A later successful retry makes this option valid again for the current session.
        unavailableSources -= EpisodeSourceKey(number, playable.provider, requestedCategory)
        confirmedSources += EpisodeSourceKey(number, playable.provider, requestedCategory)
        // A late catalog merge must not re-advertise the requested provider after this request
        // already proved it unusable for the episode.
        if (playable.provider != requestedProvider && requestedProvider != DEFAULT_PREFERRED_PROVIDER) {
            unavailableSources += EpisodeSourceKey(number, requestedProvider, requestedCategory)
        }
        val sources = playable.sources.copy(skip = normalizedSkipTimes(playable.sources.skip))
        val index = navigationEpisodeIndex(spine, number)
            ?: error("Resolved episode ${fmt(number)} is missing from the navigation catalog")
        val resume = LibraryStore.historyFor(requestAnimeId)?.resumePositionFor(number) ?: 0L
        val chosen = pickProviderStream(playable.provider, sources)
        preferred = playable.provider
        preferredProviderCommitAfterResolution(
            requestedCommit = preferredProviderToCommit,
            resolvedProvider = playable.provider,
        )?.let { committedProvider ->
            globalPreferredProvider = committedProvider
            SettingsStore.setPreferredProvider(committedProvider)
        }
        DiagnosticsLog.event(
            "Watch resolve success provider=${playable.provider} episode=${fmt(number)} index=$index " +
                "hls=${playable.sources.hlsStreams.size} total=${playable.sources.streams.size} " +
                "embed=${playable.sources.embedStreams.size} subtitles=${playable.sources.subtitles.size} " +
                "chosen=${chosen?.diagnosticLabel() ?: "none"} resumeMs=$resume",
        )
        DiagnosticsLog.event(
            "Watch source inventory provider=${playable.provider} " +
                playable.sources.streams.joinToString(separator = ",", limit = 16, truncated = "...") {
                    "${it.diagnosticLabel()}${if (it.isActive) "*" else ""}"
                },
        )
        _loadingStatus.value = null
        requestGate.finishRequest(request)
        val sourceGeneration = nextPlaybackGeneration()
        val data = WatchData(
            episodes = spine,
            currentIndex = index,
            provider = playable.provider,
            category = requestedCategory,
            sourceOptions = sourceOptions(number),
            anilistId = requestAnimeId,
            sources = sources,
            aniSkipSegments = emptyList(),
            aniSkipLookupStatus = AniSkipLookupStatus.AWAITING_DURATION,
            chosenStream = chosen,
            seriesTitle = seriesTitle,
            artworkUrl = artworkUrl,
            seriesFormat = seriesFormat,
            averageScore = averageScore,
            popularity = popularity,
            description = description,
            totalEpisodes = totalEpisodes,
            startPositionMs = resume,
            playbackGeneration = sourceGeneration,
            preferredProvider = globalPreferredProvider,
            isResolving = false,
            isLoadingMoreSources = !mergedIncludesAnivexa,
            notice = fallbackNotice,
        )
        _state.value = UiState.Success(data)
        launchSourceValidation(number, request)
    }

    fun changeSource(providerName: String, categoryApi: String) {
        DiagnosticsLog.event("Watch changeSource requested provider=$providerName category=$categoryApi")
        switchSource(providerName, categoryApi, rememberProvider = true)
    }

    fun changeCategory(categoryApi: String) {
        val providerName = (_state.value as? UiState.Success)?.data?.provider ?: return
        DiagnosticsLog.event("Watch changeCategory requested provider=$providerName category=$categoryApi")
        switchSource(providerName, categoryApi, rememberProvider = false)
    }

    private fun switchSource(providerName: String, categoryApi: String, rememberProvider: Boolean) {
        val nextCategory = Category.from(categoryApi)
        val provider = mergedEpisodes.provider(providerName) ?: return
        val selectedProviderEpisodes = provider.episodes(nextCategory)
            .takeIf { it.isNotEmpty() }
            ?: return
        val current = (_state.value as? UiState.Success)?.data
        val currentNumber = current?.current?.number ?: lastRequestedNumber
        // Source controls are episode-scoped. Never let a stale selection jump the viewer to the
        // first episode merely because that server/audio pair lacks the episode being watched.
        if (selectedProviderEpisodes.none { it.number == currentNumber }) return

        // Keep resolution on the provider that is actually playing for the rest of this watch
        // session. A SUB/DUB switch is not a request to change the user's global preference, but
        // it must not fall back to the stale provider that failed earlier in the session either.
        if (current?.provider == providerName && current.category == nextCategory) {
            if (rememberProvider) {
                preferred = providerName
                globalPreferredProvider = providerName
                SettingsStore.setPreferredProvider(providerName)
                _state.value = UiState.Success(
                    current.copy(
                        preferredProvider = providerName,
                        notice = "${ProviderCatalog.label(providerName)} is now your preferred server.",
                    ),
                )
            }
            return
        }
        val rollbackRouting = playbackRoutingState()
        withProgressFlushedBeforeTransition(current) {
            preferred = providerName
            category = nextCategory
            // Selecting a server changes metadata priority, not which episodes are navigable. Keep
            // the category-wide union so switching source cannot silently shrink the season again.
            spine = pickNavigationSpine(mergedEpisodes, providerName, nextCategory)
            failedProviders.clear()
            launchResolve(
                number = currentNumber,
                progressAlreadyFlushed = true,
                rollbackRouting = rollbackRouting,
                preferredProviderToCommit = providerName.takeIf { rememberProvider },
            )
        }
    }

    private var lastProgressSave = 0L
    private var lastProgressSaveIdentity: PlaybackIdentity? = null
    private var lastKnownProgress: PlaybackProgressSnapshot? = null
    private var confirmedHistoryIdentity: PlaybackIdentity? = null
    private var activeNativePlaybackIdentity: NativePlaybackIdentity? = null
    private var committedNativePlaybackIdentity: NativePlaybackIdentity? = null
    private var committedEmbedPlaybackKey: EmbedPlaybackKey? = null
    private var activeEmbedMediaIdentity: EmbedMediaIdentity? = null

    fun onNativePlaybackIdentityChanged(identity: NativePlaybackIdentity) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (
            data.isResolving ||
            !isCurrentNativePlaybackIdentity(
                identity = identity,
                currentAnimeId = data.anilistId,
                currentEpisodeNumber = data.current.number,
                availableMediaIds = data.nativeMediaIds(),
            )
        ) {
            DiagnosticsLog.event(
                "Watch ignored native identity episode=${fmt(identity.episodeNumber)} " +
                    "current=${data.current.displayNumber}",
            )
            return
        }
        val previousMediaId = activeNativePlaybackIdentity?.mediaId
        if (previousMediaId != null && previousMediaId != identity.mediaId) {
            // A quality/CDN replacement may have a different edit or duration. Do not retain
            // duration-adjusted markers from the outgoing concrete media item.
            cancelAniSkipWork()
            if (
                data.aniSkipSegments.isNotEmpty() ||
                data.aniSkipLookupStatus != AniSkipLookupStatus.AWAITING_DURATION
            ) {
                _state.value = UiState.Success(
                    data.copy(
                        aniSkipSegments = emptyList(),
                        aniSkipLookupStatus = AniSkipLookupStatus.AWAITING_DURATION,
                    ),
                )
            }
        }
        activeNativePlaybackIdentity = identity
        DiagnosticsLog.event(
            "Watch native identity active episode=${fmt(identity.episodeNumber)} " +
                "playbackId=${identity.playbackId.take(8)}",
        )
    }

    /** Commits a natural end synchronously before the caller is allowed to autoplay Next. */
    fun onNativePlaybackEnded(completion: NativePlaybackCompletion): Boolean {
        if (PlaybackService.wasRemotePlaybackCompletionCommitted(completion.identity.playbackId)) {
            DiagnosticsLog.event("Watch ignored service-committed remote native end")
            return false
        }
        if (
            PlaybackService.ownsRemotePlaybackCompletion(
                playbackId = completion.identity.playbackId,
                animeId = completion.identity.animeId,
                episodeNumber = completion.identity.episodeNumber,
                mediaId = completion.identity.mediaId,
            )
        ) {
            // PlaybackService commits remote terminal state before it invokes the registered
            // episode navigator. Returning false also prevents this UI listener from becoming a
            // second terminal writer for the same Cast event.
            DiagnosticsLog.event("Watch delegated remote native end to PlaybackService")
            return false
        }
        val data = (_state.value as? UiState.Success)?.data ?: return false
        if (data.isResolving) return false
        val commit = planNativeCompletionCommit(
            completion = completion,
            activeIdentity = activeNativePlaybackIdentity,
            currentAnimeId = data.anilistId,
            currentEpisodeNumber = data.current.number,
            availableMediaIds = data.nativeMediaIds(),
            alreadyCommitted = committedNativePlaybackIdentity == completion.identity,
        ) ?: run {
            DiagnosticsLog.event(
                "Watch ignored stale/invalid native end episode=${fmt(completion.identity.episodeNumber)} " +
                    "current=${data.current.displayNumber} playbackId=${completion.identity.playbackId.take(8)}",
            )
            return false
        }
        val progressIdentity = PlaybackIdentity(
            animeId = commit.identity.animeId,
            episodeNumber = commit.identity.episodeNumber,
            generation = data.playbackGeneration,
            mediaId = commit.identity.mediaId,
        )
        val activeProgressTarget = data.nativePlaybackTarget()
        if (activeProgressTarget == null || !acceptsPlaybackProgress(progressIdentity, activeProgressTarget)) {
            DiagnosticsLog.event(
                "Watch ignored native end with stale progress identity " +
                    "episode=${fmt(progressIdentity.episodeNumber)} generation=${progressIdentity.generation}",
            )
            return false
        }
        // A validated natural end is itself proof that this media item played. This also creates
        // history for very short/resumed items that can end before the periodic progress tick.
        acceptProgress(data, progressIdentity, commit.positionMs, commit.durationMs)
        val saved = LibraryStore.historyFor(progressIdentity.animeId)
        if (saved?.episodeNumber != commit.identity.episodeNumber) {
            DiagnosticsLog.event(
                "Watch ignored native end without matching history episode=${fmt(commit.identity.episodeNumber)}",
            )
            return false
        }
        val completedFinalEpisode = isConfirmedFinalSeriesEpisode(
            episodeNumber = commit.identity.episodeNumber,
            totalEpisodes = totalEpisodes,
            hasNextEpisode = data.hasNext,
        )
        val continuationEpisodeNumber = continuationEpisodeAfterNaturalEnd(
            currentEpisodeNumber = commit.identity.episodeNumber,
            nextEpisodeNumber = data.episodes.getOrNull(data.currentIndex + 1)?.number,
            completedFinalEpisode = completedFinalEpisode,
        )
        if (
            !LibraryStore.updateProgressDurably(
                anilistId = progressIdentity.animeId,
                episodeNumber = commit.identity.episodeNumber,
                positionMs = commit.positionMs,
                durationMs = commit.durationMs,
                continuationEpisodeNumber = continuationEpisodeNumber,
                completed = completedFinalEpisode,
            )
        ) {
            DiagnosticsLog.event(
                "Watch native end disk commit failed episode=${fmt(commit.identity.episodeNumber)}",
            )
            return false
        }
        val persisted = LibraryStore.historyFor(progressIdentity.animeId)
        if (
            persisted == null ||
            persisted.episodeNumber != commit.identity.episodeNumber ||
            persisted.positionMs != commit.positionMs ||
            persisted.durationMs != commit.durationMs ||
            persisted.continuationEpisodeNumber != continuationEpisodeNumber ||
            persisted.completed != completedFinalEpisode
        ) {
            DiagnosticsLog.event(
                "Watch native end persistence failed episode=${fmt(commit.identity.episodeNumber)}",
            )
            return false
        }
        committedNativePlaybackIdentity = commit.identity
        lastKnownProgress = PlaybackProgressSnapshot(progressIdentity, commit.positionMs, commit.durationMs)
        lastProgressSaveIdentity = progressIdentity
        lastProgressSave = SystemClock.elapsedRealtime()
        DiagnosticsLog.event(
            "Watch native end committed episode=${fmt(commit.identity.episodeNumber)} " +
                "positionMs=${commit.positionMs} playbackId=${commit.identity.playbackId.take(8)}",
        )
        _state.value = UiState.Success(data.copy(startPositionMs = commit.positionMs))
        return true
    }

    private fun WatchData.embedPlaybackKey(): EmbedPlaybackKey = EmbedPlaybackKey(
        animeId = anilistId,
        provider = provider,
        category = category.api,
        episodeNumber = current.number,
        sourceGeneration = playbackGeneration,
    )

    fun onEmbedMediaIdentityChanged(identity: EmbedMediaIdentity) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isResolving || data.playbackTeardownGeneration != null) {
            DiagnosticsLog.event(
                "Watch ignored embed media activation during transition " +
                    "episode=${fmt(identity.playbackKey.episodeNumber)} " +
                    "generation=${identity.playbackKey.sourceGeneration}",
            )
            return
        }
        when (
            planEmbedMediaHandoff(
                active = activeEmbedMediaIdentity,
                reported = identity,
                currentPlaybackKey = data.embedPlaybackKey(),
            )
        ) {
            EmbedMediaHandoffDecision.REJECT -> {
                DiagnosticsLog.event(
                    "Watch ignored stale embed media activation " +
                        "episode=${fmt(identity.playbackKey.episodeNumber)} " +
                        "generation=${identity.playbackKey.sourceGeneration}",
                )
            }
            EmbedMediaHandoffDecision.KEEP -> Unit
            EmbedMediaHandoffDecision.ACTIVATE_AND_RESET_ANISKIP -> {
                activeEmbedMediaIdentity = identity
                activeNativePlaybackIdentity = null
                cancelAniSkipWork()
                if (
                    data.aniSkipSegments.isNotEmpty() ||
                    data.aniSkipLookupStatus != AniSkipLookupStatus.AWAITING_DURATION
                ) {
                    _state.value = UiState.Success(
                        data.copy(
                            aniSkipSegments = emptyList(),
                            aniSkipLookupStatus = AniSkipLookupStatus.AWAITING_DURATION,
                        ),
                    )
                }
                DiagnosticsLog.event(
                    "Watch embed media active episode=${fmt(identity.playbackKey.episodeNumber)} " +
                        "generation=${identity.playbackKey.sourceGeneration} " +
                        "navigation=${identity.navigationGeneration}",
                )
            }
        }
    }

    fun onEmbedProgress(identity: EmbedMediaIdentity, positionMs: Long, durationMs: Long) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (
            !acceptsEmbedMediaCallback(
                reported = identity,
                active = activeEmbedMediaIdentity,
                currentPlaybackKey = data.embedPlaybackKey(),
            )
        ) {
            DiagnosticsLog.event(
                "Watch ignored stale embed progress episode=${fmt(identity.playbackKey.episodeNumber)} " +
                    "generation=${identity.playbackKey.sourceGeneration} " +
                    "navigation=${identity.navigationGeneration}",
            )
            return
        }
        val key = identity.playbackKey
        val sourceIdentity = identity.aniSkipSourceIdentity()
        val mediaInstanceId = identity.mediaInstanceId
        if (sourceIdentity == null || mediaInstanceId == null) {
            DiagnosticsLog.event(
                "Watch ignored embed progress without concrete video identity " +
                    "episode=${fmt(key.episodeNumber)} generation=${key.sourceGeneration} " +
                    "navigation=${identity.navigationGeneration}",
            )
            return
        }
        acceptProgress(
            data = data,
            identity = PlaybackIdentity(
                animeId = key.animeId,
                episodeNumber = key.episodeNumber,
                generation = key.sourceGeneration,
                mediaId = sourceIdentity,
                mediaInstanceId = mediaInstanceId,
            ),
            positionMs = positionMs,
            durationMs = durationMs,
        )
    }

    /** Commits a verified WebView natural end to disk before the caller may autoplay Next. */
    fun onEmbedPlaybackEnded(completion: EmbedPlaybackCompletion): Boolean {
        val data = (_state.value as? UiState.Success)?.data ?: return false
        if (data.isResolving) return false
        val commit = planEmbedCompletionCommit(
            completion = completion,
            currentPlaybackKey = data.embedPlaybackKey(),
            alreadyCommitted = committedEmbedPlaybackKey == completion.playbackKey,
        ) ?: run {
            DiagnosticsLog.event(
                "Watch ignored stale/invalid embed end episode=${fmt(completion.playbackKey.episodeNumber)} " +
                    "generation=${completion.playbackKey.sourceGeneration}",
            )
            return false
        }
        val completedFinalEpisode = isConfirmedFinalSeriesEpisode(
            episodeNumber = commit.playbackKey.episodeNumber,
            totalEpisodes = totalEpisodes,
            hasNextEpisode = data.hasNext,
        )
        val continuationEpisodeNumber = continuationEpisodeAfterNaturalEnd(
            currentEpisodeNumber = commit.playbackKey.episodeNumber,
            nextEpisodeNumber = data.episodes.getOrNull(data.currentIndex + 1)?.number,
            completedFinalEpisode = completedFinalEpisode,
        )
        val entry = HistoryEntry(
            anilistId = commit.playbackKey.animeId,
            title = data.seriesTitle,
            cover = data.artworkUrl,
            episodeNumber = commit.playbackKey.episodeNumber,
            episodeTitle = data.current.title,
            provider = commit.playbackKey.provider,
            category = commit.playbackKey.category,
            positionMs = commit.positionMs,
            durationMs = commit.durationMs,
            continuationEpisodeNumber = continuationEpisodeNumber,
            completed = completedFinalEpisode,
        )
        if (!LibraryStore.upsertHistoryDurably(entry)) {
            DiagnosticsLog.event(
                "Watch embed end disk commit failed episode=${fmt(commit.playbackKey.episodeNumber)}",
            )
            return false
        }
        val persisted = LibraryStore.historyFor(commit.playbackKey.animeId)
        if (
            persisted == null ||
            persisted.episodeNumber != commit.playbackKey.episodeNumber ||
            persisted.positionMs != commit.positionMs ||
            persisted.durationMs != commit.durationMs ||
            persisted.continuationEpisodeNumber != continuationEpisodeNumber ||
            persisted.completed != completedFinalEpisode
        ) {
            DiagnosticsLog.event(
                "Watch embed end persistence verification failed " +
                    "episode=${fmt(commit.playbackKey.episodeNumber)}",
            )
            return false
        }
        val progressIdentity = PlaybackIdentity(
            animeId = commit.playbackKey.animeId,
            episodeNumber = commit.playbackKey.episodeNumber,
            generation = commit.playbackKey.sourceGeneration,
            mediaId = data.chosenStream?.url
                ?: "embed:${commit.playbackKey.provider}:${commit.playbackKey.category}",
        )
        committedEmbedPlaybackKey = commit.playbackKey
        confirmedHistoryIdentity = progressIdentity
        lastKnownProgress = PlaybackProgressSnapshot(progressIdentity, commit.positionMs, commit.durationMs)
        lastProgressSaveIdentity = progressIdentity
        lastProgressSave = SystemClock.elapsedRealtime()
        maybeSyncAniListProgress(progressIdentity, commit.positionMs, commit.durationMs, totalEpisodes)
        DiagnosticsLog.event(
            "Watch embed end committed episode=${fmt(commit.playbackKey.episodeNumber)} " +
                "positionMs=${commit.positionMs}",
        )
        _state.value = UiState.Success(data.copy(startPositionMs = commit.positionMs))
        return true
    }

    /** Native progress must identify the MediaItem that actually produced the callback. */
    fun onNativeProgress(
        identity: PlaybackIdentity,
        positionMs: Long,
        durationMs: Long,
        playbackConfirmed: Boolean,
    ) {
        if (!playbackConfirmed) return
        val data = (_state.value as? UiState.Success)?.data ?: return
        val active = data.nativePlaybackTarget()
        if (active == null || !acceptsPlaybackProgress(identity, active)) {
            DiagnosticsLog.event(
                "Watch ignored stale native progress callbackAnime=${identity.animeId} " +
                    "callbackEpisode=${fmt(identity.episodeNumber)} callbackGeneration=${identity.generation} " +
                    "activeAnime=${data.anilistId} activeEpisode=${fmt(data.current.number)} " +
                    "activeGeneration=${data.playbackGeneration}",
            )
            return
        }
        val resumedLocalOwnership = PlaybackService.acknowledgeFreshLocalPlaybackProgress(
            animeId = identity.animeId,
            episodeNumber = identity.episodeNumber,
            generation = identity.generation,
            mediaId = identity.mediaId,
        )
        if (
            !resumedLocalOwnership &&
            PlaybackService.ownsRemotePlaybackHistory(
                animeId = identity.animeId,
                episodeNumber = identity.episodeNumber,
                generation = identity.generation,
                mediaId = identity.mediaId,
            )
        ) {
            return
        }
        acceptProgress(data, identity, positionMs, durationMs)
    }

    private fun acceptProgress(
        data: WatchData,
        identity: PlaybackIdentity,
        positionMs: Long,
        durationMs: Long,
    ) {
        val progress = progressSnapshotRetainingValidDuration(
            previous = lastKnownProgress,
            identity = identity,
            positionMs = positionMs,
            durationMs = durationMs,
        )
        lastKnownProgress = progress
        scheduleAniSkipLookup(data, identity, progress.durationMs)
        maybeSyncAniListProgress(identity, positionMs, durationMs, totalEpisodes)
        val lastIdentity = lastProgressSaveIdentity
        if (lastIdentity == null || !isSamePlaybackSession(lastIdentity, identity)) {
            lastProgressSave = 0L
            lastProgressSaveIdentity = identity
        }
        val now = SystemClock.elapsedRealtime()
        if (isNewConfirmedPlayback(confirmedHistoryIdentity, identity)) {
            confirmedHistoryIdentity = identity
            val previous = LibraryStore.historyFor(identity.animeId)
                ?.takeIf { it.episodeNumber == identity.episodeNumber }
            LibraryStore.upsertHistory(
                HistoryEntry(
                    anilistId = identity.animeId,
                    title = data.seriesTitle,
                    cover = data.artworkUrl,
                    episodeNumber = identity.episodeNumber,
                    episodeTitle = data.current.title,
                    provider = data.provider,
                    category = data.category.api,
                    positionMs = if (previous?.completed == true) {
                        positionMs.coerceAtLeast(0L)
                    } else {
                        maxOf(previous?.positionMs ?: 0L, positionMs.coerceAtLeast(0L))
                    },
                    durationMs = maxOf(previous?.durationMs ?: 0L, durationMs.coerceAtLeast(0L)),
                ),
            )
            lastProgressSave = now
            DiagnosticsLog.event(
                "Watch history confirmed episode=${fmt(identity.episodeNumber)} " +
                    "generation=${identity.generation}",
            )
            return
        }
        if (now - lastProgressSave < 8_000) return
        lastProgressSave = now
        LibraryStore.updateProgress(identity.animeId, identity.episodeNumber, positionMs, durationMs)
    }

    private fun <T> withProgressFlushedBeforeTransition(
        data: WatchData?,
        transition: () -> T,
    ): T {
        val candidateIdentity = lastKnownProgress?.identity
        val serviceOwnsCandidate = candidateIdentity != null &&
            PlaybackService.ownsRemotePlaybackHistory(
                animeId = candidateIdentity.animeId,
                episodeNumber = candidateIdentity.episodeNumber,
                generation = candidateIdentity.generation,
                mediaId = candidateIdentity.mediaId,
            )
        if (serviceOwnsCandidate || (candidateIdentity == null && PlaybackService.isRemotePlaybackHistoryOwnedByService())) {
            PlaybackService.flushRemotePlaybackHistory()
            return transition()
        }
        return flushProgressBeforeTransition(
            candidate = lastKnownProgress,
            confirmedIdentity = confirmedHistoryIdentity,
            activeTarget = data?.playbackTarget(),
            persist = ::persistTransitionProgress,
            transition = transition,
        )
    }

    private fun persistTransitionProgress(progress: PlaybackProgressSnapshot) {
        // updateProgress deliberately cannot create history. The matching entry was inserted by
        // the first confirmed playing callback; if it is gone or belongs to another episode, the
        // transition still proceeds without manufacturing an unconfirmed Continue Watching row.
        val saved = LibraryStore.historyFor(progress.identity.animeId)
            ?.takeIf { it.episodeNumber == progress.identity.episodeNumber }
            ?: run {
                DiagnosticsLog.event(
                    "Watch transition progress ignored without confirmed history " +
                        "episode=${fmt(progress.identity.episodeNumber)}",
                )
                return
            }
        val durationMs = maxOf(saved.durationMs, progress.durationMs)
        if (saved.positionMs != progress.positionMs || saved.durationMs != durationMs) {
            LibraryStore.updateProgress(
                progress.identity.animeId,
                progress.identity.episodeNumber,
                progress.positionMs,
                durationMs,
            )
        }
        lastProgressSave = SystemClock.elapsedRealtime()
        lastProgressSaveIdentity = progress.identity
        DiagnosticsLog.event(
            "Watch transition position committed episode=${fmt(progress.identity.episodeNumber)} " +
                "positionMs=${progress.positionMs}",
        )
    }

    /** Fold the player's error-time position into its last confirmed tick before failover. */
    private fun captureNativeFailureProgress(data: WatchData, streamUrl: String, positionMs: Long) {
        if (streamUrl.isBlank() || positionMs <= 0L) return
        val target = data.nativePlaybackTarget() ?: return
        val identity = PlaybackIdentity(
            animeId = data.anilistId,
            episodeNumber = data.current.number,
            generation = data.playbackGeneration,
            mediaId = streamUrl,
        )
        val confirmed = confirmedHistoryIdentity ?: return
        if (!acceptsPlaybackProgress(identity, target) || !isSamePlaybackSession(confirmed, identity)) return
        val previous = lastKnownProgress?.takeIf { isSamePlaybackSession(it.identity, identity) }
        lastKnownProgress = PlaybackProgressSnapshot(
            identity = identity,
            positionMs = positionMs,
            durationMs = previous?.durationMs
                ?: LibraryStore.historyFor(identity.animeId)?.durationMs
                ?: 0L,
        )
    }

    private fun resetProgressSession() {
        // Invalidate callbacks from the previous route even before the next MediaItem is ready.
        nextPlaybackGeneration()
        lastProgressSave = 0L
        lastProgressSaveIdentity = null
        lastKnownProgress = null
        confirmedHistoryIdentity = null
        committedEmbedPlaybackKey = null
        activeEmbedMediaIdentity = null
    }

    private fun WatchData.nativePlaybackTarget(): ActivePlaybackTarget? {
        if (isResolving) return null
        val chosen = chosenStream ?: return null
        if (chosen.isEmbed || ProviderCatalog.isEmbed(provider)) return null
        return ActivePlaybackTarget(
            animeId = anilistId,
            episodeNumber = current.number,
            generation = playbackGeneration,
            mediaIds = nativeMediaIds(),
        )
    }

    fun onEmbedPlaybackError(
        key: EmbedPlaybackKey,
        message: String,
        streamUrl: String,
        positionMs: Long,
    ) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (!acceptsEmbedPlaybackCallback(key, data.embedPlaybackKey())) {
            DiagnosticsLog.event(
                "Watch ignored stale embed error episode=${fmt(key.episodeNumber)} " +
                    "generation=${key.sourceGeneration}",
            )
            return
        }
        onPlaybackError(message, streamUrl, positionMs)
    }

    fun nextFromEmbed(key: EmbedPlaybackKey) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (acceptsEmbedPlaybackCallback(key, data.embedPlaybackKey())) next()
        else DiagnosticsLog.event("Watch ignored stale embed next episode=${fmt(key.episodeNumber)}")
    }

    fun prevFromEmbed(key: EmbedPlaybackKey) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (acceptsEmbedPlaybackCallback(key, data.embedPlaybackKey())) prev()
        else DiagnosticsLog.event("Watch ignored stale embed previous episode=${fmt(key.episodeNumber)}")
    }

    /**
     * TV tears the inline player down when leaving fullscreen. Persist the position it reached —
     * bypassing the periodic-save throttle — and fold it into [WatchData.startPositionMs], so the
     * next play (fullscreen pill, the inline play button, or re-picking the same episode chip)
     * resumes there instead of restarting from the stale resolve-time position.
     */
    fun commitPlaybackPosition() {
        val data = (_state.value as? UiState.Success)?.data ?: return
        val candidateIdentity = lastKnownProgress?.identity
        val serviceOwnsCandidate = candidateIdentity != null &&
            PlaybackService.ownsRemotePlaybackHistory(
                animeId = candidateIdentity.animeId,
                episodeNumber = candidateIdentity.episodeNumber,
                generation = candidateIdentity.generation,
                mediaId = candidateIdentity.mediaId,
            )
        if (serviceOwnsCandidate || (candidateIdentity == null && PlaybackService.isRemotePlaybackHistoryOwnedByService())) {
            PlaybackService.flushRemotePlaybackHistory()
            val saved = LibraryStore.historyFor(data.anilistId)
                ?.takeIf { it.episodeNumber == data.current.number }
            if (saved != null) {
                _state.value = UiState.Success(data.copy(startPositionMs = saved.positionMs))
            }
            return
        }
        val progress = lastKnownProgress ?: return
        if (!acceptsPlaybackProgress(progress.identity, data.playbackTarget()) || progress.positionMs <= 0) return
        lastProgressSave = SystemClock.elapsedRealtime()
        lastProgressSaveIdentity = progress.identity
        LibraryStore.updateProgress(
            progress.identity.animeId,
            progress.identity.episodeNumber,
            progress.positionMs,
            progress.durationMs,
        )
        DiagnosticsLog.event(
            "Watch commit position episode=${fmt(progress.identity.episodeNumber)} " +
                "positionMs=${progress.positionMs}",
        )
        _state.value = UiState.Success(data.copy(startPositionMs = progress.positionMs))
    }

    private fun maybeSyncAniListProgress(
        identity: PlaybackIdentity,
        positionMs: Long,
        durationMs: Long,
        totalEpisodesSnapshot: Int?,
    ) {
        val service = AccountService.active ?: return
        if (!SettingsStore.autoSyncAniList.value) return
        if (!shouldSyncAniListProgress(identity.episodeNumber, positionMs, durationMs)) return
        val episode = identity.episodeNumber.toInt()
        val syncKey = ProgressSyncKey(identity.animeId, episode, identity.generation, service)
        if (!syncedProgressEpisodes.add(syncKey)) return
        val animeIdSnapshot = identity.animeId
        val episodeNumberSnapshot = identity.episodeNumber
        viewModelScope.launch {
            runCatching {
                when (service) {
                    AccountService.ANILIST ->
                        repo.saveAniListProgress(animeIdSnapshot, episode, totalEpisodesSnapshot)
                    AccountService.MAL ->
                        repo.saveMalProgress(animeIdSnapshot, episode, totalEpisodesSnapshot)
                }
            }
                .onFailure {
                    it.rethrowIfCancellation()
                    syncedProgressEpisodes.remove(syncKey)
                    DiagnosticsLog.throwable(
                        "Watch ${service.label} progress sync failed id=$animeIdSnapshot " +
                            "episode=${fmt(episodeNumberSnapshot)}",
                        it,
                    )
                }
        }
    }

    fun playIndex(index: Int) {
        DiagnosticsLog.event("Watch playIndex requested index=$index spineSize=${spine.size}")
        if (index !in spine.indices) {
            DiagnosticsLog.event("Watch playIndex ignored out of bounds index=$index")
            return
        }
        failedProviders.clear()
        launchResolve(spine[index].number)
    }

    fun onPlaybackTeardownComplete(generation: Int) {
        val accepted = playbackTransitionBarrier.acknowledge(generation)
        DiagnosticsLog.event(
            "Watch player teardown completion generation=$generation accepted=$accepted",
        )
    }

    fun next() {
        DiagnosticsLog.event("Watch next requested")
        val cur = (_state.value as? UiState.Success)?.data?.currentIndex ?: return
        playIndex(cur + 1)
    }

    internal fun nextFromPlayback(requestedBy: PlaybackNavigationIdentity) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (!isCurrentPlaybackNavigation(requestedBy, data.playbackNavigationIdentity())) {
            DiagnosticsLog.event(
                "Watch ignored stale player next episode=${fmt(requestedBy.episodeNumber)} " +
                    "generation=${requestedBy.playbackGeneration}",
            )
            return
        }
        next()
    }

    fun prev() {
        DiagnosticsLog.event("Watch prev requested")
        val cur = (_state.value as? UiState.Success)?.data?.currentIndex ?: return
        playIndex(cur - 1)
    }

    internal fun prevFromPlayback(requestedBy: PlaybackNavigationIdentity) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (!isCurrentPlaybackNavigation(requestedBy, data.playbackNavigationIdentity())) {
            DiagnosticsLog.event(
                "Watch ignored stale player previous episode=${fmt(requestedBy.episodeNumber)} " +
                    "generation=${requestedBy.playbackGeneration}",
            )
            return
        }
        prev()
    }

    fun retry() {
        DiagnosticsLog.event("Watch retry requested episode=${fmt(lastRequestedNumber)}")
        failedProviders.clear()
        unavailableSources.removeAll { it.episode == lastRequestedNumber && it.category == category }
        launchResolve(lastRequestedNumber)
    }

    /** All episode resolution goes through here so a failure becomes an error state, not a crash. */
    private fun launchResolve(
        number: Double,
        resolvingNotice: String? = null,
        progressAlreadyFlushed: Boolean = false,
        failurePolicy: ResolutionFailurePolicy = ResolutionFailurePolicy.RESTORE_PREVIOUS,
        rollbackRouting: PlaybackRoutingState? = null,
        preferredProviderToCommit: String? = null,
    ) {
        val key = EpisodeResolutionKey(
            animeId = anilistId,
            episodeNumber = number,
            preferredProvider = preferred,
            category = category,
            excludedProviders = failedProviders.toSet(),
        )
        if (isDuplicateEpisodeResolution(resolveJob?.isActive == true, pendingResolution?.key, key)) {
            DiagnosticsLog.event("Watch ignored duplicate resolve episode=${fmt(number)}")
            return
        }
        val current = (_state.value as? UiState.Success)?.data
        // Bank the last accepted native or embed tick before invalidating callbacks from the media
        // being replaced. Source/category selection already did this before it changed its routing
        // fields, hence the explicit hand-off flag.
        val request = if (progressAlreadyFlushed) {
            activeNativePlaybackIdentity = null
            activeEmbedMediaIdentity = null
            requestGate.nextRequest()
        } else {
            withProgressFlushedBeforeTransition(current) {
                activeNativePlaybackIdentity = null
                activeEmbedMediaIdentity = null
                requestGate.nextRequest()
            }
        }
        val pending = PendingResolution(request, key)
        pendingResolution = pending
        val teardownGeneration = current?.let { nextPlaybackGeneration() }
        val teardownTicket = teardownGeneration?.let(playbackTransitionBarrier::begin)
        DiagnosticsLog.event(
            "Watch launchResolve episode=${fmt(number)} " +
                "request=${request.sessionGeneration}/${request.requestGeneration} " +
                "teardown=${teardownGeneration ?: "none"}",
        )
        // Publish the invalidation synchronously. WatchScreen removes/stops the outgoing surface
        // and acknowledges this generation before the resolver is allowed to make its first call.
        if (current != null) {
            _state.value = UiState.Success(
                current.copy(
                    isResolving = true,
                    playbackGeneration = checkNotNull(teardownGeneration),
                    playbackTeardownGeneration = teardownGeneration,
                    notice = resolvingNotice,
                ),
            )
        }
        resolveJob?.cancel()
        cancelAniSkipWork()
        sourceValidationJob?.cancel()
        resolveJob = viewModelScope.launch {
            try {
                teardownTicket?.let { ticket ->
                    DiagnosticsLog.event(
                        "Watch waiting for player teardown generation=${ticket.generation}",
                    )
                    playbackTransitionBarrier.await(ticket)
                    DiagnosticsLog.event(
                        "Watch player teardown acknowledged generation=${ticket.generation}",
                    )
                }
                ensureCurrentRequest(request)
                resolveAndPlay(
                    number = number,
                    request = request,
                    failurePolicy = failurePolicy,
                    rollbackRouting = rollbackRouting,
                    preferredProviderToCommit = preferredProviderToCommit,
                )
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                if (!requestGate.isCurrentRequest(request)) return@launch
                cancelAniSkipWork()
                requestGate.finishRequest(request)
                DiagnosticsLog.throwable("Watch resolve failed id=$anilistId episode=${fmt(number)}", e)
                _loadingStatus.value = null
                val message = e.message ?: "Failed to load episode"
                val previous = (_state.value as? UiState.Success)?.data
                val history = previous?.let { LibraryStore.historyFor(it.anilistId) }
                val effectiveFailurePolicy = effectiveResolutionFailurePolicy(
                    requestedPolicy = failurePolicy,
                    previousEpisodeNumber = previous?.current?.number,
                    requestedEpisodeNumber = number,
                    historyEpisodeNumber = history?.episodeNumber,
                    historyContinuationEpisodeNumber = history?.continuationEpisodeNumber,
                    historyCompleted = history?.completed == true,
                )
                restorePlaybackRouting(
                    routingAfterResolutionFailure(
                        policy = effectiveFailurePolicy,
                        rollback = rollbackRouting,
                        current = playbackRoutingState(),
                    ),
                )
                val rollbackPosition = previous?.let { data ->
                    history?.resumePositionFor(data.current.number)
                        ?: data.startPositionMs
                } ?: 0L
                val rollbackData = previous.watchDataAfterFailedResolution(
                    policy = effectiveFailurePolicy,
                    refreshedSourceOptions = previous?.let { sourceOptions(it.current.number) }.orEmpty(),
                    resumePositionMs = rollbackPosition,
                    failureNotice = message,
                )
                _state.value = rollbackData?.let { UiState.Success(it) } ?: UiState.Error(message)
            } finally {
                teardownTicket?.let(playbackTransitionBarrier::finish)
                if (pendingResolution?.token == request) pendingResolution = null
            }
        }
    }

    private fun invalidatePendingResolution() {
        pendingResolution = null
        playbackTransitionBarrier.cancel()
    }

    private fun scheduleAniSkipLookup(
        data: WatchData,
        playbackIdentity: PlaybackIdentity,
        actualDurationMs: Long,
    ) {
        val durationBucketMs = aniSkipDurationBucketMs(actualDurationMs) ?: return
        val expected = data.aniSkipLookupIdentity(
            request = requestGate.currentRequest(),
            playbackIdentity = playbackIdentity,
            durationBucketMs = durationBucketMs,
        )
        if (aniSkipLookupIdentity == expected) return

        aniSkipLookupJob?.cancel()
        aniSkipLookupIdentity = expected
        if (
            data.aniSkipLookupStatus != AniSkipLookupStatus.LOADING ||
            data.aniSkipSegments.isNotEmpty()
        ) {
            _state.value = UiState.Success(
                data.copy(
                    aniSkipSegments = emptyList(),
                    aniSkipLookupStatus = AniSkipLookupStatus.LOADING,
                ),
            )
        }
        aniSkipLookupJob = viewModelScope.launch {
            val segments = runCatching {
                repo.aniSkipSegments(
                    anilistId = expected.animeId,
                    episode = expected.episodeNumber,
                    // Use the same stable duration as the lookup identity and repository cache.
                    // Player containers occasionally jitter by a few milliseconds between ticks.
                    actualDurationMs = durationBucketMs,
                    sourceIdentity = expected.mediaId,
                )
            }.onFailure {
                it.rethrowIfCancellation()
                DiagnosticsLog.throwable(
                    "Watch AniSkip lookup failed id=${expected.animeId} " +
                        "episode=${fmt(expected.episodeNumber)}",
                    it,
                )
            }.getOrDefault(emptyList())

            if (aniSkipLookupIdentity != expected || !requestGate.isCurrentRequest(expected.request)) {
                return@launch
            }
            val currentData = (_state.value as? UiState.Success)?.data ?: return@launch
            val currentProgress = lastKnownProgress ?: return@launch
            val currentDurationBucket = aniSkipDurationBucketMs(currentProgress.durationMs) ?: return@launch
            val current = currentData.aniSkipLookupIdentity(
                request = requestGate.currentRequest(),
                playbackIdentity = currentProgress.identity,
                durationBucketMs = currentDurationBucket,
            )
            if (!canPublishAniSkipSegments(expected, current)) return@launch
            _state.value = UiState.Success(
                currentData.copy(
                    aniSkipSegments = segments,
                    aniSkipLookupStatus = AniSkipLookupStatus.COMPLETE,
                ),
            )
            DiagnosticsLog.event(
                "Watch AniSkip published id=${expected.animeId} " +
                    "episode=${fmt(expected.episodeNumber)} markers=${segments.size} " +
                    "generation=${expected.sourceGeneration}",
            )
        }
    }

    private fun cancelAniSkipWork() {
        aniSkipLookupIdentity = null
        aniSkipLookupJob?.cancel()
        aniSkipLookupJob = null
    }

    private fun nextPlaybackGeneration(): Int {
        playbackGenerationCounter = if (playbackGenerationCounter == Int.MAX_VALUE) {
            1
        } else {
            playbackGenerationCounter + 1
        }
        return playbackGenerationCounter
    }

    fun onPlaybackError(
        message: String,
        streamUrl: String,
        positionMs: Long,
        attemptedStreamUrls: Set<String> = setOf(streamUrl),
    ) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isResolving) return
        captureNativeFailureProgress(data, streamUrl, positionMs)
        withProgressFlushedBeforeTransition(data) {
            activeNativePlaybackIdentity = null
        }
        DiagnosticsLog.event(
            "Watch playback error provider=${data.provider} episode=${data.current.displayNumber} " +
                "streamHost=${runCatching { Uri.parse(streamUrl).host }.getOrNull() ?: "unknown"} " +
                "positionMs=$positionMs message=${message.take(160)}",
        )

        if (data.provider == "allanime" && streamUrl.isNotBlank()) {
            val attemptedUrls = (attemptedStreamUrls + streamUrl).filterTo(linkedSetOf(), String::isNotBlank)
            if (!failedStreamUrls.addAll(attemptedUrls)) {
                DiagnosticsLog.event("Watch ignored duplicate AllAnime stream failure host=${Uri.parse(streamUrl).host}")
                return
            }
            val next = nextProviderStream(
                provider = data.provider,
                sources = data.sources,
                currentUrl = streamUrl,
                failedUrls = failedStreamUrls,
            )
            if (next != null) {
                cancelAniSkipWork()
                val failed = data.sources.streams.firstOrNull { it.url == streamUrl }
                val resume = maxOf(data.startPositionMs, positionMs.coerceAtLeast(0L))
                DiagnosticsLog.event(
                    "Watch AllAnime internal fallback failed=${failed?.diagnosticLabel() ?: "unknown"} " +
                        "next=${next.diagnosticLabel()} attempted=${failedStreamUrls.size} resumeMs=$resume",
                )
                _state.value = UiState.Success(
                    data.copy(
                        chosenStream = next,
                        aniSkipSegments = emptyList(),
                        aniSkipLookupStatus = AniSkipLookupStatus.AWAITING_DURATION,
                        startPositionMs = resume,
                        playbackGeneration = nextPlaybackGeneration(),
                        notice = "${failed?.label ?: "AllAnime source"} failed. Trying ${next.label}…",
                    ),
                )
                return
            }
            DiagnosticsLog.event(
                "Watch AllAnime streams exhausted attempted=${failedStreamUrls.size}; trying another provider",
            )
        }

        failedProviders += data.provider
        unavailableSources += EpisodeSourceKey(data.current.number, data.provider, data.category)
        launchResolve(
            number = data.current.number,
            resolvingNotice =
                "${ProviderCatalog.label(data.provider)} failed: $message. Trying another source…",
            progressAlreadyFlushed = true,
            failurePolicy = ResolutionFailurePolicy.TERMINAL,
        )
    }

    /** Reject failures queued by a MediaItem after another episode/source has taken ownership. */
    fun onNativePlaybackError(
        identity: PlaybackIdentity,
        message: String,
        streamUrl: String,
        positionMs: Long,
        attemptedStreamUrls: Set<String>,
    ) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        val active = data.nativePlaybackTarget()
        if (active == null || !acceptsNativePlaybackError(identity, streamUrl, active)) {
            DiagnosticsLog.event(
                "Watch ignored stale native error episode=${fmt(identity.episodeNumber)} " +
                    "generation=${identity.generation}",
            )
            return
        }
        val currentSessionAttempts = attemptedStreamUrls.filterTo(linkedSetOf()) {
            it in active.mediaIds
        }
        onPlaybackError(message, streamUrl, positionMs, currentSessionAttempts)
    }

    private fun sourceOptions(number: Double): List<WatchSourceOption> =
        visibleSourceOptions(
            candidates = availableSourceOptions(mergedEpisodes, number),
            isConfirmed = { EpisodeSourceKey(number, it.provider, it.category) in confirmedSources },
            isUnavailable = { EpisodeSourceKey(number, it.provider, it.category) in unavailableSources },
        )

    /**
     * Episode catalogs can contain stale rows whose source endpoint returns no stream. Validate
     * those rows in small parallel batches and publish only confirmed server/audio pairs; this
     * keeps the picker truthful without delaying the first playable provider.
     */
    private fun launchSourceValidation(number: Double, request: PlaybackRequestToken) {
        if (!requestGate.isCurrentRequest(request) || requestGate.hasPendingRequest()) return
        sourceValidationJob?.cancel()
        sourceValidationJob = viewModelScope.launch {
            ensureCurrentRequest(request)
            // TV sticks have ~1GB RAM and validation is heavy (scraper networking plus Flixcloud
            // WebView loads) — running it eagerly at 4-way concurrency alongside 1080p playback
            // contributed to low-memory kills mid-episode. Give playback a head start and go
            // one server at a time there.
            val validationConcurrency = if (AppGraph.isTv) 1 else SOURCE_VALIDATION_CONCURRENCY
            if (AppGraph.isTv) kotlinx.coroutines.delay(10_000)
            ensureCurrentRequest(request)
            val episodesSnapshot = mergedEpisodes
            val requestAnimeId = anilistId
            val candidates = availableSourceOptions(episodesSnapshot, number).filter { option ->
                val key = EpisodeSourceKey(number, option.provider, option.category)
                key !in confirmedSources && key !in unavailableSources
            }
            if (candidates.isEmpty()) {
                val data = (_state.value as? UiState.Success)?.data
                if (data != null && data.current.number == number && mergedIncludesAnivexa) {
                    _state.value = UiState.Success(data.copy(isLoadingMoreSources = false))
                }
                return@launch
            }

            val initial = (_state.value as? UiState.Success)?.data
            if (initial != null && initial.current.number == number) {
                _state.value = UiState.Success(initial.copy(isLoadingMoreSources = true))
            }
            val providerNames = episodesSnapshot.providerNames.toSet()
            candidates.chunked(validationConcurrency).forEachIndexed { batchIndex, batch ->
                val results = batch.map { option ->
                    async {
                        val resolution = runCatching {
                            repo.resolveSources(
                                anilistId = requestAnimeId,
                                number = number,
                                preferred = option.provider,
                                category = option.category,
                                episodes = episodesSnapshot,
                                excludedProviders = providerNames - option.provider,
                                maxAttempts = 1,
                                mode = SourceResolutionMode.BACKGROUND_VALIDATION,
                            )
                        }.onFailure {
                            it.rethrowIfCancellation()
                            DiagnosticsLog.throwable(
                                "Watch source validation failed provider=${option.provider} " +
                                    "category=${option.category.api} episode=${fmt(number)}",
                                it,
                            )
                        }.getOrNull()
                        ensureCurrentRequest(request)
                        option to (resolution?.resolved?.provider == option.provider)
                    }
                }.awaitAll()

                ensureCurrentRequest(request)
                results.forEach { (option, available) ->
                    val key = EpisodeSourceKey(number, option.provider, option.category)
                    if (available) {
                        confirmedSources += key
                        unavailableSources -= key
                    } else {
                        unavailableSources += key
                    }
                }
                val data = (_state.value as? UiState.Success)?.data ?: return@launch
                if (data.current.number != number) return@launch
                val hasMore = (batchIndex + 1) * validationConcurrency < candidates.size
                _state.value = UiState.Success(
                    data.copy(
                        sourceOptions = sourceOptions(number),
                        isLoadingMoreSources = !mergedIncludesAnivexa || hasMore,
                    ),
                )
            }
        }
    }

    private suspend fun ensureCurrentSession(request: PlaybackRequestToken) {
        currentCoroutineContext().ensureActive()
        requestGate.requireCurrentSession(request)
    }

    private suspend fun ensureCurrentRequest(request: PlaybackRequestToken) {
        currentCoroutineContext().ensureActive()
        requestGate.requireCurrentRequest(request)
    }

    private fun fmt(n: Double): String = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    private fun StreamItem.diagnosticLabel(): String {
        val type = when {
            isEmbed -> "embed"
            isHls -> "hls"
            else -> "direct"
        }
        return "$type label=${label.take(48)} audio=${audio ?: "unknown"} " +
            "height=${height ?: "auto"} host=${runCatching { Uri.parse(url).host }.getOrNull() ?: "unknown"}"
    }
}

private fun WatchData.aniSkipLookupIdentity(
    request: PlaybackRequestToken,
    playbackIdentity: PlaybackIdentity,
    durationBucketMs: Long,
): AniSkipLookupIdentity = AniSkipLookupIdentity(
    request = request,
    animeId = anilistId,
    episodeNumber = current.number,
    provider = provider,
    category = category,
    sourceGeneration = playbackGeneration,
    mediaId = playbackIdentity.mediaId,
    durationBucketMs = durationBucketMs,
    mediaInstanceId = playbackIdentity.mediaInstanceId,
)

private fun WatchData.nativeMediaIds(): Set<String> {
    val active = chosenStream ?: return emptySet()
    if (active.isEmbed || ProviderCatalog.isEmbed(provider)) return emptySet()
    return buildSet {
        add(active.url)
        sources.streams.filterNot(StreamItem::isEmbed).forEach { add(it.url) }
    }
}

/**
 * Which of the episode's server/audio pairs are shown in the picker. Fast providers (the Miruro
 * pipe's native HLS set and the API-backed Anivexa lookups) appear the moment the catalog has
 * them — quick and reliable, no waiting on a per-server validation round-trip. Slower scrapers
 * must be confirmed playable first so the list never advertises a dead endpoint. Anything proven
 * unavailable for this episode is always hidden, even a fast one that failed validation.
 */
internal fun visibleSourceOptions(
    candidates: List<WatchSourceOption>,
    isConfirmed: (WatchSourceOption) -> Boolean,
    isUnavailable: (WatchSourceOption) -> Boolean,
): List<WatchSourceOption> = candidates.filter { option ->
    !isUnavailable(option) && (isConfirmed(option) || ProviderCatalog.isFast(option.provider))
}

/** Only server/audio pairs carrying the episode currently on screen are valid controls. */
internal fun availableSourceOptions(
    episodes: EpisodesResult,
    number: Double,
): List<WatchSourceOption> = episodes.providers
    .flatMap { provider ->
        provider.categories.mapNotNull { category ->
            val providerEpisodes = provider.episodes(category)
            val hasCurrentEpisode = providerEpisodes.any { it.number == number }
            if (!hasCurrentEpisode) return@mapNotNull null
            WatchSourceOption(
                provider = provider.name,
                category = category,
                hasCurrentEpisode = true,
                episodeCount = providerEpisodes.size,
            )
        }
    }
    .sortedWith(compareBy<WatchSourceOption> { ProviderCatalog.sortKey(it.provider) }.thenBy { it.category.ordinal })

internal fun shouldSyncAniListProgress(episodeNumber: Double, positionMs: Long, durationMs: Long): Boolean {
    if (episodeNumber < 1 || episodeNumber % 1.0 != 0.0) return false
    if (durationMs < MIN_ANILIST_SYNC_DURATION_MS || positionMs <= 0) return false
    return positionMs.toDouble() / durationMs >= ANILIST_SYNC_WATCHED_FRACTION
}

private const val MIN_ANILIST_SYNC_DURATION_MS = 60_000L
private const val ANILIST_SYNC_WATCHED_FRACTION = 0.80
private const val SOURCE_VALIDATION_CONCURRENCY = 4

/** Provider-specific first-player policy, applied only after that provider has resolved sources. */
internal fun pickProviderStream(provider: String, sources: SourcesResult): StreamItem? {
    val direct = sources.streams.filterNot(StreamItem::isEmbed)
    val embeds = sources.embedStreams

    return when (provider) {
        // Kwik's fixed-quality CDN URLs currently return 403 outside its page. The embed carries
        // the cookies/player flow those URLs require, so do not leave Media3 buffering forever.
        "kiwi" -> embeds.firstOrNull(StreamItem::isActive)
            ?: embeds.firstOrNull()
            ?: bestHls(direct)
            ?: direct.firstOrNull()

        // Ally mixes AllAnime progressive files with an unreliable HLS mirror. Its direct files
        // are independently playable and retain the selected SUB/DUB category.
        "ally" -> direct.firstOrNull { !it.isHls }
            ?: bestHls(direct)
            ?: embeds.firstOrNull()

        else -> bestHls(direct)
            ?: direct.firstOrNull()
            ?: embeds.firstOrNull(StreamItem::isActive)
            ?: embeds.firstOrNull()
            ?: sources.streams.firstOrNull()
    }
}

/** Every distinct stream in the same order the provider's first-player policy would choose it. */
internal fun providerStreamOrder(
    provider: String,
    sources: SourcesResult,
): List<StreamItem> {
    val remaining = sources.streams.distinctBy(StreamItem::url).toMutableList()
    return buildList {
        while (remaining.isNotEmpty()) {
            val next = pickProviderStream(provider, sources.copy(streams = remaining)) ?: break
            add(next)
            remaining.removeAll { it.url == next.url }
        }
    }
}

/** Finds the next untried stream after the one that failed, wrapping once before giving up. */
internal fun nextProviderStream(
    provider: String,
    sources: SourcesResult,
    currentUrl: String,
    failedUrls: Set<String>,
): StreamItem? {
    val ordered = providerStreamOrder(provider, sources)
    if (ordered.isEmpty()) return null
    val currentIndex = ordered.indexOfFirst { it.url == currentUrl }
    val candidates = if (currentIndex >= 0) {
        ordered.drop(currentIndex + 1) + ordered.take(currentIndex)
    } else {
        ordered
    }
    return candidates.firstOrNull { it.url !in failedUrls }
}

/** A saved global choice wins; before the first explicit choice, keep the launch route behavior. */
internal fun preferredProviderForWatch(storedPreferred: String?, routeProvider: String): String {
    val stored = storedPreferred?.trim()?.lowercase().orEmpty()
    if (stored.isNotBlank() && stored != DEFAULT_PREFERRED_PROVIDER) return stored
    return routeProvider.trim().lowercase().ifBlank { DEFAULT_PREFERRED_PROVIDER }
}

private fun normalizedSkipTimes(skip: SkipTimes?): SkipTimes? {
    val intro = introRange(skip)
    val outro = outroRange(skip)
    if (intro == null && outro == null) return null
    return SkipTimes(intro?.first, intro?.second, outro?.first, outro?.second)
}

private fun introRange(skip: SkipTimes?): Pair<Double, Double>? {
    val end = skip?.introEnd ?: return null
    val start = skip.introStart ?: 0.0
    return (start to end).takeIf { start >= 0.0 && end > start }
}

private fun outroRange(skip: SkipTimes?): Pair<Double, Double>? {
    val start = skip?.outroStart ?: return null
    val end = skip.outroEnd ?: return null
    return (start to end).takeIf { start >= 0.0 && end > start }
}

private fun bestHls(streams: List<StreamItem>): StreamItem? = streams
    .filter(StreamItem::isHls)
    .maxByOrNull { (it.height ?: 0) + if (it.isActive) 100_000 else 0 }

internal fun pickNavigationSpine(
    episodes: EpisodesResult,
    preferred: String,
    category: Category,
): List<EpisodeItem> {
    fun normalized(provider: String): List<EpisodeItem> = episodes.provider(provider)
        ?.episodes(category)
        .orEmpty()
        .distinctBy(EpisodeItem::number)
        .sortedBy(EpisodeItem::number)

    val providerOrder = buildList {
        if (episodes.provider(preferred) != null) add(preferred)
        addAll(episodes.providerNames.filterNot { it == preferred })
    }
    return providerOrder
        .asSequence()
        .flatMap { normalized(it).asSequence() }
        // Provider order is significant: distinctBy keeps preferred metadata for overlaps.
        .distinctBy(EpisodeItem::number)
        .sortedBy(EpisodeItem::number)
        .toList()
}

/** Never silently turn a missing requested episode into the first row. */
internal fun navigationEpisodeIndex(episodes: List<EpisodeItem>, number: Double): Int? =
    episodes.indexOfFirst { it.number == number }.takeIf { it >= 0 }

internal data class CatalogSpinePublication(
    val episodes: List<EpisodeItem>,
    val currentIndex: Int?,
)

/** Build one consistent catalog snapshot, or reject it if it would orphan the visible episode. */
internal fun catalogSpinePublication(
    rebuiltSpine: List<EpisodeItem>,
    visibleEpisodeNumber: Double?,
): CatalogSpinePublication? {
    if (rebuiltSpine.isEmpty()) return null
    val currentIndex = visibleEpisodeNumber?.let { number ->
        navigationEpisodeIndex(rebuiltSpine, number) ?: return null
    }
    return CatalogSpinePublication(rebuiltSpine, currentIndex)
}

internal fun shouldRetryAfterCatalogMerge(
    hasResolvedSource: Boolean,
    resolvedAgainstCatalogGeneration: Long,
    currentCatalogGeneration: Long,
): Boolean = !hasResolvedSource && resolvedAgainstCatalogGeneration != currentCatalogGeneration

/** A capped fast pass must never become the final answer while unchecked providers remain. */
internal fun shouldRunExhaustiveProviderFallback(
    hasResolvedSource: Boolean,
    exhaustiveResolutionAttempted: Boolean,
): Boolean = !hasResolvedSource && !exhaustiveResolutionAttempted
