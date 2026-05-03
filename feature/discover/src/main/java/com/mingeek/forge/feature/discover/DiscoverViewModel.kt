package com.mingeek.forge.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.forge.agent.core.Agent
import com.mingeek.forge.agent.curator.LlmCurator
import com.mingeek.forge.agent.curator.ModelEvaluator
import com.mingeek.forge.data.discovery.Collection
import com.mingeek.forge.data.discovery.CollectionRepository
import com.mingeek.forge.data.discovery.DiscoveryRepository
import com.mingeek.forge.data.discovery.RecommendationEngine
import com.mingeek.forge.data.discovery.RecommendedModel
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.domain.Curation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val isRefreshing: Boolean = false,
    val feeds: List<DiscoveryRepository.Feed> = emptyList(),
    val collections: List<Collection> = emptyList(),
    val recommendations: List<RecommendedModel> = emptyList(),
    val error: String? = null,
    val isCurating: Boolean = false,
    val curatorModelId: String? = null,
    val curatorError: String? = null,
    /** card.id → most recent Curation produced by [curatorModelId]. */
    val curations: Map<String, Curation> = emptyMap(),
    val curatedCount: Int = 0,
)

/**
 * [DiscoverViewModel] takes a factory rather than referencing LlmAgent
 * directly. Keeps :feature:discover decoupled from :feature:agents — the
 * factory is bound at the app DI layer where both can be reached.
 */
typealias CuratorAgentFactory = (InstalledModel) -> Agent

class DiscoverViewModel(
    private val repository: DiscoveryRepository,
    private val collectionRepository: CollectionRepository,
    private val recommender: RecommendationEngine,
    storage: ModelStorage,
    private val curatorAgentFactory: CuratorAgentFactory,
) : ViewModel() {

    val installed: StateFlow<List<InstalledModel>> = storage.installed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, error = null) }
            try {
                val feeds = repository.fetchAll()
                val collections = runCatching { collectionRepository.fetchAll() }.getOrDefault(emptyList())
                val candidates = feeds.flatMap { it.items }.distinctBy { it.card.id }
                val recs = runCatching {
                    recommender.recommend(candidates, installed.value, limit = 12)
                }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        isRefreshing = false,
                        feeds = feeds,
                        collections = collections,
                        recommendations = recs,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isRefreshing = false, error = t.message ?: "fetch failed") }
            }
        }
    }

    fun selectCurator(modelId: String?) {
        _state.update { it.copy(curatorModelId = modelId, curatorError = null) }
    }

    fun runCurator(maxItems: Int = 12) {
        if (_state.value.isCurating) return
        val modelId = _state.value.curatorModelId ?: run {
            _state.update { it.copy(curatorError = "Pick a curator model first") }
            return
        }
        val model = installed.value.firstOrNull { it.id == modelId } ?: run {
            _state.update { it.copy(curatorError = "Curator model is no longer installed") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isCurating = true, curatorError = null, curatedCount = 0) }
            try {
                val agent = curatorAgentFactory(model)
                val curator: ModelEvaluator = LlmCurator(agent, curatorId = "llm-curator:${model.id}")

                // Curate up to maxItems across all feeds, dropping items we've
                // already evaluated this session.
                val flat = _state.value.feeds.flatMap { it.items }
                    .distinctBy { it.card.id }
                    .filter { it.card.id !in _state.value.curations }
                    .take(maxItems)

                val results = mutableMapOf<String, Curation>()
                for (item in flat) {
                    val cur = runCatching { curator.evaluate(item) }.getOrNull() ?: continue
                    results[item.card.id] = cur
                    _state.update {
                        it.copy(
                            curations = it.curations + (item.card.id to cur),
                            curatedCount = it.curatedCount + 1,
                        )
                    }
                }
                _state.update { it.copy(isCurating = false) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(isCurating = false, curatorError = t.message ?: "curation failed")
                }
            }
        }
    }

    fun clearCurations() {
        _state.update { it.copy(curations = emptyMap(), curatedCount = 0) }
    }
}
