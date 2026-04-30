package com.mingeek.forge.feature.compare

import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import com.mingeek.forge.runtime.registry.RuntimeRegistry

const val CompareRoute = "compare"

fun NavGraphBuilder.compareScreen(
    storage: ModelStorage,
    registry: RuntimeRegistry,
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    composable(CompareRoute) {
        val viewModel: CompareViewModel = viewModel(
            factory = viewModelFactory {
                initializer { CompareViewModel(storage, registry, settingsStore) }
            }
        )
        CompareScreen(viewModel = viewModel, modifier = modifier)
    }
}
