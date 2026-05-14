package com.mingeek.forge.messaging

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mingeek.forge.MainActivity
import com.mingeek.forge.R

class ForgeMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // No backend yet — surface the token to logcat so it can be copied
        // into the Firebase console for test sends.
        Log.i(TAG, "FCM token: $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Background notification-only payloads are rendered automatically by
        // the system using the manifest meta-data defaults. We only need to
        // build a notification ourselves for foreground delivery or for
        // data-only payloads.
        val notification = message.notification
        val title = notification?.title ?: message.data["title"] ?: return
        val body = notification?.body ?: message.data["body"].orEmpty()
        // Derive a stable id from the message so two distinct pushes don't
        // collapse into a single notification slot. Fall back to nanoTime for
        // data-only payloads with no id — using a fixed counter range risked
        // colliding with `DownloadNotifications.childId`, which derives from
        // an unbounded `String.hashCode()`.
        val id = (message.messageId ?: message.data["google.message_id"])
            ?.hashCode()
            ?: System.nanoTime().toInt()
        showNotification(id, title, body)
    }

    private fun showNotification(id: Int, title: String, body: String) {
        FcmNotifications.ensureChannel(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Dropping FCM notification — POST_NOTIFICATIONS not granted")
            return
        }

        val launchIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, FcmNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        NotificationManagerCompat.from(this).notify(id, notification)
    }

    private companion object {
        const val TAG = "ForgeFCM"
    }
}
