package com.miruronative.data.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmLifecyclePolicyTest {
    @Test
    fun `late manual alarm remains claimable during delivery grace`() {
        val now = 200_000L

        assertTrue(isManualReminderClaimable(now - MANUAL_DELIVERY_GRACE_SECONDS, now))
        assertTrue(isManualReminderClaimable(now + 1, now))
        assertEquals(
            false,
            isManualReminderClaimable(now - MANUAL_DELIVERY_GRACE_SECONDS - 1, now),
        )
    }

    @Test
    fun `ordinary process start never restores persisted alarms`() {
        val records = listOf(
            reminder(mediaId = 10, episode = 4, airingAt = 1_000),
            reminder(mediaId = 11, episode = 1, airingAt = 2_000),
        )

        assertTrue(alarmsForRestoration(records, AlarmRestorationCause.PROCESS_START).isEmpty())
        assertEquals(
            records,
            alarmsForRestoration(records, AlarmRestorationCause.BOOT_OR_PACKAGE_REPLACEMENT),
        )
    }

    @Test
    fun `manual alarm identity does not inherit legacy arithmetic collisions`() {
        // The former 31 * mediaId + episode request code is 63 for both records.
        val first = manualReminderIntentIdentity(mediaId = 1, episode = 32, airingAt = 1_000)
        val second = manualReminderIntentIdentity(mediaId = 2, episode = 1, airingAt = 1_000)

        assertEquals(first.action, second.action)
        assertNotEquals(first.data, second.data)
    }

    @Test
    fun `timestamp and alarm kind are part of the complete intent identity`() {
        val original = manualReminderIntentIdentity(mediaId = 10, episode = 4, airingAt = 1_000)
        val corrected = manualReminderIntentIdentity(mediaId = 10, episode = 4, airingAt = 1_500)
        val automatic = automaticReleaseIntentIdentity(mediaId = 10, episode = 4, airingAt = 1_000)

        assertNotEquals(original.data, corrected.data)
        assertNotEquals(original.action, automatic.action)
        assertNotEquals(original.data, automatic.data)
        assertTrue(original.data.endsWith("/10/4/1000"))
        assertNotEquals(
            manualReminderNotificationTag("10:4:1000"),
            automaticReleaseNotificationTag("10:4:1000"),
        )
    }

    @Test
    fun `corrected manual timestamp replaces only the same logical episode`() {
        val old = reminder(mediaId = 10, episode = 4, airingAt = 1_000)
        val corrected = reminder(mediaId = 10, episode = 4, airingAt = 1_500)
        val otherEpisode = reminder(mediaId = 10, episode = 5, airingAt = 2_000)
        val otherMedia = reminder(mediaId = 20, episode = 4, airingAt = 2_500)

        val plan = planReminderSchedule(listOf(old, otherEpisode, otherMedia), corrected)

        assertEquals(listOf(old), plan.replaced)
        assertEquals(listOf(corrected, otherEpisode, otherMedia), plan.nextRecords)
    }

    @Test
    fun `rescheduling the exact manual identity updates instead of canceling itself`() {
        val existing = reminder(mediaId = 10, episode = 4, airingAt = 1_000, title = "Old title")
        val refreshed = existing.copy(title = "New title")

        val plan = planReminderSchedule(listOf(existing), refreshed)

        assertTrue(plan.replaced.isEmpty())
        assertEquals(listOf(refreshed), plan.nextRecords)
    }

    @Test
    fun `normalization retains newest record and reports historical duplicates`() {
        val newest = reminder(mediaId = 10, episode = 4, airingAt = 1_500)
        val stale = reminder(mediaId = 10, episode = 4, airingAt = 1_000)
        val other = reminder(mediaId = 10, episode = 5, airingAt = 2_000)

        val result = normalizeScheduledReminders(listOf(newest, stale, other))

        assertEquals(listOf(newest, other), result.retained)
        assertEquals(listOf(stale), result.dropped)
    }

    @Test
    fun `first delivery claim removes every duplicate and a second claim is empty`() {
        val record = reminder(mediaId = 10, episode = 4, airingAt = 1_000)
        val other = reminder(mediaId = 11, episode = 1, airingAt = 2_000)

        val first = claimAlarmDelivery(listOf(record, record.copy()), record.id, ScheduledReminder::id)
        val second = claimAlarmDelivery(first.remaining, record.id, ScheduledReminder::id)

        assertSame(record, first.delivered)
        assertEquals(listOf(other), claimAlarmDelivery(listOf(record, record.copy(), other), record.id, ScheduledReminder::id).remaining)
        assertNull(second.delivered)
        assertEquals(first.remaining, second.remaining)
    }

    @Test
    fun `release reconciliation cancels old timestamp and keeps unique desired alarms`() {
        val old = release(mediaId = 10, episode = 4, airingAt = 1_000)
        val corrected = release(mediaId = 10, episode = 4, airingAt = 1_500)
        val conflictingNext = release(mediaId = 10, episode = 5, airingAt = 2_500)
        val unchanged = release(mediaId = 11, episode = 2, airingAt = 2_000)

        val plan = planReleaseAlarmReconciliation(
            current = listOf(old, unchanged),
            desired = listOf(corrected, conflictingNext, unchanged, corrected.copy()),
        )

        assertEquals(listOf(old), plan.obsolete)
        assertEquals(listOf(corrected, unchanged), plan.desired)
    }

    private fun reminder(
        mediaId: Int,
        episode: Int,
        airingAt: Long,
        title: String = "Title",
    ) = ScheduledReminder(
        id = "$mediaId:$episode:$airingAt",
        mediaId = mediaId,
        episode = episode,
        title = title,
        airingAt = airingAt,
    )

    private fun release(mediaId: Int, episode: Int, airingAt: Long) = ReleaseAlarm(
        id = "$mediaId:$episode:$airingAt",
        mediaId = mediaId,
        episode = episode,
        title = "Title",
        airingAt = airingAt,
    )
}
