package com.mingeek.forge.feature.discover

import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mingeek.forge.data.discovery.DiscoveryRepository
import com.mingeek.forge.data.storage.ModelStorage

const val DiscoverRoute = "discover"

fun NavGraphBuilder.discoverScreen(
    repository: DiscoveryRepository,
    storage: ModelStorage,
    curatorAgentFactory: CuratorAgentFactory,
    onOpenInCatalog: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    composable(DiscoverRoute) {
        val viewModel: DiscoverViewModel = viewModel(
            factory = viewModelFactory {
                initializer { DiscoverViewModel(repository, storage, curatorAgentFactory) }
            }
        )
        DiscoverScreen(
            viewModel = viewModel,
            onOpenInCatalog = onOpenInCatalog,
            modifier = modifier,
        )
    }
}
