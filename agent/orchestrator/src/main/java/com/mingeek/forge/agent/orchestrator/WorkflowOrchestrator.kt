package com.mingeek.forge.agent.orchestrator

import com.mingeek.forge.agent.core.Agent
import com.mingeek.forge.agent.core.AgentContext
import com.mingeek.forge.agent.core.AgentEvent
import com.mingeek.forge.agent.core.AgentInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * Default orchestrator. Implements every Workflow sealed variant.
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
            is Workflow.PlanExecute -> runPlanExecute(workflow, input)
        }
    }

    private suspend fun FlowCollector<OrchestratorEvent>.runPlanExecute(
        workflow: Workflow.PlanExecute,
        userInput: String,
    ) {
        val planner = agents[workflow.plannerAgentId]
        if (planner == null) {
            emit(OrchestratorEvent.Failed(workflow.id, "Planner agent not found: ${workflow.plannerAgentId}"))
            return
        }
        val executor = agents[workflow.executorAgentId]
        if (executor == null) {
            emit(OrchestratorEvent.Failed(workflow.id, "Executor agent not found: ${workflow.executorAgentId}"))
            return
        }
        val critic = workflow.criticAgentId?.let { agents[it] }
        if (workflow.criticAgentId != null && critic == null) {
            emit(OrchestratorEvent.Failed(workflow.id, "Critic agent not found: ${workflow.criticAgentId}"))
            return
        }

        // Step 1: planner outlines a plan from the user request.
        val planText = runStep(
            workflow.id,
            stepId = "planner",
            agentId = workflow.plannerAgentId,
            agent = planner,
            prompt = userInput,
        ) ?: return

        // Step 2: executor turns the plan into a draft answer.
        val executorPrompt = buildString {
            append("Plan:\n").append(planText).append("\n\n")
            append("Original request:\n").append(userInput).append("\n\n")
            append("Carry out the plan and produce the answer.")
        }
        val executorText = runStep(
            workflow.id,
            stepId = "executor",
            agentId = workflow.executorAgentId,
            agent = executor,
            prompt = executorPrompt,
        ) ?: return

        if (critic == null) {
            emit(OrchestratorEvent.Completed(workflow.id, executorText))
            return
        }

        // Step 3: critic reviews and refines into the final answer.
        val criticPrompt = buildString {
            append("Original request:\n").append(userInput).append("\n\n")
            append("Plan that was followed:\n").append(planText).append("\n\n")
            append("Draft answer from the executor:\n").append(executorText).append("\n\n")
            append("Identify weaknesses and produce an improved final answer.")
        }
        val criticText = runStep(
            workflow.id,
            stepId = "critic",
            agentId = workflow.criticAgentId!!,
            agent = critic,
            prompt = criticPrompt,
        ) ?: return

        emit(OrchestratorEvent.Completed(workflow.id, criticText))
    }

    /**
     * Run a single agent step, emitting Started/Token/ToolCall/ToolResult/Completed
     * events. Returns the trimmed final text on success, or null after emitting
     * Failed (so the caller short-circuits the workflow).
     */
    private suspend fun FlowCollector<OrchestratorEvent>.runStep(
        workflowId: String,
        stepId: String,
        agentId: String,
        agent: Agent,
        prompt: String,
    ): String? {
        emit(OrchestratorEvent.StepStarted(workflowId, stepId, agentId))
        val output = StringBuilder()
        var failed: String? = null
        try {
            agent.run(AgentInput(text = prompt), AgentContext(agentId = agentId)).collect { ev ->
                when (ev) {
                    is AgentEvent.Token -> {
                        output.append(ev.piece)
                        emit(OrchestratorEvent.StepToken(workflowId, stepId, ev.piece))
                    }
                    is AgentEvent.Failed -> failed = ev.message
                    is AgentEvent.ToolCall -> emit(
                        OrchestratorEvent.StepToolCall(
                            workflowId, stepId, ev.name, ev.argumentsJson,
                        ),
                    )
                    is AgentEvent.ToolResult -> emit(
                        OrchestratorEvent.StepToolResult(
                            workflowId, stepId, ev.name, ev.resultJson, ev.isError,
                        ),
                    )
                    is AgentEvent.Final,
                    is AgentEvent.Thought -> { /* ignore */ }
                }
            }
        } catch (t: Throwable) {
            emit(OrchestratorEvent.Failed(workflowId, t.message ?: "$stepId failed", t))
            return null
        }
        if (failed != null) {
            emit(OrchestratorEvent.Failed(workflowId, failed!!))
            return null
        }
        val text = output.toString().trim()
        emit(OrchestratorEvent.StepCompleted(workflowId, stepId, text))
        return text
    }

    private suspend fun FlowCollector<OrchestratorEvent>.runDebate(
        workflow: Workflow.Debate,
        userInput: String,
    ) {
        if (workflow.participantAgentIds.isEmpty()) {
            emit(OrchestratorEvent.Failed(workflow.id, "Debate has no participants"))
            return
        }
        val totalRounds = workflow.maxRounds.coerceAtLeast(1)

        // outputs[round-1][index] = participant answer for that round
        val outputsByRound = MutableList(totalRounds) { mutableListOf<String>() }

        for (round in 1..totalRounds) {
            for ((index, participantId) in workflow.participantAgentIds.withIndex()) {
                val agent = agents[participantId]
                if (agent == null) {
                    emit(OrchestratorEvent.Failed(workflow.id, "Participant agent not found: $participantId"))
                    return
                }
                val stepId = stepIdForParticipant(round, index)
                emit(OrchestratorEvent.StepStarted(workflow.id, stepId, participantId))

                val turnPrompt = buildParticipantPrompt(
                    userInput = userInput,
                    round = round,
                    selfIndex = index,
                    previousOutputs = outputsByRound,
                )

                val output = StringBuilder()
                var failed: String? = null
                try {
                    agent.run(AgentInput(text = turnPrompt), AgentContext(agentId = participantId))
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
                outputsByRound[round - 1] += text
                emit(OrchestratorEvent.StepCompleted(workflow.id, stepId, text))
            }
        }

        val finalRoundOutputs = outputsByRound.last()

        val moderatorId = workflow.moderatorAgentId
        if (moderatorId == null) {
            val concatenated = workflow.participantAgentIds
                .zip(finalRoundOutputs)
                .joinToString("\n\n---\n\n") { (id, text) -> "[$id]\n$text" }
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
            if (totalRounds > 1) {
                append("(Final round of $totalRounds.) ")
            }
            append("Participant final answers:\n")
            for ((i, text) in finalRoundOutputs.withIndex()) {
                append("[").append(i + 1).append("] ").append(text).append("\n\n")
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

    /** Round 1 = `p-{i}`, rounds 2+ = `p-{i}-r{round}`. ViewModel mirrors this. */
    private fun stepIdForParticipant(round: Int, participantIndex: Int): String =
        if (round == 1) "p-$participantIndex" else "p-$participantIndex-r$round"

    private fun buildParticipantPrompt(
        userInput: String,
        round: Int,
        selfIndex: Int,
        previousOutputs: List<List<String>>,
    ): String {
        if (round == 1) return userInput
        val previousRound = previousOutputs[round - 2]
        return buildString {
            append("Original question:\n").append(userInput).append("\n\n")
            append("Round ").append(round - 1).append(" answers:\n")
            for ((i, text) in previousRound.withIndex()) {
                val label = if (i == selfIndex) "[your previous answer]" else "[participant ${i + 1}]"
                append(label).append("\n").append(text).append("\n\n")
            }
            append(
                "This is round ",
            ).append(round).append(
                ". Reconsider the question in light of the other participants' answers and refine your own. " +
                    "Stay in character (your system role).",
            )
        }
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
