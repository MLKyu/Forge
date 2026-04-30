package com.mingeek.forge.agent.orchestrator

import kotlinx.coroutines.flow.Flow

sealed interface Workflow {
    val id: String
    val name: String

    data class Pipeline(
        override val id: String,
        override val name: String,
        val steps: List<Step>,
    ) : Workflow

    data class Router(
        override val id: String,
        override val name: String,
        val routerAgentId: String,
        val routes: Map<String, String>,
    ) : Workflow

    data class Debate(
        override val id: String,
        override val name: String,
        val participantAgentIds: List<String>,
        val moderatorAgentId: String?,
        val maxRounds: Int,
    ) : Workflow

    data class PlanExecute(
        override val id: String,
        override val name: String,
        val plannerAgentId: String,
        val executorAgentId: String,
        val criticAgentId: String?,
    ) : Workflow

    data class Step(
        val id: String,
        val agentId: String,
        val promptTemplate: String,
    )
}

sealed interface OrchestratorEvent {
    val workflowId: String

    data class Started(override val workflowId: String) : OrchestratorEvent
    data class StepStarted(override val workflowId: String, val stepId: String, val agentId: String) : OrchestratorEvent
    data class StepToken(override val workflowId: String, val stepId: String, val piece: String) : OrchestratorEvent
    data class StepCompleted(override val workflowId: String, val stepId: String, val output: String) : OrchestratorEvent
    data class Completed(override val workflowId: String, val finalOutput: String) : OrchestratorEvent
    data class Failed(override val workflowId: String, val message: String, val cause: Throwable? = null) : OrchestratorEvent
}

interface Orchestrator {
    fun execute(workflow: Workflow, input: String): Flow<OrchestratorEvent>
}
