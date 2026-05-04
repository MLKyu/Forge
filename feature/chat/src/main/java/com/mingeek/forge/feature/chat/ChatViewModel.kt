package com.mingeek.forge.feature.chat

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.StringRes
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
import com.mingeek.forge.agent.core.Tool
import com.mingeek.forge.agent.core.ToolCallProtocol
import com.mingeek.forge.agent.memory.MemoryEntry
import com.mingeek.forge.agent.memory.MemoryStore
import com.mingeek.forge.agent.tools.CalculatorTool
import com.mingeek.forge.agent.tools.CurrentTimeTool
import com.mingeek.forge.agent.tools.WordCountTool
import com.mingeek.forge.runtime.registry.RuntimeRegistry
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
    data class Failed(@StringRes val messageRes: Int, val arg: String? = null) : SessionState
}

private val EXPORT_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@JsonClass(generateAdapter = true)
data class ToolCallEntry(
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String,
    val isError: Boolean,
)

@JsonClass(generateAdapter = true)
data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String,
    val isStreaming: Boolean = false,
    val usage: Token.TokenUsage? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val toolCalls: List<ToolCallEntry> = emptyList(),
) {
    /**
     * SYSTEM is used for in-line notes the user didn't type and the model
     * didn't generate — currently only "switched model" markers. Keep them
     * out of any prompt construction (only USER + ASSISTANT turns get
     * formatted into the chat template).
     */
    enum class Role { USER, ASSISTANT, SYSTEM }
}

data class PastConversation(
    val id: String,
    val title: String,
    val messageCount: Int,
    val updatedAtEpochSec: Long,
    val lastModelId: String?,
)

data class ChatUiState(
    val sessionState: SessionState = SessionState.Idle,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val draft: String = "",
    /** Stable id for auto-save. Rotates on newConversation(). */
    val conversationId: String = UUID.randomUUID().toString(),
    val pastConversations: List<PastConversation> = emptyList(),
)

class ChatViewModel(
    private val app: Application,
    private val storage: ModelStorage,
    private val registry: RuntimeRegistry,
    private val settingsStore: SettingsStore,
    private val chatHistory: MemoryStore,
) : ViewModel() {

    val installed: StateFlow<List<InstalledModel>> = storage.installed

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generationJob: Job? = null

    private val moshi: Moshi = Moshi.Builder().build()
    private val messagesAdapter: JsonAdapter<List<ChatMessage>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, ChatMessage::class.java),
    )

    @OptIn(FlowPreview::class)
    private fun startAutoSave() {
        viewModelScope.launch {
            _state
                .map { Triple(it.conversationId, it.messages, (it.sessionState as? SessionState.Ready)?.model?.id) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(500)
                .collect { (id, msgs, modelId) ->
                    if (msgs.isEmpty()) return@collect
                    persistConversation(id, msgs, modelId)
                }
        }
    }

    init {
        startAutoSave()
        viewModelScope.launch { refreshPastConversations() }
    }

    private suspend fun persistConversation(id: String, msgs: List<ChatMessage>, modelId: String?) {
        val firstUserMsg = msgs.firstOrNull { it.role == ChatMessage.Role.USER }?.content
        val title = firstUserMsg?.take(60)?.ifBlank { null }
            ?: app.getString(R.string.chat_untitled_chat)
        chatHistory.put(
            MemoryEntry(
                id = "chat:$id",
                content = messagesAdapter.toJson(msgs),
                createdAtEpochSec = System.currentTimeMillis() / 1000,
                tags = setOf("chat-conversation"),
                metadata = buildMap {
                    put("title", title)
                    put("count", msgs.size.toString())
                    if (modelId != null) put("modelId", modelId)
                },
            ),
        )
        refreshPastConversations()
    }

    private suspend fun refreshPastConversations() {
        val entries = chatHistory.query(text = "", limit = 50, tags = setOf("chat-conversation"))
        val mapped = entries.map { entry ->
            PastConversation(
                id = entry.id.removePrefix("chat:"),
                title = entry.metadata["title"] ?: app.getString(R.string.chat_untitled_chat),
                messageCount = entry.metadata["count"]?.toIntOrNull() ?: 0,
                updatedAtEpochSec = entry.createdAtEpochSec,
                lastModelId = entry.metadata["modelId"],
            )
        }
        _state.update { it.copy(pastConversations = mapped) }
    }

    /** Save current conversation, then start a fresh one keeping the loaded model. */
    fun newConversation() {
        viewModelScope.launch {
            val s = _state.value
            if (s.messages.isNotEmpty()) {
                persistConversation(
                    s.conversationId,
                    s.messages,
                    (s.sessionState as? SessionState.Ready)?.model?.id,
                )
            }
            generationJob?.cancel()
            _state.update {
                it.copy(
                    messages = emptyList(),
                    conversationId = UUID.randomUUID().toString(),
                    isGenerating = false,
                )
            }
        }
    }

    fun resumeConversation(id: String) {
        viewModelScope.launch {
            // Persist whatever's open before switching away.
            val current = _state.value
            if (current.messages.isNotEmpty()) {
                persistConversation(
                    current.conversationId,
                    current.messages,
                    (current.sessionState as? SessionState.Ready)?.model?.id,
                )
            }
            val entry = chatHistory.get("chat:$id") ?: return@launch
            val msgs = runCatching { messagesAdapter.fromJson(entry.content) }.getOrNull().orEmpty()
            generationJob?.cancel()
            _state.update {
                it.copy(
                    messages = msgs,
                    conversationId = id,
                    isGenerating = false,
                )
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            chatHistory.remove("chat:$id")
            refreshPastConversations()
            // If we deleted the active conversation, rotate to a fresh id.
            if (_state.value.conversationId == id) {
                _state.update {
                    it.copy(messages = emptyList(), conversationId = UUID.randomUUID().toString())
                }
            }
        }
    }

    fun onDraftChanged(text: String) {
        _state.update { it.copy(draft = text) }
    }

    /**
     * Swap to [model] without dropping the conversation. Cancels generation,
     * unloads the old session, loads the new one, and inserts a system note
     * so the user can see where the swap happened. The messages list is
     * preserved — both halves share the same prompt history.
     */
    fun swapModel(model: InstalledModel) {
        val current = _state.value.sessionState as? SessionState.Ready ?: run {
            // No active session yet — fall back to the regular load that
            // also clears messages.
            loadModel(model)
            return
        }
        if (current.model.id == model.id) return
        viewModelScope.launch {
            generationJob?.cancel()
            unloadInternal()
            val note = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatMessage.Role.SYSTEM,
                content = app.getString(R.string.chat_switched_to, model.displayName),
                timestampMs = System.currentTimeMillis(),
            )
            _state.update {
                it.copy(
                    sessionState = SessionState.Loading,
                    messages = it.messages + note,
                )
            }
            doLoad(model, preserveMessages = true)
        }
    }

    fun loadModel(model: InstalledModel) {
        viewModelScope.launch {
            unloadInternal()
            _state.update { it.copy(sessionState = SessionState.Loading, messages = emptyList()) }
            doLoad(model)
        }
    }

    private suspend fun doLoad(model: InstalledModel, preserveMessages: Boolean = false) {
        val runtimeId = runCatching { RuntimeId.valueOf(model.recommendedRuntime) }
            .getOrDefault(RuntimeId.LLAMA_CPP)
        val runtime = registry.pick(model.format, runtimeId)
        if (runtime == null) {
            _state.update {
                it.copy(
                    sessionState = SessionState.Failed(
                        R.string.chat_load_no_runtime,
                        model.format.toString(),
                    ),
                )
            }
            return
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
            storage.markUsed(model.id)
            _state.update {
                it.copy(sessionState = SessionState.Ready(loaded.sessionId, model, template))
            }
        } catch (t: Throwable) {
            // On failure during a swap we still keep the preserved messages;
            // user can retry with a different model.
            val reason = t.message ?: app.getString(R.string.chat_load_failed_generic)
            _state.update {
                it.copy(
                    sessionState = SessionState.Failed(
                        R.string.chat_load_failed_prefix,
                        reason,
                    ),
                )
            }
            if (preserveMessages) {
                // Fold an error note in so the system marker isn't dangling.
                val swapReason = t.message ?: app.getString(R.string.chat_load_error_fallback)
                _state.update {
                    it.copy(messages = it.messages + ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.SYSTEM,
                        content = app.getString(R.string.chat_swap_failed, swapReason),
                    ))
                }
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

        val toolsEnabled = settingsStore.toolsEnabled.value
        val tools: List<Tool> = if (toolsEnabled) DEFAULT_TOOLS else emptyList()

        // Build the canonical conversation as ChatTemplate turns. We mutate this
        // across the tool loop instead of re-deriving from messages.
        val baseTurns = mutableListOf<ChatTemplate.Turn>()
        if (tools.isNotEmpty()) {
            baseTurns += ChatTemplate.Turn(
                role = ChatTemplate.Turn.Role.SYSTEM,
                content = ToolCallProtocol.buildPrelude(tools),
            )
        }
        for (m in _state.value.messages) {
            if (m.id == assistantMsg.id) continue
            // SYSTEM notes (e.g. "Switched to X") never get sent to the model.
            if (m.role == ChatMessage.Role.SYSTEM) continue
            baseTurns += ChatTemplate.Turn(
                role = if (m.role == ChatMessage.Role.USER) ChatTemplate.Turn.Role.USER
                else ChatTemplate.Turn.Role.ASSISTANT,
                content = m.content,
            )
        }

        generationJob = viewModelScope.launch {
            try {
                runToolLoop(
                    runtime = runtime,
                    sessionId = ready.sessionId,
                    template = ready.template,
                    baseTurns = baseTurns,
                    tools = tools,
                    assistantMsgId = assistantMsg.id,
                )
            } catch (t: Throwable) {
                appendToAssistant(
                    assistantMsg.id,
                    app.getString(R.string.chat_error_inline, t.message ?: ""),
                )
                finalizeAssistant(assistantMsg.id, null)
            }
        }
    }

    private suspend fun runToolLoop(
        runtime: com.mingeek.forge.runtime.core.InferenceRuntime,
        sessionId: SessionId,
        template: ChatTemplate,
        baseTurns: MutableList<ChatTemplate.Turn>,
        tools: List<Tool>,
        assistantMsgId: String,
    ) {
        val temperature = settingsStore.defaultTemperature.value
        val maxIterations = settingsStore.toolMaxIterations.value
        val stops = template.stopSequences
        var iteration = 0

        while (true) {
            val promptText = template.format(baseTurns, addAssistantPrefix = true)
            val turnOutput = StringBuilder()
            var lastUsage: Token.TokenUsage? = null

            runtime.generate(
                sessionId,
                Prompt(
                    text = promptText,
                    maxTokens = 512,
                    temperature = temperature,
                    stopSequences = stops,
                ),
            ).collect { token ->
                when (token) {
                    is Token.Text -> {
                        turnOutput.append(token.piece)
                        appendToAssistant(assistantMsgId, token.piece)
                    }
                    is Token.Done -> { lastUsage = token.usage }
                    is Token.ToolCall -> { /* runtime-level tool calls unused */ }
                }
            }

            val rendered = turnOutput.toString().trim()
            val parsedCall = if (tools.isNotEmpty()) ToolCallProtocol.parseCall(rendered) else null

            if (parsedCall == null) {
                finalizeAssistant(assistantMsgId, lastUsage)
                return
            }

            iteration++

            // Invoke the tool.
            val tool = tools.firstOrNull { it.name == parsedCall.name }
            val result = if (tool == null) {
                com.mingeek.forge.agent.core.ToolResult(
                    outputJson = """{"error": "unknown tool: ${parsedCall.name}"}""",
                    isError = true,
                )
            } else {
                runCatching { tool.invoke(parsedCall.argumentsJson) }
                    .getOrElse {
                        com.mingeek.forge.agent.core.ToolResult(
                            outputJson = """{"error": "${it.message?.replace("\"", "'") ?: "tool failed"}"}""",
                            isError = true,
                        )
                    }
            }

            attachToolCall(
                assistantMsgId,
                ToolCallEntry(
                    toolName = parsedCall.name,
                    argumentsJson = parsedCall.argumentsJson,
                    resultJson = result.outputJson,
                    isError = result.isError,
                ),
            )

            // Wipe the streamed call text — it isn't the final answer; the next
            // generate will fill in the assistant body.
            replaceAssistantContent(assistantMsgId, "")

            // Add the assistant's call turn + the synthetic tool result so the
            // next generate can produce a final answer.
            baseTurns += ChatTemplate.Turn(ChatTemplate.Turn.Role.ASSISTANT, rendered)
            baseTurns += ChatTemplate.Turn(
                role = ChatTemplate.Turn.Role.USER,
                content = ToolCallProtocol.renderResult(result),
            )

            if (iteration >= maxIterations) {
                appendToAssistant(
                    assistantMsgId,
                    app.getString(R.string.chat_tool_loop_exceeded, maxIterations),
                )
                finalizeAssistant(assistantMsgId, lastUsage)
                return
            }
        }
    }

    private fun attachToolCall(messageId: String, entry: ToolCallEntry) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { m ->
                    if (m.id == messageId) m.copy(toolCalls = m.toolCalls + entry) else m
                },
            )
        }
    }

    private fun replaceAssistantContent(messageId: String, content: String) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { m ->
                    if (m.id == messageId) m.copy(content = content) else m
                },
            )
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
                                when (m.role) {
                                    ChatMessage.Role.SYSTEM -> {
                                        w.append("> _").append(m.content).append("_\n\n")
                                    }
                                    else -> {
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
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { unloadInternal() }
    }

    private companion object {
        val DEFAULT_TOOLS: List<Tool> = listOf(CalculatorTool(), CurrentTimeTool(), WordCountTool())
    }
}
