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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.miruronative.MainActivity
import com.miruronative.R
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.model.Media
import com.miruronative.data.model.IncompleteSourceException
import com.miruronative.data.model.SourceCompleteness
import com.miruronative.data.settings.SettingsStore
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.nav.Routes
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal data class ReleaseAlarm(
    val id: String,
    val mediaId: Int,
    val episode: Int,
    val title: String,
    val airingAt: Long,
)

/** Schedules one local notification at the release time of each tracked anime's next episode. */
object AutomaticReleaseManager {
    const val CHANNEL_ID = "automatic_episode_releases"
    private const val PREFS = "anilili_release_alerts"
    private const val KEY_ALARMS = "alarms"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var appContext: Context

    // Read/written from main (init, cancelAll, receivers) and WorkManager threads (sync).
    private val lock = Any()
    private var alarms: List<ReleaseAlarm> = emptyList()

    fun init(context: Context) {
        appContext = context.applicationContext
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New episode releases", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when a new episode airs for anime saved here or on AniList"
            },
        )
        val oldestUseful = System.currentTimeMillis() / 1000L - 24 * 60 * 60
        synchronized(lock) {
            val decoded = read()
            // Migrate away from the old id.hashCode() PendingIntent identity. Application startup
            // only loads state; the package-replaced receiver explicitly restores retained alarms.
            decoded.forEach(::cancelLegacy)
            // One media has exactly one "next episode" release alarm. Keep the newest persisted
            // record if an interrupted historical reconciliation left older identities behind.
            val retained = decoded.filter { it.airingAt >= oldestUseful }.distinctBy { it.mediaId }
            val retainedIds = retained.mapTo(hashSetOf(), ReleaseAlarm::id)
            decoded.filterNot { it.id in retainedIds }.forEach(::cancel)
            replaceAlarms(retained)
            restoreAlarmsLocked(AlarmRestorationCause.PROCESS_START)
        }
    }

    fun sync(media: Collection<Media>) {
        // Future callers must not turn the in-memory default into scheduled alarms during startup.
        if (!SettingsStore.isLoaded.value) return
        if (!SettingsStore.releaseNotifications.value) {
            cancelAllIfDisabled()
            return
        }
        val now = System.currentTimeMillis() / 1000L
        val desired = media.mapNotNull { item ->
            val next = item.nextAiringEpisode ?: return@mapNotNull null
            val episode = next.episode ?: return@mapNotNull null
            val airingAt = next.airingAt ?: return@mapNotNull null
            if (airingAt <= now) return@mapNotNull null
            ReleaseAlarm(
                id = "${item.id}:$episode:$airingAt",
                mediaId = item.id,
                episode = episode,
                title = item.title.preferred,
                airingAt = airingAt,
            )
        }.distinctBy { it.id }

        synchronized(lock) {
            // Serialize the final setting check with scheduling/cancellation. A UI toggle can
            // otherwise cancel first and let this older reconciliation recreate alarms after it.
            if (!SettingsStore.releaseNotifications.value) {
                cancelAllLocked()
                return
            }
            val plan = planReleaseAlarmReconciliation(alarms, desired)
            val previousIds = alarms.mapTo(hashSetOf(), ReleaseAlarm::id)
            // Install every desired alarm before removing obsolete identities. If scheduling
            // fails, the previous alarm remains available and WorkManager can retry safely.
            plan.desired.forEach(::schedule)
            if (!replaceAlarms(plan.desired, durable = true)) {
                plan.desired.filterNot { it.id in previousIds }.forEach(::cancel)
                error("Could not persist automatic release alarm reconciliation")
            }
            plan.obsolete.forEach {
                cancel(it)
                cancelLegacy(it)
            }
        }
    }

    fun cancelAll() {
        if (!::appContext.isInitialized) return
        synchronized(lock) {
            cancelAllLocked()
        }
    }

    /** Returns canonical persisted data only for the first durably claimed delivery. */
    internal fun claimDelivery(id: String?): ReleaseAlarm? {
        if (id == null || !::appContext.isInitialized) return null
        return synchronized(lock) {
            val claim = claimAlarmDelivery(alarms, id, ReleaseAlarm::id)
            val delivered = claim.delivered ?: return@synchronized null
            if (!replaceAlarms(claim.remaining, durable = true)) {
                DiagnosticsLog.event("Automatic release durable claim failed id=$id")
                return@synchronized null
            }
            cancel(delivered)
            cancelLegacy(delivered)
            delivered
        }
    }

    /** Recreates alarms only for an explicit boot/package-replacement event, never process start. */
    internal fun restoreAlarms() {
        if (!::appContext.isInitialized || !SettingsStore.isLoaded.value) return
        synchronized(lock) {
            if (!SettingsStore.releaseNotifications.value) return@synchronized
            restoreAlarmsLocked(AlarmRestorationCause.BOOT_OR_PACKAGE_REPLACEMENT)
        }
    }

    private fun restoreAlarmsLocked(cause: AlarmRestorationCause) {
        alarmsForRestoration(alarms, cause).forEach { alarm ->
            runCatching { schedule(alarm) }
                .onFailure { DiagnosticsLog.throwable("Automatic release restore failed id=${alarm.id}", it) }
        }
    }

    /** Cancels a stale background action only if the setting is still disabled under the lock. */
    internal fun cancelAllIfDisabled() {
        if (!::appContext.isInitialized || !SettingsStore.isLoaded.value) return
        synchronized(lock) {
            if (!SettingsStore.releaseNotifications.value) cancelAllLocked()
        }
    }

    private fun cancelAllLocked() {
        val previous = alarms
        if (!replaceAlarms(emptyList(), durable = true)) {
            DiagnosticsLog.event("Automatic release durable cancellation failed")
            return
        }
        previous.forEach(::cancel)
        previous.forEach(::cancelLegacy)
    }

    private fun schedule(alarm: ReleaseAlarm) {
        val triggerAt = (alarm.airingAt * 1000L).coerceAtLeast(System.currentTimeMillis() + 1_000L)
        appContext.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent(alarm, PendingIntent.FLAG_UPDATE_CURRENT) ?: return,
        )
    }

    private fun cancel(alarm: ReleaseAlarm) {
        pendingIntent(alarm, PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            appContext.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
    }

    private fun pendingIntent(alarm: ReleaseAlarm, mode: Int): PendingIntent? {
        val identity = automaticReleaseIntentIdentity(alarm.mediaId, alarm.episode, alarm.airingAt)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(appContext, AutomaticReleaseReceiver::class.java)
                .setAction(identity.action)
                .setData(Uri.parse(identity.data))
                .putExtra("releaseId", alarm.id)
                .putExtra("mediaId", alarm.mediaId)
                .putExtra("episode", alarm.episode)
                .putExtra("title", alarm.title),
            mode or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun legacyPendingIntent(alarm: ReleaseAlarm, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        appContext,
        alarm.id.hashCode(),
        Intent(appContext, AutomaticReleaseReceiver::class.java)
            .putExtra("releaseId", alarm.id)
            .putExtra("mediaId", alarm.mediaId)
            .putExtra("episode", alarm.episode)
            .putExtra("title", alarm.title),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelLegacy(alarm: ReleaseAlarm) {
        legacyPendingIntent(alarm, PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            appContext.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
    }

    @SuppressLint("ApplySharedPref") // Delivery claims must reach disk before notification.
    private fun replaceAlarms(value: List<ReleaseAlarm>, durable: Boolean = false): Boolean {
        val raw = json.encodeToString(ListSerializer(ReleaseAlarm.serializer()), value)
        val editor = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALARMS, raw)
        val persisted = if (durable) editor.commit() else {
            editor.apply()
            true
        }
        if (persisted) alarms = value
        return persisted
    }

    private fun read(): List<ReleaseAlarm> {
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ALARMS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ReleaseAlarm.serializer()), raw)
        }.getOrDefault(emptyList())
    }
}

class AutomaticReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val releaseId = intent.getStringExtra("releaseId")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SettingsStore.awaitLoaded()
                if (!SettingsStore.releaseNotifications.value) {
                    AutomaticReleaseManager.cancelAllIfDisabled()
                    return@launch
                }
                val alarm = AutomaticReleaseManager.claimDelivery(releaseId) ?: return@launch
                runCatching { ReleaseSyncScheduler.runNow(appContext) }
                    .onFailure { DiagnosticsLog.throwable("Release sync enqueue after delivery failed", it) }
                deliver(appContext, alarm)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DiagnosticsLog.throwable("Automatic release receiver failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun deliver(context: Context, alarm: ReleaseAlarm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val mediaId = alarm.mediaId
        val episode = alarm.episode
        val title = alarm.title
        val open = PendingIntent.getActivity(
            context,
            mediaId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Routes.EXTRA_ROUTE, Routes.detail(mediaId))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, AutomaticReleaseManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New episode released")
            .setContentText("$title • Episode $episode")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Episode $episode of $title has just aired."))
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, "View anime", open)
            .setAutoCancel(true)
            .setGroup(AutomaticReleaseManager.CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(automaticReleaseNotificationTag(alarm.id), 0, notification)
        // Group summary so multiple release alerts collapse into one expandable entry.
        manager.notify(
            -1002,
            NotificationCompat.Builder(context, AutomaticReleaseManager.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("New episode releases")
                .setContentIntent(open)
                .setGroup(AutomaticReleaseManager.CHANNEL_ID)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .build(),
        )
    }
}

object ReleaseSyncScheduler {
    private const val PERIODIC_NAME = "automatic-release-sync"
    private const val IMMEDIATE_NAME = "automatic-release-sync-now"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReleaseSyncWorker>(3, TimeUnit.HOURS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        runNow(context)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReleaseSyncWorker>()
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

class ReleaseSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            SettingsStore.awaitLoaded()
            if (!SettingsStore.releaseNotifications.value) {
                AutomaticReleaseManager.cancelAllIfDisabled()
                return Result.success()
            }
            val repo = AppGraph.repository
            // Reconciliation removes alarms that are absent from this list, so never feed it a
            // partial snapshot after a transient metadata failure.
            val localSaved = fetchCompleteSnapshot(LibraryStore.watchlist.value) { saved ->
                repo.releaseMetadataCompleteness(saved.anilistId)
            }
            // Capture one account generation. Network results from this worker may only affect
            // notifications and alarms while that exact account still owns the session.
            val accountGeneration = AuthManager.current()?.let { AuthManager.sessionGeneration() }
            if (accountGeneration != null) {
                runCatching {
                    val (items, unread) = repo.notifications(markAllRead = false)
                    AuthManager.commitIfSessionCurrent(accountGeneration) {
                        NotificationCenter.setUnread(unread)
                        AniListNotificationPushManager.notifyUnread(applicationContext, items)
                    }
                }

                val aniListTracked = anilistTrackedMedia(repo)
                val aniListIds = aniListTracked.mapTo(hashSetOf()) { it.id }
                // AniList notifications are now the canonical push source for logged-in users.
                // Keep local alarms only for device-only saves that AniList cannot notify about.
                AuthManager.commitIfSessionCurrent(accountGeneration) {
                    AutomaticReleaseManager.sync(localSaved.present.filterNot { it.id in aniListIds })
                }
            } else {
                AuthManager.commitIfLoggedOut {
                    NotificationCenter.setUnread(0)
                    AutomaticReleaseManager.sync(localSaved.present.distinctBy { it.id })
                }
            }
            Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Result.retry()
        }
    }

    private suspend fun anilistTrackedMedia(repo: com.miruronative.data.MiruroRepository): List<Media> {
        val viewerId = AuthManager.viewerId() ?: repo.viewer()?.id
            ?: throw IncompleteSourceException("AniList viewer identity is missing")
        // AniList's airing pushes cover shows the user is actively watching/rewatching. Planning,
        // paused, and favourite-only titles still need the app's local release alarm.
        val activeStatuses = setOf("CURRENT", "REPEATING")
        val collection = when (val signal = repo.userAnimeListCompleteness(viewerId)) {
            is SourceCompleteness.Present -> signal.value
            SourceCompleteness.DefinitiveAbsence -> return emptyList()
            is SourceCompleteness.Incomplete -> throw IncompleteSourceException(signal.reason, signal.cause)
        }
        val listMedia = collection.lists
            .flatMap { it.entries }
            .distinctBy { it.id }
            .filter { it.status in activeStatuses }
            .mapNotNull { it.media }
        return listMedia.distinctBy { it.id }
    }
}
