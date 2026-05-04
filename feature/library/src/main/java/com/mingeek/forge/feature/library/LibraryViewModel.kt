package com.mingeek.forge.feature.library

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.forge.core.hardware.DeviceFitScorer
import com.mingeek.forge.data.storage.BenchmarkRecord
import com.mingeek.forge.data.storage.BenchmarkStore
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import com.mingeek.forge.domain.DeviceFitScore
import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.Quant
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.runtime.registry.BenchmarkRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface BenchmarkState {
    data object Idle : BenchmarkState
    data object Running : BenchmarkState

    /**
     * Failure carries a localized message resource and an optional dynamic detail
     * (typically an exception message which we don't translate).
     */
    data class Failed(@StringRes val messageRes: Int, val detail: String? = null) : BenchmarkState
}

enum class LibrarySort(@StringRes val labelRes: Int) {
    NAME(R.string.library_sort_name),
    SIZE_DESC(R.string.library_sort_largest),
    INSTALLED_DESC(R.string.library_sort_newest),
    SPEED_DESC(R.string.library_sort_fastest),
}

/**
 * UI message for the import flow. The screen renders the resource so that
 * locale changes are picked up automatically.
 */
sealed interface ImportStatus {
    val isError: Boolean

    data object InProgress : ImportStatus {
        override val isError: Boolean = false
    }
    data class Unsupported(val fileName: String) : ImportStatus {
        override val isError: Boolean = true
    }
    data class Succeeded(val fileName: String) : ImportStatus {
        override val isError: Boolean = false
    }
    data class Failed(val detail: String) : ImportStatus {
        override val isError: Boolean = true
    }
}

/** UI message for the auto-cleanup background task. */
data class CleanupReport(val deletedCount: Int, val budgetGb: Int)

data class LibraryRow(
    val model: InstalledModel,
    val fit: DeviceFitScore,
    val benchmark: BenchmarkRecord?,
    val benchmarkState: BenchmarkState,
    val pinned: Boolean,
)

class LibraryViewModel(
    private val storage: ModelStorage,
    private val benchmarkStore: BenchmarkStore,
    private val benchmarkRunner: BenchmarkRunner,
    private val fitScorer: DeviceFitScorer,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _benchmarkStates = MutableStateFlow<Map<String, BenchmarkState>>(emptyMap())
    val benchmarkStates: StateFlow<Map<String, BenchmarkState>> = _benchmarkStates.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
    }

    private val _sort = MutableStateFlow(LibrarySort.INSTALLED_DESC)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    fun setSort(value: LibrarySort) {
        _sort.value = value
    }

    val rows: StateFlow<List<LibraryRow>> = combine(
        storage.installed,
        benchmarkStore.records,
        _benchmarkStates,
        settingsStore.pinnedModelIds,
    ) { models, benchmarks, runStates, pinned ->
        models.map { model ->
            val bench = benchmarks[model.id]
            val runtime = runCatching { RuntimeId.valueOf(model.recommendedRuntime) }
                .getOrDefault(RuntimeId.LLAMA_CPP)
            LibraryRow(
                model = model,
                fit = fitScorer.score(
                    sizeBytes = model.sizeBytes,
                    contextLength = model.contextLength,
                    format = model.format,
                    runtime = runtime,
                    measuredTokensPerSecond = bench?.tokensPerSecond,
                ),
                benchmark = bench,
                benchmarkState = runStates[model.id] ?: BenchmarkState.Idle,
                pinned = model.id in pinned,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun togglePin(model: InstalledModel) {
        viewModelScope.launch { settingsStore.togglePinnedModel(model.id) }
    }

    private val _lastCleanupReport = MutableStateFlow<CleanupReport?>(null)
    val lastCleanupReport: StateFlow<CleanupReport?> = _lastCleanupReport.asStateFlow()

    fun clearCleanupReport() {
        _lastCleanupReport.value = null
    }

    init {
        // Auto-cleanup: when enabled, observe installed/pinned/budget and evict
        // oldest non-pinned models until total disk usage fits the budget.
        viewModelScope.launch {
            combine(
                settingsStore.autoCleanupEnabled,
                settingsStore.autoCleanupBudgetGb,
                storage.installed,
                settingsStore.pinnedModelIds,
            ) { enabled, gb, models, pinned ->
                CleanupTrigger(enabled, gb, models.sumOf { it.sizeBytes }, pinned)
            }.distinctUntilChanged().collect { trigger ->
                if (!trigger.enabled) return@collect
                val budgetBytes = trigger.budgetGb.toLong() * 1024L * 1024L * 1024L
                if (trigger.currentBytes <= budgetBytes) return@collect
                val deleted = storage.cleanupIfOverBudget(budgetBytes, trigger.pinned)
                deleted.forEach { benchmarkStore.remove(it) }
                if (deleted.isNotEmpty()) {
                    _lastCleanupReport.value = CleanupReport(
                        deletedCount = deleted.size,
                        budgetGb = trigger.budgetGb,
                    )
                }
            }
        }
    }

    private data class CleanupTrigger(
        val enabled: Boolean,
        val budgetGb: Int,
        val currentBytes: Long,
        val pinned: Set<String>,
    )

    fun delete(model: InstalledModel) {
        viewModelScope.launch {
            storage.delete(model.id)
            benchmarkStore.remove(model.id)
            settingsStore.unpinModel(model.id)
        }
    }

    private val _importStatus = MutableStateFlow<ImportStatus?>(null)
    val importStatus: StateFlow<ImportStatus?> = _importStatus.asStateFlow()

    fun clearImportStatus() {
        _importStatus.value = null
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _importStatus.value = ImportStatus.InProgress
            try {
                val tempId = "imported://${System.currentTimeMillis()}"
                val (file, displayName) = storage.importFromUri(uri, tempId)
                val format = when {
                    displayName.endsWith(".gguf", ignoreCase = true) -> ModelFormat.GGUF
                    displayName.endsWith(".task", ignoreCase = true) -> ModelFormat.MEDIAPIPE_TASK
                    else -> {
                        _importStatus.value = ImportStatus.Unsupported(displayName)
                        file.delete()
                        return@launch
                    }
                }
                val runtime = if (format == ModelFormat.MEDIAPIPE_TASK) RuntimeId.MEDIAPIPE else RuntimeId.LLAMA_CPP
                val record = InstalledModel(
                    id = "imported://$displayName",
                    displayName = displayName.substringBeforeLast('.'),
                    sourceId = "local",
                    sourceRepoId = null,
                    fileName = displayName,
                    filePath = file.absolutePath,
                    sizeBytes = file.length(),
                    quantization = parseQuant(displayName),
                    format = format,
                    contextLength = 4096,
                    recommendedRuntime = runtime.name,
                    installedAtEpochSec = System.currentTimeMillis() / 1000,
                    licenseSpdxId = "unknown",
                    commercialUseAllowed = false,
                )
                storage.register(record)
                _importStatus.value = ImportStatus.Succeeded(displayName)
            } catch (t: Throwable) {
                _importStatus.value = ImportStatus.Failed(t.message ?: "")
            }
        }
    }

    private fun parseQuant(filename: String): Quant {
        val u = filename.uppercase()
        return when {
            "Q4_K_M" in u -> Quant.Q4_K_M
            "Q4_0" in u -> Quant.Q4_0
            "Q5_K_M" in u -> Quant.Q5_K_M
            "Q6_K" in u -> Quant.Q6_K
            "Q8_0" in u -> Quant.Q8_0
            "F16" in u || "FP16" in u -> Quant.F16
            "BF16" in u -> Quant.BF16
            "F32" in u -> Quant.F32
            "INT8" in u -> Quant.INT8
            "INT4" in u -> Quant.INT4
            else -> Quant.UNKNOWN
        }
    }

    fun runBenchmark(model: InstalledModel) {
        if (_benchmarkStates.value[model.id] == BenchmarkState.Running) return
        _benchmarkStates.update { it + (model.id to BenchmarkState.Running) }
        viewModelScope.launch {
            try {
                val record = benchmarkRunner.run(model)
                if (record != null) {
                    benchmarkStore.put(record)
                    _benchmarkStates.update { it - model.id }
                } else {
                    _benchmarkStates.update {
                        it + (model.id to BenchmarkState.Failed(R.string.library_benchmark_no_tokens))
                    }
                }
            } catch (t: Throwable) {
                _benchmarkStates.update {
                    it + (model.id to BenchmarkState.Failed(
                        messageRes = R.string.library_benchmark_generic_failure,
                        detail = t.message,
                    ))
                }
            }
        }
    }
}
