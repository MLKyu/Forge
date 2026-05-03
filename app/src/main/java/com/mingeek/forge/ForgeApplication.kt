package com.mingeek.forge

import android.app.Application
import com.mingeek.forge.data.discovery.DiscoveryWorkBindings
import com.mingeek.forge.data.discovery.DiscoveryWorkScheduler
import com.mingeek.forge.di.ForgeContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
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
        // Apply current toggle state on launch and react to changes — no
        // need for the user to bounce the app after flipping the switch.
        appScope.launch {
            container.settingsStore.discoveryNotificationsEnabled.collectLatest { enabled ->
                DiscoveryWorkScheduler.apply(this@ForgeApplication, enabled)
            }
        }
    }
}
