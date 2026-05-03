package com.mingeek.forge.data.discovery

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
import com.mingeek.forge.domain.DiscoveredModel

/**
 * Posts a "new model discovered" notification. Tap on the notification fires
 * a generic open-app intent (the user lands on Discover); we don't deep-link
 * to a specific card yet because the navigation graph doesn't expose stable
 * destinations for arbitrary discovered ids.
 *
 * The channel is created lazily on first use. POST_NOTIFICATIONS permission
 * is the caller's problem — Settings is the right place to ask for it,
 * because that's where the toggle lives.
 */
class DiscoveryNotifier(private val context: Context) {

    private val nm: NotificationManagerCompat = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model discoveries",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "New models surfaced by trending / RSS sources."
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Notify about up to [MAX_PER_NOTIFY] new models in a single bundled
     * notification. Returns true if a notification was posted (i.e. we have
     * runtime permission and at least one new model).
     */
    fun notifyNewModels(newModels: List<DiscoveredModel>): Boolean {
        if (newModels.isEmpty()) return false
        if (!hasNotificationPermission()) return false
        ensureChannel()

        val title = if (newModels.size == 1) {
            "New model: ${newModels.first().card.displayName}"
        } else {
            "${newModels.size} new models discovered"
        }
        val body = newModels.take(MAX_PER_NOTIFY).joinToString("\n") { d ->
            val sourceName = SOURCE_LABELS[d.sourceId] ?: d.sourceId
            "• ${d.card.displayName} ($sourceName)"
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("forge://discover"))
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

        val pi = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching { nm.notify(NOTIF_ID, notification) }
        return true
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val CHANNEL_ID = "discovery"
        const val NOTIF_ID = 1001
        const val MAX_PER_NOTIFY = 5
        val SOURCE_LABELS = mapOf(
            "hf-trending" to "HF Trending",
            "hf-recent" to "HF Recent",
            "hf-liked" to "HF Liked",
            "hf-blog" to "HF Blog",
            "reddit-localllama" to "r/LocalLLaMA",
        )
    }
}
