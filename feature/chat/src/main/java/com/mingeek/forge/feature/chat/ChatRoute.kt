package com.mingeek.forge.feature.chat

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mingeek.forge.agent.memory.MemoryStore
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import com.mingeek.forge.runtime.registry.RuntimeRegistry
import kotlinx.coroutines.CoroutineScope

const val ChatRoute = "chat"

fun NavGraphBuilder.chatScreen(
    storage: ModelStorage,
    registry: RuntimeRegistry,
    settingsStore: SettingsStore,
    chatHistory: MemoryStore,
    appScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    composable(ChatRoute) {
        val application = LocalContext.current.applicationContext as Application
        val viewModel: ChatViewModel = viewModel(
            factory = viewModelFactory {
                initializer {
                    ChatViewModel(
                        app = application,
                        storage = storage,
                        registry = registry,
                        settingsStore = settingsStore,
                        chatHistory = chatHistory,
                        appScope = appScope,
                    )
                }
            }
        )
        ChatScreen(viewModel = viewModel, modifier = modifier)
    }
}
