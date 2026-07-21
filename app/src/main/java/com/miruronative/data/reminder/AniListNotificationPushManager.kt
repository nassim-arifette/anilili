package com.miruronative.data.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.miruronative.MainActivity
import com.miruronative.R
import com.miruronative.data.model.AppNotification
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.nav.Routes
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Bridges AniList's in-app notification feed to Android notifications. AniList exposes unread
 * state as a count instead of per-item flags, so delivered IDs are stored locally to avoid
 * re-pushing the same unread items during every periodic sync.
 */
object AniListNotificationPushManager {
    const val CHANNEL_ID = "anilist_account_notifications"
    private const val PREFS = "anilili_anilist_notifications"
    private const val KEY_DELIVERED_IDS = "delivered_ids"
    private const val MAX_DELIVERED_IDS = 400
    private const val MAX_PUSHES_PER_SYNC = 8
    private const val NOTIFICATION_TAG = "anilist-account"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val deliveryLock = Any()
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        createChannel(appContext)
    }

    fun notifyUnread(context: Context, items: List<AppNotification>) {
        val app = if (::appContext.isInitialized) appContext else context.applicationContext.also {
            appContext = it
        }
        createChannel(app)
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (!canPostNotifications(app, manager)) return

        synchronized(deliveryLock) {
            // Re-check while holding the same lock as the ledger. Permission or channel state can
            // change while a worker is waiting behind another delivery.
            if (!canPostNotifications(app, manager)) return
            val delivered = readDelivered(app)
            val selected = selectNotificationsForDelivery(items, delivered, MAX_PUSHES_PER_SYNC)
            if (selected.isEmpty()) return

            val successfullyPosted = selected.asReversed()
                .filter { item -> notify(app, manager, item) }
                .mapTo(hashSetOf(), AppNotification::id)
            if (successfullyPosted.isEmpty()) return

            val successfulIdsInFeedOrder = selected
                .map(AppNotification::id)
                .filter(successfullyPosted::contains)
            val nextLedger = updateDeliveredLedger(
                deliveredIds = delivered,
                successfullyPostedIds = successfulIdsInFeedOrder,
                limit = MAX_DELIVERED_IDS,
            )
            if (!writeDelivered(app, nextLedger)) {
                DiagnosticsLog.event("AniList notification ledger commit failed")
                successfullyPosted.forEach { manager.cancel(NOTIFICATION_TAG, it) }
                return
            }
            postGroupSummary(app, manager)
        }
    }

    fun clearDelivered(context: Context) {
        val app = context.applicationContext
        synchronized(deliveryLock) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_DELIVERED_IDS)
                .commit()
        }
    }

    /** Clears every account-owned surface when logging out or replacing the AniList account. */
    fun resetAccountState(context: Context) {
        val app = context.applicationContext
        synchronized(deliveryLock) {
            val deliveredIds = readDelivered(app)
            clearDelivered(app)
            dismissAll(app, deliveredIds)
            NotificationCenter.setUnread(0)
        }
    }

    /**
     * Removes this channel's notifications from the system tray. Called when the in-app
     * Notifications page opens (the user is looking at the same items) and on "mark all read",
     * so the shade never shows entries the app already considers handled.
     */
    fun dismissAll(context: Context) {
        synchronized(deliveryLock) {
            dismissAll(context.applicationContext, emptyList())
        }
    }

    private fun dismissAll(context: Context, knownIds: List<Int>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        knownIds.forEach { manager.cancel(NOTIFICATION_TAG, it) }
        manager.cancel(NOTIFICATION_TAG, SUMMARY_ID)
        runCatching {
            manager.activeNotifications
                .filter { it.tag == NOTIFICATION_TAG || it.notification.channelId == CHANNEL_ID }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }

    private fun notify(context: Context, manager: NotificationManager, item: AppNotification): Boolean {
        val route = item.mediaId?.let(Routes::detail) ?: Routes.NOTIFICATIONS
        val open = PendingIntent.getActivity(
            context,
            item.id,
            Intent(context, MainActivity::class.java).apply {
                data = Uri.parse("anililiplus://notification/anilist/${item.id}")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Routes.EXTRA_ROUTE, route)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (item.kind) {
            AppNotification.Kind.AIRING -> "New episode on AniList"
            AppNotification.Kind.MEDIA -> "AniList media update"
            AppNotification.Kind.SOCIAL -> "AniList notification"
        }
        val text = itemText(item)
        val actionLabel = if (item.mediaId != null) "View anime" else "Open notifications"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, actionLabel, open)
            .setAutoCancel(true)
            .setGroup(CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return runCatching {
            manager.notify(NOTIFICATION_TAG, item.id, notification)
            true
        }.getOrElse { error ->
            DiagnosticsLog.throwable("AniList notification ${item.id} was not posted", error)
            false
        }
    }

    /** Without a summary, Android never collapses the group — each push stays a separate row. */
    private fun postGroupSummary(context: Context, manager: NotificationManager) {
        val open = PendingIntent.getActivity(
            context,
            SUMMARY_ID,
            Intent(context, MainActivity::class.java).apply {
                data = Uri.parse("anililiplus://notification/anilist/summary")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Routes.EXTRA_ROUTE, Routes.NOTIFICATIONS)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AniList notifications")
            .setContentIntent(open)
            .setGroup(CHANNEL_ID)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()
        runCatching { manager.notify(NOTIFICATION_TAG, SUMMARY_ID, summary) }
            .onFailure { DiagnosticsLog.throwable("AniList notification summary was not posted", it) }
    }

    private const val SUMMARY_ID = -1001

    private fun itemText(item: AppNotification): String = when {
        item.badge != null -> "${item.title} - ${item.badge}"
        item.detail != null -> "${item.title} - ${item.detail}"
        else -> item.title
    }

    private fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AniList notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Pushes unread notifications from your AniList account"
            },
        )
    }

    private fun canPostNotifications(context: Context, manager: NotificationManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun readDelivered(context: Context): List<Int> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DELIVERED_IDS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Int.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writeDelivered(context: Context, ids: List<Int>): Boolean {
        val raw = json.encodeToString(ListSerializer(Int.serializer()), ids)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DELIVERED_IDS, raw)
            .commit()
    }
}
