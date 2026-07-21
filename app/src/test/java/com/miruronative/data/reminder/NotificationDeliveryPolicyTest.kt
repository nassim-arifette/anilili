package com.miruronative.data.reminder

import com.miruronative.data.model.AppNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationDeliveryPolicyTest {
    @Test
    fun `twelve fresh notifications are delivered as eight then four`() {
        val feed = (1..12).map(::notification)

        val firstBatch = selectNotificationsForDelivery(feed, emptyList(), limit = 8)
        val firstLedger = updateDeliveredLedger(
            deliveredIds = emptyList(),
            successfullyPostedIds = firstBatch.map(AppNotification::id),
            limit = 400,
        )
        val secondBatch = selectNotificationsForDelivery(feed, firstLedger, limit = 8)

        assertEquals((1..8).toList(), firstBatch.map(AppNotification::id))
        assertEquals((9..12).toList(), secondBatch.map(AppNotification::id))
    }

    @Test
    fun `only successfully posted ids enter the durable ledger`() {
        val selected = selectNotificationsForDelivery((1..10).map(::notification), emptyList(), limit = 8)
        val successful = listOf(selected[0].id, selected[2].id)

        val ledger = updateDeliveredLedger(listOf(99), successful, limit = 400)

        assertEquals(listOf(1, 3, 99), ledger)
        assertFalse(2 in ledger)
        assertFalse(8 in ledger)
    }

    @Test
    fun `selection excludes read delivered and duplicate entries`() {
        val feed = listOf(
            notification(1),
            notification(1),
            notification(2, unread = false),
            notification(3),
        )

        val selected = selectNotificationsForDelivery(feed, deliveredIds = listOf(3), limit = 8)

        assertEquals(listOf(1), selected.map(AppNotification::id))
    }

    private fun notification(id: Int, unread: Boolean = true) = AppNotification(
        id = id,
        kind = AppNotification.Kind.AIRING,
        createdAt = id.toLong(),
        title = "Title $id",
        badge = null,
        detail = null,
        mediaId = id,
        image = null,
        banner = null,
        unread = unread,
    )
}
