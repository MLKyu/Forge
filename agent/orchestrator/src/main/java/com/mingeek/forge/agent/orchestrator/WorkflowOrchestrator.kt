package com.mingeek.forge.agent.orchestrator

import com.mingeek.forge.agent.core.Agent
import com.mingeek.forge.agent.core.AgentContext
import com.mingeek.forge.agent.core.AgentEvent
import com.mingeek.forge.agent.core.AgentInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * Default orchestrator. Implements Pipeline and Router; Debate / PlanExecute emit Failed.
 */
class WorkflowOrchestrator(
    private val agents: Map<String, Agent>,
) : Orchestrator {

    override fun execute(workflow: Workflow, input: String): Flow<OrchestratorEvent> = flow {
        emit(OrchestratorEvent.Started(workflow.id))
        when (workflow) {
            is Workflow.Pipeline -> runPipeline(workflow, input)
            is Workflow.Router -> runRouter(workflow, input)
            is Workflow.Debate -> runDebate(workflow, input)
            is Workflow.PlanExecute -> emit(
                OrchestratorEvent.Failed(
                    workflow.id,
                    "PlanExecute workflow not implemented (use a 3-step Pipeline preset instead)",
                )
            )
        }
    }

    private suspend fun FlowCollector<OrchestratorEvent>.runDebate(
        workflow: Workflow.Debate,
        userInput: String,
    ) {
        if (workflow.participantAgentIds.isEmpty()) {
            emit(OrchestratorEvent.Failed(workflow.id, "Debate has no participants"))
            return
        }

        val participantOutputs = mutableListOf<Pair<String, String>>() // agentId, output

        for ((index, participantId) in workflow.participantAgentIds.withIndex()) {
            val agent = agents[participantId]
            if (agent == null) {
                emit(OrchestratorEvent.Failed(workflow.id, "Participant agent not found: $participantId"))
                return
            }
            val stepId = "p-$index"
            emit(OrchestratorEvent.StepStarted(workflow.id, stepId, participantId))
            val output = StringBuilder()
            var failed: String? = null
            try {
                agent.run(AgentInput(text = userInput), AgentContext(agentId = participantId))
                    .collect { ev ->
                        when (ev) {
                            is AgentEvent.Token -> {
                                output.append(ev.piece)
                                emit(OrchestratorEvent.StepToken(workflow.id, stepId, ev.piece))
                            }
                            is AgentEvent.Failed -> failed = ev.message
                            is AgentEvent.ToolCall -> emit(
                                OrchestratorEvent.StepToolCall(
                                    workflow.id, stepId, ev.name, ev.argumentsJson,
                                ),
                            )
                            is AgentEvent.ToolResult -> emit(
                                OrchestratorEvent.StepToolResult(
                                    workflow.id, stepId, ev.name, ev.resultJson, ev.isError,
                                ),
                            )
                            is AgentEvent.Final,
                            is AgentEvent.Thought -> { /* ignore */ }
                        }
                    }
            } catch (t: Throwable) {
                emit(OrchestratorEvent.Failed(workflow.id, t.message ?: "participant failed", t))
                return
            }
            if (failed != null) {
                emit(OrchestratorEvent.Failed(workflow.id, failed!!))
                return
            }
            val text = output.toString().trim()
            participantOutputs += participantId to text
            emit(OrchestratorEvent.StepCompleted(workflow.id, stepId, text))
        }

        val moderatorId = workflow.moderatorAgentId
        if (moderatorId == null) {
            // No moderator — concatenate participant outputs as final answer.
            val concatenated = participantOutputs.joinToString("\n\n---\n\n") { (id, text) ->
                "[$id]\n$text"
            }
            emit(OrchestratorEvent.Completed(workflow.id, concatenated))
            return
        }

        val moderator = agents[moderatorId]
        if (moderator == null) {
            emit(OrchestratorEvent.Failed(workflow.id, "Moderator agent not found: $moderatorId"))
            return
        }
        val moderatorStepId = "moderator"
        emit(OrchestratorEvent.StepStarted(workflow.id, moderatorStepId, moderatorId))

        val synthPrompt = buildString {
            append("Original question:\n").append(userInput).append("\n\n")
            append("Participant answers:\n")
            for ((i, pair) in participantOutputs.withIndex()) {
                append("[").append(i + 1).append("] ").append(pair.second).append("\n\n")
            }
            append("Synthesize a final answer drawing on the strongest points from each:")
        }

        val finalOutput = StringBuilder()
        var modFailed: String? = null
        try {
            moderator.run(AgentInput(text = synthPrompt), AgentContext(agentId = moderatorId))
                .collect { ev ->
                    when (ev) {
                        is AgentEvent.Token -> {
                            finalOutput.append(ev.piece)
                            emit(OrchestratorEvent.StepToken(workflow.id, moderatorStepId, ev.piece))
                        }
                        is AgentEvent.Failed -> modFailed = ev.message
                        is AgentEvent.ToolCall -> emit(
                            OrchestratorEvent.StepToolCall(
                                workflow.id, moderatorStepId, ev.name, ev.argumentsJson,
                            ),
                        )
                        is AgentEvent.ToolResult -> emit(
                            OrchestratorEvent.StepToolResult(
                                workflow.id, moderatorStepId, ev.name, ev.resultJson, ev.isError,
                            ),
                        )
                        is AgentEvent.Final,
                        is AgentEvent.Thought -> { /* ignore */ }
                    }
                }
        } catch (t: Throwable) {
            emit(OrchestratorEvent.Failed(workflow.id, t.message ?: "moderator failed", t))
            return
        }
        if (modFailed != null) {
            emit(OrchestratorEvent.Failed(workflow.id, modFailed!!))
            return
        }
        val text = finalOutput.toString().trim()
        emit(OrchestratorEvent.StepCompleted(workflow.id, moderatorStepId, text))
        emit(OrchestratorEvent.Completed(workflow.id, text))
    }

    private suspend fun FlowCollector<OrchestratorEvent>.runRouter(
        workflow: Workflow.Router,
        userInput: String,
    ) {
        val router = agents[workflow.routerAgentId]
        if (router == null) {
            emit(OrchestratorEvent.Failed(workflow.id, "Router agent not found: ${workflow.routerAgentId}"))
            return
        }
        if (workflow.routes.isEmpty()) {
            emit(OrchestratorEvent.Failed(workflow.id, "Router has no routes configured"))
            return
        }

        // Stage 1: ask the router agent to classify.
        val routerStepId = "router"
        emit(OrchestratorEvent.StepStarted(workflow.id, routerStepId, workflow.routerAgentId))
        val routerOutput = StringBuilder()
        var routerFailed: String? = null
        try {
            router.run(AgentInput(text = userInput), AgentContext(agentId = workflow.routerAgentId))
                .collect { ev ->
                    when (ev) {
                        is AgentEvent.Token -> {
                            routerOutput.append(ev.piece)
                            emit(OrchestratorEvent.StepToken(workflow.id, routerStepId, ev.piece))
                        }
                        is AgentEvent.Failed -> routerFailed = ev.message
                        is AgentEvent.ToolCall -> emit(
                            OrchestratorEvent.StepToolCall(
                                workflow.id, routerStepId, ev.name, ev.argumentsJson,
                            ),
                        )
                        is AgentEvent.ToolResult -> emit(
                            OrchestratorEvent.StepToolResult(
                                workflow.id, routerStepId, ev.name, ev.resultJson, ev.isError,
                            ),
                        )
                        is AgentEvent.Final,
                        is AgentEvent.Thought -> { /* ignore */ }
                    }
                }
        } catch (t: Throwable) {
            emit(OrchestratorEvent.Failed(workflow.id, t.message ?: "router failed", t))
            return
        }
        if (routerFailed != null) {
            emit(OrchestratorEvent.Failed(workflow.id, routerFailed!!))
            return
        }
        val routerText = routerOutput.toString().trim()
        emit(OrchestratorEvent.StepCompleted(workflow.id, routerStepId, routerText))

        // Pick the first route whose key appears in router output (case-insensitive).
        val routedAgentId = workflow.routes.entries
            .firstOrNull { (key, _) -> routerText.contains(key, ignoreCase = true) }
            ?.value
            ?: workflow.routes.entries.first().value // fallback to first route

        val routedAgent = agents[routedAgentId]
        if (routedAgent == null) {
            emit(OrchestratorEvent.Failed(workflow.id, "Routed agent not found: $routedAgentId"))
            return
        }

        // Stage 2: run the routed agent with the original user input.
        val routedStepId = "routed"
        emit(OrchestratorEvent.StepStarted(workflow.id, routedStepId, routedAgentId))
        val routedOutput = StringBuilder()
        var routedFailed: String? = null
        try {
            routedAgent.run(AgentInput(text = userInput), AgentContext(agentId = routedAgentId))
                .collect { ev ->
                    when (ev) {
                        is AgentEvent.Token -> {
                            routedOutput.append(ev.piece)
                            emit(OrchestratorEvent.StepToken(workflow.id, routedStepId, ev.piece))
                        }
                        is AgentEvent.Failed -> routedFailed = ev.message
                        is AgentEvent.ToolCall -> emit(
                            OrchestratorEvent.StepToolCall(
                                workflow.id, routedStepId, ev.name, ev.argumentsJson,
                            ),
                        )
                        is AgentEvent.ToolResult -> emit(
                            OrchestratorEvent.StepToolResult(
                                workflow.id, routedStepId, ev.name, ev.resultJson, ev.isError,
                            ),
                        )
                        is AgentEvent.Final,
                        is AgentEvent.Thought -> { /* ignore */ }
                    }
                }
        } catch (t: Throwable) {
            emit(OrchestratorEvent.Failed(workflow.id, t.message ?: "routed step failed", t))
            return
        }
        if (routedFailed != null) {
            emit(OrchestratorEvent.Failed(workflow.id, routedFailed!!))
            return
        }
        val finalText = routedOutput.toString().trim()
        emit(OrchestratorEvent.StepCompleted(workflow.id, routedStepId, finalText))
        emit(OrchestratorEvent.Completed(workflow.id, finalText))
    }

    private suspend fun FlowCollector<OrchestratorEvent>.runPipeline(
        workflow: Workflow.Pipeline,
        initialInput: String,
    ) {
        var currentInput = initialInput
        for (step in workflow.steps) {
            val agent = agents[step.agentId]
            if (agent == null) {
                emit(OrchestratorEvent.Failed(workflow.id, "Agent not found: ${step.agentId}"))
                return
            }
            emit(OrchestratorEvent.StepStarted(workflow.id, step.id, step.agentId))

            val rendered = step.promptTemplate
                .ifBlank { "{input}" }
                .replace("{input}", currentInput)

            val output = StringBuilder()
            var failedMessage: String? = null
            try {
                agent.run(AgentInput(text = rendered), AgentContext(agentId = step.agentId))
                    .collect { ev ->
                        when (ev) {
                            is AgentEvent.Token -> {
                                output.append(ev.piece)
                                emit(OrchestratorEvent.StepToken(workflow.id, step.id, ev.piece))
                            }
                            is AgentEvent.Final -> {
                                // Output already accumulated via tokens; ignore.
                            }
                            is AgentEvent.Failed -> {
                                failedMessage = ev.message
                            }
                            is AgentEvent.ToolCall -> emit(
                                OrchestratorEvent.StepToolCall(
                                    workflow.id, step.id, ev.name, ev.argumentsJson,
                                ),
                            )
                            is AgentEvent.ToolResult -> emit(
                                OrchestratorEvent.StepToolResult(
                                    workflow.id, step.id, ev.name, ev.resultJson, ev.isError,
                                ),
                            )
                            is AgentEvent.Thought -> { /* ignore */ }
                        }
                    }
            } catch (t: Throwable) {
                emit(OrchestratorEvent.Failed(workflow.id, t.message ?: "step failed", t))
                return
            }

            if (failedMessage != null) {
                emit(OrchestratorEvent.Failed(workflow.id, failedMessage!!))
                return
            }

            currentInput = output.toString().trim()
            emit(OrchestratorEvent.StepCompleted(workflow.id, step.id, currentInput))
        }
        emit(OrchestratorEvent.Completed(workflow.id, currentInput))
    }
}
