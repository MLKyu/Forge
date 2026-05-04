package com.mingeek.forge.download

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mingeek.forge.ForgeApplication
import com.mingeek.forge.data.download.DownloadState
import com.mingeek.forge.data.download.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Hosts the in-flight downloads. The service is started by
 * [com.mingeek.forge.ForgeApplication] when [com.mingeek.forge.data.download.DownloadQueue]
 * has any non-terminal entry, and stops itself when the queue drains —
 * we never want a foreground notification hanging around with nothing
 * to show.
 *
 * The actual byte-pumping coroutines run inside the queue, which is
 * scoped to the application. The service exists purely to (a) keep the
 * process alive while bytes flow, (b) render notifications, and (c)
 * funnel pause/resume/cancel taps from the notification back into the
 * queue (via [DownloadActionReceiver]).
 */
class DownloadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    /** Notification ids we've posted so we can retract them when their
     *  state disappears (e.g. on cancel or terminal-cleared). */
    private val postedChildIds: MutableSet<Int> = mutableSetOf()

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground within 5s of startForegroundService.
        // Use an idle placeholder; the observer below will replace it.
        val initial = DownloadNotifications.buildSummary(this, currentStates())
        if (!startForegroundCompat(initial)) {
            // Background-restart path on API 31+ throws
            // ForegroundServiceStartNotAllowedException — bail without
            // crashing. The application observer will spin us back up
            // when there's actually work and the app is in the
            // foreground.
            stopSelf()
            return START_NOT_STICKY
        }
        observe()
        // START_NOT_STICKY is intentional: queue state lives in memory
        // only, so a sticky restart would resurrect an empty service
        // and (worse) crash on API 31+ when the app is in the
        // background. The user re-tapping Download is the real
        // resurrection signal.
        return START_NOT_STICKY
    }

    /**
     * @return true on success, false if the platform refused the
     *   foreground start (Android 12+ background-restart path).
     */
    private fun startForegroundCompat(notification: android.app.Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                DownloadNotifications.SUMMARY_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(DownloadNotifications.SUMMARY_NOTIFICATION_ID, notification)
        }
        true
    } catch (_: Throwable) {
        false
    }

    private fun observe() {
        if (observerJob?.isActive == true) return
        val container = (application as ForgeApplication).container
        observerJob = scope.launch {
            container.downloadQueue.state.collectLatest { states ->
                render(states)
                val anyActive = states.values.any { it.isActive } ||
                    states.values.any { it is DownloadState.Paused }
                if (!anyActive) {
                    // Drain done — drop FG state and self-stop. The
                    // application observer will spin us back up when a
                    // new download arrives.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun render(states: Map<String, DownloadState>) {
        val nm = NotificationManagerCompat.from(this)

        // Update the summary that anchors the foreground state.
        val summary = DownloadNotifications.buildSummary(this, states)
        notifySafely(nm, DownloadNotifications.SUMMARY_NOTIFICATION_ID, summary)

        // Per-key children. Track posted ids so we can cancel ones that
        // disappear (e.g. user dismissed completed, or cancelled).
        val seenIds = mutableSetOf<Int>()
        for ((key, state) in states) {
            val id = DownloadNotifications.childId(key)
            seenIds.add(id)
            val notif = DownloadNotifications.buildChild(this, state)
            notifySafely(nm, id, notif)
        }
        for (stale in postedChildIds - seenIds) {
            runCatching { nm.cancel(stale) }
        }
        postedChildIds.clear()
        postedChildIds.addAll(seenIds)
    }

    /**
     * Posts a notification only when POST_NOTIFICATIONS is granted (API
     * 33+). Without the runtime permission [NotificationManagerCompat.notify]
     * silently no-ops, but lint still flags it — so the early return
     * keeps the static analyzer honest and saves a SecurityException
     * round-trip when the user has denied us. The foreground state of
     * the service is unaffected: [startForeground] is permission-free
     * for `dataSync` services.
     */
    private fun notifySafely(nm: NotificationManagerCompat, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        runCatching { nm.notify(id, notification) }
    }

    private fun currentStates(): Map<String, DownloadState> =
        (application as? ForgeApplication)?.container?.downloadQueue?.state?.value
            ?: emptyMap()

    override fun onDestroy() {
        observerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
