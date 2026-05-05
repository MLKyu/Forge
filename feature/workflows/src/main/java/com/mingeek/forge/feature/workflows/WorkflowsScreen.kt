package com.mingeek.forge.feature.workflows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.feature.workflows.R

@Composable
fun WorkflowsScreen(
    viewModel: WorkflowsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val clipboardManager = LocalClipboardManager.current
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri != null) viewModel.export(uri, resolver)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when (state.mode) {
                WorkflowMode.PIPELINE -> {
                    val n = state.steps.size
                    if (n == 1) stringResource(R.string.workflows_header_pipeline_one, n)
                    else stringResource(R.string.workflows_header_pipeline_many, n)
                }
                WorkflowMode.ROUTER -> {
                    val n = state.router.routes.size
                    if (n == 1) stringResource(R.string.workflows_header_router_one, n)
                    else stringResource(R.string.workflows_header_router_many, n)
                }
                WorkflowMode.DEBATE -> {
                    val n = state.debate.participants.size
                    if (state.debate.moderatorEnabled)
                        stringResource(R.string.workflows_header_debate_with_moderator, n)
                    else stringResource(R.string.workflows_header_debate, n)
                }
                WorkflowMode.PLAN_EXECUTE ->
                    if (state.planExecute.criticEnabled)
                        stringResource(R.string.workflows_header_plan_execute_with_critic)
                    else stringResource(R.string.workflows_header_plan_execute)
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            when (state.mode) {
                WorkflowMode.PIPELINE -> stringResource(R.string.workflows_subtitle_pipeline)
                WorkflowMode.ROUTER -> stringResource(R.string.workflows_subtitle_router)
                WorkflowMode.DEBATE -> stringResource(R.string.workflows_subtitle_debate)
                WorkflowMode.PLAN_EXECUTE -> stringResource(R.string.workflows_subtitle_plan_execute)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )

        if (state.installed.isEmpty()) {
            Text(
                stringResource(R.string.workflows_no_models_installed),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text(stringResource(R.string.workflows_label_mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (mode in WorkflowMode.entries) {
                androidx.compose.material3.FilterChip(
                    selected = state.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    label = { Text(stringResource(mode.displayNameRes)) },
                )
            }
        }

        // ---- Workflow presets bar (named saves across modes) ----
        WorkflowPresetsBar(
            presets = state.presets,
            running = state.status == RunStatus.Running,
            onApply = viewModel::applyPreset,
            onSave = viewModel::saveCurrentAsPreset,
            onDelete = viewModel::deletePreset,
        )

        if (state.mode == WorkflowMode.PIPELINE) {
            Text(stringResource(R.string.workflows_label_presets), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (preset in PipelinePreset.entries) {
                    AssistChip(
                        onClick = { viewModel.applyPreset(preset) },
                        label = { Text(stringResource(preset.displayNameRes)) },
                    )
                }
            }

            state.steps.forEachIndexed { index, step ->
                StepCard(
                    index = index,
                    step = step,
                    installed = state.installed,
                    canRemove = state.steps.size > 1,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.steps.lastIndex,
                    onPickModel = { viewModel.setStepModel(step.agentId, it.id) },
                    onSystemChange = { viewModel.setStepSystemPrompt(step.agentId, it) },
                    onTemplateChange = { viewModel.setStepPromptTemplate(step.agentId, it) },
                    onMaxTokensChange = { viewModel.setStepMaxTokens(step.agentId, it) },
                    onRemove = { viewModel.removeStep(step.agentId) },
                    onMoveUp = { viewModel.moveStepUp(step.agentId) },
                    onMoveDown = { viewModel.moveStepDown(step.agentId) },
                )
            }

            OutlinedButton(
                onClick = viewModel::addStep,
                enabled = state.status != RunStatus.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.workflows_add_step))
            }
        } else if (state.mode == WorkflowMode.ROUTER) {
            RouterCard(
                router = state.router,
                installed = state.installed,
                onPickRouterModel = { viewModel.setRouterModel(it.id) },
                onRouterSystemChange = viewModel::setRouterSystemPrompt,
            )
            state.router.routes.forEach { route ->
                RouteCard(
                    route = route,
                    installed = state.installed,
                    canRemove = state.router.routes.size > 1,
                    onPickModel = { viewModel.setRouteModel(route.agentId, it.id) },
                    onKeyChange = { viewModel.setRouteKey(route.agentId, it) },
                    onSystemChange = { viewModel.setRouteSystemPrompt(route.agentId, it) },
                    onRemove = { viewModel.removeRoute(route.agentId) },
                )
            }
            OutlinedButton(
                onClick = viewModel::addRoute,
                enabled = state.status != RunStatus.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.workflows_add_route))
            }
        } else if (state.mode == WorkflowMode.DEBATE) {
            state.debate.participants.forEachIndexed { index, p ->
                ParticipantCard(
                    index = index,
                    participant = p,
                    installed = state.installed,
                    canRemove = state.debate.participants.size > 2,
                    onPickModel = { viewModel.setParticipantModel(p.agentId, it.id) },
                    onSystemChange = { viewModel.setParticipantSystem(p.agentId, it) },
                    onRemove = { viewModel.removeParticipant(p.agentId) },
                )
            }
            OutlinedButton(
                onClick = viewModel::addParticipant,
                enabled = state.status != RunStatus.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.workflows_add_participant))
            }
            DebateRoundsCard(
                rounds = state.debate.maxRounds,
                onRoundsChange = viewModel::setDebateMaxRounds,
            )
            ModeratorCard(
                debate = state.debate,
                installed = state.installed,
                onToggle = viewModel::setModeratorEnabled,
                onPickModel = { viewModel.setModeratorModel(it.id) },
                onSystemChange = viewModel::setModeratorSystem,
            )
        } else {
            // PLAN_EXECUTE
            PlanExecuteCard(
                pe = state.planExecute,
                installed = state.installed,
                onPickPlanner = { viewModel.setPlannerModel(it.id) },
                onPlannerSystem = viewModel::setPlannerSystem,
                onPickExecutor = { viewModel.setExecutorModel(it.id) },
                onExecutorSystem = viewModel::setExecutorSystem,
                onCriticToggle = viewModel::setCriticEnabled,
                onPickCritic = { viewModel.setCriticModel(it.id) },
                onCriticSystem = viewModel::setCriticSystem,
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.workflows_user_prompt), fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = state.userPrompt,
                    onValueChange = viewModel::setUserPrompt,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                    enabled = state.status != RunStatus.Running,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.status == RunStatus.Running) {
                        OutlinedButton(onClick = viewModel::cancel) { Text(stringResource(R.string.workflows_button_stop)) }
                    } else {
                        val readyToRun = state.userPrompt.isNotBlank() && when (state.mode) {
                            WorkflowMode.PIPELINE -> state.steps.all { it.modelId != null }
                            WorkflowMode.ROUTER -> state.router.routerModelId != null &&
                                state.router.routes.all { it.modelId != null }
                            WorkflowMode.DEBATE -> state.debate.participants.all { it.modelId != null } &&
                                (!state.debate.moderatorEnabled || state.debate.moderatorModelId != null)
                            WorkflowMode.PLAN_EXECUTE -> state.planExecute.plannerModelId != null &&
                                state.planExecute.executorModelId != null &&
                                (!state.planExecute.criticEnabled || state.planExecute.criticModelId != null)
                        }
                        val runLabel = when (state.mode) {
                            WorkflowMode.PIPELINE -> stringResource(R.string.workflows_button_run_pipeline)
                            WorkflowMode.ROUTER -> stringResource(R.string.workflows_button_run_router)
                            WorkflowMode.DEBATE -> stringResource(R.string.workflows_button_run_debate)
                            WorkflowMode.PLAN_EXECUTE -> stringResource(R.string.workflows_button_run_plan_execute)
                        }
                        Button(
                            onClick = viewModel::run,
                            enabled = readyToRun,
                        ) { Text(runLabel) }
                    }
                    OutlinedButton(
                        onClick = {
                            val text = state.runs
                                .filter { it.output.isNotBlank() }
                                .joinToString("\n\n---\n\n") { it.output }
                            if (text.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(text))
                            }
                        },
                        enabled = state.runs.any { it.output.isNotEmpty() } &&
                            state.status != RunStatus.Running,
                    ) { Text(stringResource(R.string.workflows_button_copy)) }
                    OutlinedButton(
                        onClick = {
                            val name = when (state.mode) {
                                WorkflowMode.PIPELINE -> "agents-pipeline.md"
                                WorkflowMode.ROUTER -> "agents-router.md"
                                WorkflowMode.DEBATE -> "agents-debate.md"
                                WorkflowMode.PLAN_EXECUTE -> "agents-plan-execute.md"
                            }
                            exportLauncher.launch(name)
                        },
                        enabled = state.runs.any { it.output.isNotEmpty() } &&
                            state.status != RunStatus.Running,
                    ) { Text(stringResource(R.string.workflows_button_export)) }
                }
                StatusLine(state.status)
            }
        }

        if (state.pastRuns.isNotEmpty()) {
            PastRunsSection(
                pastRuns = state.pastRuns,
                onDelete = viewModel::deletePastRun,
            )
        }

        if (state.runs.isNotEmpty()) {
            Text(stringResource(R.string.workflows_output), style = MaterialTheme.typography.titleMedium)
            val isMultiRound = state.runs.any { it.stepId.contains("-r") }
            state.runs.forEachIndexed { index, run ->
                RunCard(index, run, isMultiRound)
            }
            if (state.mode == WorkflowMode.ROUTER) {
                val routerOutput = state.runs.firstOrNull { it.stepId == "router" }?.output
                val routedComplete = state.runs.any { it.stepId == "routed" && it.isComplete }
                if (!routerOutput.isNullOrBlank() && routedComplete) {
                    val matched = state.router.routes.any { route ->
                        routerOutput.contains(route.key, ignoreCase = true)
                    }
                    if (!matched) {
                        val firstKey = state.router.routes.firstOrNull()?.key ?: "—"
                        Text(
                            stringResource(R.string.workflows_router_fallback_warning, firstKey),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    step: StepConfig,
    installed: List<InstalledModel>,
    canRemove: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPickModel: (InstalledModel) -> Unit,
    onSystemChange: (String) -> Unit,
    onTemplateChange: (String) -> Unit,
    onMaxTokensChange: (Int) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.workflows_step_index, index + 1),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text(stringResource(R.string.workflows_move_up)) }
                androidx.compose.material3.TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text(stringResource(R.string.workflows_move_down)) }
                if (canRemove) {
                    androidx.compose.material3.TextButton(onClick = onRemove) { Text(stringResource(R.string.workflows_remove)) }
                }
            }
            DeletedModelNotice(step.modelId, installed)
            Text(stringResource(R.string.workflows_pick_a_model), style = MaterialTheme.typography.bodySmall)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (model in installed) {
                    AssistChip(
                        onClick = { onPickModel(model) },
                        label = {
                            val prefix = if (model.id == step.modelId) stringResource(R.string.workflows_check_mark_prefix) else ""
                            Text("$prefix${model.displayName.takeLast(36)}")
                        },
                    )
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = step.systemPrompt,
                onValueChange = onSystemChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workflows_label_system_prompt)) },
                minLines = 2,
                maxLines = 4,
            )
            OutlinedTextField(
                value = step.promptTemplate,
                onValueChange = onTemplateChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workflows_label_prompt_template)) },
                minLines = 1,
                maxLines = 3,
            )
            Text(stringResource(R.string.workflows_max_output_tokens, step.maxTokens), style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.Slider(
                value = step.maxTokens.toFloat(),
                onValueChange = { onMaxTokensChange(it.toInt()) },
                valueRange = 64f..1024f,
                steps = 14,
            )
        }
    }
}

@Composable
private fun RouterCard(
    router: RouterConfig,
    installed: List<InstalledModel>,
    onPickRouterModel: (InstalledModel) -> Unit,
    onRouterSystemChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.workflows_router_title), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.workflows_router_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DeletedModelNotice(router.routerModelId, installed)
            Text(stringResource(R.string.workflows_pick_a_model), style = MaterialTheme.typography.bodySmall)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (model in installed) {
                    AssistChip(
                        onClick = { onPickRouterModel(model) },
                        label = {
                            val prefix = if (model.id == router.routerModelId) stringResource(R.string.workflows_check_mark_prefix) else ""
                            Text("$prefix${model.displayName.takeLast(36)}")
                        },
                    )
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = router.routerSystemPrompt,
                onValueChange = onRouterSystemChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workflows_label_router_system_prompt)) },
                minLines = 2,
                maxLines = 4,
            )
        }
    }
}

@Composable
private fun RouteCard(
    route: RouteConfig,
    installed: List<InstalledModel>,
    canRemove: Boolean,
    onPickModel: (InstalledModel) -> Unit,
    onKeyChange: (String) -> Unit,
    onSystemChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.workflows_route_title), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (canRemove) {
                    androidx.compose.material3.TextButton(onClick = onRemove) { Text(stringResource(R.string.workflows_remove)) }
                }
            }
            OutlinedTextField(
                value = route.key,
                onValueChange = onKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workflows_label_match_key)) },
                singleLine = true,
            )
            DeletedModelNotice(route.modelId, installed)
            Text(stringResource(R.string.workflows_pick_a_model), style = MaterialTheme.typography.bodySmall)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (model in installed) {
                    AssistChip(
                        onClick = { onPickModel(model) },
                        label = {
                            val prefix = if (model.id == route.modelId) stringResource(R.string.workflows_check_mark_prefix) else ""
                            Text("$prefix${model.displayName.takeLast(36)}")
                        },
                    )
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = route.systemPrompt,
                onValueChange = onSystemChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workflows_label_route_system_prompt)) },
                minLines = 2,
                maxLines = 4,
            )
        }
    }
}

@Composable
private fun ParticipantCard(
    index: Int,
    participant: ParticipantConfig,
    installed: List<InstalledModel>,
    canRemove: Boolean,
    onPickModel: (InstalledModel) -> Unit,
    onSystemChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.workflows_participant_index, index + 1), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (canRemove) {
                    androidx.compose.material3.TextButton(onClick = onRemove) { Text(stringResource(R.string.workflows_remove)) }
                }
            }
            DeletedModelNotice(participant.modelId, installed)
            Text(stringResource(R.string.workflows_pick_a_model), style = MaterialTheme.typography.bodySmall)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (model in installed) {
                    AssistChip(
                        onClick = { onPickModel(model) },
                        label = {
                            val prefix = if (model.id == participant.modelId) stringResource(R.string.workflows_check_mark_prefix) else ""
                            Text("$prefix${model.displayName.takeLast(36)}")
                        },
                    )
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = participant.systemPrompt,
                onValueChange = onSystemChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workflows_label_stance_system_prompt)) },
                minLines = 2,
                maxLines = 4,
            )
        }
    }
}

@Composable
private fun DebateRoundsCard(rounds: Int, onRoundsChange: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.workflows_rounds_title), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.workflows_rounds_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.workflows_max_rounds, rounds), style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.Slider(
                value = rounds.toFloat(),
                onValueChange = { onRoundsChange(it.toInt()) },
                valueRange = 1f..3f,
                steps = 1, // 1, 2, 3
            )
        }
    }
}

@Composable
private fun ModeratorCard(
    debate: DebateConfig,
    installed: List<InstalledModel>,
    onToggle: (Boolean) -> Unit,
    onPickModel: (InstalledModel) -> Unit,
    onSystemChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.workflows_moderator_title), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = debate.moderatorEnabled,
                    onCheckedChange = onToggle,
                )
            }
            if (debate.moderatorEnabled) {
                DeletedModelNotice(debate.moderatorModelId, installed)
                Text(stringResource(R.string.workflows_pick_a_model), style = MaterialTheme.typography.bodySmall)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (model in installed) {
                        AssistChip(
                            onClick = { onPickModel(model) },
                            label = {
                                val prefix = if (model.id == debate.moderatorModelId) stringResource(R.string.workflows_check_mark_prefix) else ""
                                Text("$prefix${model.displayName.takeLast(36)}")
                            },
                        )
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = debate.moderatorSystemPrompt,
                    onValueChange = onSystemChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workflows_label_moderator_system_prompt)) },
                    minLines = 2,
                    maxLines = 4,
                )
            } else {
                Text(
                    stringResource(R.string.workflows_moderator_disabled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlanExecuteCard(
    pe: PlanExecuteConfig,
    installed: List<InstalledModel>,
    onPickPlanner: (InstalledModel) -> Unit,
    onPlannerSystem: (String) -> Unit,
    onPickExecutor: (InstalledModel) -> Unit,
    onExecutorSystem: (String) -> Unit,
    onCriticToggle: (Boolean) -> Unit,
    onPickCritic: (InstalledModel) -> Unit,
    onCriticSystem: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.workflows_planner_title), fontWeight = FontWeight.Medium)
            DeletedModelNotice(pe.plannerModelId, installed)
            Text(stringResource(R.string.workflows_pick_a_model), style = MaterialTheme.typography.bodySmall)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (model in installed) {
                    AssistChip(
                        onClick = { onPickPlanner(model) },
                        label = {
                            val prefix = if (model.id == pe.plannerModelId) stringResource(R.string.workflows_check_mark_prefix) else ""
                            Text("$prefix${model.displayName.takeLast(36)}")
                        },
                    )
                }
            }
            OutlinedTextField(
                value = pe.plannerSystemPrompt,
                onValueChange = onPlannerSystem,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workflows_label_planner_system_prompt)) },
                minLines = 2,
                maxLines = 4,
            )

            HorizontalDivider()
            Text(stringResource(R.string.workflows_executor_title), fontWeight = FontWeight.Medium)
            DeletedModelNotice(pe.executorModelId, installed)
            Text(stringResource(R.string.workflows_pick_a_model), style = MaterialTheme.typography.bodySmall)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (model in installed) {
                    AssistChip(
                        onClick = { onPickExecutor(model) },
                        label = {
                            val prefix = if (model.id == pe.executorModelId) stringResource(R.string.workflows_check_mark_prefix) else ""
                            Text("$prefix${model.displayName.takeLast(36)}")
                        },
                    )
                }
            }
            OutlinedTextField(
                value = pe.executorSystemPrompt,
                onValueChange = onExecutorSystem,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workflows_label_executor_system_prompt)) },
                minLines = 2,
                maxLines = 4,
            )

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.workflows_critic_optional_title), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = pe.criticEnabled,
                    onCheckedChange = onCriticToggle,
                )
            }
            if (pe.criticEnabled) {
                DeletedModelNotice(pe.criticModelId, installed)
                Text(stringResource(R.string.workflows_pick_a_model), style = MaterialTheme.typography.bodySmall)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (model in installed) {
                        AssistChip(
                            onClick = { onPickCritic(model) },
                            label = {
                                val prefix = if (model.id == pe.criticModelId) stringResource(R.string.workflows_check_mark_prefix) else ""
                                Text("$prefix${model.displayName.takeLast(36)}")
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = pe.criticSystemPrompt,
                    onValueChange = onCriticSystem,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workflows_label_critic_system_prompt)) },
                    minLines = 2,
                    maxLines = 4,
                )
            } else {
                Text(
                    stringResource(R.string.workflows_critic_disabled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WorkflowPresetsBar(
    presets: List<WorkflowPreset>,
    running: Boolean,
    onApply: (String) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.workflows_saved_presets_title, presets.size),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(
                    onClick = { newPresetName = ""; showSaveDialog = true },
                    enabled = !running,
                ) { Text(stringResource(R.string.workflows_save_current)) }
            }
            if (presets.isEmpty()) {
                Text(
                    stringResource(R.string.workflows_presets_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (preset in presets) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val modeLabel = stringResource(preset.mode.displayNameRes)
                        AssistChip(
                            onClick = { if (!running) onApply(preset.id) },
                            label = { Text(stringResource(R.string.workflows_preset_label, preset.name, modeLabel)) },
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.TextButton(
                            onClick = { onDelete(preset.id) },
                            enabled = !running,
                        ) { Text(stringResource(R.string.workflows_delete)) }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.workflows_dialog_save_preset)) },
            text = {
                OutlinedTextField(
                    value = newPresetName,
                    onValueChange = { newPresetName = it },
                    label = { Text(stringResource(R.string.workflows_dialog_label_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onSave(newPresetName)
                        showSaveDialog = false
                    },
                    enabled = newPresetName.isNotBlank(),
                ) { Text(stringResource(R.string.workflows_button_save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(R.string.workflows_button_cancel))
                }
            },
        )
    }
}

@Composable
private fun PastRunsSection(
    pastRuns: List<PastRun>,
    onDelete: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.workflows_recent_runs_title, pastRuns.size),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) stringResource(R.string.workflows_hide) else stringResource(R.string.workflows_show))
                }
            }
            if (expanded) {
                for (run in pastRuns) {
                    PastRunCard(run = run, onDelete = { onDelete(run.id) })
                }
            }
        }
    }
}

@Composable
private fun PastRunCard(run: PastRun, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.workflows_past_run_header,
                        stringResource(run.mode.displayNameRes),
                        formatRunTime(run.createdAtEpochSec),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onDelete) { Text(stringResource(R.string.workflows_delete)) }
            }
            if (run.userPrompt.isNotBlank()) {
                val truncated = run.userPrompt.length > 120
                Text(
                    if (truncated) stringResource(R.string.workflows_past_run_prompt_short_truncated, run.userPrompt.take(120))
                    else stringResource(R.string.workflows_past_run_prompt_short, run.userPrompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                run.finalOutput.take(240) + if (run.finalOutput.length > 240) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun formatRunTime(epochSec: Long): String {
    val instant = java.time.Instant.ofEpochSecond(epochSec)
    return java.time.format.DateTimeFormatter
        .ofPattern("MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)
}

@Composable
private fun DeletedModelNotice(modelId: String?, installed: List<InstalledModel>) {
    val isDeleted = modelId != null && installed.none { it.id == modelId }
    if (!isDeleted) return
    Text(
        stringResource(R.string.workflows_deleted_model_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * Convert internal stepIds into something a user can read.
 *
 * - `router` / `routed` / `moderator` are fixed names used by the orchestrator.
 * - `p-{i}` and `p-{i}-r{round}` come from the Debate runner; round numbers
 *   are only shown when the run actually spans multiple rounds, otherwise
 *   "Round 1 — Participant 1" would be needless noise.
 * - Anything else (Pipeline custom agentIds) falls back to "Step {index+1}".
 */
@Composable
private fun friendlyStepLabel(run: StepRun, fallbackIndex: Int, isMultiRound: Boolean): String {
    val id = run.stepId
    return when {
        id == "router" -> stringResource(R.string.workflows_step_label_router)
        id == "routed" -> stringResource(R.string.workflows_step_label_routed)
        id == "moderator" -> stringResource(R.string.workflows_step_label_moderator)
        id.startsWith("p-") -> {
            val rest = id.removePrefix("p-")
            val roundSep = rest.indexOf("-r")
            if (roundSep < 0) {
                val n = rest.toIntOrNull()
                    ?: return stringResource(R.string.workflows_step_label_step, fallbackIndex + 1)
                if (isMultiRound) stringResource(R.string.workflows_step_label_round_participant, 1, n + 1)
                else stringResource(R.string.workflows_step_label_participant, n + 1)
            } else {
                val n = rest.substring(0, roundSep).toIntOrNull()
                val r = rest.substring(roundSep + 2).toIntOrNull()
                if (n == null || r == null) stringResource(R.string.workflows_step_label_step, fallbackIndex + 1)
                else stringResource(R.string.workflows_step_label_round_participant, r, n + 1)
            }
        }
        else -> stringResource(R.string.workflows_step_label_step, fallbackIndex + 1)
    }
}

@Composable
private fun RunCard(index: Int, run: StepRun, isMultiRound: Boolean = false) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    friendlyStepLabel(run, index, isMultiRound),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (run.isComplete) stringResource(R.string.workflows_run_done) else stringResource(R.string.workflows_run_ellipsis),
                    color = if (run.isComplete) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            for (call in run.toolCalls) {
                StepToolCallChip(call)
            }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(if (run.output.isEmpty() && !run.isComplete) stringResource(R.string.workflows_run_ellipsis) else run.output)
            }
        }
    }
}

@Composable
private fun StepToolCallChip(call: StepToolCallRecord) {
    val accent = if (call.isError) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column {
            val statusSuffix = when {
                call.isError -> stringResource(R.string.workflows_tool_status_error)
                call.resultJson == null -> stringResource(R.string.workflows_tool_status_running)
                else -> ""
            }
            Text(
                stringResource(R.string.workflows_tool_icon_prefix) + call.toolName + statusSuffix,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
            Text(
                stringResource(R.string.workflows_tool_args, call.argumentsJson),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            call.resultJson?.let {
                Text(
                    stringResource(R.string.workflows_tool_result, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusLine(status: RunStatus) {
    when (status) {
        RunStatus.Idle -> Unit
        RunStatus.Running -> Text(
            stringResource(R.string.workflows_status_running),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
        RunStatus.Done -> Text(
            stringResource(R.string.workflows_status_done),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
        is RunStatus.Failed -> {
            val msg = stringResource(status.messageRes)
            // The localized reason; append untranslated technical detail when present.
            val full = status.details?.let { "$msg ($it)" } ?: msg
            Text(
                stringResource(R.string.workflows_status_failed, full),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
