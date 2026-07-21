package com.miruronative.data.reminder

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotificationPolicyTest {
    @Test
    fun `provisional enabled default never triggers a permission side effect`() {
        assertEquals(
            NotificationPermissionAction.WAIT_FOR_SETTINGS,
            notificationPermissionAction(
                settingsLoaded = false,
                releaseNotificationsEnabled = true,
                runtimePermissionRequired = true,
                permissionGranted = false,
                permissionWasPrompted = true,
            ),
        )
    }

    @Test
    fun `persisted disabled setting only cancels releases`() {
        assertEquals(
            NotificationPermissionAction.CANCEL_RELEASES,
            action(enabled = false),
        )
    }

    @Test
    fun `devices without runtime notification permission need no side effect`() {
        assertEquals(
            NotificationPermissionAction.NO_ACTION,
            action(runtimePermissionRequired = false),
        )
    }

    @Test
    fun `granted runtime permission starts release synchronization`() {
        assertEquals(
            NotificationPermissionAction.SYNC_RELEASES,
            action(permissionGranted = true),
        )
    }

    @Test
    fun `missing permission is requested only once`() {
        assertEquals(
            NotificationPermissionAction.REQUEST_PERMISSION,
            action(permissionWasPrompted = false),
        )
        assertEquals(
            NotificationPermissionAction.DISABLE_AND_CANCEL,
            action(permissionWasPrompted = true),
        )
    }

    private fun action(
        enabled: Boolean = true,
        runtimePermissionRequired: Boolean = true,
        permissionGranted: Boolean = false,
        permissionWasPrompted: Boolean = false,
    ): NotificationPermissionAction = notificationPermissionAction(
        settingsLoaded = true,
        releaseNotificationsEnabled = enabled,
        runtimePermissionRequired = runtimePermissionRequired,
        permissionGranted = permissionGranted,
        permissionWasPrompted = permissionWasPrompted,
    )
}
