package com.mingeek.forge.feature.agents

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.forge.agent.core.Agent
import com.mingeek.forge.agent.orchestrator.OrchestratorEvent
import com.mingeek.forge.agent.orchestrator.Workflow
import com.mingeek.forge.agent.orchestrator.WorkflowOrchestrator
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import com.mingeek.forge.runtime.registry.RuntimeRegistry
import com.mingeek.forge.runtime.registry.SharedSessionRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RunStatus {
    data object Idle : RunStatus
    data object Running : RunStatus
    data object Done : RunStatus
    data class Failed(val message: String) : RunStatus
}

data class StepConfig(
    val agentId: String,
    val modelId: String?,
    val systemPrompt: String,
    val promptTemplate: String,
    val maxTokens: Int = 384,
)

data class StepRun(
    val stepId: String,
    val agentId: String,
    val modelId: String?,
    val output: String = "",
    val isComplete: Boolean = false,
)

enum class PipelinePreset(val displayName: String) {
    SINGLE_SHOT("Single shot"),
    PLAN_EXECUTE("Plan → Execute"),
    PLAN_EXECUTE_CRITIC("Plan → Execute → Critic"),
}

enum class WorkflowMode(val displayName: String) {
    PIPELINE("Pipeline"),
    ROUTER("Router"),
    DEBATE("Debate"),
}

data class ParticipantConfig(
    val agentId: String,
    val modelId: String?,
    val systemPrompt: String,
    val maxTokens: Int = 384,
)

data class DebateConfig(
    val participants: List<ParticipantConfig> = listOf(
        ParticipantConfig("participant-pro", null,
            "You are an optimist. Argue persuasively in favour of the user's proposition or scenario."),
        ParticipantConfig("participant-con", null,
            "You are a skeptic. Identify weaknesses, risks, and counterarguments."),
    ),
    val moderatorEnabled: Boolean = true,
    val moderatorAgentId: String = "moderator",
    val moderatorModelId: String? = null,
    val moderatorSystemPrompt: String =
        "You are a fair moderator. Synthesize the strongest points from each participant into a balanced final answer.",
    val moderatorMaxTokens: Int = 512,
)

data class RouteConfig(
    val agentId: String,
    val key: String,            // matched in router output (case-insensitive substring)
    val modelId: String?,
    val systemPrompt: String,
    val maxTokens: Int = 384,
)

data class RouterConfig(
    val routerAgentId: String = "router",
    val routerModelId: String? = null,
    val routerSystemPrompt: String =
        "Classify the user's request as exactly one label from this list and output only the label: code, math, creative, general.",
    val routerMaxTokens: Int = 32,
    val routes: List<RouteConfig> = listOf(
        RouteConfig("route-code", "code", null,
            "You are a senior software engineer. Answer programming and code questions concisely with examples."),
        RouteConfig("route-math", "math", null,
            "You are a careful mathematician. Show concise reasoning and the final answer."),
        RouteConfig("route-creative", "creative", null,
            "You are a creative writer. Produce vivid, imaginative prose."),
        RouteConfig("route-general", "general", null,
            "You are a helpful assistant. Answer clearly."),
    ),
)

data class AgentsUiState(
    val installed: List<InstalledModel> = emptyList(),
    val mode: WorkflowMode = WorkflowMode.PIPELINE,
    val steps: List<StepConfig> = listOf(
        StepConfig(
            agentId = "step-1",
            modelId = null,
            systemPrompt = "You are a careful planner. Outline a brief plan for the user's request.",
            promptTemplate = "{input}",
        ),
        StepConfig(
            agentId = "step-2",
            modelId = null,
            systemPrompt = "You are a polished writer. Take the plan below and turn it into a final answer.",
            promptTemplate = "Plan:\n{input}\n\nFinal answer:",
        ),
    ),
    val router: RouterConfig = RouterConfig(),
    val debate: DebateConfig = DebateConfig(),
    val userPrompt: String = "",
    val status: RunStatus = RunStatus.Idle,
    val runs: List<StepRun> = emptyList(),
)

class AgentsViewModel(
    private val storage: ModelStorage,
    private val registry: RuntimeRegistry,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentsUiState())
    val state: StateFlow<AgentsUiState> = _state.asStateFlow()

    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            storage.installed.collect { models ->
                _state.update { it.copy(installed = models) }
            }
        }
    }

    fun setStepModel(agentId: String, modelId: String?) {
        _state.update { current ->
            current.copy(steps = current.steps.map {
                if (it.agentId == agentId) it.copy(modelId = modelId) else it
            })
        }
    }

    fun setStepSystemPrompt(agentId: String, prompt: String) {
        _state.update { current ->
            current.copy(steps = current.steps.map {
                if (it.agentId == agentId) it.copy(systemPrompt = prompt) else it
            })
        }
    }

    fun setStepMaxTokens(agentId: String, value: Int) {
        _state.update { current ->
            current.copy(steps = current.steps.map {
                if (it.agentId == agentId) it.copy(maxTokens = value.coerceIn(32, 2048)) else it
            })
        }
    }

    fun setStepPromptTemplate(agentId: String, template: String) {
        _state.update { current ->
            current.copy(steps = current.steps.map {
                if (it.agentId == agentId) it.copy(promptTemplate = template) else it
            })
        }
    }

    fun applyPreset(preset: PipelinePreset) {
        if (_state.value.status == RunStatus.Running) return
        val now = System.currentTimeMillis()
        val newSteps = when (preset) {
            PipelinePreset.SINGLE_SHOT -> listOf(
                StepConfig(
                    agentId = "step-$now-1",
                    modelId = null,
                    systemPrompt = "You are a helpful assistant.",
                    promptTemplate = "{input}",
                ),
            )
            PipelinePreset.PLAN_EXECUTE -> listOf(
                StepConfig(
                    agentId = "step-$now-1",
                    modelId = null,
                    systemPrompt = "You are a careful planner. Outline a brief plan for the user's request.",
                    promptTemplate = "{input}",
                ),
                StepConfig(
                    agentId = "step-$now-2",
                    modelId = null,
                    systemPrompt = "You are a polished writer. Take the plan below and turn it into a final answer.",
                    promptTemplate = "Plan:\n{input}\n\nFinal answer:",
                ),
            )
            PipelinePreset.PLAN_EXECUTE_CRITIC -> listOf(
                StepConfig(
                    agentId = "step-$now-1",
                    modelId = null,
                    systemPrompt = "You are a careful planner. Outline a brief plan.",
                    promptTemplate = "{input}",
                ),
                StepConfig(
                    agentId = "step-$now-2",
                    modelId = null,
                    systemPrompt = "You are a polished writer. Turn the plan into a draft answer.",
                    promptTemplate = "Plan:\n{input}\n\nDraft:",
                ),
                StepConfig(
                    agentId = "step-$now-3",
                    modelId = null,
                    systemPrompt = "You are a sharp critic. Identify weaknesses and produce an improved final answer.",
                    promptTemplate = "Draft:\n{input}\n\nImproved final answer:",
                ),
            )
        }
        _state.update { it.copy(steps = newSteps) }
    }

    fun addStep() {
        if (_state.value.status == RunStatus.Running) return
        _state.update { current ->
            val newId = "step-${System.currentTimeMillis()}"
            val newStep = StepConfig(
                agentId = newId,
                modelId = null,
                systemPrompt = "",
                promptTemplate = "{input}",
            )
            current.copy(steps = current.steps + newStep)
        }
    }

    fun removeStep(agentId: String) {
        if (_state.value.status == RunStatus.Running) return
        _state.update { current ->
            if (current.steps.size <= 1) return@update current
            current.copy(steps = current.steps.filterNot { it.agentId == agentId })
        }
    }

    fun moveStepUp(agentId: String) {
        if (_state.value.status == RunStatus.Running) return
        _state.update { current ->
            val index = current.steps.indexOfFirst { it.agentId == agentId }
            if (index <= 0) return@update current
            val mutable = current.steps.toMutableList()
            val tmp = mutable[index - 1]
            mutable[index - 1] = mutable[index]
            mutable[index] = tmp
            current.copy(steps = mutable)
        }
    }

    fun moveStepDown(agentId: String) {
        if (_state.value.status == RunStatus.Running) return
        _state.update { current ->
            val index = current.steps.indexOfFirst { it.agentId == agentId }
            if (index < 0 || index >= current.steps.lastIndex) return@update current
            val mutable = current.steps.toMutableList()
            val tmp = mutable[index + 1]
            mutable[index + 1] = mutable[index]
            mutable[index] = tmp
            current.copy(steps = mutable)
        }
    }

    fun setUserPrompt(text: String) {
        _state.update { it.copy(userPrompt = text) }
    }

    fun setMode(mode: WorkflowMode) {
        if (_state.value.status == RunStatus.Running) return
        _state.update { it.copy(mode = mode, runs = emptyList(), status = RunStatus.Idle) }
    }

    fun setRouterModel(modelId: String?) {
        _state.update { it.copy(router = it.router.copy(routerModelId = modelId)) }
    }

    fun setRouterSystemPrompt(value: String) {
        _state.update { it.copy(router = it.router.copy(routerSystemPrompt = value)) }
    }

    fun setRouteModel(routeAgentId: String, modelId: String?) {
        _state.update { current ->
            val r = current.router
            current.copy(router = r.copy(routes = r.routes.map {
                if (it.agentId == routeAgentId) it.copy(modelId = modelId) else it
            }))
        }
    }

    fun setRouteSystemPrompt(routeAgentId: String, value: String) {
        _state.update { current ->
            val r = current.router
            current.copy(router = r.copy(routes = r.routes.map {
                if (it.agentId == routeAgentId) it.copy(systemPrompt = value) else it
            }))
        }
    }

    fun setRouteKey(routeAgentId: String, value: String) {
        _state.update { current ->
            val r = current.router
            current.copy(router = r.copy(routes = r.routes.map {
                if (it.agentId == routeAgentId) it.copy(key = value) else it
            }))
        }
    }

    fun addRoute() {
        if (_state.value.status == RunStatus.Running) return
        _state.update { current ->
            val r = current.router
            val now = System.currentTimeMillis()
            current.copy(router = r.copy(routes = r.routes + RouteConfig(
                agentId = "route-$now",
                key = "label",
                modelId = null,
                systemPrompt = "",
            )))
        }
    }

    fun removeRoute(routeAgentId: String) {
        if (_state.value.status == RunStatus.Running) return
        _state.update { current ->
            val r = current.router
            if (r.routes.size <= 1) return@update current
            current.copy(router = r.copy(routes = r.routes.filterNot { it.agentId == routeAgentId }))
        }
    }

    fun setParticipantModel(agentId: String, modelId: String?) {
        _state.update { current ->
            val d = current.debate
            current.copy(debate = d.copy(participants = d.participants.map {
                if (it.agentId == agentId) it.copy(modelId = modelId) else it
            }))
        }
    }

    fun setParticipantSystem(agentId: String, value: String) {
        _state.update { current ->
            val d = current.debate
            current.copy(debate = d.copy(participants = d.participants.map {
                if (it.agentId == agentId) it.copy(systemPrompt = value) else it
            }))
        }
    }

    fun addParticipant() {
        if (_state.value.status == RunStatus.Running) return
        _state.update { current ->
            val d = current.debate
            val now = System.currentTimeMillis()
            current.copy(debate = d.copy(participants = d.participants + ParticipantConfig(
                agentId = "participant-$now",
                modelId = null,
                systemPrompt = "",
            )))
        }
    }

    fun removeParticipant(agentId: String) {
        if (_state.value.status == RunStatus.Running) return
        _state.update { current ->
            val d = current.debate
            if (d.participants.size <= 2) return@update current
            current.copy(debate = d.copy(participants = d.participants.filterNot { it.agentId == agentId }))
        }
    }

    fun setModeratorEnabled(enabled: Boolean) {
        _state.update { it.copy(debate = it.debate.copy(moderatorEnabled = enabled)) }
    }

    fun setModeratorModel(modelId: String?) {
        _state.update { it.copy(debate = it.debate.copy(moderatorModelId = modelId)) }
    }

    fun setModeratorSystem(value: String) {
        _state.update { it.copy(debate = it.debate.copy(moderatorSystemPrompt = value)) }
    }

    fun cancel() {
        runJob?.cancel()
        _state.update { it.copy(status = RunStatus.Idle) }
    }

    fun run() {
        val current = _state.value
        if (current.userPrompt.isBlank()) return
        val temperature = settingsStore.defaultTemperature.value
        when (current.mode) {
            WorkflowMode.PIPELINE -> runPipeline(current, temperature)
            WorkflowMode.ROUTER -> runRouter(current, temperature)
            WorkflowMode.DEBATE -> runDebate(current, temperature)
        }
    }

    private fun runDebate(current: AgentsUiState, temperature: Float) {
        val d = current.debate
        val participantPairs = d.participants.mapNotNull { p ->
            val model = current.installed.firstOrNull { it.id == p.modelId } ?: return@mapNotNull null
            p to model
        }
        if (participantPairs.size != d.participants.size) {
            _state.update { it.copy(status = RunStatus.Failed("Pick a model for every participant")) }
            return
        }
        val moderatorModel = if (d.moderatorEnabled) {
            current.installed.firstOrNull { it.id == d.moderatorModelId } ?: run {
                _state.update { it.copy(status = RunStatus.Failed("Pick a moderator model or disable moderator")) }
                return
            }
        } else null

        val sessions = SharedSessionRegistry(registry)
        val agents: MutableMap<String, Agent> = mutableMapOf()
        for ((p, model) in participantPairs) {
            agents[p.agentId] = LlmAgent(
                id = p.agentId,
                displayName = model.displayName,
                model = model,
                registry = registry,
                systemPrompt = p.systemPrompt.takeIf { it.isNotBlank() },
                maxTokens = p.maxTokens,
                temperature = temperature,
                sharedSessions = sessions,
            )
        }
        if (moderatorModel != null) {
            agents[d.moderatorAgentId] = LlmAgent(
                id = d.moderatorAgentId,
                displayName = moderatorModel.displayName,
                model = moderatorModel,
                registry = registry,
                systemPrompt = d.moderatorSystemPrompt.takeIf { it.isNotBlank() },
                maxTokens = d.moderatorMaxTokens,
                temperature = temperature,
                sharedSessions = sessions,
            )
        }

        val workflow = Workflow.Debate(
            id = "debate-${System.currentTimeMillis()}",
            name = "Debate",
            participantAgentIds = d.participants.map { it.agentId },
            moderatorAgentId = if (d.moderatorEnabled) d.moderatorAgentId else null,
            maxRounds = 1,
        )

        val initialRuns = buildList {
            d.participants.forEachIndexed { i, p ->
                add(StepRun(stepId = "p-$i", agentId = p.agentId, modelId = p.modelId))
            }
            if (d.moderatorEnabled) {
                add(StepRun(stepId = "moderator", agentId = d.moderatorAgentId, modelId = d.moderatorModelId))
            }
        }
        _state.update { it.copy(status = RunStatus.Running, runs = initialRuns) }

        startWorkflow(agents, workflow, current.userPrompt, sessions)
    }

    private fun runPipeline(current: AgentsUiState, temperature: Float) {
        val configured = current.steps.mapNotNull { step ->
            val model = current.installed.firstOrNull { it.id == step.modelId } ?: return@mapNotNull null
            step to model
        }
        if (configured.size != current.steps.size) {
            _state.update { it.copy(status = RunStatus.Failed("Pick a model for every step")) }
            return
        }

        val sessions = SharedSessionRegistry(registry)
        val agents: Map<String, Agent> = configured.associate { (step, model) ->
            step.agentId to LlmAgent(
                id = step.agentId,
                displayName = model.displayName,
                model = model,
                registry = registry,
                systemPrompt = step.systemPrompt.takeIf { it.isNotBlank() },
                maxTokens = step.maxTokens,
                temperature = temperature,
                sharedSessions = sessions,
            )
        }

        val workflow = Workflow.Pipeline(
            id = "pipeline-${System.currentTimeMillis()}",
            name = "Pipeline",
            steps = current.steps.map { Workflow.Step(it.agentId, it.agentId, it.promptTemplate) },
        )

        val initialRuns = current.steps.map { step ->
            StepRun(stepId = step.agentId, agentId = step.agentId, modelId = step.modelId)
        }
        _state.update { it.copy(status = RunStatus.Running, runs = initialRuns) }

        startWorkflow(agents, workflow, current.userPrompt, sessions)
    }

    private fun runRouter(current: AgentsUiState, temperature: Float) {
        val router = current.router
        val routerModel = current.installed.firstOrNull { it.id == router.routerModelId }
        if (routerModel == null) {
            _state.update { it.copy(status = RunStatus.Failed("Pick a router model")) }
            return
        }
        val routePairs = router.routes.mapNotNull { route ->
            val model = current.installed.firstOrNull { it.id == route.modelId } ?: return@mapNotNull null
            route to model
        }
        if (routePairs.size != router.routes.size) {
            _state.update { it.copy(status = RunStatus.Failed("Pick a model for every route")) }
            return
        }

        val sessions = SharedSessionRegistry(registry)
        val routerAgent = LlmAgent(
            id = router.routerAgentId,
            displayName = routerModel.displayName,
            model = routerModel,
            registry = registry,
            systemPrompt = router.routerSystemPrompt.takeIf { it.isNotBlank() },
            maxTokens = router.routerMaxTokens,
            temperature = 0.0f,
            sharedSessions = sessions,
        )
        val routeAgents = routePairs.associate { (route, model) ->
            route.agentId to LlmAgent(
                id = route.agentId,
                displayName = model.displayName,
                model = model,
                registry = registry,
                systemPrompt = route.systemPrompt.takeIf { it.isNotBlank() },
                maxTokens = route.maxTokens,
                temperature = temperature,
                sharedSessions = sessions,
            )
        }
        val agents: Map<String, Agent> = routeAgents + (router.routerAgentId to routerAgent)

        val workflow = Workflow.Router(
            id = "router-${System.currentTimeMillis()}",
            name = "Router",
            routerAgentId = router.routerAgentId,
            routes = router.routes.associate { it.key to it.agentId },
        )

        val initialRuns = listOf(
            StepRun(stepId = "router", agentId = router.routerAgentId, modelId = router.routerModelId),
            StepRun(stepId = "routed", agentId = "(pending)", modelId = null),
        )
        _state.update { it.copy(status = RunStatus.Running, runs = initialRuns) }

        startWorkflow(agents, workflow, current.userPrompt, sessions)
    }

    private fun startWorkflow(
        agents: Map<String, Agent>,
        workflow: Workflow,
        input: String,
        sessions: SharedSessionRegistry,
    ) {
        val orchestrator = WorkflowOrchestrator(agents)
        runJob = viewModelScope.launch {
            try {
                orchestrator.execute(workflow, input).collect { event ->
                    handleEvent(event)
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(status = RunStatus.Failed(t.message ?: "workflow failed"))
                }
            } finally {
                // Release every session this workflow loaded — including on
                // job cancellation, errors, and normal completion.
                sessions.releaseAll()
            }
        }
    }

    private fun handleEvent(event: OrchestratorEvent) {
        when (event) {
            is OrchestratorEvent.Started -> Unit
            is OrchestratorEvent.StepStarted -> Unit
            is OrchestratorEvent.StepToken -> _state.update { current ->
                current.copy(runs = current.runs.map {
                    if (it.stepId == event.stepId) it.copy(output = it.output + event.piece) else it
                })
            }
            is OrchestratorEvent.StepCompleted -> _state.update { current ->
                current.copy(runs = current.runs.map {
                    if (it.stepId == event.stepId) it.copy(isComplete = true) else it
                })
            }
            is OrchestratorEvent.Completed -> _state.update { it.copy(status = RunStatus.Done) }
            is OrchestratorEvent.Failed -> _state.update {
                it.copy(status = RunStatus.Failed(event.message))
            }
        }
    }

    fun export(uri: Uri, resolver: ContentResolver) {
        val snapshot = _state.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.bufferedWriter().use { w ->
                            w.append("# Agents pipeline\n\n")
                            w.append("**User prompt:** ").append(snapshot.userPrompt.ifBlank { "(empty)" }).append("\n\n")
                            snapshot.steps.forEachIndexed { i, step ->
                                val run = snapshot.runs.firstOrNull { it.stepId == step.agentId }
                                val model = snapshot.installed.firstOrNull { it.id == step.modelId }
                                w.append("## Step ").append((i + 1).toString())
                                model?.let { w.append(" — ").append(it.displayName) }
                                w.append("\n\n")
                                if (step.systemPrompt.isNotBlank()) {
                                    w.append("**System:** ").append(step.systemPrompt).append("\n\n")
                                }
                                w.append("**Template:** `").append(step.promptTemplate).append("`\n\n")
                                w.append("**Output:**\n\n")
                                w.append(run?.output?.ifBlank { "_(no output)_" } ?: "_(not run)_")
                                w.append("\n\n")
                            }
                        }
                    }
                }
            }
        }
    }
}
