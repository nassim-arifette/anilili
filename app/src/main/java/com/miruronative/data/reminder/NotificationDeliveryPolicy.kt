package com.miruronative.data.reminder

import com.miruronative.data.model.AppNotification

internal fun selectNotificationsForDelivery(
    items: List<AppNotification>,
    deliveredIds: Collection<Int>,
    limit: Int,
): List<AppNotification> {
    if (limit <= 0) return emptyList()
    val delivered = deliveredIds.toHashSet()
    return items.asSequence()
        .filter { it.unread && it.id !in delivered }
        .distinctBy(AppNotification::id)
        .take(limit)
        .toList()
}

internal fun updateDeliveredLedger(
    deliveredIds: List<Int>,
    successfullyPostedIds: List<Int>,
    limit: Int,
): List<Int> {
    if (limit <= 0) return emptyList()
    return (successfullyPostedIds + deliveredIds).distinct().take(limit)
}
