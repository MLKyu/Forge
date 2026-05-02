package com.mingeek.forge.feature.agents

import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mingeek.forge.agent.memory.MemoryStore
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import com.mingeek.forge.runtime.registry.RuntimeRegistry

const val AgentsRoute = "agents"

fun NavGraphBuilder.agentsScreen(
    storage: ModelStorage,
    registry: RuntimeRegistry,
    settingsStore: SettingsStore,
    runHistory: MemoryStore,
    modifier: Modifier = Modifier,
) {
    composable(AgentsRoute) {
        val viewModel: AgentsViewModel = viewModel(
            factory = viewModelFactory {
                initializer { AgentsViewModel(storage, registry, settingsStore, runHistory) }
            }
        )
        AgentsScreen(viewModel = viewModel, modifier = modifier)
    }
}
