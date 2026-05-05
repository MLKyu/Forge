package com.mingeek.forge.feature.workflows

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.forge.agent.core.Agent
import com.mingeek.forge.agent.core.Tool
import com.mingeek.forge.agent.memory.MemoryEntry
import com.mingeek.forge.agent.memory.MemoryStore
import com.mingeek.forge.agent.orchestrator.OrchestratorEvent
import com.mingeek.forge.agent.tools.CalculatorTool
import com.mingeek.forge.agent.tools.CurrentTimeTool
import com.mingeek.forge.agent.tools.WordCountTool
import com.mingeek.forge.agent.orchestrator.Workflow
import com.mingeek.forge.agent.orchestrator.WorkflowOrchestrator
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import com.mingeek.forge.runtime.registry.RuntimeRegistry
import com.mingeek.forge.runtime.registry.SharedSessionRegistry
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RunStatus {
    data object Idle : RunStatus
    data object Running : RunStatus
    data object Done : RunStatus
    /**
     * @param messageRes localized user-facing reason
     * @param details optional untranslated technical detail (exception message)
     */
    data class Failed(@StringRes val messageRes: Int, val details: String? = null) : RunStatus
}

@JsonClass(generateAdapter = true)
data class StepConfig(
    val agentId: String,
    val modelId: String?,
    val systemPrompt: String,
    val promptTemplate: String,
    val maxTokens: Int = 384,
)

data class StepToolCallRecord(
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String? = null,
    val isError: Boolean = false,
)

data class StepRun(
    val stepId: String,
    val agentId: String,
    val modelId: String?,
    val output: String = "",
    val isComplete: Boolean = false,
    val toolCalls: List<StepToolCallRecord> = emptyList(),
)

enum class PipelinePreset(@StringRes val displayNameRes: Int) {
    SINGLE_SHOT(R.string.workflows_preset_single_shot),
    PLAN_EXECUTE(R.string.workflows_preset_plan_execute),
    PLAN_EXECUTE_CRITIC(R.string.workflows_preset_plan_execute_critic),
}

enum class WorkflowMode(@StringRes val displayNameRes: Int) {
    PIPELINE(R.string.workflows_mode_pipeline),
    ROUTER(R.string.workflows_mode_router),
    DEBATE(R.string.workflows_mode_debate),
    PLAN_EXECUTE(R.string.workflows_mode_plan_execute),
}

@JsonClass(generateAdapter = true)
data class PlanExecuteConfig(
    val plannerAgentId: String = "planner",
    val plannerModelId: String? = null,
    val plannerSystemPrompt: String =
        "You are a careful planner. Outline a step-by-step plan that solves the user's request.",
    val plannerMaxTokens: Int = 384,
    val executorAgentId: String = "executor",
    val executorModelId: String? = null,
    val executorSystemPrompt: String =
        "You are a polished writer. Carry out the supplied plan and produce the answer.",
    val executorMaxTokens: Int = 512,
    val criticEnabled: Boolean = false,
    val criticAgentId: String = "critic",
    val criticModelId: String? = null,
    val criticSystemPrompt: String =
        "You are a sharp critic. Identify weaknesses in the draft and produce an improved final answer.",
    val criticMaxTokens: Int = 512,
)

@JsonClass(generateAdapter = true)
data class ParticipantConfig(
    val agentId: String,
    val modelId: String?,
    val systemPrompt: String,
    val maxTokens: Int = 384,
)

@JsonClass(generateAdapter = true)
data class DebateConfig(
    val participants: List<ParticipantConfig> = listOf(
        ParticipantConfig("participant-pro", null,
            "You are an optimist. Argue persuasively in favour of the user's proposition or scenario."),
        ParticipantConfig("participant-con", null,
            "You are a skeptic. Identify weaknesses, risks, and counterarguments."),
    ),
    val maxRounds: Int = 1,
    val moderatorEnabled: Boolean = true,
    val moderatorAgentId: String = "moderator",
    val moderatorModelId: String? = null,
    val moderatorSystemPrompt: String =
        "You are a fair moderator. Synthesize the strongest points from each participant into a balanced final answer.",
    val moderatorMaxTokens: Int = 512,
)

@JsonClass(generateAdapter = true)
data class RouteConfig(
    val agentId: String,
    val key: String,            // matched in router output (case-insensitive substring)
    val modelId: String?,
    val systemPrompt: String,
    val maxTokens: Int = 384,
)

@JsonClass(generateAdapter = true)
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

/**
 * A named snapshot of every workflow mode's config. Stored as a JSON list in
 * SettingsStore so the user can shuttle between named setups ("RAG-style
 * pipeline", "code router", "research debate") without re-entering prompts.
 *
 * Only the active mode's config is *required* to be meaningful — the others
 * carry the values that were live when the preset was saved, so loading a
 * preset that targeted PIPELINE still leaves your last DEBATE setup intact
 * if you switch modes after applying it.
 */
@JsonClass(generateAdapter = true)
data class WorkflowPreset(
    val id: String,
    val name: String,
    val mode: WorkflowMode,
    val steps: List<StepConfig>,
    val router: RouterConfig,
    val debate: DebateConfig,
    val planExecute: PlanExecuteConfig,
    val createdAtEpochSec: Long,
)

data class PastRun(
    val id: String,
    val mode: WorkflowMode,
    val userPrompt: String,
    val finalOutput: String,
    val createdAtEpochSec: Long,
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
    val planExecute: PlanExecuteConfig = PlanExecuteConfig(),
    val presets: List<WorkflowPreset> = emptyList(),
    val userPrompt: String = "",
    val status: RunStatus = RunStatus.Idle,
    val runs: List<StepRun> = emptyList(),
    val pastRuns: List<PastRun> = emptyList(),
)

class WorkflowsViewModel(
    private val storage: ModelStorage,
    private val registry: RuntimeRegistry,
    private val settingsStore: SettingsStore,
    private val runHistory: MemoryStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentsUiState())
    val state: StateFlow<AgentsUiState> = _state.asStateFlow()

    private var runJob: Job? = null

    private val moshi: Moshi = Moshi.Builder().build()
    private val stepsAdapter: JsonAdapter<List<StepConfig>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, StepConfig::class.java),
    )
    private val routerAdapter: JsonAdapter<RouterConfig> = moshi.adapter(RouterConfig::class.java)
    private val debateAdapter: JsonAdapter<DebateConfig> = moshi.adapter(DebateConfig::class.java)
    private val planExecuteAdapter: JsonAdapter<PlanExecuteConfig> =
        moshi.adapter(PlanExecuteConfig::class.java)
    private val presetsAdapter: JsonAdapter<List<WorkflowPreset>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, WorkflowPreset::class.java),
    )

    init {
        viewModelScope.launch {
            storage.installed.collect { models ->
                _state.update { it.copy(installed = models) }
            }
        }
        // Restore saved configs first, then start the auto-save observer so we
        // don't immediately re-write what we just loaded.
        viewModelScope.launch {
            restorePersistedConfig()
            refreshPastRuns()
            observePersistableChanges()
        }
    }

    private suspend fun refreshPastRuns() {
        val entries = runHistory.query(text = "", limit = 20, tags = setOf("agents-run"))
        val mapped = entries.mapNotNull { entry ->
            val modeName = entry.metadata["mode"] ?: return@mapNotNull null
            val mode = runCatching { WorkflowMode.valueOf(modeName) }.getOrNull() ?: return@mapNotNull null
            PastRun(
                id = entry.id,
                mode = mode,
                userPrompt = entry.metadata["prompt"] ?: "",
                finalOutput = entry.content,
                createdAtEpochSec = entry.createdAtEpochSec,
            )
        }
        _state.update { it.copy(pastRuns = mapped) }
    }

    fun deletePastRun(id: String) {
        viewModelScope.launch {
            runHistory.remove(id)
            refreshPastRuns()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun observePersistableChanges() {
        _state
            .map {
                PersistableSnapshot(
                    mode = it.mode,
                    steps = it.steps,
                    router = it.router,
                    debate = it.debate,
                    planExecute = it.planExecute,
                    presets = it.presets,
                )
            }
            .distinctUntilChanged()
            .drop(1)
            .debounce(300)
            .collect { persist(it) }
    }

    private data class PersistableSnapshot(
        val mode: WorkflowMode,
        val steps: List<StepConfig>,
        val router: RouterConfig,
        val debate: DebateConfig,
        val planExecute: PlanExecuteConfig,
        val presets: List<WorkflowPreset>,
    )

    private suspend fun restorePersistedConfig() {
        val savedMode = settingsStore.workflowsMode.first()
        val savedSteps = settingsStore.workflowsPipelineJson.first()
            ?.let { runCatching { stepsAdapter.fromJson(it) }.getOrNull() }
        val savedRouter = settingsStore.workflowsRouterJson.first()
            ?.let { runCatching { routerAdapter.fromJson(it) }.getOrNull() }
        val savedDebate = settingsStore.workflowsDebateJson.first()
            ?.let { runCatching { debateAdapter.fromJson(it) }.getOrNull() }
        val savedPlanExecute = settingsStore.workflowsPlanExecuteJson.first()
            ?.let { runCatching { planExecuteAdapter.fromJson(it) }.getOrNull() }
        val savedPresets = settingsStore.workflowsPresetsJson.first()
            ?.let { runCatching { presetsAdapter.fromJson(it) }.getOrNull() }

        _state.update { current ->
            current.copy(
                mode = savedMode?.let { runCatching { WorkflowMode.valueOf(it) }.getOrNull() } ?: current.mode,
                steps = savedSteps?.takeIf { it.isNotEmpty() } ?: current.steps,
                router = savedRouter ?: current.router,
                debate = savedDebate ?: current.debate,
                planExecute = savedPlanExecute ?: current.planExecute,
                presets = savedPresets ?: current.presets,
            )
        }
    }

    private suspend fun persist(snap: PersistableSnapshot) {
        settingsStore.setWorkflowsMode(snap.mode.name)
        settingsStore.setWorkflowsPipelineJson(stepsAdapter.toJson(snap.steps))
        settingsStore.setWorkflowsRouterJson(routerAdapter.toJson(snap.router))
        settingsStore.setWorkflowsDebateJson(debateAdapter.toJson(snap.debate))
        settingsStore.setWorkflowsPlanExecuteJson(planExecuteAdapter.toJson(snap.planExecute))
        settingsStore.setWorkflowsPresetsJson(presetsAdapter.toJson(snap.presets))
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

    fun setDebateMaxRounds(value: Int) {
        if (_state.value.status == RunStatus.Running) return
        _state.update {
            it.copy(debate = it.debate.copy(maxRounds = value.coerceIn(1, 3)))
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

    // ---- PlanExecute ----------------------------------------------------------

    fun setPlannerModel(modelId: String?) {
        _state.update { it.copy(planExecute = it.planExecute.copy(plannerModelId = modelId)) }
    }

    fun setPlannerSystem(value: String) {
        _state.update { it.copy(planExecute = it.planExecute.copy(plannerSystemPrompt = value)) }
    }

    fun setExecutorModel(modelId: String?) {
        _state.update { it.copy(planExecute = it.planExecute.copy(executorModelId = modelId)) }
    }

    fun setExecutorSystem(value: String) {
        _state.update { it.copy(planExecute = it.planExecute.copy(executorSystemPrompt = value)) }
    }

    fun setCriticEnabled(enabled: Boolean) {
        _state.update { it.copy(planExecute = it.planExecute.copy(criticEnabled = enabled)) }
    }

    fun setCriticModel(modelId: String?) {
        _state.update { it.copy(planExecute = it.planExecute.copy(criticModelId = modelId)) }
    }

    fun setCriticSystem(value: String) {
        _state.update { it.copy(planExecute = it.planExecute.copy(criticSystemPrompt = value)) }
    }

    // ---- Workflow presets -----------------------------------------------------

    fun saveCurrentAsPreset(name: String) {
        if (name.isBlank() || _state.value.status == RunStatus.Running) return
        _state.update { current ->
            val preset = WorkflowPreset(
                id = "preset-${System.currentTimeMillis()}",
                name = name.trim().take(60),
                mode = current.mode,
                steps = current.steps,
                router = current.router,
                debate = current.debate,
                planExecute = current.planExecute,
                createdAtEpochSec = System.currentTimeMillis() / 1000,
            )
            current.copy(presets = current.presets + preset)
        }
    }

    fun applyPreset(id: String) {
        if (_state.value.status == RunStatus.Running) return
        val preset = _state.value.presets.firstOrNull { it.id == id } ?: return
        _state.update { current ->
            current.copy(
                mode = preset.mode,
                steps = preset.steps,
                router = preset.router,
                debate = preset.debate,
                planExecute = preset.planExecute,
                runs = emptyList(),
                status = RunStatus.Idle,
            )
        }
    }

    fun deletePreset(id: String) {
        _state.update { it.copy(presets = it.presets.filterNot { p -> p.id == id }) }
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
            WorkflowMode.PLAN_EXECUTE -> runPlanExecuteMode(current, temperature)
        }
    }

    private fun runDebate(current: AgentsUiState, temperature: Float) {
        val d = current.debate
        val participantPairs = d.participants.mapNotNull { p ->
            val model = current.installed.firstOrNull { it.id == p.modelId } ?: return@mapNotNull null
            p to model
        }
        if (participantPairs.size != d.participants.size) {
            _state.update { it.copy(status = RunStatus.Failed(R.string.workflows_error_pick_model_per_participant)) }
            return
        }
        val moderatorModel = if (d.moderatorEnabled) {
            current.installed.firstOrNull { it.id == d.moderatorModelId } ?: run {
                _state.update { it.copy(status = RunStatus.Failed(R.string.workflows_error_pick_moderator)) }
                return
            }
        } else null

        val sessions = SharedSessionRegistry(registry, onLoaded = { storage.markUsed(it) })
        val tools = activeTools()
        val maxIter = settingsStore.toolMaxIterations.value
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
                tools = tools,
                maxToolIterations = maxIter,
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
                tools = tools,
                maxToolIterations = maxIter,
            )
        }

        val workflow = Workflow.Debate(
            id = "debate-${System.currentTimeMillis()}",
            name = "Debate",
            participantAgentIds = d.participants.map { it.agentId },
            moderatorAgentId = if (d.moderatorEnabled) d.moderatorAgentId else null,
            maxRounds = d.maxRounds.coerceAtLeast(1),
        )

        val rounds = d.maxRounds.coerceAtLeast(1)
        val initialRuns = buildList {
            for (round in 1..rounds) {
                d.participants.forEachIndexed { i, p ->
                    add(
                        StepRun(
                            stepId = stepIdForParticipant(round, i),
                            agentId = p.agentId,
                            modelId = p.modelId,
                        )
                    )
                }
            }
            if (d.moderatorEnabled) {
                add(StepRun(stepId = "moderator", agentId = d.moderatorAgentId, modelId = d.moderatorModelId))
            }
        }
        _state.update { it.copy(status = RunStatus.Running, runs = initialRuns) }

        startWorkflow(agents, workflow, current.userPrompt, sessions)
    }

    private fun runPlanExecuteMode(current: AgentsUiState, temperature: Float) {
        val pe = current.planExecute
        val plannerModel = current.installed.firstOrNull { it.id == pe.plannerModelId }
        val executorModel = current.installed.firstOrNull { it.id == pe.executorModelId }
        if (plannerModel == null || executorModel == null) {
            _state.update { it.copy(status = RunStatus.Failed(R.string.workflows_error_pick_planner_executor)) }
            return
        }
        val criticModel = if (pe.criticEnabled) {
            current.installed.firstOrNull { it.id == pe.criticModelId } ?: run {
                _state.update { it.copy(status = RunStatus.Failed(R.string.workflows_error_pick_critic)) }
                return
            }
        } else null

        val sessions = SharedSessionRegistry(registry, onLoaded = { storage.markUsed(it) })
        val tools = activeTools()
        val maxIter = settingsStore.toolMaxIterations.value
        val agents: MutableMap<String, Agent> = mutableMapOf()
        agents[pe.plannerAgentId] = LlmAgent(
            id = pe.plannerAgentId,
            displayName = plannerModel.displayName,
            model = plannerModel,
            registry = registry,
            systemPrompt = pe.plannerSystemPrompt.takeIf { it.isNotBlank() },
            maxTokens = pe.plannerMaxTokens,
            temperature = temperature,
            sharedSessions = sessions,
            tools = tools,
            maxToolIterations = maxIter,
        )
        agents[pe.executorAgentId] = LlmAgent(
            id = pe.executorAgentId,
            displayName = executorModel.displayName,
            model = executorModel,
            registry = registry,
            systemPrompt = pe.executorSystemPrompt.takeIf { it.isNotBlank() },
            maxTokens = pe.executorMaxTokens,
            temperature = temperature,
            sharedSessions = sessions,
            tools = tools,
            maxToolIterations = maxIter,
        )
        if (criticModel != null) {
            agents[pe.criticAgentId] = LlmAgent(
                id = pe.criticAgentId,
                displayName = criticModel.displayName,
                model = criticModel,
                registry = registry,
                systemPrompt = pe.criticSystemPrompt.takeIf { it.isNotBlank() },
                maxTokens = pe.criticMaxTokens,
                temperature = temperature,
                sharedSessions = sessions,
                tools = tools,
                maxToolIterations = maxIter,
            )
        }

        val workflow = Workflow.PlanExecute(
            id = "plan-execute-${System.currentTimeMillis()}",
            name = "Plan-Execute",
            plannerAgentId = pe.plannerAgentId,
            executorAgentId = pe.executorAgentId,
            criticAgentId = if (pe.criticEnabled) pe.criticAgentId else null,
        )

        val initialRuns = buildList {
            add(StepRun(stepId = "planner", agentId = pe.plannerAgentId, modelId = pe.plannerModelId))
            add(StepRun(stepId = "executor", agentId = pe.executorAgentId, modelId = pe.executorModelId))
            if (pe.criticEnabled) {
                add(StepRun(stepId = "critic", agentId = pe.criticAgentId, modelId = pe.criticModelId))
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
            _state.update { it.copy(status = RunStatus.Failed(R.string.workflows_error_pick_model_per_step)) }
            return
        }

        val sessions = SharedSessionRegistry(registry, onLoaded = { storage.markUsed(it) })
        val tools = activeTools()
        val maxIter = settingsStore.toolMaxIterations.value
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
                tools = tools,
                maxToolIterations = maxIter,
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
            _state.update { it.copy(status = RunStatus.Failed(R.string.workflows_error_pick_router)) }
            return
        }
        val routePairs = router.routes.mapNotNull { route ->
            val model = current.installed.firstOrNull { it.id == route.modelId } ?: return@mapNotNull null
            route to model
        }
        if (routePairs.size != router.routes.size) {
            _state.update { it.copy(status = RunStatus.Failed(R.string.workflows_error_pick_model_per_route)) }
            return
        }

        val sessions = SharedSessionRegistry(registry, onLoaded = { storage.markUsed(it) })
        val tools = activeTools()
        val maxIter = settingsStore.toolMaxIterations.value
        val routerAgent = LlmAgent(
            id = router.routerAgentId,
            displayName = routerModel.displayName,
            model = routerModel,
            registry = registry,
            systemPrompt = router.routerSystemPrompt.takeIf { it.isNotBlank() },
            maxTokens = router.routerMaxTokens,
            temperature = 0.0f,
            sharedSessions = sessions,
            // Router emits a single classification label — no tools or it
            // confuses the parser.
            tools = emptyList(),
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
                tools = tools,
                maxToolIterations = maxIter,
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
                    it.copy(status = RunStatus.Failed(R.string.workflows_error_workflow_failed, t.message))
                }
            } finally {
                // Release every session this workflow loaded — including on
                // job cancellation, errors, and normal completion.
                sessions.releaseAll()
            }
        }
    }

    /** Same encoding the orchestrator uses, kept in sync. */
    private fun stepIdForParticipant(round: Int, participantIndex: Int): String =
        if (round == 1) "p-$participantIndex" else "p-$participantIndex-r$round"

    private fun handleEvent(event: OrchestratorEvent) {
        when (event) {
            is OrchestratorEvent.Started -> Unit
            is OrchestratorEvent.StepStarted -> Unit
            is OrchestratorEvent.StepToken -> _state.update { current ->
                current.copy(runs = current.runs.map {
                    if (it.stepId == event.stepId) it.copy(output = it.output + event.piece) else it
                })
            }
            is OrchestratorEvent.StepToolCall -> _state.update { current ->
                current.copy(runs = current.runs.map { run ->
                    if (run.stepId != event.stepId) run
                    else run.copy(
                        // The streamed call text isn't a real answer; clear it
                        // before the next iteration writes the actual response.
                        output = "",
                        toolCalls = run.toolCalls + StepToolCallRecord(
                            toolName = event.toolName,
                            argumentsJson = event.argumentsJson,
                        ),
                    )
                })
            }
            is OrchestratorEvent.StepToolResult -> _state.update { current ->
                current.copy(runs = current.runs.map { run ->
                    if (run.stepId != event.stepId) run
                    else run.copy(
                        toolCalls = run.toolCalls.toMutableList().also { list ->
                            // Resolve the most recent unresolved call with the same tool name.
                            val idx = list.indexOfLast { it.toolName == event.toolName && it.resultJson == null }
                            if (idx >= 0) {
                                list[idx] = list[idx].copy(
                                    resultJson = event.resultJson,
                                    isError = event.isError,
                                )
                            }
                        },
                    )
                })
            }
            is OrchestratorEvent.StepCompleted -> _state.update { current ->
                current.copy(runs = current.runs.map {
                    if (it.stepId == event.stepId) it.copy(isComplete = true) else it
                })
            }
            is OrchestratorEvent.Completed -> {
                _state.update { it.copy(status = RunStatus.Done) }
                viewModelScope.launch { recordRun(event.finalOutput) }
            }
            is OrchestratorEvent.Failed -> _state.update {
                it.copy(status = RunStatus.Failed(R.string.workflows_error_workflow_failed, event.message))
            }
        }
    }

    private suspend fun recordRun(finalOutput: String) {
        val snapshot = _state.value
        val entry = MemoryEntry(
            id = "agents-run:${System.currentTimeMillis()}",
            content = finalOutput,
            createdAtEpochSec = System.currentTimeMillis() / 1000,
            tags = setOf("agents-run", "agents-run:${snapshot.mode.name.lowercase()}"),
            metadata = mapOf(
                "mode" to snapshot.mode.name,
                "prompt" to snapshot.userPrompt,
            ),
        )
        runHistory.put(entry)
        refreshPastRuns()
    }

    private fun activeTools(): List<Tool> =
        if (settingsStore.toolsEnabled.value) DEFAULT_TOOLS else emptyList()

    private companion object {
        val DEFAULT_TOOLS: List<Tool> = listOf(CalculatorTool(), CurrentTimeTool(), WordCountTool())

        /** English mode label for markdown export (universal export documents). */
        fun WorkflowMode.exportName(): String = when (this) {
            WorkflowMode.PIPELINE -> "Pipeline"
            WorkflowMode.ROUTER -> "Router"
            WorkflowMode.DEBATE -> "Debate"
            WorkflowMode.PLAN_EXECUTE -> "Plan-Execute"
        }
    }

    fun export(uri: Uri, resolver: ContentResolver) {
        val snapshot = _state.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.bufferedWriter().use { w ->
                            w.append("# Agents — ").append(snapshot.mode.exportName()).append("\n\n")
                            w.append("**User prompt:** ").append(snapshot.userPrompt.ifBlank { "(empty)" }).append("\n\n")
                            when (snapshot.mode) {
                                WorkflowMode.PIPELINE -> writePipelineExport(w, snapshot)
                                WorkflowMode.ROUTER -> writeRouterExport(w, snapshot)
                                WorkflowMode.DEBATE -> writeDebateExport(w, snapshot)
                                WorkflowMode.PLAN_EXECUTE -> writePlanExecuteExport(w, snapshot)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun writePipelineExport(w: java.io.BufferedWriter, s: AgentsUiState) {
        s.steps.forEachIndexed { i, step ->
            val run = s.runs.firstOrNull { it.stepId == step.agentId }
            val model = s.installed.firstOrNull { it.id == step.modelId }
            w.append("## Step ").append((i + 1).toString())
            model?.let { w.append(" — ").append(it.displayName) }
            w.append("\n\n")
            if (step.systemPrompt.isNotBlank()) {
                w.append("**System:** ").append(step.systemPrompt).append("\n\n")
            }
            w.append("**Template:** `").append(step.promptTemplate).append("`\n\n")
            writeToolCalls(w, run)
            w.append("**Output:**\n\n")
            w.append(run?.output?.ifBlank { "_(no output)_" } ?: "_(not run)_")
            w.append("\n\n")
        }
    }

    private fun writeRouterExport(w: java.io.BufferedWriter, s: AgentsUiState) {
        val r = s.router
        val routerModel = s.installed.firstOrNull { it.id == r.routerModelId }
        w.append("## Router")
        routerModel?.let { w.append(" — ").append(it.displayName) }
        w.append("\n\n")
        if (r.routerSystemPrompt.isNotBlank()) {
            w.append("**System:** ").append(r.routerSystemPrompt).append("\n\n")
        }
        val routerRun = s.runs.firstOrNull { it.stepId == "router" }
        writeToolCalls(w, routerRun)
        w.append("**Classification:**\n\n")
        w.append(routerRun?.output?.ifBlank { "_(no output)_" } ?: "_(not run)_").append("\n\n")
        w.append("**Routes:**\n\n")
        for (route in r.routes) {
            val routeModel = s.installed.firstOrNull { it.id == route.modelId }
            w.append("- `").append(route.key).append("` → ").append(route.agentId)
            routeModel?.let { w.append(" (").append(it.displayName).append(")") }
            w.append("\n")
        }
        w.append("\n## Routed answer\n\n")
        val routed = s.runs.firstOrNull { it.stepId == "routed" }
        writeToolCalls(w, routed)
        w.append(routed?.output?.ifBlank { "_(no output)_" } ?: "_(not run)_").append("\n\n")
    }

    private fun writePlanExecuteExport(w: java.io.BufferedWriter, s: AgentsUiState) {
        val pe = s.planExecute
        val plannerModel = s.installed.firstOrNull { it.id == pe.plannerModelId }
        val executorModel = s.installed.firstOrNull { it.id == pe.executorModelId }
        val criticModel = s.installed.firstOrNull { it.id == pe.criticModelId }

        w.append("## Planner")
        plannerModel?.let { w.append(" — ").append(it.displayName) }
        w.append("\n\n")
        if (pe.plannerSystemPrompt.isNotBlank()) {
            w.append("**System:** ").append(pe.plannerSystemPrompt).append("\n\n")
        }
        val plannerRun = s.runs.firstOrNull { it.stepId == "planner" }
        writeToolCalls(w, plannerRun)
        w.append(plannerRun?.output?.ifBlank { "_(no output)_" } ?: "_(not run)_").append("\n\n")

        w.append("## Executor")
        executorModel?.let { w.append(" — ").append(it.displayName) }
        w.append("\n\n")
        if (pe.executorSystemPrompt.isNotBlank()) {
            w.append("**System:** ").append(pe.executorSystemPrompt).append("\n\n")
        }
        val executorRun = s.runs.firstOrNull { it.stepId == "executor" }
        writeToolCalls(w, executorRun)
        w.append(executorRun?.output?.ifBlank { "_(no output)_" } ?: "_(not run)_").append("\n\n")

        if (pe.criticEnabled) {
            w.append("## Critic")
            criticModel?.let { w.append(" — ").append(it.displayName) }
            w.append("\n\n")
            if (pe.criticSystemPrompt.isNotBlank()) {
                w.append("**System:** ").append(pe.criticSystemPrompt).append("\n\n")
            }
            val criticRun = s.runs.firstOrNull { it.stepId == "critic" }
            writeToolCalls(w, criticRun)
            w.append(criticRun?.output?.ifBlank { "_(no output)_" } ?: "_(not run)_").append("\n\n")
        }
    }

    private fun writeDebateExport(w: java.io.BufferedWriter, s: AgentsUiState) {
        val d = s.debate
        val rounds = d.maxRounds.coerceAtLeast(1)
        val isMultiRound = rounds > 1
        for (round in 1..rounds) {
            if (isMultiRound) w.append("## Round ").append(round.toString()).append("\n\n")
            d.participants.forEachIndexed { i, p ->
                val stepId = if (round == 1) "p-$i" else "p-$i-r$round"
                val run = s.runs.firstOrNull { it.stepId == stepId }
                val model = s.installed.firstOrNull { it.id == p.modelId }
                val header = if (isMultiRound) "### Participant " else "## Participant "
                w.append(header).append((i + 1).toString())
                model?.let { w.append(" — ").append(it.displayName) }
                w.append("\n\n")
                if (round == 1 && p.systemPrompt.isNotBlank()) {
                    w.append("**System:** ").append(p.systemPrompt).append("\n\n")
                }
                writeToolCalls(w, run)
                w.append(run?.output?.ifBlank { "_(no output)_" } ?: "_(not run)_").append("\n\n")
            }
        }
        if (d.moderatorEnabled) {
            val modModel = s.installed.firstOrNull { it.id == d.moderatorModelId }
            w.append("## Moderator")
            modModel?.let { w.append(" — ").append(it.displayName) }
            w.append("\n\n")
            if (d.moderatorSystemPrompt.isNotBlank()) {
                w.append("**System:** ").append(d.moderatorSystemPrompt).append("\n\n")
            }
            val modRun = s.runs.firstOrNull { it.stepId == "moderator" }
            writeToolCalls(w, modRun)
            w.append(modRun?.output?.ifBlank { "_(no output)_" } ?: "_(not run)_").append("\n\n")
        }
    }

    private fun writeToolCalls(w: java.io.BufferedWriter, run: StepRun?) {
        val calls = run?.toolCalls.orEmpty()
        if (calls.isEmpty()) return
        w.append("**Tool calls:**\n\n")
        for (call in calls) {
            w.append("- `").append(call.toolName).append("` ").append(call.argumentsJson)
            val result = call.resultJson
            if (result != null) {
                val tag = if (call.isError) "error" else "result"
                w.append(" → _").append(tag).append(":_ ").append(result)
            }
            w.append("\n")
        }
        w.append("\n")
    }
}
