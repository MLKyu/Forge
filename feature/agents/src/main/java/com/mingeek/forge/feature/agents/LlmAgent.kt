package com.mingeek.forge.feature.agents

import com.mingeek.forge.agent.core.Agent
import com.mingeek.forge.agent.core.AgentContext
import com.mingeek.forge.agent.core.AgentEvent
import com.mingeek.forge.agent.core.AgentInput
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.domain.ChatTemplate
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.runtime.core.LoadConfig
import com.mingeek.forge.runtime.core.ModelHandle
import com.mingeek.forge.runtime.core.Prompt
import com.mingeek.forge.runtime.core.Token
import com.mingeek.forge.runtime.registry.RuntimeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class LlmAgent(
    override val id: String,
    override val displayName: String,
    private val model: InstalledModel,
    private val registry: RuntimeRegistry,
    private val systemPrompt: String? = null,
    private val maxTokens: Int = 384,
    private val temperature: Float = 0.7f,
) : Agent {

    override fun run(input: AgentInput, ctx: AgentContext): Flow<AgentEvent> = flow {
        val runtimeId = runCatching { RuntimeId.valueOf(model.recommendedRuntime) }
            .getOrDefault(RuntimeId.LLAMA_CPP)
        val runtime = registry.pick(model.format, runtimeId)
        if (runtime == null) {
            emit(AgentEvent.Failed("No runtime supports ${model.format}"))
            return@flow
        }

        val loaded = runtime.load(
            ModelHandle(model.id, model.filePath, model.format),
            LoadConfig(contextLength = model.contextLength.coerceAtMost(2048)),
        )
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
            runtime.unload(loaded.sessionId)
        }
    }
        .flowOn(Dispatchers.IO)
        .catch { emit(AgentEvent.Failed(it.message ?: "agent failed", it)) }
}
