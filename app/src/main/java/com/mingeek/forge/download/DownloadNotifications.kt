package com.mingeek.forge.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mingeek.forge.MainActivity
import com.mingeek.forge.R
import com.mingeek.forge.data.download.DownloadState

/**
 * Notification builders for the download Foreground Service. The queue
 * publishes one summary notification (the FG one — required for the
 * service to stay alive) plus one child per active download with
 * per-row actions. Stable IDs are derived from [DownloadState.request]
 * so updates replace existing notifications instead of stacking.
 */
internal object DownloadNotifications {

    const val CHANNEL_ID = "downloads"
    const val GROUP_KEY = "forge_downloads"
    const val SUMMARY_NOTIFICATION_ID = 2_000

    /** Maps a queue key to a notification id stable for the queue's lifetime. */
    fun childId(key: String): Int {
        val hash = key.hashCode()
        // Avoid collision with SUMMARY_NOTIFICATION_ID; +1 buys distance.
        return if (hash == SUMMARY_NOTIFICATION_ID) hash + 1 else hash
    }

    fun ensureChannel(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.download_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun buildSummary(context: Context, states: Map<String, DownloadState>): android.app.Notification {
        val active = states.values.count {
            it is DownloadState.Queued || it is DownloadState.Running || it is DownloadState.Verifying
        }
        val paused = states.values.count { it is DownloadState.Paused }

        val title = if (active > 0) {
            context.resources.getQuantityString(R.plurals.download_summary_active, active, active)
        } else if (paused > 0) {
            context.resources.getQuantityString(R.plurals.download_summary_paused, paused, paused)
        } else {
            context.getString(R.string.download_summary_idle)
        }

        val (bytes, total) = states.values
            .filterIsInstance<DownloadState.Running>()
            .fold(0L to 0L) { (b, t), st ->
                (b + st.bytesDownloaded) to (t + (st.totalBytes ?: 0L))
            }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(formatProgressLine(context, bytes, total))
            .setOnlyAlertOnce(true)
            .setOngoing(active > 0)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(launchAppIntent(context))
            .apply {
                if (total > 0) setProgress(100, ((bytes * 100) / total).toInt().coerceIn(0, 100), false)
                else if (active > 0) setProgress(0, 0, true)
            }
            .build()
    }

    fun buildChild(context: Context, state: DownloadState): android.app.Notification {
        val request = state.request
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(request.displayName)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(launchAppIntent(context))

        when (state) {
            is DownloadState.Queued -> {
                builder.setContentText(context.getString(R.string.download_status_queued))
                    .setProgress(0, 0, true)
                    .setOngoing(true)
                    .addAction(cancelAction(context, request.key))
            }
            is DownloadState.Running -> {
                builder.setContentText(formatProgressLine(context, state.bytesDownloaded, state.totalBytes ?: 0L))
                    .also {
                        val total = state.totalBytes
                        if (total != null && total > 0) {
                            val pct = ((state.bytesDownloaded * 100) / total).toInt().coerceIn(0, 100)
                            it.setProgress(100, pct, false)
                        } else {
                            it.setProgress(0, 0, true)
                        }
                    }
                    .setOngoing(true)
                    .addAction(pauseAction(context, request.key))
                    .addAction(cancelAction(context, request.key))
            }
            is DownloadState.Verifying -> {
                builder.setContentText(context.getString(R.string.download_status_verifying))
                    .setProgress(0, 0, true)
                    .setOngoing(true)
            }
            is DownloadState.Paused -> {
                builder.setContentText(formatProgressLine(context, state.bytesDownloaded, state.totalBytes ?: 0L) +
                    " · " + context.getString(R.string.download_status_paused))
                    .also {
                        val total = state.totalBytes
                        if (total != null && total > 0) {
                            val pct = ((state.bytesDownloaded * 100) / total).toInt().coerceIn(0, 100)
                            it.setProgress(100, pct, false)
                        }
                    }
                    .setOngoing(false)
                    .addAction(resumeAction(context, request.key))
                    .addAction(cancelAction(context, request.key))
            }
            is DownloadState.Completed -> {
                builder.setContentText(context.getString(R.string.download_status_completed))
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(false)
                    .setAutoCancel(true)
            }
            is DownloadState.Failed -> {
                builder.setContentText(context.getString(R.string.download_status_failed_with_message, state.message))
                    .setOngoing(false)
                    .setAutoCancel(true)
            }
        }
        return builder.build()
    }

    private fun formatProgressLine(context: Context, bytes: Long, total: Long): String {
        val totalLabel = if (total > 0) formatSize(total) else "?"
        return context.getString(R.string.download_progress_size, formatSize(bytes), totalLabel)
    }

    private fun launchAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun pauseAction(context: Context, key: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_pause,
            context.getString(R.string.download_action_pause),
            DownloadActionReceiver.pendingIntent(context, DownloadActionReceiver.ACTION_PAUSE, key),
        ).build()

    private fun resumeAction(context: Context, key: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_play,
            context.getString(R.string.download_action_resume),
            DownloadActionReceiver.pendingIntent(context, DownloadActionReceiver.ACTION_RESUME, key),
        ).build()

    private fun cancelAction(context: Context, key: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.download_action_cancel),
            DownloadActionReceiver.pendingIntent(context, DownloadActionReceiver.ACTION_CANCEL, key),
        ).build()

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "?"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return "%.1f %s".format(v, units[i])
    }
}
