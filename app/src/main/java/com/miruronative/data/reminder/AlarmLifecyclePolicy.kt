package com.miruronative.data.reminder

/**
 * Stable identity used by Android when matching an alarm PendingIntent.
 *
 * PendingIntent deliberately ignores extras when comparing two intents. Keeping the complete
 * record identity in both the action and data URI lets alarms coexist without relying on an Int
 * request code, whose finite namespace necessarily permits collisions.
 */
internal data class AlarmIntentIdentity(
    val action: String,
    val data: String,
)

internal fun manualReminderIntentIdentity(
    mediaId: Int,
    episode: Int,
    airingAt: Long,
): AlarmIntentIdentity = AlarmIntentIdentity(
    action = MANUAL_REMINDER_ACTION,
    data = "$ALARM_URI_PREFIX/manual/$mediaId/$episode/$airingAt",
)

internal fun automaticReleaseIntentIdentity(
    mediaId: Int,
    episode: Int,
    airingAt: Long,
): AlarmIntentIdentity = AlarmIntentIdentity(
    action = AUTOMATIC_RELEASE_ACTION,
    data = "$ALARM_URI_PREFIX/automatic/$mediaId/$episode/$airingAt",
)

internal fun manualReminderNotificationTag(recordId: String): String = "manual:$recordId"

internal fun automaticReleaseNotificationTag(recordId: String): String = "automatic:$recordId"

/** Inexact/idle alarms may arrive after airtime; retain their canonical data long enough to claim. */
internal fun isManualReminderClaimable(
    airingAtEpochSeconds: Long,
    nowEpochSeconds: Long,
): Boolean = airingAtEpochSeconds >= nowEpochSeconds - MANUAL_DELIVERY_GRACE_SECONDS

internal data class AlarmDeliveryClaim<T>(
    val delivered: T?,
    val remaining: List<T>,
)

internal enum class AlarmRestorationCause {
    PROCESS_START,
    BOOT_OR_PACKAGE_REPLACEMENT,
}

/** Ordinary process creation must never re-arm an alarm that may already be in delivery. */
internal fun <T> alarmsForRestoration(
    records: List<T>,
    cause: AlarmRestorationCause,
): List<T> = when (cause) {
    AlarmRestorationCause.PROCESS_START -> emptyList()
    AlarmRestorationCause.BOOT_OR_PACKAGE_REPLACEMENT -> records
}

/** Removes every copy of [id], but returns only one record for a first, idempotent delivery. */
internal fun <T> claimAlarmDelivery(
    records: List<T>,
    id: String,
    idOf: (T) -> String,
): AlarmDeliveryClaim<T> {
    val delivered = records.firstOrNull { idOf(it) == id }
    return AlarmDeliveryClaim(
        delivered = delivered,
        remaining = if (delivered == null) records else records.filterNot { idOf(it) == id },
    )
}

internal data class ReminderNormalization(
    val retained: List<ScheduledReminder>,
    val dropped: List<ScheduledReminder>,
)

/**
 * Historical versions could persist two timestamps for one logical episode. Records are stored
 * newest-first, so retain the first and expose the rest for alarm cancellation.
 */
internal fun normalizeScheduledReminders(records: List<ScheduledReminder>): ReminderNormalization {
    val seen = hashSetOf<Pair<Int, Int>>()
    val retained = mutableListOf<ScheduledReminder>()
    val dropped = mutableListOf<ScheduledReminder>()
    records.forEach { record ->
        if (seen.add(record.mediaId to record.episode)) retained += record else dropped += record
    }
    return ReminderNormalization(retained = retained, dropped = dropped)
}

internal data class ReminderSchedulePlan(
    val nextRecords: List<ScheduledReminder>,
    val replaced: List<ScheduledReminder>,
)

/** Replaces a corrected timestamp instead of leaving the previous episode alarm armed. */
internal fun planReminderSchedule(
    records: List<ScheduledReminder>,
    candidate: ScheduledReminder,
): ReminderSchedulePlan {
    val sameEpisode = records.filter {
        it.mediaId == candidate.mediaId && it.episode == candidate.episode
    }
    return ReminderSchedulePlan(
        nextRecords = listOf(candidate) + records.filterNot {
            it.mediaId == candidate.mediaId && it.episode == candidate.episode
        },
        // Re-scheduling the exact identity updates its extras and trigger. Only distinct old
        // identities must be canceled after the replacement alarm has been installed.
        replaced = sameEpisode.filterNot { it.id == candidate.id },
    )
}

internal data class ReleaseAlarmReconciliation(
    val desired: List<ReleaseAlarm>,
    val obsolete: List<ReleaseAlarm>,
)

internal fun planReleaseAlarmReconciliation(
    current: List<ReleaseAlarm>,
    desired: List<ReleaseAlarm>,
): ReleaseAlarmReconciliation {
    // The manager models only the next episode for a media. Preserve the first source result
    // deterministically and make every older/different identity for that media obsolete.
    val uniqueDesired = desired.distinctBy { it.mediaId }
    val desiredIds = uniqueDesired.mapTo(hashSetOf(), ReleaseAlarm::id)
    return ReleaseAlarmReconciliation(
        desired = uniqueDesired,
        obsolete = current.filterNot { it.id in desiredIds },
    )
}

internal const val MANUAL_REMINDER_ACTION =
    "com.nassimarifette.anililiplus.action.AIRING_REMINDER"
internal const val AUTOMATIC_RELEASE_ACTION =
    "com.nassimarifette.anililiplus.action.AUTOMATIC_RELEASE"
private const val ALARM_URI_PREFIX = "anililiplus://alarm"
internal const val MANUAL_DELIVERY_GRACE_SECONDS = 24L * 60 * 60
