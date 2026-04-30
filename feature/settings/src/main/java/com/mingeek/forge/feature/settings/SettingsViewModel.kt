package com.mingeek.forge.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.forge.core.hardware.DeviceProfile
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StorageSummary(
    val installedCount: Int,
    val totalBytes: Long,
    val freeBytes: Long,
)

data class SettingsUiState(
    val hfToken: String = "",
    val tokenSet: Boolean = false,
    val npuEnabled: Boolean = true,
    val temperature: Float = 0.7f,
    val deviceProfile: DeviceProfile,
    val storage: StorageSummary,
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    storage: ModelStorage,
    private val deviceProfile: DeviceProfile,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settingsStore.hfToken,
        settingsStore.npuEnabled,
        settingsStore.defaultTemperature,
        storage.installed,
    ) { token, npu, temp, installed ->
        SettingsUiState(
            hfToken = token.orEmpty(),
            tokenSet = !token.isNullOrEmpty(),
            npuEnabled = npu,
            temperature = temp,
            deviceProfile = deviceProfile,
            storage = installed.toSummary(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(
            deviceProfile = deviceProfile,
            storage = StorageSummary(0, 0L, deviceProfile.freeStorageBytes),
        ),
    )

    private val pendingToken = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    private val pendingTemperature = MutableSharedFlow<Float>(
        replay = 0,
        extraBufferCapacity = 1,
    )

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            pendingToken
                .debounce(400)
                .distinctUntilChanged()
                .collect { raw ->
                    settingsStore.setHfToken(raw.takeIf { it.isNotBlank() })
                }
        }
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            pendingTemperature
                .debounce(150)
                .distinctUntilChanged()
                .collect { settingsStore.setDefaultTemperature(it) }
        }
    }

    fun onTokenChanged(token: String) {
        pendingToken.tryEmit(token)
    }

    fun onNpuChanged(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNpuEnabled(enabled) }
    }

    fun onTemperatureChanged(value: Float) {
        pendingTemperature.tryEmit(value)
    }

    private fun List<InstalledModel>.toSummary() = StorageSummary(
        installedCount = size,
        totalBytes = sumOf { it.sizeBytes },
        freeBytes = deviceProfile.freeStorageBytes,
    )
}
