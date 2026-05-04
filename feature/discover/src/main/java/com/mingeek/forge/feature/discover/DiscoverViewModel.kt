package com.mingeek.forge.feature.discover

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.forge.agent.core.Agent
import com.mingeek.forge.agent.curator.LlmCurator
import com.mingeek.forge.agent.curator.ModelEvaluator
import com.mingeek.forge.core.hardware.DeviceFitScorer
import com.mingeek.forge.data.discovery.Collection
import com.mingeek.forge.data.discovery.CollectionEntry
import com.mingeek.forge.data.discovery.CollectionRepository
import com.mingeek.forge.data.discovery.DiscoveryRepository
import com.mingeek.forge.data.discovery.RecommendationEngine
import com.mingeek.forge.data.discovery.RecommendedModel
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import com.mingeek.forge.domain.Curation
import com.mingeek.forge.domain.DeviceFitScore
import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.RuntimeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A user-facing message produced by the ViewModel. The ViewModel cannot
 * resolve resources itself (no Context), so it surfaces a [messageRes] plus
 * an optional [detail] (e.g. an exception message) — the UI assembles the
 * final localized string via `stringResource`.
 */
data class DiscoverUiMessage(
    @StringRes val messageRes: Int,
    val detail: String? = null,
)

/**
 * One curated entry decorated with the precomputed device-fit score so the
 * UI can show a traffic-light badge without first fetching HF detail.
 *
 * `fit == null` means the entry has no [CollectionEntry.approxSizeBytes]
 * and we therefore can't pre-score it — the UI degrades to "unknown".
 */
data class CollectionEntryView(
    val entry: CollectionEntry,
    val fit: DeviceFitScore?,
)

/**
 * Collection paired with its scored entries, ordered best-fit first.
 * Keeps [Collection.entries] untouched so anything else that wants the
 * raw curated order can still use it.
 */
data class CollectionView(
    val collection: Collection,
    val entries: List<CollectionEntryView>,
)

data class DiscoverUiState(
    val isRefreshing: Boolean = false,
    val feeds: List<DiscoveryRepository.Feed> = emptyList(),
    val collections: List<CollectionView> = emptyList(),
    val recommendations: List<RecommendedModel> = emptyList(),
    val error: DiscoverUiMessage? = null,
    val isCurating: Boolean = false,
    val curatorModelId: String? = null,
    val curatorError: DiscoverUiMessage? = null,
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
    private val fitScorer: DeviceFitScorer,
    storage: ModelStorage,
    settingsStore: SettingsStore,
    private val curatorAgentFactory: CuratorAgentFactory,
) : ViewModel() {

    val installed: StateFlow<List<InstalledModel>> = storage.installed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether the user has an HF access token configured. Surfaced to the
     * UI so gated-repo cards can switch between "needs auth" and "✓
     * authenticated" without each card duplicating the SettingsStore read.
     */
    val hasHfAuth: StateFlow<Boolean> = settingsStore.hfToken
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, error = null) }
            try {
                val feeds = repository.fetchAll()
                val rawCollections = runCatching { collectionRepository.fetchAll() }
                    .getOrDefault(emptyList())
                val collections = rawCollections.map(::scoreCollection)
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
                _state.update {
                    it.copy(
                        isRefreshing = false,
                        error = DiscoverUiMessage(R.string.discover_error_fetch_failed, t.message),
                    )
                }
            }
        }
    }

    /**
     * Scores each curated entry against the device profile and reorders so
     * the best fit floats to the front. Entries without a known size keep
     * `fit = null` and are placed last (we genuinely can't say).
     */
    private fun scoreCollection(collection: Collection): CollectionView {
        val scored = collection.entries.map { entry ->
            val fit = entry.approxSizeBytes?.let { size ->
                fitScorer.score(
                    sizeBytes = size,
                    contextLength = 4096,
                    format = ModelFormat.GGUF,
                    runtime = RuntimeId.LLAMA_CPP,
                )
            }
            CollectionEntryView(entry = entry, fit = fit)
        }.sortedWith(BEST_FIT_FIRST)
        return CollectionView(collection = collection, entries = scored)
    }

    fun selectCurator(modelId: String?) {
        _state.update { it.copy(curatorModelId = modelId, curatorError = null) }
    }

    fun runCurator(maxItems: Int = 12) {
        if (_state.value.isCurating) return
        val modelId = _state.value.curatorModelId ?: run {
            _state.update {
                it.copy(curatorError = DiscoverUiMessage(R.string.discover_error_pick_curator_first))
            }
            return
        }
        val model = installed.value.firstOrNull { it.id == modelId } ?: run {
            _state.update {
                it.copy(curatorError = DiscoverUiMessage(R.string.discover_error_curator_uninstalled))
            }
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
                    it.copy(
                        isCurating = false,
                        curatorError = DiscoverUiMessage(R.string.discover_error_curation_failed, t.message),
                    )
                }
            }
        }
    }

    fun clearCurations() {
        _state.update { it.copy(curations = emptyMap(), curatedCount = 0) }
    }

    private companion object {
        // Sort priority for fit tiers — GREEN first, "unknown" last so the
        // user sees actionable items above ambiguous ones.
        private val TIER_RANK = mapOf(
            DeviceFitScore.Tier.GREEN to 0,
            DeviceFitScore.Tier.YELLOW to 1,
            DeviceFitScore.Tier.RED to 2,
            DeviceFitScore.Tier.UNSUPPORTED to 3,
        )
        val BEST_FIT_FIRST = compareBy<CollectionEntryView> { view ->
            view.fit?.tier?.let(TIER_RANK::get) ?: Int.MAX_VALUE
        }
    }
}
