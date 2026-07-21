package com.miruronative.data.settings

internal enum class SettingField {
    AUTOPLAY,
    AUTO_SYNC_ANILIST,
    PREFER_DUB,
    RELEASE_NOTIFICATIONS,
    SYNC_SAVED_TO_ANILIST,
    AUTO_SKIP_INTRO_OUTRO,
    HIDE_ADULT_CONTENT,
    SUBTITLES_WITH_DUB,
    UPDATE_CHECK_ON_LAUNCH,
    CAPTION_STYLE,
    MENU_LANGUAGE,
    DEFAULT_QUALITY,
    PREFERRED_PROVIDER,
}

internal data class SettingsSnapshot(
    val autoplay: Boolean = true,
    val autoSyncAniList: Boolean = true,
    val preferDub: Boolean = false,
    val releaseNotifications: Boolean = true,
    val syncSavedToAniList: Boolean = true,
    val autoSkipIntroOutro: Boolean = false,
    val hideAdultContent: Boolean = true,
    val subtitlesWithDub: Boolean = false,
    val updateCheckOnLaunch: Boolean = true,
    val captionStyle: CaptionStyle = CaptionStyle(),
    val menuLanguage: MenuLanguage = MenuLanguage.SYSTEM,
    val defaultQuality: DefaultQuality = DefaultQuality.HIGHEST,
    val preferredProvider: String = DEFAULT_PREFERRED_PROVIDER,
)

internal fun overlayPendingSettings(
    persisted: SettingsSnapshot,
    desired: SettingsSnapshot,
    pending: Set<SettingField>,
): SettingsSnapshot = SettingsSnapshot(
    autoplay = if (SettingField.AUTOPLAY in pending) desired.autoplay else persisted.autoplay,
    autoSyncAniList = if (SettingField.AUTO_SYNC_ANILIST in pending) {
        desired.autoSyncAniList
    } else {
        persisted.autoSyncAniList
    },
    preferDub = if (SettingField.PREFER_DUB in pending) desired.preferDub else persisted.preferDub,
    releaseNotifications = if (SettingField.RELEASE_NOTIFICATIONS in pending) {
        desired.releaseNotifications
    } else {
        persisted.releaseNotifications
    },
    syncSavedToAniList = if (SettingField.SYNC_SAVED_TO_ANILIST in pending) {
        desired.syncSavedToAniList
    } else {
        persisted.syncSavedToAniList
    },
    autoSkipIntroOutro = if (SettingField.AUTO_SKIP_INTRO_OUTRO in pending) {
        desired.autoSkipIntroOutro
    } else {
        persisted.autoSkipIntroOutro
    },
    hideAdultContent = if (SettingField.HIDE_ADULT_CONTENT in pending) {
        desired.hideAdultContent
    } else {
        persisted.hideAdultContent
    },
    subtitlesWithDub = if (SettingField.SUBTITLES_WITH_DUB in pending) {
        desired.subtitlesWithDub
    } else {
        persisted.subtitlesWithDub
    },
    updateCheckOnLaunch = if (SettingField.UPDATE_CHECK_ON_LAUNCH in pending) {
        desired.updateCheckOnLaunch
    } else {
        persisted.updateCheckOnLaunch
    },
    captionStyle = if (SettingField.CAPTION_STYLE in pending) desired.captionStyle else persisted.captionStyle,
    menuLanguage = if (SettingField.MENU_LANGUAGE in pending) desired.menuLanguage else persisted.menuLanguage,
    defaultQuality = if (SettingField.DEFAULT_QUALITY in pending) desired.defaultQuality else persisted.defaultQuality,
    preferredProvider = if (SettingField.PREFERRED_PROVIDER in pending) {
        desired.preferredProvider
    } else {
        persisted.preferredProvider
    },
)
