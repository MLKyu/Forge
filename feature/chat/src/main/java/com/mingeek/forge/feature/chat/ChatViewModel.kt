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
import com.mingeek.forge.data.storage.effectiveLastUsedEpochSec
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
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
    /**
     * Process-scoped coroutine scope used by [onCleared] to release the
     * native runtime session. We can't reuse [viewModelScope] there
     * because Android cancels it *before* `onCleared` runs — any
     * `viewModelScope.launch { ... }` issued from `onCleared` is
     * scheduled into an already-cancelled scope and never executes,
     * leaking the loaded model's KV-cache and mmap'd weights until
     * process death. The app scope is held by [com.mingeek.forge.di.ForgeContainer].
     */
    private val appScope: CoroutineScope,
) : ViewModel() {

    val installed: StateFlow<List<InstalledModel>> = storage.installed

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generationJob: Job? = null
    /**
     * Tracks the most recent load coroutine across [loadModel],
     * [loadModelAndResume], and [swapModel]. A new load cancels the
     * previous one before starting; combined with the
     * `NonCancellable`-protected unload in [doLoad]'s finally block,
     * this prevents native runtime sessions from being orphaned when
     * the user (or auto-load) issues a second load while the first is
     * still inside `runtime.load(...)`.
     */
    private var loadJob: Job? = null

    private val moshi: Moshi = Moshi.Builder().build()
    private val messagesAdapter: JsonAdapter<List<ChatMessage>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, ChatMessage::class.java),
    )

    @OptIn(FlowPreview::class)
    private fun startAutoSave() {
        viewModelScope.launch {
            // Only persist while a Ready session is active. During
            // [SessionState.Loading] (e.g. resume-from-history or
            // model swap) the model id is not yet bound, and writing
            // a `null` modelId would clobber the conversation's
            // metadata and break the model picker's per-model
            // grouping. The Ready transition itself re-emits the
            // same triple with the correct modelId, so no edits are
            // lost.
            _state
                .mapNotNull { st ->
                    val ready = st.sessionState as? SessionState.Ready ?: return@mapNotNull null
                    Triple(st.conversationId, st.messages, ready.model.id)
                }
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
        viewModelScope.launch {
            refreshPastConversations()
            autoLoadMostRecent()
        }
    }

    /**
     * If the user has previously loaded any model, jump straight into a
     * fresh chat with that one — saves a tap on every chat-tab entry.
     * First-time users (no `lastUsedEpochSec` on any model) still see
     * the picker so they can choose deliberately.
     *
     * Only runs when we're in [SessionState.Idle] so it never disturbs
     * an in-progress session restored across config changes.
     */
    private fun autoLoadMostRecent() {
        if (_state.value.sessionState !is SessionState.Idle) return
        val candidate = installed.value
            .filter { it.lastUsedEpochSec != null }
            .maxByOrNull { it.effectiveLastUsedEpochSec() }
            ?: return
        loadModel(candidate)
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
        val previous = loadJob
        loadJob = viewModelScope.launch {
            previous?.cancelAndJoin()
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
        val previous = loadJob
        loadJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            unloadInternal()
            _state.update {
                it.copy(
                    sessionState = SessionState.Loading,
                    messages = emptyList(),
                    conversationId = UUID.randomUUID().toString(),
                )
            }
            doLoad(model)
        }
    }

    /**
     * Pick a model AND restore a specific past conversation in one shot
     * — used by the model picker's per-model history list so the user
     * can jump from "model X had these 3 chats" → "this chat" without a
     * two-step flow. Falls back to a fresh chat if the conversation has
     * been deleted in the meantime.
     */
    fun loadModelAndResume(model: InstalledModel, conversationId: String) {
        val previous = loadJob
        loadJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            val entry = chatHistory.get("chat:$conversationId")
            if (entry == null) {
                // Inline the fresh-load path rather than re-entering
                // [loadModel], which would dispatch another coroutine
                // and re-run cancel-previous against ourselves.
                unloadInternal()
                _state.update {
                    it.copy(
                        sessionState = SessionState.Loading,
                        messages = emptyList(),
                        conversationId = UUID.randomUUID().toString(),
                    )
                }
                doLoad(model)
                return@launch
            }
            val msgs = runCatching { messagesAdapter.fromJson(entry.content) }.getOrNull().orEmpty()
            unloadInternal()
            _state.update {
                it.copy(
                    sessionState = SessionState.Loading,
                    messages = msgs,
                    conversationId = conversationId,
                )
            }
            doLoad(model, preserveMessages = true)
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

        // Tracks a session id we've taken ownership of from the
        // runtime but haven't yet handed off to UI state. If the
        // coroutine is cancelled mid-flight (e.g. user kicked off a
        // second load), the finally block releases it so the native
        // KV-cache / mmap'd weights aren't leaked until process death.
        var orphanedSessionId: com.mingeek.forge.runtime.core.SessionId? = null
        try {
            val loaded = runtime.load(
                ModelHandle(
                    modelId = model.id,
                    modelPath = model.filePath,
                    format = model.format,
                ),
                LoadConfig(
                    contextLength = model.contextLength.coerceAtMost(LOAD_CONTEXT_CAP),
                    threads = 0,
                ),
            )
            orphanedSessionId = loaded.sessionId
            val template = loaded.chatTemplate
                ?.let { ChatTemplate.fromChatTemplateString(it) }
                ?: ChatTemplate.detect(model.id, model.fileName)
            storage.markUsed(model.id)
            _state.update {
                it.copy(sessionState = SessionState.Ready(loaded.sessionId, model, template))
            }
            // Ownership transferred to state; nothing to clean up.
            orphanedSessionId = null
        } catch (ce: CancellationException) {
            // Don't surface a failure UI for cooperative cancellation;
            // the new load has already taken over. Cleanup happens in
            // the finally block.
            throw ce
        } catch (t: Throwable) {
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
        } finally {
            val orphan = orphanedSessionId
            if (orphan != null) {
                withContext(NonCancellable) {
                    runCatching { runtime.unload(orphan) }
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

        // The prompt is split into three zones so we can trim to fit n_ctx
        // without ever touching the parts the model needs intact:
        //
        //   system   — tool prelude / sys prompt; always at the top.
        //   history  — older USER/ASSISTANT pairs; droppable from the front.
        //   current  — the just-sent user message + everything appended
        //              during the tool loop (assistant tool call ↔ tool
        //              result pairs that semantically belong together).
        //
        // chars≈tokens is the budgeting heuristic — accurate enough for
        // Korean BPE worst case, generous for English/code. The runtime
        // surfaces a clean Token.Done(ERROR) if the heuristic still
        // underestimates, which we promote to a visible error below.
        val systemTurnList = mutableListOf<ChatTemplate.Turn>()
        if (tools.isNotEmpty()) {
            systemTurnList += ChatTemplate.Turn(
                role = ChatTemplate.Turn.Role.SYSTEM,
                content = ToolCallProtocol.buildPrelude(tools),
            )
        }
        val allHistory = mutableListOf<ChatTemplate.Turn>()
        for (m in _state.value.messages) {
            if (m.id == assistantMsg.id) continue
            // SYSTEM notes (e.g. "Switched to X") never get sent to the model.
            if (m.role == ChatMessage.Role.SYSTEM) continue
            allHistory += ChatTemplate.Turn(
                role = if (m.role == ChatMessage.Role.USER) ChatTemplate.Turn.Role.USER
                else ChatTemplate.Turn.Role.ASSISTANT,
                content = m.content,
            )
        }
        // The just-sent user message is the last item of allHistory; lift
        // it into `current` so it is never dropped by the trim loop.
        val currentTurn = mutableListOf<ChatTemplate.Turn>()
        if (allHistory.isNotEmpty()) currentTurn += allHistory.removeAt(allHistory.lastIndex)
        val zones = PromptZones(
            system = systemTurnList,
            history = allHistory,
            current = currentTurn,
        )
        val effectiveCtx = ready.model.contextLength.coerceAtMost(LOAD_CONTEXT_CAP)
        val budget = (effectiveCtx - GENERATION_RESERVE).coerceAtLeast(MIN_HISTORY_BUDGET)
        zones.trimToBudget(budget)

        generationJob = viewModelScope.launch {
            try {
                runToolLoop(
                    runtime = runtime,
                    sessionId = ready.sessionId,
                    template = ready.template,
                    zones = zones,
                    budget = budget,
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
        zones: PromptZones,
        budget: Int,
        tools: List<Tool>,
        assistantMsgId: String,
    ) {
        val temperature = settingsStore.defaultTemperature.value
        val maxIterations = settingsStore.toolMaxIterations.value
        val stops = template.stopSequences
        var iteration = 0

        while (true) {
            // Tool loop iterations grow `current` (assistant call + tool
            // result pairs); re-trim before every generate so we stay
            // under n_ctx without dropping the call/result chain.
            zones.trimToBudget(budget)
            val promptText = template.format(zones.compose(), addAssistantPrefix = true)
            val turnOutput = StringBuilder()
            var lastUsage: Token.TokenUsage? = null
            var finishReason: Token.FinishReason? = null

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
                    is Token.Done -> {
                        lastUsage = token.usage
                        finishReason = token.finishReason
                    }
                    is Token.ToolCall -> { /* runtime-level tool calls unused */ }
                }
            }

            // Promote a runtime-level error (KV cache exhausted, decode
            // failure, …) to a visible chat message instead of silently
            // finalizing on whatever partial text we collected.
            if (finishReason == Token.FinishReason.ERROR) {
                appendToAssistant(
                    assistantMsgId,
                    app.getString(R.string.chat_error_inline, "inference failed"),
                )
                finalizeAssistant(assistantMsgId, lastUsage)
                return
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
            // next generate can produce a final answer. These belong to the
            // *current* turn — they share semantics with the tool call right
            // before them and must never be split by the trim loop.
            zones.current += ChatTemplate.Turn(ChatTemplate.Turn.Role.ASSISTANT, rendered)
            zones.current += ChatTemplate.Turn(
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

    /**
     * Three-zone prompt composition that lets the trim loop reclaim space
     * by dropping ONLY older history, leaving system instructions and the
     * in-flight turn (latest user message + tool exchange chain) intact.
     */
    private class PromptZones(
        val system: List<ChatTemplate.Turn>,
        val history: MutableList<ChatTemplate.Turn>,
        val current: MutableList<ChatTemplate.Turn>,
    ) {
        fun compose(): List<ChatTemplate.Turn> = system + history + current

        fun trimToBudget(budget: Int) {
            val sacrosanctChars = system.sumOf { it.content.length } +
                current.sumOf { it.content.length }
            val historyBudget = (budget - sacrosanctChars).coerceAtLeast(0)
            var historyChars = history.sumOf { it.content.length }
            while (history.isNotEmpty() && historyChars > historyBudget) {
                historyChars -= history.removeAt(0).content.length
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
        // Snapshot the live session before launching — the VM (and its
        // state flow) might be GC-eligible the moment we return, but
        // local vals captured into the appScope-launched coroutine
        // outlive that.
        val ready = _state.value.sessionState as? SessionState.Ready ?: return
        val runtimeId = runCatching { RuntimeId.valueOf(ready.model.recommendedRuntime) }
            .getOrDefault(RuntimeId.LLAMA_CPP)
        val runtime = registry.pick(ready.model.format, runtimeId) ?: return
        val sessionId = ready.sessionId
        appScope.launch(NonCancellable) {
            runCatching { runtime.unload(sessionId) }
        }
    }

    private companion object {
        val DEFAULT_TOOLS: List<Tool> = listOf(CalculatorTool(), CurrentTimeTool(), WordCountTool())

        /** Hard cap on llama context size — KV-cache RAM stays bounded on phones. */
        const val LOAD_CONTEXT_CAP = 4096

        /**
         * Headroom subtracted from `n_ctx` before computing how much
         * conversation history to keep. Covers the assistant response
         * (`Prompt.maxTokens` = 512) plus chat-template scaffolding
         * (BOS/EOS, role markers, tool prelude expansion).
         */
        const val GENERATION_RESERVE = 768

        /** Floor so even a small `n_ctx` still admits the latest user turn. */
        const val MIN_HISTORY_BUDGET = 256
    }
}
