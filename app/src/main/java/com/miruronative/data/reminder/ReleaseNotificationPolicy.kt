package com.miruronative.data.reminder

internal enum class NotificationPermissionAction {
    WAIT_FOR_SETTINGS,
    CANCEL_RELEASES,
    NO_ACTION,
    SYNC_RELEASES,
    REQUEST_PERMISSION,
    DISABLE_AND_CANCEL,
}

/** Pure cold-start policy used by the Compose permission side effect. */
internal fun notificationPermissionAction(
    settingsLoaded: Boolean,
    releaseNotificationsEnabled: Boolean,
    runtimePermissionRequired: Boolean,
    permissionGranted: Boolean,
    permissionWasPrompted: Boolean,
): NotificationPermissionAction = when {
    !settingsLoaded -> NotificationPermissionAction.WAIT_FOR_SETTINGS
    !releaseNotificationsEnabled -> NotificationPermissionAction.CANCEL_RELEASES
    !runtimePermissionRequired -> NotificationPermissionAction.NO_ACTION
    permissionGranted -> NotificationPermissionAction.SYNC_RELEASES
    !permissionWasPrompted -> NotificationPermissionAction.REQUEST_PERMISSION
    else -> NotificationPermissionAction.DISABLE_AND_CANCEL
}
