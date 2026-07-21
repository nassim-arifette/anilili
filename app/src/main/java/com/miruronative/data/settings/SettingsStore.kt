package com.miruronative.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.data.LatestMutationTracker
import com.miruronative.data.MutationTicket
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

enum class MenuLanguage(val storedValue: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    SPANISH("es");

    fun usesSpanish(systemLanguage: String = Locale.getDefault().language): Boolean =
        this == SPANISH || (this == SYSTEM && systemLanguage.equals("es", ignoreCase = true))

    companion object {
        fun fromStored(value: String?): MenuLanguage = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

enum class DefaultQuality(val storedValue: String, val label: String) {
    AUTO("auto", "Auto"),
    HIGHEST("highest", "Highest"),
    P1080("1080", "1080p"),
    P720("720", "720p"),
    P480("480", "480p");

    /** Best matching height from [heights], or null to leave adaptive selection alone. */
    fun pickHeight(heights: List<Int>): Int? = when (this) {
        AUTO -> null
        HIGHEST -> heights.maxOrNull()
        // Closest height without going over; a low-quality-only source still plays its best.
        else -> {
            val target = storedValue.toInt()
            heights.filter { it <= target }.maxOrNull() ?: heights.minOrNull()
        }
    }

    companion object {
        fun fromStored(value: String?): DefaultQuality =
            entries.firstOrNull { it.storedValue == value } ?: HIGHEST
    }
}

/** No global server has been chosen yet; the launch route supplies the initial server. */
const val DEFAULT_PREFERRED_PROVIDER = "auto"

/** Transactional DataStore preferences shared by playback and the Library settings UI. */
object SettingsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: DataStore<Preferences>
    private val snapshotLock = Any()
    private val writeVersions = LatestMutationTracker<SettingField>()
    private val writeQueue = Channel<SettingsMutation>(Channel.UNLIMITED)
    private var persistedSnapshot = SettingsSnapshot()
    private var desiredSnapshot = SettingsSnapshot()

    private val _autoplay = MutableStateFlow(true)
    val autoplay = _autoplay.asStateFlow()

    private val _autoSyncAniList = MutableStateFlow(true)
    val autoSyncAniList = _autoSyncAniList.asStateFlow()

    private val _preferDub = MutableStateFlow(false)
    val preferDub = _preferDub.asStateFlow()

    private val _releaseNotifications = MutableStateFlow(true)
    val releaseNotifications = _releaseNotifications.asStateFlow()

    private val _syncSavedToAniList = MutableStateFlow(true)
    val syncSavedToAniList = _syncSavedToAniList.asStateFlow()

    private val _autoSkipIntroOutro = MutableStateFlow(false)
    val autoSkipIntroOutro = _autoSkipIntroOutro.asStateFlow()

    private val _hideAdultContent = MutableStateFlow(true)
    val hideAdultContent = _hideAdultContent.asStateFlow()

    private val _subtitlesWithDub = MutableStateFlow(false)
    val subtitlesWithDub = _subtitlesWithDub.asStateFlow()

    private val _updateCheckOnLaunch = MutableStateFlow(true)
    val updateCheckOnLaunch = _updateCheckOnLaunch.asStateFlow()

    // Kept as one compound value rather than a flow per field: both players and the editor read
    // the whole style at once, and a partial style is never meaningful.
    private val _captionStyle = MutableStateFlow(CaptionStyle())
    val captionStyle = _captionStyle.asStateFlow()

    private val _menuLanguage = MutableStateFlow(MenuLanguage.SYSTEM)
    val menuLanguage = _menuLanguage.asStateFlow()

    private val _defaultQuality = MutableStateFlow(DefaultQuality.HIGHEST)
    val defaultQuality = _defaultQuality.asStateFlow()

    private val _preferredProvider = MutableStateFlow(DEFAULT_PREFERRED_PROVIDER)
    val preferredProvider = _preferredProvider.asStateFlow()
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded = _isLoaded.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { app.preferencesDataStoreFile("anilili_settings") },
        )
        scope.launch {
            try {
                migrateLegacyPreferences(app)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                CrashReporter.logNonFatal("Settings migration failed", error)
            }
            store.data
                .catch { error ->
                    if (error is CancellationException) throw error
                    // A settings read must never take the process down; fall back to defaults.
                    if (error !is IOException) CrashReporter.logNonFatal("Settings read failed", error)
                    emit(emptyPreferences())
                }
                .collect(::applyPreferences)
        }
        scope.launch {
            awaitLoaded()
            for (mutation in writeQueue) persistMutation(mutation)
        }
    }

    fun setAutoplay(value: Boolean) = mutate(
        SettingField.AUTOPLAY,
        update = { it.copy(autoplay = value) },
        write = { it[AUTOPLAY] = value },
    )

    fun setAutoSyncAniList(value: Boolean) = mutate(
        SettingField.AUTO_SYNC_ANILIST,
        update = { it.copy(autoSyncAniList = value) },
        write = { it[AUTO_SYNC] = value },
    )

    fun setPreferDub(value: Boolean) = mutate(
        SettingField.PREFER_DUB,
        update = { it.copy(preferDub = value) },
        write = { it[PREFER_DUB] = value },
    )

    fun setReleaseNotifications(value: Boolean) = mutate(
        SettingField.RELEASE_NOTIFICATIONS,
        update = { it.copy(releaseNotifications = value) },
        write = { it[RELEASE_NOTIFICATIONS] = value },
    )

    fun setSyncSavedToAniList(value: Boolean) = mutate(
        SettingField.SYNC_SAVED_TO_ANILIST,
        update = { it.copy(syncSavedToAniList = value) },
        write = { it[SYNC_SAVED_TO_ANILIST] = value },
    )

    fun setAutoSkipIntroOutro(value: Boolean) = mutate(
        SettingField.AUTO_SKIP_INTRO_OUTRO,
        update = { it.copy(autoSkipIntroOutro = value) },
        write = { it[AUTO_SKIP_INTRO_OUTRO] = value },
    )

    fun setHideAdultContent(value: Boolean) = mutate(
        SettingField.HIDE_ADULT_CONTENT,
        update = { it.copy(hideAdultContent = value) },
        write = { it[HIDE_ADULT_CONTENT] = value },
    )

    fun setSubtitlesWithDub(value: Boolean) = mutate(
        SettingField.SUBTITLES_WITH_DUB,
        update = { it.copy(subtitlesWithDub = value) },
        write = { it[SUBTITLES_WITH_DUB] = value },
    )

    fun setUpdateCheckOnLaunch(value: Boolean) = mutate(
        SettingField.UPDATE_CHECK_ON_LAUNCH,
        update = { it.copy(updateCheckOnLaunch = value) },
        write = { it[UPDATE_CHECK_ON_LAUNCH] = value },
    )

    fun setCaptionBackgroundOpacity(percent: Int) =
        editCaptionStyle { it.copy(backgroundOpacityPercent = percent.coerceIn(0, 100)) }
    fun setCaptionBackgroundColor(value: CaptionBackgroundColor) =
        editCaptionStyle { it.copy(backgroundColor = value) }
    fun setCaptionTextScale(percent: Int) =
        editCaptionStyle { it.copy(textScalePercent = percent.coerceIn(CaptionStyle.MIN_TEXT_SCALE_PERCENT, CaptionStyle.MAX_TEXT_SCALE_PERCENT)) }
    fun setCaptionTextColor(value: CaptionTextColor) = editCaptionStyle { it.copy(textColor = value) }
    fun setCaptionEdgeStyle(value: CaptionEdgeStyle) = editCaptionStyle { it.copy(edgeStyle = value) }
    fun resetCaptionStyle() = editCaptionStyle { CaptionStyle() }

    fun setDefaultQuality(value: DefaultQuality) {
        mutate(
            SettingField.DEFAULT_QUALITY,
            update = { it.copy(defaultQuality = value) },
            write = { it[DEFAULT_QUALITY] = value.storedValue },
        )
    }

    fun setMenuLanguage(value: MenuLanguage) {
        mutate(
            SettingField.MENU_LANGUAGE,
            update = { it.copy(menuLanguage = value) },
            write = { it[MENU_LANGUAGE] = value.storedValue },
        )
    }
    fun setPreferredProvider(value: String) {
        val storedValue = value.trim().lowercase().ifBlank { DEFAULT_PREFERRED_PROVIDER }
        mutate(
            SettingField.PREFERRED_PROVIDER,
            update = { it.copy(preferredProvider = storedValue) },
            write = { it[PREFERRED_PROVIDER] = storedValue },
        )
    }

    /** Guarantees cold-start consumers see the persisted preference instead of the in-memory default. */
    suspend fun awaitLoaded() {
        awaitSettingsReady(isLoaded)
    }

    private fun editCaptionStyle(transform: (CaptionStyle) -> CaptionStyle) {
        lateinit var next: CaptionStyle
        mutate(
            SettingField.CAPTION_STYLE,
            update = { snapshot ->
                next = transform(snapshot.captionStyle)
                snapshot.copy(captionStyle = next)
            },
            write = { prefs ->
                prefs[CAPTION_BACKGROUND_OPACITY] = next.backgroundOpacityPercent
                prefs[CAPTION_BACKGROUND_COLOR] = next.backgroundColor.storedValue
                prefs[CAPTION_TEXT_SCALE] = next.textScalePercent
                prefs[CAPTION_TEXT_COLOR] = next.textColor.storedValue
                prefs[CAPTION_EDGE_STYLE] = next.edgeStyle.storedValue
            },
        )
    }

    internal fun readCaptionStyle(prefs: Preferences): CaptionStyle = CaptionStyle(
        backgroundOpacityPercent = prefs[CAPTION_BACKGROUND_OPACITY]?.coerceIn(0, 100)
            ?: CaptionStyle.DEFAULT_BACKGROUND_OPACITY_PERCENT,
        backgroundColor = CaptionBackgroundColor.fromStored(prefs[CAPTION_BACKGROUND_COLOR]),
        textScalePercent = prefs[CAPTION_TEXT_SCALE]?.coerceIn(CaptionStyle.MIN_TEXT_SCALE_PERCENT, CaptionStyle.MAX_TEXT_SCALE_PERCENT)
            ?: CaptionStyle.DEFAULT_TEXT_SCALE_PERCENT,
        textColor = CaptionTextColor.fromStored(prefs[CAPTION_TEXT_COLOR]),
        edgeStyle = CaptionEdgeStyle.fromStored(prefs[CAPTION_EDGE_STYLE]),
    )

    private fun applyPreferences(prefs: Preferences) {
        val persisted = snapshotFromPreferences(prefs)
        synchronized(snapshotLock) {
            prefs[LAST_APPLIED_MUTATION]?.let(writeVersions::acknowledge)
            persistedSnapshot = persisted
            overlayPendingSettings(persisted, desiredSnapshot, writeVersions.pendingKeys())
                .also { desiredSnapshot = it }
                .also(::publishSnapshot)
        }
        _isLoaded.value = true
    }

    private fun mutate(
        field: SettingField,
        update: (SettingsSnapshot) -> SettingsSnapshot,
        write: (MutablePreferences) -> Unit,
    ) {
        val mutation = synchronized(snapshotLock) {
            desiredSnapshot = update(desiredSnapshot)
            SettingsMutation(writeVersions.begin(listOf(field)), write)
                .also { publishSnapshot(desiredSnapshot) }
        }
        if (writeQueue.trySend(mutation).isFailure) {
            failMutation(mutation, IllegalStateException("Settings write queue is unavailable"))
        }
    }

    private suspend fun persistMutation(mutation: SettingsMutation) {
        try {
            store.edit { prefs ->
                mutation.write(prefs)
                prefs[LAST_APPLIED_MUTATION] = mutation.ticket.persistenceToken
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            failMutation(mutation, error)
        }
    }

    private fun failMutation(mutation: SettingsMutation, error: Throwable) {
        CrashReporter.logNonFatal("Settings write failed", error)
        synchronized(snapshotLock) {
            writeVersions.complete(mutation.ticket)
            overlayPendingSettings(persistedSnapshot, desiredSnapshot, writeVersions.pendingKeys())
                .also { desiredSnapshot = it }
                .also(::publishSnapshot)
        }
    }

    private fun snapshotFromPreferences(prefs: Preferences): SettingsSnapshot = SettingsSnapshot(
        autoplay = prefs[AUTOPLAY] ?: true,
        autoSyncAniList = prefs[AUTO_SYNC] ?: true,
        preferDub = prefs[PREFER_DUB] ?: false,
        releaseNotifications = prefs[RELEASE_NOTIFICATIONS] ?: true,
        syncSavedToAniList = prefs[SYNC_SAVED_TO_ANILIST] ?: true,
        autoSkipIntroOutro = prefs[AUTO_SKIP_INTRO_OUTRO] ?: false,
        hideAdultContent = prefs[HIDE_ADULT_CONTENT] ?: true,
        subtitlesWithDub = prefs[SUBTITLES_WITH_DUB] ?: false,
        updateCheckOnLaunch = prefs[UPDATE_CHECK_ON_LAUNCH] ?: true,
        captionStyle = readCaptionStyle(prefs),
        menuLanguage = MenuLanguage.fromStored(prefs[MENU_LANGUAGE]),
        defaultQuality = DefaultQuality.fromStored(prefs[DEFAULT_QUALITY]),
        preferredProvider = prefs[PREFERRED_PROVIDER]?.takeIf(String::isNotBlank)
            ?: DEFAULT_PREFERRED_PROVIDER,
    )

    private fun publishSnapshot(snapshot: SettingsSnapshot) {
        _autoplay.value = snapshot.autoplay
        _autoSyncAniList.value = snapshot.autoSyncAniList
        _preferDub.value = snapshot.preferDub
        _releaseNotifications.value = snapshot.releaseNotifications
        _syncSavedToAniList.value = snapshot.syncSavedToAniList
        _autoSkipIntroOutro.value = snapshot.autoSkipIntroOutro
        _hideAdultContent.value = snapshot.hideAdultContent
        _subtitlesWithDub.value = snapshot.subtitlesWithDub
        _updateCheckOnLaunch.value = snapshot.updateCheckOnLaunch
        _captionStyle.value = snapshot.captionStyle
        _menuLanguage.value = snapshot.menuLanguage
        _defaultQuality.value = snapshot.defaultQuality
        _preferredProvider.value = snapshot.preferredProvider
    }

    private data class SettingsMutation(
        val ticket: MutationTicket<SettingField>,
        val write: (MutablePreferences) -> Unit,
    )

    private suspend fun migrateLegacyPreferences(context: Context) {
        val current = store.data.first()
        if (current[MIGRATED] == true) return
        val legacy = context.getSharedPreferences("anilili_settings", Context.MODE_PRIVATE)
        store.edit { prefs ->
            prefs[AUTOPLAY] = legacy.getBoolean("autoplay", true)
            prefs[AUTO_SYNC] = legacy.getBoolean("auto_sync_anilist", true)
            prefs[PREFER_DUB] = legacy.getBoolean("prefer_dub", false)
            prefs[RELEASE_NOTIFICATIONS] = true
            prefs[SYNC_SAVED_TO_ANILIST] = true
            prefs[AUTO_SKIP_INTRO_OUTRO] = false
            prefs[MIGRATED] = true
        }
        legacy.edit().clear().apply()
    }

    private val AUTOPLAY = booleanPreferencesKey("autoplay")
    private val AUTO_SYNC = booleanPreferencesKey("auto_sync_anilist")
    private val PREFER_DUB = booleanPreferencesKey("prefer_dub")
    private val RELEASE_NOTIFICATIONS = booleanPreferencesKey("release_notifications")
    private val SYNC_SAVED_TO_ANILIST = booleanPreferencesKey("sync_saved_to_anilist")
    private val AUTO_SKIP_INTRO_OUTRO = booleanPreferencesKey("auto_skip_intro_outro")
    private val HIDE_ADULT_CONTENT = booleanPreferencesKey("hide_adult_content")
    private val SUBTITLES_WITH_DUB = booleanPreferencesKey("subtitles_with_dub")
    private val UPDATE_CHECK_ON_LAUNCH = booleanPreferencesKey("update_check_on_launch")
    private val CAPTION_BACKGROUND_OPACITY = intPreferencesKey("caption_background_opacity")
    private val CAPTION_BACKGROUND_COLOR = stringPreferencesKey("caption_background_color")
    private val CAPTION_TEXT_SCALE = intPreferencesKey("caption_text_scale")
    private val CAPTION_TEXT_COLOR = stringPreferencesKey("caption_text_color")
    private val CAPTION_EDGE_STYLE = stringPreferencesKey("caption_edge_style")
    private val MENU_LANGUAGE = stringPreferencesKey("menu_language")
    private val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
    private val PREFERRED_PROVIDER = stringPreferencesKey("preferred_provider")
    private val LAST_APPLIED_MUTATION = stringPreferencesKey("last_applied_mutation")
    private val MIGRATED = booleanPreferencesKey("migrated_from_shared_preferences")
}
