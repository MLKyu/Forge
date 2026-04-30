package com.mingeek.forge.feature.settings

import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mingeek.forge.core.hardware.DeviceProfile
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore

const val SettingsRoute = "settings"

fun NavGraphBuilder.settingsScreen(
    settingsStore: SettingsStore,
    storage: ModelStorage,
    deviceProfile: DeviceProfile,
    modifier: Modifier = Modifier,
) {
    composable(SettingsRoute) {
        val viewModel: SettingsViewModel = viewModel(
            factory = viewModelFactory {
                initializer { SettingsViewModel(settingsStore, storage, deviceProfile) }
            }
        )
        SettingsScreen(viewModel = viewModel, modifier = modifier)
    }
}
