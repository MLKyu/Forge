package com.mingeek.forge

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mingeek.forge.data.discovery.DiscoveryWorkBindings
import com.mingeek.forge.data.discovery.DiscoveryWorkScheduler
import com.mingeek.forge.data.download.isActive
import com.mingeek.forge.di.ForgeContainer
import com.mingeek.forge.download.DownloadForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ForgeApplication : Application() {

    lateinit var container: ForgeContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = ForgeContainer(this)
        DiscoveryWorkBindings.bind(
            repository = container.discoveryRepository,
            settingsStore = container.settingsStore,
            notifier = container.discoveryNotifier,
        )
        // Apply current toggle / interval on launch and react to either
        // changing — no need for the user to bounce the app.
        appScope.launch {
            combine(
                container.settingsStore.discoveryNotificationsEnabled,
                container.settingsStore.discoveryNotificationsIntervalHours,
            ) { enabled, hours -> enabled to hours }
                .collectLatest { (enabled, hours) ->
                    DiscoveryWorkScheduler.apply(this@ForgeApplication, enabled, hours)
                }
        }
        // Start the foreground service whenever the queue picks up its
        // first non-terminal entry. The service stops itself when the
        // queue drains, so we don't have to track that side here.
        appScope.launch {
            container.downloadQueue.state
                .map { states -> states.values.any { it.isActive } }
                .distinctUntilChanged()
                .collectLatest { hasActive ->
                    if (hasActive) {
                        val intent = Intent(this@ForgeApplication, DownloadForegroundService::class.java)
                        ContextCompat.startForegroundService(this@ForgeApplication, intent)
                    }
                }
        }
    }
}
