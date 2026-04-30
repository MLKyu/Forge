package com.mingeek.forge.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.forge.data.discovery.DiscoveryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val isRefreshing: Boolean = false,
    val feeds: List<DiscoveryRepository.Feed> = emptyList(),
    val error: String? = null,
)

class DiscoverViewModel(
    private val repository: DiscoveryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, error = null) }
            try {
                val feeds = repository.fetchAll()
                _state.update { it.copy(isRefreshing = false, feeds = feeds) }
            } catch (t: Throwable) {
                _state.update { it.copy(isRefreshing = false, error = t.message ?: "fetch failed") }
            }
        }
    }
}
