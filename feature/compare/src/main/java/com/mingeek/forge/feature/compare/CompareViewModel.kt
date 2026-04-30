package com.mingeek.forge.feature.compare

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import com.mingeek.forge.domain.ChatTemplate
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.runtime.core.LoadConfig
import com.mingeek.forge.runtime.core.ModelHandle
import com.mingeek.forge.runtime.core.Prompt
import com.mingeek.forge.runtime.core.Token
import com.mingeek.forge.runtime.registry.RuntimeRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

sealed interface PaneStatus {
    data object Idle : PaneStatus
    data object Loading : PaneStatus
    data object Generating : PaneStatus
    data object Done : PaneStatus
    data class Failed(val message: String) : PaneStatus
}

data class ComparePane(
    val model: InstalledModel,
    val output: String,
    val status: PaneStatus,
    val firstTokenLatencyMs: Long? = null,
    val tokensPerSecond: Float? = null,
)

data class CompareUiState(
    val installed: List<InstalledModel> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val draft: String = "",
    val isRunning: Boolean = false,
    val panes: List<ComparePane> = emptyList(),
)

class CompareViewModel(
    private val storage: ModelStorage,
    private val registry: RuntimeRegistry,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state.asStateFlow()

    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            storage.installed.collect { installed ->
                _state.update { current ->
                    val validIds = installed.map { it.id }.toSet()
                    current.copy(
                        installed = installed,
                        selectedIds = current.selectedIds intersect validIds,
                    )
                }
            }
        }
    }

    fun toggleSelection(modelId: String) {
        if (_state.value.isRunning) return
        _state.update { current ->
            val newSet = if (modelId in current.selectedIds) {
                current.selectedIds - modelId
            } else {
                current.selectedIds + modelId
            }
            current.copy(selectedIds = newSet)
        }
    }

    fun onDraftChanged(text: String) {
        _state.update { it.copy(draft = text) }
    }

    fun cancel() {
        runJob?.cancel()
        _state.update { it.copy(isRunning = false) }
    }

    fun run() {
        val draft = _state.value.draft.trim()
        if (draft.isEmpty()) return
        val targets = _state.value.installed.filter { it.id in _state.value.selectedIds }
        if (targets.isEmpty()) return

        val initialPanes = targets.map { ComparePane(it, "", PaneStatus.Loading) }
        _state.update { it.copy(isRunning = true, panes = initialPanes) }

        runJob = viewModelScope.launch {
            val supervisor = SupervisorJob(coroutineContext[Job])
            val children = targets.map { model ->
                launch(supervisor) { runOnePane(model, draft) }
            }
            children.joinAll()
            _state.update { it.copy(isRunning = false) }
        }
    }

    private suspend fun runOnePane(model: InstalledModel, userText: String) {
        val runtimeId = runCatching { RuntimeId.valueOf(model.recommendedRuntime) }
            .getOrDefault(RuntimeId.LLAMA_CPP)
        val runtime = registry.pick(model.format, runtimeId)
        if (runtime == null) {
            updatePane(model.id) { it.copy(status = PaneStatus.Failed("No runtime supports ${model.format}")) }
            return
        }

        try {
            val loaded = runtime.load(
                ModelHandle(model.id, model.filePath, model.format),
                LoadConfig(contextLength = model.contextLength.coerceAtMost(2048)),
            )
            val template = loaded.chatTemplate
                ?.let { ChatTemplate.fromChatTemplateString(it) }
                ?: ChatTemplate.detect(model.id, model.fileName)

            val turns = listOf(ChatTemplate.Turn(ChatTemplate.Turn.Role.USER, userText))
            val promptText = template.format(turns, addAssistantPrefix = true)

            updatePane(model.id) { it.copy(status = PaneStatus.Generating) }

            val startNanos = System.nanoTime()
            var firstTokenNanos = 0L
            var emittedTokens = 0
            try {
                runtime.generate(
                    loaded.sessionId,
                    Prompt(
                        text = promptText,
                        maxTokens = 256,
                        temperature = settingsStore.defaultTemperature.value,
                        stopSequences = template.stopSequences,
                    ),
                ).collect { token ->
                    when (token) {
                        is Token.Text -> {
                            if (firstTokenNanos == 0L) firstTokenNanos = System.nanoTime()
                            emittedTokens++
                            updatePane(model.id) { it.copy(output = it.output + token.piece) }
                        }
                        is Token.Done -> {
                            val ttftMs = if (firstTokenNanos > 0L) (firstTokenNanos - startNanos) / 1_000_000 else null
                            val decodeSec = if (firstTokenNanos > 0L) (System.nanoTime() - firstTokenNanos) / 1e9 else 0.0
                            val tps = if (decodeSec > 0 && emittedTokens > 1) ((emittedTokens - 1) / decodeSec).toFloat() else null
                            updatePane(model.id) {
                                it.copy(status = PaneStatus.Done, firstTokenLatencyMs = ttftMs, tokensPerSecond = tps)
                            }
                        }
                        is Token.ToolCall -> { /* ignored */ }
                    }
                }
            } finally {
                runtime.unload(loaded.sessionId)
            }
        } catch (t: Throwable) {
            updatePane(model.id) { it.copy(status = PaneStatus.Failed(t.message ?: "failed")) }
        }
    }

    private fun updatePane(modelId: String, block: (ComparePane) -> ComparePane) {
        _state.update { state ->
            state.copy(
                panes = state.panes.map { if (it.model.id == modelId) block(it) else it },
            )
        }
    }

    fun export(uri: Uri, resolver: ContentResolver) {
        val snapshot = _state.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.bufferedWriter().use { w ->
                            w.append("# Compare\n\n")
                            w.append("**Prompt:** ").append(snapshot.draft.ifBlank { "(empty)" }).append("\n\n")
                            for (pane in snapshot.panes) {
                                w.append("## ${pane.model.displayName}\n\n")
                                val tps = pane.tokensPerSecond?.let { "%.1f tok/s".format(it) }
                                val ttft = pane.firstTokenLatencyMs?.let { "TTFT ${it}ms" }
                                val meta = listOfNotNull(ttft, tps).joinToString(" · ")
                                if (meta.isNotEmpty()) w.append("_").append(meta).append("_\n\n")
                                w.append(pane.output.ifBlank { "_(no output)_" })
                                w.append("\n\n")
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun List<Job>.joinAll() = forEach { it.join() }
