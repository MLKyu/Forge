package com.mingeek.forge.feature.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.forge.data.catalog.ModelCardDetail
import com.mingeek.forge.data.catalog.ModelCatalogSource
import com.mingeek.forge.data.catalog.RemoteFile
import com.mingeek.forge.data.catalog.SearchQuery
import com.mingeek.forge.data.download.DownloadProgress
import com.mingeek.forge.data.download.ModelDownloader
import com.mingeek.forge.core.hardware.DeviceFitScorer
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.domain.DeviceFitScore
import com.mingeek.forge.domain.ModelCard
import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.domain.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class CatalogUiState(
    val query: String = "",
    val sort: SearchQuery.Sort = SearchQuery.Sort.RELEVANCE,
    val formatFilter: ModelFormat? = null,
    val isSearching: Boolean = false,
    val results: List<ModelCard> = emptyList(),
    val error: String? = null,
    val selectedDetail: ModelCardDetail? = null,
    val variantFits: Map<String, DeviceFitScore> = emptyMap(),
    val isLoadingDetail: Boolean = false,
    val downloads: Map<String, DownloadProgress> = emptyMap(),
) {
    val displayedResults: List<ModelCard>
        get() = if (formatFilter == null) results else results.filter { it.format == formatFilter }
}

class CatalogViewModel(
    private val catalogSource: ModelCatalogSource,
    private val downloader: ModelDownloader,
    private val storage: ModelStorage,
    private val fitScorer: DeviceFitScorer,
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogUiState(sort = SearchQuery.Sort.DOWNLOADS))
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private val downloadJobs = mutableMapOf<String, Job>()

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
                _state.update { it.copy(isSearching = false, error = t.message ?: "Search failed") }
            }
        }
    }

    fun openDetails(card: ModelCard) {
        openDetailsById(card.id)
    }

    fun openDetailsById(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingDetail = true, selectedDetail = null, variantFits = emptyMap(), error = null) }
            try {
                val detail = catalogSource.details(id)
                val fits = detail.variants.zip(detail.files).associate { (variant, file) ->
                    file.url to fitScorer.score(
                        sizeBytes = file.sizeBytes,
                        contextLength = variant.contextLength,
                        format = variant.format,
                        runtime = variant.recommendedRuntimes.firstOrNull() ?: RuntimeId.LLAMA_CPP,
                    )
                }
                _state.update { it.copy(isLoadingDetail = false, selectedDetail = detail, variantFits = fits) }
            } catch (t: Throwable) {
                _state.update { it.copy(isLoadingDetail = false, error = t.message ?: "Detail load failed") }
            }
        }
    }

    fun closeDetails() {
        _state.update { it.copy(selectedDetail = null, variantFits = emptyMap()) }
    }

    fun downloadVariant(card: ModelCard, file: RemoteFile) {
        val downloadKey = file.url
        if (downloadJobs[downloadKey]?.isActive == true) return

        downloadJobs[downloadKey] = viewModelScope.launch {
            val target = storage.fileFor(card.id.substringBefore("::"), file.name)
            downloader.download(file.url, target, file.sha256).collect { progress ->
                _state.update { it.copy(downloads = it.downloads + (downloadKey to progress)) }
                if (progress is DownloadProgress.Completed) {
                    val record = InstalledModel(
                        id = card.id,
                        displayName = card.displayName,
                        sourceId = catalogSource.sourceId,
                        sourceRepoId = (card.source as? Source.HuggingFace)?.repoId,
                        fileName = file.name,
                        filePath = progress.file.absolutePath,
                        sizeBytes = progress.file.length(),
                        quantization = card.quantization,
                        format = card.format,
                        contextLength = card.contextLength,
                        recommendedRuntime = card.recommendedRuntimes.firstOrNull()?.name
                            ?: RuntimeId.LLAMA_CPP.name,
                        installedAtEpochSec = Instant.now().epochSecond,
                        licenseSpdxId = card.license.spdxId,
                        commercialUseAllowed = card.license.commercialUseAllowed,
                    )
                    storage.register(record)
                }
            }
        }
    }
}
