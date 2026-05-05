package com.mingeek.forge.feature.workflows

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

const val WorkflowsRoute = "workflows"

fun NavGraphBuilder.workflowsScreen(
    storage: ModelStorage,
    registry: RuntimeRegistry,
    settingsStore: SettingsStore,
    runHistory: MemoryStore,
    modifier: Modifier = Modifier,
) {
    composable(WorkflowsRoute) {
        val viewModel: WorkflowsViewModel = viewModel(
            factory = viewModelFactory {
                initializer { WorkflowsViewModel(storage, registry, settingsStore, runHistory) }
            }
        )
        WorkflowsScreen(viewModel = viewModel, modifier = modifier)
    }
}
