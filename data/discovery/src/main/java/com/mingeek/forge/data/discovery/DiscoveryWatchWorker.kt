package com.mingeek.forge.data.discovery

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mingeek.forge.data.storage.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background worker that polls every [DiscoverySource] via the supplied
 * repository, diffs against `seenDiscoveryIds`, and posts a notification
 * for what's new. Wiring is done through [DiscoveryWorkBindings] because
 * WorkManager instantiates workers itself; without Hilt this is the
 * simplest way to give the worker access to the singletons it needs.
 *
 * Schedule lifetime: enable / disable via [DiscoveryWorkScheduler]
 * — the work request is keyed by a unique name, so toggling the setting
 * cancels and re-enqueues without leaking duplicates.
 */
class DiscoveryWatchWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val deps = DiscoveryWorkBindings.snapshot() ?: return Result.success()
        if (!deps.settingsStore.discoveryNotificationsEnabled.first()) return Result.success()

        val feeds = runCatching { deps.repository.fetchAll() }.getOrNull()
            ?: return Result.retry()
        val discovered = feeds.flatMap { it.items }

        val seen = deps.settingsStore.seenDiscoveryIds.first()
        val newOnes = discovered.filter { it.card.id !in seen }
        if (newOnes.isEmpty()) return Result.success()

        deps.notifier.notifyNewModels(newOnes)
        deps.settingsStore.markDiscoveryIdsSeen(newOnes.map { it.card.id }.toSet())
        return Result.success()
    }
}

/**
 * Process-singleton accessor for worker dependencies. Populated by
 * ForgeApplication.onCreate so the Worker class — which WorkManager
 * instantiates on its own — can still reach the repository, settings
 * store, and notifier.
 */
object DiscoveryWorkBindings {
    @Volatile private var repository: DiscoveryRepository? = null
    @Volatile private var settingsStore: SettingsStore? = null
    @Volatile private var notifier: DiscoveryNotifier? = null

    fun bind(repository: DiscoveryRepository, settingsStore: SettingsStore, notifier: DiscoveryNotifier) {
        this.repository = repository
        this.settingsStore = settingsStore
        this.notifier = notifier
    }

    internal data class Snapshot(
        val repository: DiscoveryRepository,
        val settingsStore: SettingsStore,
        val notifier: DiscoveryNotifier,
    )

    internal fun snapshot(): Snapshot? {
        val r = repository ?: return null
        val s = settingsStore ?: return null
        val n = notifier ?: return null
        return Snapshot(r, s, n)
    }
}

/** Schedules / cancels the periodic discovery poll. */
object DiscoveryWorkScheduler {

    private const val UNIQUE_NAME = "forge-discovery-watch"

    fun apply(context: Context, enabled: Boolean, intervalHours: Int = 6) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val safeHours = intervalHours.coerceIn(1, 24).toLong()
        val request = PeriodicWorkRequestBuilder<DiscoveryWatchWorker>(safeHours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        wm.enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
