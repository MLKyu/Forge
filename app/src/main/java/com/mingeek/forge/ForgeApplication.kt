package com.mingeek.forge

import android.app.Application
import com.mingeek.forge.data.discovery.DiscoveryWorkBindings
import com.mingeek.forge.data.discovery.DiscoveryWorkScheduler
import com.mingeek.forge.di.ForgeContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    }
}
