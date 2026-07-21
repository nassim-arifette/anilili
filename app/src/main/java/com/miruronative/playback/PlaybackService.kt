package com.miruronative.playback

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebSettings
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.CastPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.diagnostics.playerErrorDiagnosticCategory
import com.miruronative.diagnostics.privacySafeUrlDiagnosticLabel
import com.miruronative.MainActivity
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AccountService
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.settings.SettingsStore
import com.miruronative.ui.nav.Routes
import com.miruronative.ui.watch.shouldSyncAniListProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Process-wide playback state used by the activity to decide when PiP is appropriate. */
object PlaybackStatus {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()
    private val _pictureInPictureSnapshot = MutableStateFlow(PictureInPicturePlaybackSnapshot())
    internal val pictureInPictureSnapshot = _pictureInPictureSnapshot.asStateFlow()

    internal fun update(playing: Boolean) {
        _isPlaying.value = playing
        _pictureInPictureSnapshot.update { it.copy(isPlaying = playing) }
    }

    internal fun updatePlaybackRoute(route: PlaybackRoute) {
        _pictureInPictureSnapshot.update { it.copy(playbackRoute = route) }
    }

    internal fun updateNativeSurface(active: Boolean) {
        _pictureInPictureSnapshot.update {
            it.copy(
                hasNativeSurface = active,
                sourceRect = it.sourceRect.takeIf { active },
            )
        }
    }

    internal fun updateVideoSize(width: Int, height: Int, pixelWidthHeightRatio: Float) {
        _pictureInPictureSnapshot.update {
            it.copy(aspectRatio = pictureInPictureAspectRatio(width, height, pixelWidthHeightRatio))
        }
    }

    internal fun updateSourceRect(sourceRect: PictureInPictureSourceRect?) {
        _pictureInPictureSnapshot.update { current ->
            current.copy(sourceRect = sourceRect?.takeIf(PictureInPictureSourceRect::isUsable))
        }
    }

    internal fun resetPlayerState() {
        _isPlaying.value = false
        _pictureInPictureSnapshot.update {
            it.copy(
                isPlaying = false,
                playbackRoute = PlaybackRoute.LOCAL,
                aspectRatio = PictureInPictureAspectRatio.DEFAULT,
            )
        }
    }
}

private class EpisodeNavigatorRegistration(
    val owner: WatchPlaybackOwnerToken,
    var playback: EpisodeNavigatorPlaybackIdentity,
    val navigate: (direction: Int) -> Unit,
)

private data class RemoteProgressSyncKey(
    val animeId: Int,
    val episode: Int,
    val generation: Int,
    val service: AccountService,
)

/**
 * Owns the app playback player so playback survives activity backgrounding and exposes Android
 * notification, lock-screen, headset, Bluetooth, and Cast controls through a MediaSession.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var castPlayer: CastPlayer
    private lateinit var session: MediaSession
    private lateinit var httpFactory: HttpDataSource.Factory
    private val remoteHistoryCoordinator = RemoteCastHistoryCoordinator()
    private val remoteHistoryRegistry = RemotePlaybackHistoryRegistry()
    private val remoteHistoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var remoteHistoryPreferences: SharedPreferences
    private var remoteHistoryRegistryDirty = false
    private val syncedRemoteProgress = mutableSetOf<RemoteProgressSyncKey>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.event("PlaybackService.onCreate")
        remoteHistoryPreferences = getSharedPreferences(REMOTE_HISTORY_PREFS, Context.MODE_PRIVATE)
        restoreRemotePlaybackHistoryMetadata()
        remoteHistoryMetadataRegistrar = ::rememberRemotePlaybackHistoryMetadata
        remoteHistoryMetadataResolver = remoteHistoryRegistry::resolve
        val playerUserAgent = runCatching { WebSettings.getDefaultUserAgent(this).replace("; wv", "") }
            .getOrDefault(FALLBACK_PLAYER_USER_AGENT)
        activeUserAgent = playerUserAgent
        val defaultHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(playerUserAgent)
            .setAllowCrossProtocolRedirects(true)
        val cronetEngine = CronetUtil.buildCronetEngine(this, null, true)
        httpFactory = if (cronetEngine != null) {
            DiagnosticsLog.event("PlaybackService HTTP transport=Cronet provider=${cronetEngine.versionString}")
            CronetDataSource.Factory(cronetEngine, Runnable::run)
                .setUserAgent(playerUserAgent)
        } else {
            DiagnosticsLog.event("PlaybackService HTTP transport=DefaultHttpDataSource")
            defaultHttpFactory
        }
        activeHttpFactory = httpFactory
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(MediaCache.get(this))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val playbackDataSource = DataSource.Factory {
            FlixcloudPlaylistDataSource(cacheDataSource.createDataSource()) { activePlaylistKey }
        }

        // Fire TV sticks have ~1GB of RAM and were getting killed by the low-memory killer mid
        // episode: ExoPlayer's default ~50s buffer of 1080p HLS plus the app's WebViews exceed
        // what the OS tolerates. A 30s cap keeps the buffer's memory (and network bursts)
        // proportionate to the device; phones keep the defaults.
        val isTvDevice = (getSystemService(UI_MODE_SERVICE) as? android.app.UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val loadControl = if (isTvDevice) {
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
                .build()
        } else {
            androidx.media3.exoplayer.DefaultLoadControl.Builder().build()
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playbackDataSource))
            .setRenderersFactory(SubtitleDelayRenderersFactory(this))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
            }
        castPlayer = CastPlayer.Builder(this)
            .setLocalPlayer(player)
            .setTransferCallback { sourcePlayer, targetPlayer ->
                val sourceRoute = sourcePlayer.playbackRoute()
                val targetRoute = targetPlayer.playbackRoute()
                val sourceMetadata = remoteHistoryMetadataFor(sourcePlayer.currentMediaItem)
                if (targetRoute == PlaybackRoute.REMOTE) {
                    remoteHistoryHandoffIdentity = null
                } else if (sourceRoute == PlaybackRoute.REMOTE) {
                    // Keep the exact receiver identity until Watch observes a fresh local sample.
                    // This closes the short window where its last cached tick is still pre-Cast.
                    remoteHistoryHandoffIdentity = sourceMetadata?.identity
                }
                if (targetRoute == PlaybackRoute.REMOTE && sourceMetadata != null) {
                    remoteHistoryOwnerIdentity = sourceMetadata.identity
                    val matchingHistoryExists = LibraryStore.historyFor(sourceMetadata.animeId)
                        ?.episodeNumber == sourceMetadata.episodeNumber
                    if (sourcePlayer.isPlaying && matchingHistoryExists) {
                        remoteHistoryCoordinator.adoptConfirmedPlayback(
                            metadata = sourceMetadata,
                            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        )
                    }
                }
                if (sourceRoute == PlaybackRoute.REMOTE) {
                    // Capture the receiver's exact last position before Media3 swaps the active
                    // player. Once local playback owns the session again, UI persistence resumes.
                    persistRemoteProgress(sourcePlayer, force = true, isRemote = true)
                }
                val directive = castTransferDirective(
                    sourceRoute = sourceRoute,
                    targetRoute = targetRoute,
                    hasLocalPlaybackOwner = localPlaybackOwners.hasOwner(),
                )
                DiagnosticsLog.event(
                    "PlaybackService Cast transfer source=$sourceRoute target=$targetRoute " +
                        "directive=$directive",
                )
                if (directive == CastTransferDirective.TRANSFER_LOCAL_PAUSED) {
                    // Copy the same state as Media3's default callback, but never send a transient
                    // play command to ExoPlayer while it is still becoming the active route.
                    PlayerTransferState.builderFromPlayer(sourcePlayer)
                        .setPlayWhenReady(false)
                        .build()
                        .setToPlayer(targetPlayer)
                    suppressMediaButtonResume = true
                } else {
                    CastPlayer.TransferCallback.DEFAULT.transferState(sourcePlayer, targetPlayer)
                }
                if (targetRoute == PlaybackRoute.LOCAL) remoteHistoryOwnerIdentity = null
            }
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        DiagnosticsLog.event("PlaybackService player isPlaying=$isPlaying")
                        PlaybackStatus.update(isPlaying)
                        // Any playback that actually starts (notification play, in-app play)
                        // re-arms the media buttons; the suppression only covers the window
                        // between the lifecycle pause and the next deliberate start.
                        if (isPlaying) allowMediaButtonResume()
                        // A remote pause is a lifecycle boundary: bank it synchronously rather
                        // than waiting for another playing sample that may never arrive.
                        persistRemoteProgress(castPlayer, force = !isPlaying)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        DiagnosticsLog.event("PlaybackService player state=${playbackState.stateName()}")
                        if (playbackState == Player.STATE_ENDED) {
                            persistRemoteCompletion(castPlayer)
                        } else {
                            persistRemoteProgress(castPlayer)
                        }
                    }

                    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                        val route = if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                            PlaybackRoute.REMOTE
                        } else {
                            PlaybackRoute.LOCAL
                        }
                        PlaybackStatus.updatePlaybackRoute(route)
                        DiagnosticsLog.event(
                            "PlaybackService route=${route.name.lowercase()} " +
                                "routingController=${deviceInfo.routingControllerId ?: "none"}",
                        )
                        if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                            persistRemoteProgress(castPlayer)
                        } else {
                            remoteHistoryOwnerIdentity = null
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        PlaybackStatus.updateVideoSize(
                            width = videoSize.width,
                            height = videoSize.height,
                            pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
                        )
                        DiagnosticsLog.event(
                            "PlaybackService video size=${videoSize.width}x${videoSize.height} " +
                                "pixelRatio=${videoSize.pixelWidthHeightRatio}",
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        DiagnosticsLog.event(
                            "PlaybackService player error " +
                                "category=${playerErrorDiagnosticCategory(error.errorCode)} " +
                                "code=${error.errorCode}",
                        )
                    }

                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        DiagnosticsLog.event(
                            "PlaybackService media transition reason=$reason " +
                                "media=${privacySafeUrlDiagnosticLabel(mediaItem?.mediaId)}",
                        )
                        if (!::session.isInitialized) return
                        val retained = remoteHistoryMetadataFor(mediaItem)
                        val route = mediaItem?.mediaMetadata?.extras?.getString(EXTRA_WATCH_ROUTE)
                            ?: retained?.let {
                                Routes.watch(
                                    it.animeId,
                                    it.provider,
                                    it.category,
                                    it.episodeNumber.routeEpisodeLabel(),
                                )
                            }
                        session.setSessionActivity(sessionActivity(route))
                        persistRemoteProgress(castPlayer)
                    }
                })
            }
        activePlayer = castPlayer
        PlaybackStatus.updatePlaybackRoute(castPlayer.playbackRoute())
        remoteHistoryFlusher = { persistRemoteProgress(castPlayer, force = true) }
        serviceScope.launch {
            while (isActive) {
                delay(REMOTE_HISTORY_POLL_MS)
                if (!::castPlayer.isInitialized) continue
                if (castPlayer.playbackState == Player.STATE_ENDED) {
                    persistRemoteCompletion(castPlayer)
                } else {
                    persistRemoteProgress(castPlayer)
                }
            }
        }

        // The playlist holds one media item at a time (episodes resolve lazily, per provider),
        // but the session still advertises next/previous by forwarding them to the app's
        // episode navigator. That lights up Media3's built-in prev/next buttons and makes the
        // media notification and hardware/Bluetooth media keys switch episodes.
        val episodeAwarePlayer = object : ForwardingPlayer(castPlayer) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .addAll(
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    )
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                    super.isCommandAvailable(command)

            override fun hasNextMediaItem(): Boolean = hasActiveEpisodeNavigator()
            override fun hasPreviousMediaItem(): Boolean = hasActiveEpisodeNavigator()

            override fun seekToNext() {
                persistRemoteProgress(castPlayer, force = true)
                val handled = navigateActiveEpisode(1)
                DiagnosticsLog.event("PlaybackService seekToNext navigator=$handled")
                if (!handled) super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                persistRemoteProgress(castPlayer, force = true)
                val handled = navigateActiveEpisode(1)
                DiagnosticsLog.event("PlaybackService seekToNextMediaItem navigator=$handled")
                if (!handled) super.seekToNextMediaItem()
            }

            override fun seekToPrevious() {
                persistRemoteProgress(castPlayer, force = true)
                val handled = navigateActiveEpisode(-1)
                DiagnosticsLog.event("PlaybackService seekToPrevious navigator=$handled")
                if (!handled) super.seekToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                persistRemoteProgress(castPlayer, force = true)
                val handled = navigateActiveEpisode(-1)
                DiagnosticsLog.event("PlaybackService seekToPreviousMediaItem navigator=$handled")
                if (!handled) super.seekToPreviousMediaItem()
            }
        }

        // Brand the media notification: Media3's provider handles layout, actions, and artwork
        // (loading MediaMetadata.artworkUri itself); only the small icon needs overriding.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply { setSmallIcon(com.miruronative.R.drawable.ic_notification) },
        )
        session = MediaSession.Builder(this, episodeAwarePlayer)
            .setSessionActivity(sessionActivity(null))
            .setCallback(object : MediaSession.Callback {
                // A paused-but-loaded session normally resumes on any KEYCODE_MEDIA_PLAY —
                // including ones a Bluetooth headset or car fires on reconnect, which made
                // paused audio restart in the background "by itself". While the app is
                // backgrounded after a lifecycle pause, ignore media-button play; the
                // notification's own play action arrives as a controller command (not a key
                // event), so deliberate resumes still work.
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent,
                ): Boolean {
                    val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    val isResumeKey = key?.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        key?.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        key?.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                    if (isResumeKey && suppressMediaButtonResume && !castPlayer.isPlaying) {
                        DiagnosticsLog.event(
                            "PlaybackService ignored background media-button resume key=${key?.keyCode}",
                        )
                        return true
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }
            })
            .build()
        session.setMediaButtonPreferences(
            listOf(
                CommandButton.Builder(CommandButton.ICON_REWIND)
                    .setDisplayName("Rewind 10 seconds")
                    .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_FAST_FORWARD)
                    .setDisplayName("Forward 10 seconds")
                    .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .build(),
            ),
        )
    }

    private fun restoreRemotePlaybackHistoryMetadata() {
        val encoded = remoteHistoryPreferences.getString(KEY_REMOTE_HISTORY_METADATA, null) ?: return
        runCatching {
            remoteHistoryJson.decodeFromString(
                ListSerializer(RemotePlaybackHistoryMetadata.serializer()),
                encoded,
            )
        }.onSuccess { restored ->
            restored.forEach(remoteHistoryRegistry::register)
            DiagnosticsLog.event("PlaybackService restored remote history metadata=${restored.size}")
        }.onFailure {
            DiagnosticsLog.throwable("PlaybackService remote history metadata restore failed", it)
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun rememberRemotePlaybackHistoryMetadata(metadata: RemotePlaybackHistoryMetadata) {
        if (remoteHistoryRegistry.register(metadata)) remoteHistoryRegistryDirty = true
        persistRemotePlaybackHistoryRegistry()
    }

    @SuppressLint("ApplySharedPref")
    private fun persistRemotePlaybackHistoryRegistry(): Boolean {
        if (!remoteHistoryRegistryDirty) return true
        val committed = runCatching {
            val encoded = remoteHistoryJson.encodeToString(
                ListSerializer(RemotePlaybackHistoryMetadata.serializer()),
                remoteHistoryRegistry.snapshot(),
            )
            remoteHistoryPreferences.edit().putString(KEY_REMOTE_HISTORY_METADATA, encoded).commit()
        }.onFailure {
            DiagnosticsLog.throwable("PlaybackService remote history metadata encode failed", it)
        }.getOrDefault(false)
        remoteHistoryRegistryDirty = !committed
        if (!committed) {
            DiagnosticsLog.event("PlaybackService remote history metadata disk commit failed")
        }
        return committed
    }

    private fun remoteHistoryMetadataFor(
        item: androidx.media3.common.MediaItem?,
    ): RemotePlaybackHistoryMetadata? {
        if (item == null) return null
        val embedded = item.remotePlaybackHistoryMetadataOrNull()
        if (embedded != null) {
            rememberRemotePlaybackHistoryMetadata(embedded)
            return embedded
        }
        // DefaultMediaItemConverter keeps MediaItem.mediaId but drops MediaMetadata.extras.
        // PlayerSurface registers the immutable metadata before setMediaItem(), so the unique
        // playback ID remains sufficient after the Cast round trip even when URLs are reused.
        return remoteHistoryRegistry.resolve(item.mediaId)?.also { retained ->
            persistRemotePlaybackHistoryRegistry()
            // Re-bind a live Watch navigator after service/process recreation as well as during
            // the initial sender-side registration. The UUID remains the authority in both cases.
            latestRegisteredRemoteHistoryMetadata = retained
            bindEpisodeNavigatorPlayback(retained)
        }
    }

    private fun persistRemoteProgress(
        source: Player,
        force: Boolean = false,
        isRemote: Boolean = source.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
    ): Boolean {
        val metadata = remoteHistoryMetadataFor(source.currentMediaItem)
        if (isRemote) remoteHistoryOwnerIdentity = metadata?.identity
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val canRestorePausedConfirmation = force &&
            isRemote &&
            !source.isPlaying &&
            metadata != null &&
            LibraryStore.historyFor(metadata.animeId)?.episodeNumber == metadata.episodeNumber
        val restoredPausedConfirmation = canRestorePausedConfirmation &&
            remoteHistoryCoordinator.adoptConfirmedPlayback(metadata, elapsedRealtimeMs)
        val write = if (restoredPausedConfirmation) {
            // A durable matching entry proves this receiver item was already confirmed before
            // service recreation. Reinsert through the non-regressing confirmation path first.
            RemoteHistoryWrite.Confirmed(
                metadata = checkNotNull(metadata),
                positionMs = source.currentPosition.coerceAtLeast(0L),
                durationMs = source.duration.coerceAtLeast(0L),
            )
        } else {
            remoteHistoryCoordinator.sample(
                metadata = metadata,
                positionMs = source.currentPosition,
                durationMs = source.duration,
                isPlaying = source.isPlaying,
                isRemote = isRemote,
                elapsedRealtimeMs = elapsedRealtimeMs,
                force = force,
            )
        } ?: return false
        val persisted = runCatching {
            when (write) {
                is RemoteHistoryWrite.Confirmed -> persistConfirmedRemoteHistory(write)
                is RemoteHistoryWrite.Progress -> persistRemoteHistoryProgress(write)
                is RemoteHistoryWrite.Completion -> false
            }
        }.onFailure {
            DiagnosticsLog.throwable("PlaybackService remote history progress failed", it)
        }.getOrDefault(false)
        if (!persisted && write is RemoteHistoryWrite.Confirmed) {
            remoteHistoryCoordinator.retryConfirmation(write.metadata.identity)
        }
        return persisted
    }

    private fun persistConfirmedRemoteHistory(write: RemoteHistoryWrite.Confirmed): Boolean {
        val metadata = write.metadata
        val previous = LibraryStore.historyFor(metadata.animeId)
            ?.takeIf { it.episodeNumber == metadata.episodeNumber }
        val entry = metadata.toHistoryEntry(
            positionMs = if (previous?.completed == true) {
                write.positionMs
            } else {
                maxOf(previous?.positionMs ?: 0L, write.positionMs)
            },
            durationMs = maxOf(previous?.durationMs ?: 0L, write.durationMs),
            completed = false,
        )
        if (!LibraryStore.upsertHistoryDurably(entry)) return false
        maybeSyncRemoteAccountProgress(metadata, entry.positionMs, entry.durationMs)
        DiagnosticsLog.event(
            "PlaybackService remote history confirmed episode=${metadata.episodeNumber} " +
                "playbackId=${metadata.playbackId.take(8)} positionMs=${entry.positionMs}",
        )
        return true
    }

    private fun persistRemoteHistoryProgress(write: RemoteHistoryWrite.Progress): Boolean {
        val metadata = write.metadata
        val saved = LibraryStore.historyFor(metadata.animeId)
            ?.takeIf { it.episodeNumber == metadata.episodeNumber }
            ?: return false
        val durationMs = maxOf(saved.durationMs, write.durationMs)
        val persisted = if (write.durable) {
            LibraryStore.upsertHistoryDurably(
                saved.copy(positionMs = write.positionMs, durationMs = durationMs),
            )
        } else {
            LibraryStore.updateProgress(
                anilistId = metadata.animeId,
                episodeNumber = metadata.episodeNumber,
                positionMs = write.positionMs,
                durationMs = durationMs,
            )
            true
        }
        if (!persisted) return false
        maybeSyncRemoteAccountProgress(metadata, write.positionMs, durationMs)
        DiagnosticsLog.event(
            "PlaybackService remote history progress episode=${metadata.episodeNumber} " +
                "positionMs=${write.positionMs}",
        )
        return true
    }

    private fun persistRemoteCompletion(source: Player): Boolean {
        val isRemote = source.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        val metadata = remoteHistoryMetadataFor(source.currentMediaItem)
        if (isRemote) remoteHistoryOwnerIdentity = metadata?.identity
        remoteHistoryCoordinator.sample(
            metadata = metadata,
            positionMs = source.currentPosition,
            durationMs = source.duration,
            isPlaying = false,
            isRemote = isRemote,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
        val completion = remoteHistoryCoordinator.completion(
            metadata = metadata,
            reportedPositionMs = source.currentPosition,
            durationMs = source.duration,
            isRemote = isRemote,
        ) ?: return false
        val persisted = runCatching {
            LibraryStore.upsertHistoryDurably(
                completion.metadata.toHistoryEntry(
                    positionMs = completion.positionMs,
                    durationMs = completion.durationMs,
                    completed = completion.completedFinalEpisode,
                    continuationEpisodeNumber = completion.metadata.nextEpisodeNumber
                        ?.takeIf {
                            completion.metadata.hasNextEpisode &&
                                !completion.completedFinalEpisode &&
                                it.isFinite() &&
                                it != completion.metadata.episodeNumber
                        },
                ),
            )
        }.onFailure {
            DiagnosticsLog.throwable("PlaybackService remote completion failed", it)
        }.getOrDefault(false)
        if (!persisted || !remoteHistoryCoordinator.acknowledgeCompletion(completion.metadata.identity)) {
            return false
        }
        maybeSyncRemoteAccountProgress(
            metadata = completion.metadata,
            positionMs = completion.positionMs,
            durationMs = completion.durationMs,
        )
        recordRemoteCompletion(completion.metadata.playbackId)
        DiagnosticsLog.event(
            "PlaybackService remote completion committed episode=${completion.metadata.episodeNumber} " +
                "playbackId=${completion.metadata.playbackId.take(8)}",
        )
        if (completion.metadata.hasNextEpisode && SettingsStore.autoplay.value) {
            // Resolving the next provider URL still needs the live Watch catalog. The owner
            // generation prevents a late receiver event from advancing a newer Watch screen;
            // without an active matching navigator, completion remains durably saved and stops.
            val navigated = navigateActiveEpisode(
                direction = 1,
                expectedPlayback = completion.metadata.identity,
            )
            DiagnosticsLog.event(
                "PlaybackService remote completion autoplay navigator=$navigated " +
                    "next=${completion.metadata.nextEpisodeNumber ?: "unknown"}",
            )
        }
        return true
    }

    private fun maybeSyncRemoteAccountProgress(
        metadata: RemotePlaybackHistoryMetadata,
        positionMs: Long,
        durationMs: Long,
    ) {
        val accountService = AccountService.active ?: return
        if (!SettingsStore.autoSyncAniList.value) return
        if (!shouldSyncAniListProgress(metadata.episodeNumber, positionMs, durationMs)) return
        val episode = metadata.episodeNumber.toInt()
        val key = RemoteProgressSyncKey(
            animeId = metadata.animeId,
            episode = episode,
            generation = metadata.generation,
            service = accountService,
        )
        if (!syncedRemoteProgress.add(key)) return
        serviceScope.launch {
            runCatching {
                when (accountService) {
                    AccountService.ANILIST -> AppGraph.repository.saveAniListProgress(
                        metadata.animeId,
                        episode,
                        metadata.totalEpisodes,
                    )
                    AccountService.MAL -> AppGraph.repository.saveMalProgress(
                        metadata.animeId,
                        episode,
                        metadata.totalEpisodes,
                    )
                }
            }.onFailure {
                if (it is CancellationException) throw it
                syncedRemoteProgress.remove(key)
                DiagnosticsLog.throwable(
                    "PlaybackService remote ${accountService.label} progress sync failed",
                    it,
                )
            }
        }
    }

    private fun RemotePlaybackHistoryMetadata.toHistoryEntry(
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
        continuationEpisodeNumber: Double? = null,
    ): HistoryEntry = HistoryEntry(
        anilistId = animeId,
        title = seriesTitle,
        cover = coverUrl,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        provider = provider,
        category = category,
        positionMs = positionMs.coerceAtLeast(0L),
        durationMs = durationMs.coerceAtLeast(0L),
        continuationEpisodeNumber = continuationEpisodeNumber,
        completed = completed,
    )

    private fun Double.routeEpisodeLabel(): String =
        if (isFinite() && this % 1.0 == 0.0) toInt().toString() else toString()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticsLog.event("PlaybackService.onTaskRemoved playing=${if (::castPlayer.isInitialized) castPlayer.playWhenReady else false}")
        val initialized = ::castPlayer.isInitialized
        val shouldStop = shouldStopPlaybackServiceAfterTaskRemoved(
            playerInitialized = initialized,
            route = if (initialized) castPlayer.playbackRoute() else PlaybackRoute.LOCAL,
            mediaItemCount = if (initialized) castPlayer.mediaItemCount else 0,
            playWhenReady = initialized && castPlayer.playWhenReady,
        )
        if (shouldStop) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        DiagnosticsLog.event("PlaybackService.onDestroy")
        if (::castPlayer.isInitialized) persistRemoteProgress(castPlayer, force = true)
        persistRemotePlaybackHistoryRegistry()
        serviceScope.cancel()
        activeHttpFactory = null
        activePlayer = null
        remoteHistoryFlusher = null
        remoteHistoryMetadataRegistrar = null
        remoteHistoryMetadataResolver = null
        remoteHistoryOwnerIdentity = null
        remoteHistoryHandoffIdentity = null
        latestRegisteredRemoteHistoryMetadata = null
        remoteHistoryRegistry.clear()
        episodeNavigator = null
        watchPlaybackOwners.clear()
        PlaybackStatus.resetPlayerState()
        if (::session.isInitialized) session.release()
        if (::castPlayer.isInitialized) {
            castPlayer.release()
        } else if (::player.isInitialized) {
            player.release()
        }
        super.onDestroy()
    }

    private fun sessionActivity(route: String?): PendingIntent {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(Routes.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(
            this,
            route?.hashCode() ?: 0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val EXTRA_WATCH_ROUTE = "watch_route"
        private const val REMOTE_HISTORY_POLL_MS = 2_000L
        private const val MAX_REMOTE_HISTORY_ITEMS = 16
        private const val REMOTE_HISTORY_PREFS = "remote_playback_history"
        private const val KEY_REMOTE_HISTORY_METADATA = "metadata"
        private const val FALLBACK_PLAYER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var activeHttpFactory: HttpDataSource.Factory? = null
        @Volatile
        private var activeUserAgent: String = FALLBACK_PLAYER_USER_AGENT
        @Volatile
        private var activePlaylistKey: String? = null
        @Volatile
        private var activePlayer: Player? = null
        @Volatile
        private var remoteHistoryFlusher: (() -> Boolean)? = null
        @Volatile
        private var remoteHistoryMetadataRegistrar: ((RemotePlaybackHistoryMetadata) -> Unit)? = null
        @Volatile
        private var remoteHistoryMetadataResolver: ((String) -> RemotePlaybackHistoryMetadata?)? = null
        @Volatile
        private var remoteHistoryOwnerIdentity: RemotePlaybackHistoryIdentity? = null
        @Volatile
        private var remoteHistoryHandoffIdentity: RemotePlaybackHistoryIdentity? = null
        @Volatile
        private var latestRegisteredRemoteHistoryMetadata: RemotePlaybackHistoryMetadata? = null
        private val remoteCompletionLedger = LinkedHashSet<String>()
        private val localPlaybackOwners = LocalPlaybackOwnerRegistry()
        private val watchPlaybackOwners = WatchPlaybackOwnerRegistry()

        /** Register before setMediaItem; Cast retains playbackId even though it drops extras. */
        internal fun registerRemotePlaybackHistoryMetadata(metadata: RemotePlaybackHistoryMetadata) {
            remoteHistoryMetadataRegistrar?.invoke(metadata)
            val handoff = remoteHistoryHandoffIdentity
            if (
                handoff != null &&
                activePlayer?.deviceInfo?.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE &&
                handoff != metadata.identity
            ) {
                remoteHistoryHandoffIdentity = null
            }
            latestRegisteredRemoteHistoryMetadata = metadata
            bindEpisodeNavigatorPlayback(metadata)
        }

        private fun bindEpisodeNavigatorPlayback(metadata: RemotePlaybackHistoryMetadata) {
            val registration = episodeNavigator
            if (
                registration != null &&
                registration.owner.generation == metadata.watchOwnerGeneration &&
                registration.playback.animeId == metadata.animeId &&
                registration.playback.episodeNumber == metadata.episodeNumber &&
                registration.playback.playbackGeneration == metadata.generation
            ) {
                registration.playback = registration.playback.copy(playbackId = metadata.playbackId)
            }
        }

        internal fun registeredRemotePlaybackHistoryMetadata(
            retainedMediaId: String,
        ): RemotePlaybackHistoryMetadata? = remoteHistoryMetadataResolver?.invoke(retainedMediaId)

        /** True only while a valid Cast item owns progress/history persistence. */
        fun isRemotePlaybackHistoryOwnedByService(): Boolean =
            serviceHistoryIdentity() != null

        private fun serviceHistoryIdentity(): RemotePlaybackHistoryIdentity? =
            if (activePlayer?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                remoteHistoryOwnerIdentity
            } else {
                remoteHistoryHandoffIdentity
            }

        internal fun ownsRemotePlaybackHistory(
            animeId: Int,
            episodeNumber: Double,
            generation: Int,
            mediaId: String,
        ): Boolean {
            val owner = serviceHistoryIdentity() ?: return false
            return owner.animeId == animeId &&
                owner.episodeNumber == episodeNumber &&
                owner.generation == generation &&
                owner.mediaId == mediaId
        }

        internal fun ownsRemotePlaybackCompletion(
            playbackId: String,
            animeId: Int,
            episodeNumber: Double,
            mediaId: String,
        ): Boolean {
            val owner = serviceHistoryIdentity() ?: return false
            return owner.playbackId == playbackId &&
                owner.animeId == animeId &&
                owner.episodeNumber == episodeNumber &&
                owner.mediaId == mediaId
        }

        /** Releases the remote-to-local barrier only for the first valid local sample. */
        internal fun acknowledgeFreshLocalPlaybackProgress(
            animeId: Int,
            episodeNumber: Double,
            generation: Int,
            mediaId: String,
        ): Boolean {
            if (activePlayer?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return false
            val handoff = remoteHistoryHandoffIdentity ?: return false
            if (
                handoff.animeId != animeId ||
                handoff.episodeNumber != episodeNumber ||
                handoff.generation != generation ||
                handoff.mediaId != mediaId
            ) {
                return false
            }
            remoteHistoryHandoffIdentity = null
            DiagnosticsLog.event(
                "PlaybackService local progress acknowledged remote handoff " +
                    "playbackId=${handoff.playbackId.take(8)}",
            )
            return true
        }

        @Synchronized
        private fun recordRemoteCompletion(playbackId: String) {
            remoteCompletionLedger.remove(playbackId)
            remoteCompletionLedger += playbackId
            while (remoteCompletionLedger.size > MAX_REMOTE_HISTORY_ITEMS) {
                remoteCompletionLedger.remove(remoteCompletionLedger.first())
            }
        }

        @Synchronized
        internal fun wasRemotePlaybackCompletionCommitted(playbackId: String): Boolean =
            playbackId in remoteCompletionLedger

        /** Banks the receiver's current position before a UI transition or disposal. */
        fun flushRemotePlaybackHistory(): Boolean =
            if (isRemotePlaybackHistoryOwnedByService()) remoteHistoryFlusher?.invoke() == true else false

        @Volatile
        private var episodeNavigator: EpisodeNavigatorRegistration? = null

        internal fun acquireLocalPlaybackOwner(): LocalPlaybackOwnerToken {
            val token = localPlaybackOwners.acquire()
            PlaybackStatus.updateNativeSurface(true)
            DiagnosticsLog.event("PlaybackService local playback owner acquired id=${token.id}")
            return token
        }

        internal fun releaseLocalPlaybackOwner(token: LocalPlaybackOwnerToken) {
            val wasLatest = localPlaybackOwners.isLatest(token)
            if (localPlaybackOwners.release(token)) {
                PlaybackStatus.updateNativeSurface(localPlaybackOwners.hasOwner())
                if (wasLatest) PlaybackStatus.updateSourceRect(null)
                DiagnosticsLog.event("PlaybackService local playback owner released id=${token.id}")
            }
        }

        internal fun updatePictureInPictureSourceRect(
            token: LocalPlaybackOwnerToken,
            sourceRect: PictureInPictureSourceRect,
        ): Boolean {
            if (!localPlaybackOwners.isLatest(token)) return false
            PlaybackStatus.updateSourceRect(sourceRect)
            return true
        }

        internal fun clearPictureInPictureSourceRect(token: LocalPlaybackOwnerToken): Boolean {
            if (!localPlaybackOwners.isLatest(token)) return false
            PlaybackStatus.updateSourceRect(null)
            return true
        }

        internal fun activateWatchPlaybackOwner(): WatchPlaybackOwnerToken {
            val token = watchPlaybackOwners.activate { error ->
                DiagnosticsLog.throwable("PlaybackService outgoing watch handoff failed", error)
            }
            DiagnosticsLog.event("PlaybackService watch playback owner activated generation=${token.generation}")
            return token
        }

        /**
         * Install the outgoing screen's synchronous barrier. Activation consumes this callback
         * before invalidating [token], so its last progress snapshot and embed teardown cannot be
         * overtaken by an incoming watch destination.
         */
        internal fun registerWatchPlaybackHandoff(
            token: WatchPlaybackOwnerToken,
            handoff: () -> Unit,
        ): Boolean = watchPlaybackOwners.registerOutgoingHandoff(token, handoff)

        internal fun clearWatchPlaybackHandoff(token: WatchPlaybackOwnerToken): Boolean =
            watchPlaybackOwners.clearOutgoingHandoff(token)

        internal fun releaseWatchPlaybackOwner(token: WatchPlaybackOwnerToken) {
            val released = watchPlaybackOwners.runIfActive(token) {
                if (episodeNavigator?.owner === token) episodeNavigator = null
                watchPlaybackOwners.release(token)
            }
            if (released) {
                DiagnosticsLog.event(
                    "PlaybackService watch playback owner released generation=${token.generation}",
                )
            }
        }

        internal fun isWatchPlaybackOwnerActive(token: WatchPlaybackOwnerToken): Boolean =
            watchPlaybackOwners.isActive(token)

        internal fun runIfWatchPlaybackOwnerActive(
            token: WatchPlaybackOwnerToken,
            action: () -> Unit,
        ): Boolean = watchPlaybackOwners.runIfActive(token, action)

        /**
         * Register navigation for the current watch-screen generation. A late effect from an
         * outgoing screen cannot overwrite or clear the incoming screen's callback.
         */
        internal fun registerEpisodeNavigator(
            owner: WatchPlaybackOwnerToken,
            playback: EpisodeNavigatorPlaybackIdentity,
            navigator: (direction: Int) -> Unit,
        ): Boolean = watchPlaybackOwners.runIfActive(owner) {
            episodeNavigator = EpisodeNavigatorRegistration(owner, playback, navigator)
            latestRegisteredRemoteHistoryMetadata?.let(::bindEpisodeNavigatorPlayback)
        }

        internal fun clearEpisodeNavigator(
            owner: WatchPlaybackOwnerToken,
            playback: EpisodeNavigatorPlaybackIdentity,
        ) {
            watchPlaybackOwners.runIfActive(owner) {
                val registered = episodeNavigator
                if (
                    registered?.owner === owner &&
                    registered.playback.matchesLogicalPlayback(playback)
                ) {
                    episodeNavigator = null
                }
            }
        }

        private fun hasActiveEpisodeNavigator(): Boolean {
            val registration = episodeNavigator ?: return false
            return watchPlaybackOwners.isActive(registration.owner)
        }

        private fun navigateActiveEpisode(
            direction: Int,
            expectedPlayback: RemotePlaybackHistoryIdentity? = null,
        ): Boolean {
            val registration = episodeNavigator ?: return false
            if (
                !acceptsEpisodeNavigatorPlayback(
                    activeOwnerGeneration = registration.owner.generation,
                    active = registration.playback,
                    expected = expectedPlayback,
                )
            ) {
                DiagnosticsLog.event(
                    "PlaybackService rejected stale episode navigator " +
                        "expected=${expectedPlayback?.playbackId?.take(8) ?: "manual"} " +
                        "active=${registration.playback.playbackId?.take(8) ?: "unbound"}",
                )
                return false
            }
            return watchPlaybackOwners.runIfActive(registration.owner) {
                registration.navigate(direction)
            }
        }

        /** Used when switching explicitly from native playback to a provider WebView. */
        fun stopActivePlayback() {
            DiagnosticsLog.event("PlaybackService.stopActivePlayback")
            activePlayer?.run {
                if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                    remoteHistoryFlusher?.invoke()
                }
                stop()
                clearMediaItems()
                remoteHistoryOwnerIdentity = null
                remoteHistoryHandoffIdentity = null
            }
        }

        /** Stop only if this screen is still the newest watch destination. */
        internal fun stopActivePlayback(owner: WatchPlaybackOwnerToken): Boolean =
            watchPlaybackOwners.runIfActive(owner) { stopActivePlayback() }

        @Volatile
        private var suppressMediaButtonResume = false

        /** Pause playback when the app is backgrounded while keeping the current media loaded. */
        fun pauseActivePlayback() {
            DiagnosticsLog.event("PlaybackService.pauseActivePlayback")
            val active = activePlayer ?: return
            if (active.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                DiagnosticsLog.event("PlaybackService.pauseActivePlayback skipped remote route")
                return
            }
            active.pause()
            // The media stays loaded so the notification can resume it, but stray media-button
            // events (Bluetooth reconnects) must not restart it while the app is away.
            suppressMediaButtonResume = true
        }

        /** Pause only if this screen is still the newest watch destination. */
        internal fun pauseActivePlayback(owner: WatchPlaybackOwnerToken): Boolean =
            watchPlaybackOwners.runIfActive(owner) { pauseActivePlayback() }

        /** The app is visible again (or playback deliberately restarted): buttons act normally. */
        fun allowMediaButtonResume() {
            suppressMediaButtonResume = false
        }

        /** Applies per-provider headers before Media3 creates manifest and segment data sources. */
        fun configureRequestHeaders(referer: String?, playlistKey: String?) {
            activePlaylistKey = playlistKey
            val safeReferer = referer ?: "https://www.miruro.to/"
            val refererUri = android.net.Uri.parse(safeReferer)
            val origin = refererUri.let { uri ->
                if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else safeReferer
            }
            val headers = mutableMapOf("Referer" to safeReferer, "Origin" to origin)
            if (refererUri.host.orEmpty().endsWith("flixcloud.cc", ignoreCase = true)) {
                val chromiumMajor = Regex("(?:Chrome|Chromium)/(\\d+)")
                    .find(activeUserAgent)?.groupValues?.get(1) ?: "137"
                headers += mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Sec-CH-UA" to "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"$chromiumMajor\", \"Android WebView\";v=\"$chromiumMajor\"",
                    "Sec-CH-UA-Mobile" to "?1",
                    "Sec-CH-UA-Platform" to "\"Android\"",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-site",
                    "X-Requested-With" to "com.miruronative",
                )
            }
            activeHttpFactory?.setDefaultRequestProperties(
                headers,
            )
            DiagnosticsLog.event(
                "PlaybackService.configureRequestHeaders " +
                    "referer=${privacySafeUrlDiagnosticLabel(safeReferer)} " +
                    "playlistKey=${playlistKey != null}",
            )
        }
    }
}

private fun Player.playbackRoute(): PlaybackRoute =
    if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
        PlaybackRoute.REMOTE
    } else {
        PlaybackRoute.LOCAL
    }

private fun Int.stateName(): String = when (this) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> toString()
}
