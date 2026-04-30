package com.mingeek.forge.feature.chat

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
import com.mingeek.forge.runtime.core.SessionId
import com.mingeek.forge.runtime.core.Token
import com.mingeek.forge.runtime.registry.RuntimeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

sealed interface SessionState {
    data object Idle : SessionState
    data object Loading : SessionState
    data class Ready(
        val sessionId: SessionId,
        val model: InstalledModel,
        val template: ChatTemplate,
    ) : SessionState
    data class Failed(val message: String) : SessionState
}

private val EXPORT_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String,
    val isStreaming: Boolean = false,
    val usage: Token.TokenUsage? = null,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    enum class Role { USER, ASSISTANT }
}

data class ChatUiState(
    val sessionState: SessionState = SessionState.Idle,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val draft: String = "",
)

class ChatViewModel(
    private val storage: ModelStorage,
    private val registry: RuntimeRegistry,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val installed: StateFlow<List<InstalledModel>> = storage.installed

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generationJob: Job? = null

    fun onDraftChanged(text: String) {
        _state.update { it.copy(draft = text) }
    }

    fun loadModel(model: InstalledModel) {
        viewModelScope.launch {
            unloadInternal()
            _state.update { it.copy(sessionState = SessionState.Loading, messages = emptyList()) }

            val runtimeId = runCatching { RuntimeId.valueOf(model.recommendedRuntime) }
                .getOrDefault(RuntimeId.LLAMA_CPP)
            val runtime = registry.pick(model.format, runtimeId)
            if (runtime == null) {
                _state.update { it.copy(sessionState = SessionState.Failed("No runtime supports ${model.format}")) }
                return@launch
            }

            try {
                val loaded = runtime.load(
                    ModelHandle(
                        modelId = model.id,
                        modelPath = model.filePath,
                        format = model.format,
                    ),
                    LoadConfig(
                        contextLength = model.contextLength.coerceAtMost(4096),
                        threads = 0,
                    ),
                )
                val template = loaded.chatTemplate
                    ?.let { ChatTemplate.fromChatTemplateString(it) }
                    ?: ChatTemplate.detect(model.id, model.fileName)
                _state.update {
                    it.copy(sessionState = SessionState.Ready(loaded.sessionId, model, template))
                }
            } catch (t: Throwable) {
                _state.update { it.copy(sessionState = SessionState.Failed(t.message ?: "load failed")) }
            }
        }
    }

    fun unload() {
        viewModelScope.launch { unloadInternal() }
    }

    private suspend fun unloadInternal() {
        generationJob?.cancel()
        val ready = _state.value.sessionState as? SessionState.Ready ?: return
        val runtimeId = runCatching { RuntimeId.valueOf(ready.model.recommendedRuntime) }
            .getOrDefault(RuntimeId.LLAMA_CPP)
        val runtime = registry.pick(ready.model.format, runtimeId) ?: return
        runtime.unload(ready.sessionId)
        _state.update { it.copy(sessionState = SessionState.Idle, messages = emptyList(), isGenerating = false) }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        _state.update { it.copy(isGenerating = false) }
    }

    fun clearMessages() {
        generationJob?.cancel()
        _state.update { it.copy(messages = emptyList(), isGenerating = false) }
    }

    fun send() {
        val draft = _state.value.draft.trim()
        if (draft.isEmpty()) return
        val ready = _state.value.sessionState as? SessionState.Ready ?: return
        val runtimeId = runCatching { RuntimeId.valueOf(ready.model.recommendedRuntime) }
            .getOrDefault(RuntimeId.LLAMA_CPP)
        val runtime = registry.pick(ready.model.format, runtimeId) ?: return

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatMessage.Role.USER,
            content = draft,
        )
        val assistantMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatMessage.Role.ASSISTANT,
            content = "",
            isStreaming = true,
        )
        _state.update {
            it.copy(
                draft = "",
                messages = it.messages + userMsg + assistantMsg,
                isGenerating = true,
            )
        }

        val turns = (_state.value.messages)
            .filter { it.id != assistantMsg.id }
            .map {
                ChatTemplate.Turn(
                    role = if (it.role == ChatMessage.Role.USER) ChatTemplate.Turn.Role.USER
                    else ChatTemplate.Turn.Role.ASSISTANT,
                    content = it.content,
                )
            }
        val promptText = ready.template.format(turns, addAssistantPrefix = true)
        val stops = ready.template.stopSequences

        generationJob = viewModelScope.launch {
            try {
                runtime.generate(
                    ready.sessionId,
                    Prompt(
                        text = promptText,
                        maxTokens = 512,
                        temperature = settingsStore.defaultTemperature.value,
                        stopSequences = stops,
                    ),
                ).collect { token ->
                    when (token) {
                        is Token.Text -> appendToAssistant(assistantMsg.id, token.piece)
                        is Token.Done -> finalizeAssistant(assistantMsg.id, token.usage)
                        is Token.ToolCall -> { /* phase 4+ */ }
                    }
                }
            } catch (t: Throwable) {
                appendToAssistant(assistantMsg.id, "\n\n[error: ${t.message}]")
                finalizeAssistant(assistantMsg.id, null)
            }
        }
    }

    private fun appendToAssistant(messageId: String, piece: String) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { m ->
                    if (m.id == messageId) m.copy(content = m.content + piece) else m
                }
            )
        }
    }

    private fun finalizeAssistant(messageId: String, usage: Token.TokenUsage?) {
        _state.update { state ->
            state.copy(
                isGenerating = false,
                messages = state.messages.map { m ->
                    if (m.id == messageId) m.copy(isStreaming = false, usage = usage) else m
                }
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
                            val ready = snapshot.sessionState as? SessionState.Ready
                            w.append("# Chat\n\n")
                            ready?.let {
                                w.append("**Model:** ").append(it.model.displayName).append("\n\n")
                                w.append("**Runtime:** ").append(it.model.recommendedRuntime).append("\n\n")
                                w.append("**Template:** ").append(it.template.id).append("\n\n")
                                w.append("---\n\n")
                            }
                            for (m in snapshot.messages) {
                                val role = if (m.role == ChatMessage.Role.USER) "User" else "Assistant"
                                w.append("## ").append(role).append(" — ").append(EXPORT_TIME.format(Instant.ofEpochMilli(m.timestampMs))).append("\n\n")
                                m.usage?.let { u ->
                                    w.append("_P:").append(u.promptTokens.toString()).append(" C:").append(u.completionTokens.toString()).append("_\n\n")
                                }
                                w.append(m.content.ifBlank { "_(empty)_" })
                                w.append("\n\n")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { unloadInternal() }
    }
}
