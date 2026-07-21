package com.miruronative.data.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.miruronative.MainActivity
import com.miruronative.R
import com.miruronative.data.model.AiringSchedule
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.nav.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal data class ScheduledReminder(
    val id: String,
    val mediaId: Int,
    val episode: Int,
    val title: String,
    val airingAt: Long,
)

@SuppressLint("StaticFieldLeak") // Always assigned applicationContext; never an Activity or receiver context.
object ReminderManager {
    private const val PREFS = "anilili_reminders"
    private const val KEY_RECORDS = "scheduled_records"
    const val CHANNEL_ID = "airing_reminders"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private lateinit var context: Context
    private var records: List<ScheduledReminder> = emptyList()
    private val _scheduled = MutableStateFlow<Set<String>>(emptySet())
    val scheduled = _scheduled.asStateFlow()

    fun init(appContext: Context) {
        context = appContext.applicationContext
        createChannel()
        synchronized(lock) {
            val decoded = decodeRecords()
            // Upgrade migration: old PendingIntents used an arithmetic request code with no
            // action/data identity. Cancel every legacy token before retaining the records. The
            // package-replaced receiver restores them with the collision-free identity below.
            decoded.forEach(::cancelLegacyAlarm)

            val now = System.currentTimeMillis() / 1000L
            val (retained, droppedDuplicates) = normalizeScheduledReminders(
                // setAndAllowWhileIdle is inexact. Keep a bounded grace window so a receiver
                // waking the process after airtime can still durably claim canonical data.
                decoded.filter { isManualReminderClaimable(it.airingAt, now) },
            )
            decoded.filterNot { isManualReminderClaimable(it.airingAt, now) }.forEach(::cancelAlarm)
            droppedDuplicates.forEach(::cancelAlarm)
            update(retained)
            restoreAlarmsLocked(AlarmRestorationCause.PROCESS_START)
        }
    }

    fun id(item: AiringSchedule): String = "${item.media?.id}:${item.episode}:${item.airingAt}"
    fun isScheduled(item: AiringSchedule): Boolean = id(item) in _scheduled.value

    fun toggle(item: AiringSchedule) {
        synchronized(lock) {
            if (records.any { it.id == id(item) }) cancelLocked(item) else scheduleLocked(item)
        }
    }

    private fun scheduleLocked(item: AiringSchedule) {
        val record = item.toScheduledReminder() ?: return
        scheduleRecordLocked(record)
    }

    private fun AiringSchedule.toScheduledReminder(): ScheduledReminder? {
        val scheduleMedia = media ?: return null
        return ScheduledReminder(
            id = id(this),
            mediaId = scheduleMedia.id,
            episode = episode,
            title = scheduleMedia.title.preferred,
            airingAt = airingAt,
        )
    }

    private fun scheduleRecordLocked(record: ScheduledReminder) {
        val plan = planReminderSchedule(records, record)
        val previousSameIdentity = records.firstOrNull { it.id == record.id }
        // Install the replacement first. If AlarmManager rejects it, the previous reminder and
        // persisted state remain intact instead of silently losing the user's reminder.
        if (!scheduleAlarm(record)) return
        // Persist the new claimable identity before canceling the previous alarm. If the process
        // dies in between, a residual old broadcast is harmless because claimDelivery rejects it.
        if (!update(plan.nextRecords, durable = true)) {
            if (previousSameIdentity == null) cancelAlarm(record) else scheduleAlarm(previousSameIdentity)
            DiagnosticsLog.event("Manual reminder durable schedule failed id=${record.id}")
            return
        }
        plan.replaced.forEach {
            cancelAlarm(it)
            cancelLegacyAlarm(it)
        }
    }

    /** Moves an existing reminder when a refreshed AniList schedule corrects its air time. */
    fun reconcileSchedule(items: Collection<AiringSchedule>) {
        synchronized(lock) {
            items.mapNotNull { it.toScheduledReminder() }
                .distinctBy { it.mediaId to it.episode }
                .forEach { candidate ->
                    val sameEpisode = records.filter {
                        it.mediaId == candidate.mediaId && it.episode == candidate.episode
                    }
                    if (sameEpisode.isNotEmpty() && sameEpisode.none { it.id == candidate.id }) {
                        scheduleRecordLocked(candidate)
                    }
                }
        }
    }

    private fun cancelLocked(item: AiringSchedule) {
        val record = records.firstOrNull { it.id == id(item) } ?: return
        // Remove claimability durably first. A broadcast already queued by Android will then be
        // ignored even if the process dies before PendingIntent cancellation completes.
        if (!update(records.filterNot { it.id == record.id }, durable = true)) {
            DiagnosticsLog.event("Manual reminder durable cancellation failed id=${record.id}")
            return
        }
        cancelAlarm(record)
        cancelLegacyAlarm(record)
    }

    private fun scheduleAlarm(record: ScheduledReminder): Boolean = runCatching {
        val pending = alarmPendingIntent(record, PendingIntent.FLAG_UPDATE_CURRENT)
            ?: return@runCatching false
        val desired = record.airingAt * 1000L - 10 * 60 * 1000L
        val triggerAt = desired.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        true
    }.onFailure {
        DiagnosticsLog.throwable("Manual reminder alarm scheduling failed id=${record.id}", it)
    }.getOrDefault(false)

    private fun alarmPendingIntent(record: ScheduledReminder, mode: Int): PendingIntent? {
        val identity = manualReminderIntentIdentity(record.mediaId, record.episode, record.airingAt)
        val intent = Intent(context, AiringReminderReceiver::class.java)
            .setAction(identity.action)
            .setData(Uri.parse(identity.data))
            .putExtra("title", record.title)
            .putExtra("episode", record.episode)
            .putExtra("mediaId", record.mediaId)
            .putExtra("reminderId", record.id)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            mode or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun legacyAlarmPendingIntent(record: ScheduledReminder, mode: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            legacyRequestCode(record.mediaId, record.episode),
            Intent(context, AiringReminderReceiver::class.java)
                .putExtra("title", record.title)
                .putExtra("episode", record.episode)
                .putExtra("mediaId", record.mediaId)
                .putExtra("reminderId", record.id),
            mode or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelAlarm(record: ScheduledReminder) {
        alarmPendingIntent(record, PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
    }

    private fun cancelLegacyAlarm(record: ScheduledReminder) {
        legacyAlarmPendingIntent(record, PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
    }

    private fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New episode reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts shortly before a saved anime episode airs"
            },
        )
    }

    @SuppressLint("ApplySharedPref") // Delivery claims must reach disk before notification.
    private fun update(value: List<ScheduledReminder>, durable: Boolean = false): Boolean {
        val encoded = json.encodeToString(ListSerializer(ScheduledReminder.serializer()), value)
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, encoded)
        val persisted = if (durable) editor.commit() else {
            editor.apply()
            true
        }
        if (persisted) {
            records = value
            _scheduled.value = value.mapTo(linkedSetOf()) { it.id }
        }
        return persisted
    }

    private fun decodeRecords(): List<ScheduledReminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ScheduledReminder.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    /** Returns canonical persisted data only for the first durably claimed delivery. */
    internal fun claimDelivery(reminderId: String?): ScheduledReminder? {
        if (reminderId == null || !::context.isInitialized) return null
        return synchronized(lock) {
            val claim = claimAlarmDelivery(records, reminderId, ScheduledReminder::id)
            val delivered = claim.delivered ?: return@synchronized null
            if (!update(claim.remaining, durable = true)) {
                DiagnosticsLog.event("Manual reminder durable claim failed id=$reminderId")
                return@synchronized null
            }
            cancelAlarm(delivered)
            cancelLegacyAlarm(delivered)
            delivered
        }
    }

    /** Recreates alarms only for an explicit boot/package-replacement event, never process start. */
    internal fun restoreAlarms() {
        if (!::context.isInitialized) return
        synchronized(lock) { restoreAlarmsLocked(AlarmRestorationCause.BOOT_OR_PACKAGE_REPLACEMENT) }
    }

    private fun restoreAlarmsLocked(cause: AlarmRestorationCause) {
        alarmsForRestoration(records, cause).forEach(::scheduleAlarm)
    }

    private fun legacyRequestCode(mediaId: Int, episode: Int): Int = 31 * mediaId + episode
}

class AiringReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminder = ReminderManager.claimDelivery(intent.getStringExtra("reminderId")) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = reminder.title
        val episode = reminder.episode
        val mediaId = reminder.mediaId
        val open = PendingIntent.getActivity(
            context,
            mediaId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Routes.EXTRA_ROUTE, Routes.detail(mediaId))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ReminderManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New episode airing soon")
            .setContentText("$title • Episode $episode")
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, "Open details", open)
            .setAutoCancel(true)
            .setGroup(ReminderManager.CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(manualReminderNotificationTag(reminder.id), 0, notification)
    }
}

/** Recreates alarms cleared by reboot or app replacement. */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { ReminderManager.restoreAlarms() }
                    .onFailure { DiagnosticsLog.throwable("Manual reminder restore failed", it) }
                runCatching { AutomaticReleaseManager.restoreAlarms() }
                    .onFailure { DiagnosticsLog.throwable("Automatic release restore failed", it) }
                runCatching { ReleaseSyncScheduler.runNow(context.applicationContext) }
                    .onFailure { DiagnosticsLog.throwable("Release sync enqueue after restore failed", it) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
