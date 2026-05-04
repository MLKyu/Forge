package com.mingeek.forge.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mingeek.forge.di.ForgeContainer
import com.mingeek.forge.feature.agents.agentsScreen
import com.mingeek.forge.feature.catalog.catalogRouteWithModelId
import com.mingeek.forge.feature.catalog.catalogScreen
import com.mingeek.forge.feature.chat.chatScreen
import com.mingeek.forge.feature.compare.compareScreen
import com.mingeek.forge.feature.discover.DiscoverRoute
import com.mingeek.forge.feature.discover.discoverScreen
import com.mingeek.forge.feature.library.libraryScreen
import com.mingeek.forge.feature.settings.SettingsRoute
import com.mingeek.forge.feature.settings.settingsScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

@Composable
fun ForgeNavHost(
    navController: NavHostController,
    container: ForgeContainer,
    modifier: Modifier = Modifier,
    startDestination: String = DiscoverRoute,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        discoverScreen(
            repository = container.discoveryRepository,
            collectionRepository = container.collectionRepository,
            recommender = container.recommender,
            fitScorer = container.deviceFitScorer,
            storage = container.storage,
            settingsStore = container.settingsStore,
            curatorAgentFactory = container.curatorAgentFactory,
            onOpenInCatalog = { modelId ->
                navController.navigate(catalogRouteWithModelId(modelId))
            },
            onOpenSettings = { navController.navigate(SettingsRoute) },
        )
        catalogScreen(
            catalogSource = container.catalogSource,
            downloadQueue = container.downloadQueue,
            storage = container.storage,
            fitScorer = container.deviceFitScorer,
            runtimeRegistry = container.runtimeRegistry,
        )
        libraryScreen(
            storage = container.storage,
            benchmarkStore = container.benchmarkStore,
            benchmarkRunner = container.benchmarkRunner,
            fitScorer = container.deviceFitScorer,
            settingsStore = container.settingsStore,
        )
        chatScreen(
            storage = container.storage,
            registry = container.runtimeRegistry,
            settingsStore = container.settingsStore,
            chatHistory = container.chatHistory,
        )
        compareScreen(
            storage = container.storage,
            registry = container.runtimeRegistry,
            settingsStore = container.settingsStore,
        )
        agentsScreen(
            storage = container.storage,
            registry = container.runtimeRegistry,
            settingsStore = container.settingsStore,
            runHistory = container.agentRunHistory,
        )
        settingsScreen(
            settingsStore = container.settingsStore,
            storage = container.storage,
            deviceProfile = container.deviceProfile,
        )
    }
}
