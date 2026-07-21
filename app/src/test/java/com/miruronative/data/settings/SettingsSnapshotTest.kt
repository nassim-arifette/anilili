package com.miruronative.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSnapshotTest {
    @Test
    fun `pending UI values overlay only their persisted fields`() {
        val persisted = SettingsSnapshot(
            autoplay = true,
            preferDub = false,
            defaultQuality = DefaultQuality.P1080,
        )
        val desired = persisted.copy(
            autoplay = false,
            preferDub = true,
            defaultQuality = DefaultQuality.P480,
        )

        val visible = overlayPendingSettings(
            persisted = persisted,
            desired = desired,
            pending = setOf(SettingField.AUTOPLAY, SettingField.DEFAULT_QUALITY),
        )

        assertFalse(visible.autoplay)
        assertFalse(visible.preferDub)
        assertEquals(DefaultQuality.P480, visible.defaultQuality)
    }

    @Test
    fun `latest desired value survives an older datastore emission`() {
        val oldPersisted = SettingsSnapshot(autoplay = true)
        val latestDesired = SettingsSnapshot(autoplay = false)

        val visible = overlayPendingSettings(
            persisted = oldPersisted,
            desired = latestDesired,
            pending = setOf(SettingField.AUTOPLAY),
        )

        assertFalse(visible.autoplay)
        assertTrue(oldPersisted.autoplay)
    }
}
