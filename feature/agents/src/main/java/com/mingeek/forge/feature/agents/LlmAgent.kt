package com.mingeek.forge.feature.agents

import com.mingeek.forge.agent.core.Agent
import com.mingeek.forge.agent.core.AgentContext
import com.mingeek.forge.agent.core.AgentEvent
import com.mingeek.forge.agent.core.AgentInput
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.domain.ChatTemplate
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.runtime.core.InferenceRuntime
import com.mingeek.forge.runtime.core.LoadConfig
import com.mingeek.forge.runtime.core.LoadedModel
import com.mingeek.forge.runtime.core.ModelHandle
import com.mingeek.forge.runtime.core.Prompt
import com.mingeek.forge.runtime.core.Token
import com.mingeek.forge.runtime.registry.RuntimeRegistry
import com.mingeek.forge.runtime.registry.SharedSessionRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * LLM-backed Agent.
 *
 * By default each [run] performs its own load/generate/unload cycle. If the
 * caller wires [sharedSessions] in, the model lifecycle is delegated to the
 * registry: the first agent that touches a given modelId loads it, subsequent
 * agents (and subsequent run() calls on the same agent) reuse the cached
 * session, and the caller is responsible for `releaseAll()` at the end of the
 * workflow.
 */
class LlmAgent(
    override val id: String,
    override val displayName: String,
    private val model: InstalledModel,
    private val registry: RuntimeRegistry,
    private val systemPrompt: String? = null,
    private val maxTokens: Int = 384,
    private val temperature: Float = 0.7f,
    private val sharedSessions: SharedSessionRegistry? = null,
) : Agent {

    override fun run(input: AgentInput, ctx: AgentContext): Flow<AgentEvent> = flow {
        val acquired = acquireSession()
        if (acquired == null) {
            emit(AgentEvent.Failed("No runtime supports ${model.format}"))
            return@flow
        }
        val (runtime, loaded) = acquired

        val template = loaded.chatTemplate
            ?.let { ChatTemplate.fromChatTemplateString(it) }
            ?: ChatTemplate.detect(model.id, model.fileName)

        val turns = buildList {
            systemPrompt?.takeIf { it.isNotBlank() }
                ?.let { add(ChatTemplate.Turn(ChatTemplate.Turn.Role.SYSTEM, it)) }
            add(ChatTemplate.Turn(ChatTemplate.Turn.Role.USER, input.text))
        }
        val promptText = template.format(turns, addAssistantPrefix = true)

        val accumulated = StringBuilder()
        try {
            runtime.generate(
                loaded.sessionId,
                Prompt(
                    text = promptText,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    stopSequences = template.stopSequences,
                ),
            ).collect { token ->
                when (token) {
                    is Token.Text -> {
                        accumulated.append(token.piece)
                        emit(AgentEvent.Token(token.piece))
                    }
                    is Token.Done -> emit(AgentEvent.Final(accumulated.toString()))
                    is Token.ToolCall -> { /* tool calls not wired yet */ }
                }
            }
        } finally {
            // Only unload when we own the session lifecycle. With a shared
            // registry the caller releases everything at the end of the
            // workflow so we leave it loaded for subsequent steps.
            if (sharedSessions == null) {
                runCatching { runtime.unload(loaded.sessionId) }
            }
        }
    }
        .flowOn(Dispatchers.IO)
        .catch { emit(AgentEvent.Failed(it.message ?: "agent failed", it)) }

    private suspend fun acquireSession(): Pair<InferenceRuntime, LoadedModel>? {
        val config = LoadConfig(contextLength = model.contextLength.coerceAtMost(2048))
        sharedSessions?.let { return it.acquire(model, config) }

        val runtimeId = runCatching { RuntimeId.valueOf(model.recommendedRuntime) }
            .getOrDefault(RuntimeId.LLAMA_CPP)
        val runtime = registry.pick(model.format, runtimeId) ?: return null
        val loaded = runtime.load(
            ModelHandle(model.id, model.filePath, model.format),
            config,
        )
        return runtime to loaded
    }
}
