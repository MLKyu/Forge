package com.mingeek.forge.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mingeek.forge.ForgeApplication

/**
 * Translates notification action taps into [DownloadQueue][com.mingeek.forge.data.download.DownloadQueue]
 * calls. Lives in a receiver rather than the service so taps work even
 * when the service is briefly between foreground states.
 */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val container = (context.applicationContext as? ForgeApplication)?.container ?: return
        when (intent.action) {
            ACTION_PAUSE -> container.downloadQueue.pause(key)
            ACTION_RESUME -> container.downloadQueue.resume(key)
            ACTION_CANCEL -> container.downloadQueue.cancel(key)
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.mingeek.forge.action.DOWNLOAD_PAUSE"
        const val ACTION_RESUME = "com.mingeek.forge.action.DOWNLOAD_RESUME"
        const val ACTION_CANCEL = "com.mingeek.forge.action.DOWNLOAD_CANCEL"
        const val EXTRA_KEY = "key"

        fun pendingIntent(context: Context, action: String, key: String): PendingIntent {
            val intent = Intent(context, DownloadActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_KEY, key)
                // Force-distinct request codes per (action, key) pair so PIs
                // don't get collapsed by the system.
                .also { it.setData(android.net.Uri.parse("forge://dl/${action.hashCode()}/$key")) }
            return PendingIntent.getBroadcast(
                context,
                (action.hashCode() xor key.hashCode()),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
