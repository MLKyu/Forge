package com.mingeek.forge.feature.catalog

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.forge.data.catalog.CatalogException
import com.mingeek.forge.data.catalog.ModelCardDetail
import com.mingeek.forge.data.catalog.ModelCatalogSource
import com.mingeek.forge.data.catalog.RemoteFile
import com.mingeek.forge.data.catalog.SearchQuery
import com.mingeek.forge.data.download.DownloadQueue
import com.mingeek.forge.data.download.DownloadRequest
import com.mingeek.forge.data.download.DownloadState
import com.mingeek.forge.core.hardware.DeviceFitScorer
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.domain.DeviceFitScore
import com.mingeek.forge.domain.ModelCard
import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.domain.Source
import com.mingeek.forge.runtime.registry.RuntimeRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class CatalogUiState(
    val query: String = "",
    val sort: SearchQuery.Sort = SearchQuery.Sort.RELEVANCE,
    val formatFilter: ModelFormat? = null,
    val isSearching: Boolean = false,
    val results: List<ModelCard> = emptyList(),
    /**
     * Error to display. Either a raw exception message (preferred when
     * available) or a fallback @StringRes for localized generic copy.
     */
    val error: CatalogError? = null,
    val selectedDetail: ModelCardDetail? = null,
    /**
     * Per-variant fit per runtime. Outer key is the file URL (matches the
     * existing `variants/files` zip), inner map keys are RuntimeIds compatible
     * with that variant's format. PLANNING §10's "same model, different
     * runtime, different fit" UI reads from this.
     */
    val variantRuntimeFits: Map<String, Map<RuntimeId, DeviceFitScore>> = emptyMap(),
    val isLoadingDetail: Boolean = false,
) {
    val displayedResults: List<ModelCard>
        get() = if (formatFilter == null) results else results.filter { it.format == formatFilter }
}

sealed interface CatalogError {
    data class Message(val text: String) : CatalogError
    data class Res(@StringRes val resId: Int) : CatalogError
}

class CatalogViewModel(
    private val catalogSource: ModelCatalogSource,
    private val downloadQueue: DownloadQueue,
    private val storage: ModelStorage,
    private val fitScorer: DeviceFitScorer,
    private val runtimeRegistry: RuntimeRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogUiState(sort = SearchQuery.Sort.DOWNLOADS))
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    /**
     * Mirror of the process-wide [DownloadQueue] state, restricted to
     * the file URL keys this catalog cares about. The screen subscribes
     * to this directly — we don't fold it into [state] because the
     * queue is shared across screens and we want any change there to
     * propagate without copying.
     */
    val downloads: StateFlow<Map<String, DownloadState>> = downloadQueue.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var searchJob: Job? = null

    init {
        // Surface popular models on first open so the screen isn't empty.
        search()
    }

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setSort(sort: SearchQuery.Sort) {
        if (_state.value.sort == sort) return
        _state.update { it.copy(sort = sort) }
        if (_state.value.results.isNotEmpty() || _state.value.query.isNotBlank()) {
            search()
        }
    }

    fun setFormatFilter(format: ModelFormat?) {
        _state.update { it.copy(formatFilter = format) }
    }

    fun search() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isSearching = true, error = null) }
            try {
                catalogSource.search(
                    SearchQuery(
                        text = _state.value.query.takeIf { it.isNotBlank() },
                        sort = _state.value.sort,
                    )
                ).collectLatest { results ->
                    _state.update { it.copy(isSearching = false, results = results) }
                }
            } catch (t: Throwable) {
                val err = t.message?.let(CatalogError::Message)
                    ?: CatalogError.Res(R.string.catalog_error_search_failed)
                _state.update { it.copy(isSearching = false, error = err) }
            }
        }
    }

    fun openDetails(card: ModelCard) {
        openDetailsById(card.id)
    }

    fun openDetailsById(id: String) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoadingDetail = true,
                    selectedDetail = null,
                    variantRuntimeFits = emptyMap(),
                    error = null,
                )
            }
            try {
                val detail = catalogSource.details(id)
                // For each (variant, file) pair, score every registered runtime
                // that supports the variant's format. Empty list means no
                // runtime is available — UI will show that explicitly.
                val fits = detail.variants.zip(detail.files).associate { (variant, file) ->
                    val runtimes = runtimeRegistry.runtimesFor(variant.format)
                    file.url to runtimes.associate { runtime ->
                        runtime.id to fitScorer.score(
                            sizeBytes = file.sizeBytes,
                            contextLength = variant.contextLength,
                            format = variant.format,
                            runtime = runtime.id,
                        )
                    }
                }
                _state.update {
                    it.copy(
                        isLoadingDetail = false,
                        selectedDetail = detail,
                        variantRuntimeFits = fits,
                    )
                }
            } catch (t: Throwable) {
                val err = when (t) {
                    is CatalogException.Gated -> CatalogError.Res(R.string.catalog_error_gated)
                    is CatalogException.NotFound -> CatalogError.Res(R.string.catalog_error_not_found)
                    is CatalogException.Network -> CatalogError.Res(R.string.catalog_error_network)
                    else -> t.message?.let(CatalogError::Message)
                        ?: CatalogError.Res(R.string.catalog_error_detail_failed)
                }
                _state.update { it.copy(isLoadingDetail = false, error = err) }
            }
        }
    }

    fun closeDetails() {
        _state.update { it.copy(selectedDetail = null, variantRuntimeFits = emptyMap()) }
    }

    /**
     * Hand the download to the process-singleton queue. Safe to call
     * from any screen — if the user navigates away, the queue keeps
     * pumping bytes via the foreground service.
     */
    fun downloadVariant(card: ModelCard, file: RemoteFile) {
        val target = storage.fileFor(card.id.substringBefore("::"), file.name)
        // Capture the registration template at enqueue time. The
        // closure runs on the queue's IO scope after byte verification,
        // so it survives this ViewModel's death.
        val storageRef = storage
        val sourceId = catalogSource.sourceId
        val onCompleted: suspend (java.io.File) -> Unit = { savedFile ->
            val record = InstalledModel(
                id = card.id,
                displayName = card.displayName,
                sourceId = sourceId,
                sourceRepoId = (card.source as? Source.HuggingFace)?.repoId,
                fileName = file.name,
                filePath = savedFile.absolutePath,
                sizeBytes = savedFile.length(),
                quantization = card.quantization,
                format = card.format,
                contextLength = card.contextLength,
                recommendedRuntime = card.recommendedRuntimes.firstOrNull()?.name
                    ?: RuntimeId.LLAMA_CPP.name,
                installedAtEpochSec = Instant.now().epochSecond,
                licenseSpdxId = card.license.spdxId,
                commercialUseAllowed = card.license.commercialUseAllowed,
            )
            storageRef.register(record)
        }
        downloadQueue.enqueue(
            DownloadRequest(
                key = file.url,
                url = file.url,
                target = target,
                expectedSha256 = file.sha256,
                displayName = card.displayName,
                sizeBytesHint = file.sizeBytes.takeIf { it > 0 },
                onCompleted = onCompleted,
            )
        )
    }

    fun pauseDownload(key: String) = downloadQueue.pause(key)
    fun resumeDownload(key: String) = downloadQueue.resume(key)
    fun cancelDownload(key: String) = downloadQueue.cancel(key)
    fun dismissDownload(key: String) = downloadQueue.dismiss(key)
}
