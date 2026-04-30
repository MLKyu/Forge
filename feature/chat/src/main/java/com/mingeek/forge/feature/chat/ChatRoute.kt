package com.mingeek.forge.feature.chat

import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import com.mingeek.forge.runtime.registry.RuntimeRegistry

const val ChatRoute = "chat"

fun NavGraphBuilder.chatScreen(
    storage: ModelStorage,
    registry: RuntimeRegistry,
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    composable(ChatRoute) {
        val viewModel: ChatViewModel = viewModel(
            factory = viewModelFactory {
                initializer { ChatViewModel(storage, registry, settingsStore) }
            }
        )
        ChatScreen(viewModel = viewModel, modifier = modifier)
    }
}
