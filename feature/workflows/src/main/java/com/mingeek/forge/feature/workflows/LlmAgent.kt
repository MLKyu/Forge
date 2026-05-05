package com.mingeek.forge.feature.workflows

import com.mingeek.forge.agent.core.Agent
import com.mingeek.forge.agent.core.AgentContext
import com.mingeek.forge.agent.core.AgentEvent
import com.mingeek.forge.agent.core.AgentInput
import com.mingeek.forge.agent.core.Tool
import com.mingeek.forge.agent.core.ToolCallProtocol
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
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * LLM-backed Agent.
 *
 * Two orthogonal opt-ins:
 *
 * - [sharedSessions]: when provided, model load/unload is delegated to the
 *   registry — the first agent that touches a given modelId loads it, every
 *   later acquire reuses the cached session, and the caller is responsible for
 *   `releaseAll()` at the end of the workflow. Without it, each [run] does
 *   its own load/unload cycle.
 *
 * - [tools]: when non-empty the run drives a multi-turn tool loop instead of
 *   a one-shot generate:
 *     1. inject a tool prelude into the system prompt
 *     2. generate the assistant turn
 *     3. if it parses as `TOOL: name / ARGS: {...}`, invoke the tool, append a
 *        `TOOL_RESULT:` user turn, and loop
 *     4. otherwise emit it as the final answer
 *   The loop is capped at [maxToolIterations] to bound runaway models.
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
    private val tools: List<Tool> = emptyList(),
    private val maxToolIterations: Int = 4,
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

        try {
            runConversation(runtime, loaded, template, input.text)
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

    private suspend fun FlowCollector<AgentEvent>.runConversation(
        runtime: InferenceRuntime,
        loaded: LoadedModel,
        template: ChatTemplate,
        userText: String,
    ) {
        val effectiveSystem = buildString {
            if (tools.isNotEmpty()) append(ToolCallProtocol.buildPrelude(tools))
            systemPrompt?.takeIf { it.isNotBlank() }?.let { append(it) }
        }.takeIf { it.isNotBlank() }

        val turns = mutableListOf<ChatTemplate.Turn>()
        effectiveSystem?.let {
            turns += ChatTemplate.Turn(ChatTemplate.Turn.Role.SYSTEM, it)
        }
        turns += ChatTemplate.Turn(ChatTemplate.Turn.Role.USER, userText)

        // chars≈tokens budgeting heuristic — see the ChatViewModel
        // PromptZones comment for the rationale. LlmAgent is single-turn
        // (system + user), so there's no "older history" we can drop;
        // the only thing we can do is bail out cleanly when the input
        // alone (or a tool-loop accumulation) would overflow n_ctx.
        val effectiveCtx = model.contextLength.coerceAtMost(LOAD_CONTEXT_CAP)
        val budget = (effectiveCtx - maxTokens - TEMPLATE_RESERVE)
            .coerceAtLeast(MIN_PROMPT_BUDGET)
        if (turns.sumOf { it.content.length } > budget) {
            emit(AgentEvent.Failed("input too long for model context window"))
            return
        }

        var iteration = 0
        while (true) {
            val promptText = template.format(turns, addAssistantPrefix = true)
            val output = StringBuilder()
            var finishReason: Token.FinishReason? = null
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
                        output.append(token.piece)
                        emit(AgentEvent.Token(token.piece))
                    }
                    is Token.Done -> { finishReason = token.finishReason }
                    is Token.ToolCall -> { /* runtime-emitted tool calls unused */ }
                }
            }

            // Promote runtime-level errors (KV cache exhausted, decode
            // failure) into a Failed event so the caller can show a
            // useful message instead of treating partial output as Final.
            if (finishReason == Token.FinishReason.ERROR) {
                emit(AgentEvent.Failed("inference failed (likely n_ctx overflow)"))
                return
            }

            val rendered = output.toString().trim()
            val toolCall = if (tools.isNotEmpty()) ToolCallProtocol.parseCall(rendered) else null

            if (toolCall == null) {
                emit(AgentEvent.Final(rendered))
                return
            }

            iteration++
            emit(AgentEvent.ToolCall(toolCall.name, toolCall.argumentsJson))

            val tool = tools.firstOrNull { it.name == toolCall.name }
            val result = if (tool == null) {
                com.mingeek.forge.agent.core.ToolResult(
                    outputJson = """{"error": "unknown tool: ${toolCall.name}"}""",
                    isError = true,
                )
            } else {
                runCatching { tool.invoke(toolCall.argumentsJson) }
                    .getOrElse {
                        com.mingeek.forge.agent.core.ToolResult(
                            outputJson = """{"error": "${it.message?.replace("\"", "'") ?: "tool failed"}"}""",
                            isError = true,
                        )
                    }
            }
            emit(AgentEvent.ToolResult(toolCall.name, result.outputJson, result.isError))

            // Append the assistant's tool-call turn and the synthetic tool result so the
            // next iteration sees them.
            turns += ChatTemplate.Turn(ChatTemplate.Turn.Role.ASSISTANT, rendered)
            turns += ChatTemplate.Turn(
                role = ChatTemplate.Turn.Role.USER,
                content = ToolCallProtocol.renderResult(result),
            )

            // Bail before kicking off another generate that would overflow.
            // Without this, the runtime would just emit Token.Done(ERROR)
            // and we'd surface it above — same outcome but slower (a wasted
            // prefill) and the user gets a less specific reason.
            if (turns.sumOf { it.content.length } > budget) {
                emit(AgentEvent.Failed("tool loop ran out of context room"))
                return
            }

            if (iteration >= maxToolIterations) {
                emit(AgentEvent.Failed("Tool call loop exceeded $maxToolIterations iterations"))
                return
            }
        }
    }

    private suspend fun acquireSession(): Pair<InferenceRuntime, LoadedModel>? {
        val config = LoadConfig(contextLength = model.contextLength.coerceAtMost(LOAD_CONTEXT_CAP))
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

    private companion object {
        /**
         * Cap on llama context size. Tighter than ChatViewModel's 4096
         * because Workflow steps run multiple agents in series and each
         * one carries its own KV-cache RAM.
         */
        const val LOAD_CONTEXT_CAP = 2048

        /** Headroom for chat-template scaffolding (BOS/EOS, role markers). */
        const val TEMPLATE_RESERVE = 128

        /** Floor so very small `n_ctx` still admits a basic prompt. */
        const val MIN_PROMPT_BUDGET = 256
    }
}
