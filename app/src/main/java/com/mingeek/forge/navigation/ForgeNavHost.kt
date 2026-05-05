package com.mingeek.forge.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mingeek.forge.di.ForgeContainer
import com.mingeek.forge.feature.workflows.workflowsScreen
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

/**
 * Hosts the top-level destination graph.
 *
 * [paddingValues] are passed through `consumeWindowInsets` *before* being
 * applied as visual padding so descendant `Modifier.imePadding()` calls
 * (notably the chat composer) don't double-count the bottom-bar height
 * — without this, every chat-style screen would render an empty
 * BottomBar-height gap between the input row and the soft keyboard.
 */
@Composable
fun ForgeNavHost(
    navController: NavHostController,
    container: ForgeContainer,
    paddingValues: PaddingValues,
    modifier: Modifier = Modifier,
    startDestination: String = DiscoverRoute,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
            .consumeWindowInsets(paddingValues)
            .padding(paddingValues),
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
            appScope = container.appScope,
        )
        compareScreen(
            storage = container.storage,
            registry = container.runtimeRegistry,
            settingsStore = container.settingsStore,
        )
        workflowsScreen(
            storage = container.storage,
            registry = container.runtimeRegistry,
            settingsStore = container.settingsStore,
            runHistory = container.workflowRunHistory,
        )
        settingsScreen(
            settingsStore = container.settingsStore,
            storage = container.storage,
            deviceProfile = container.deviceProfile,
        )
    }
}
