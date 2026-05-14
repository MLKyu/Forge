package com.mingeek.forge.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.mingeek.forge.R

internal object FcmNotifications {

    const val CHANNEL_ID = "fcm_default"

    fun ensureChannel(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.fcm_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.fcm_channel_description)
        }
        nm.createNotificationChannel(channel)
    }
}
