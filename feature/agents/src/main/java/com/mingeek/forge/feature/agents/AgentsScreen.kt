package com.mingeek.forge.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingeek.forge.data.storage.InstalledModel

@Composable
fun AgentsScreen(
    viewModel: AgentsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
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
                WorkflowMode.PIPELINE -> "Agents · Pipeline (${state.steps.size} step${if (state.steps.size == 1) "" else "s"})"
                WorkflowMode.ROUTER -> "Agents · Router (${state.router.routes.size} route${if (state.router.routes.size == 1) "" else "s"})"
                WorkflowMode.DEBATE -> "Agents · Debate (${state.debate.participants.size} participants${if (state.debate.moderatorEnabled) " + moderator" else ""})"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            when (state.mode) {
                WorkflowMode.PIPELINE -> "Each step's output flows to the next via {input}."
                WorkflowMode.ROUTER -> "Router classifies the input; the matching route handles the response."
                WorkflowMode.DEBATE -> "Each participant answers; the moderator (if enabled) synthesizes a final answer."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )

        if (state.installed.isEmpty()) {
            Text(
                "No models installed. Download some in Catalog first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text("Mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (mode in WorkflowMode.entries) {
                androidx.compose.material3.FilterChip(
                    selected = state.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    label = { Text(mode.displayName) },
                )
            }
        }

        if (state.mode == WorkflowMode.PIPELINE) {
            Text("Presets", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (preset in PipelinePreset.entries) {
                    AssistChip(
                        onClick = { viewModel.applyPreset(preset) },
                        label = { Text(preset.displayName) },
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
                Text("+ Add step")
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
                Text("+ Add route")
            }
        } else {
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
                Text("+ Add participant")
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
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("User prompt", fontWeight = FontWeight.Medium)
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
                        OutlinedButton(onClick = viewModel::cancel) { Text("Stop") }
                    } else {
                        val readyToRun = state.userPrompt.isNotBlank() && when (state.mode) {
                            WorkflowMode.PIPELINE -> state.steps.all { it.modelId != null }
                            WorkflowMode.ROUTER -> state.router.routerModelId != null &&
                                state.router.routes.all { it.modelId != null }
                            WorkflowMode.DEBATE -> state.debate.participants.all { it.modelId != null } &&
                                (!state.debate.moderatorEnabled || state.debate.moderatorModelId != null)
                        }
                        val runLabel = when (state.mode) {
                            WorkflowMode.PIPELINE -> "Run pipeline"
                            WorkflowMode.ROUTER -> "Run router"
                            WorkflowMode.DEBATE -> "Run debate"
                        }
                        Button(
                            onClick = viewModel::run,
                            enabled = readyToRun,
                        ) { Text(runLabel) }
                    }
                    OutlinedButton(
                        onClick = {
                            val name = when (state.mode) {
                                WorkflowMode.PIPELINE -> "agents-pipeline.md"
                                WorkflowMode.ROUTER -> "agents-router.md"
                                WorkflowMode.DEBATE -> "agents-debate.md"
                            }
                            exportLauncher.launch(name)
                        },
                        enabled = state.runs.any { it.output.isNotEmpty() } &&
                            state.status != RunStatus.Running,
                    ) { Text("Export") }
                }
                StatusLine(state.status)
            }
        }

        if (state.runs.isNotEmpty()) {
            Text("Output", style = MaterialTheme.typography.titleMedium)
            val isMultiRound = state.runs.any { it.stepId.contains("-r") }
            state.runs.forEachIndexed { index, run ->
                RunCard(index, run, isMultiRound)
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
                    "Step ${index + 1}",
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("↑") }
                androidx.compose.material3.TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("↓") }
                if (canRemove) {
                    androidx.compose.material3.TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
            Text("Pick a model", style = MaterialTheme.typography.bodySmall)
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(installed, key = { "step${index}-${it.id}" }) { model ->
                    AssistChip(
                        onClick = { onPickModel(model) },
                        label = {
                            val prefix = if (model.id == step.modelId) "✓ " else ""
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
                label = { Text("System prompt") },
                minLines = 2,
                maxLines = 4,
            )
            OutlinedTextField(
                value = step.promptTemplate,
                onValueChange = onTemplateChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Prompt template ({input} = previous step output)") },
                minLines = 1,
                maxLines = 3,
            )
            Text("Max output tokens: ${step.maxTokens}", style = MaterialTheme.typography.bodySmall)
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
            Text("Router", fontWeight = FontWeight.Medium)
            Text(
                "Picks a route by matching its key (case-insensitive substring) in the router output.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Pick a model", style = MaterialTheme.typography.bodySmall)
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(installed, key = { "router-${it.id}" }) { model ->
                    AssistChip(
                        onClick = { onPickRouterModel(model) },
                        label = {
                            val prefix = if (model.id == router.routerModelId) "✓ " else ""
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
                label = { Text("Router system prompt (should output a route key)") },
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
                Text("Route", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (canRemove) {
                    androidx.compose.material3.TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
            OutlinedTextField(
                value = route.key,
                onValueChange = onKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Match key") },
                singleLine = true,
            )
            Text("Pick a model", style = MaterialTheme.typography.bodySmall)
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(installed, key = { "${route.agentId}-${it.id}" }) { model ->
                    AssistChip(
                        onClick = { onPickModel(model) },
                        label = {
                            val prefix = if (model.id == route.modelId) "✓ " else ""
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
                label = { Text("Route system prompt") },
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
                Text("Participant ${index + 1}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (canRemove) {
                    androidx.compose.material3.TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
            Text("Pick a model", style = MaterialTheme.typography.bodySmall)
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(installed, key = { "${participant.agentId}-${it.id}" }) { model ->
                    AssistChip(
                        onClick = { onPickModel(model) },
                        label = {
                            val prefix = if (model.id == participant.modelId) "✓ " else ""
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
                label = { Text("Stance / system prompt") },
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
            Text("Rounds", fontWeight = FontWeight.Medium)
            Text(
                "Round 1: each participant answers the user. Rounds 2+: every participant sees the others' previous answers and refines their own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Max rounds: $rounds", style = MaterialTheme.typography.bodySmall)
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
                Text("Moderator", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = debate.moderatorEnabled,
                    onCheckedChange = onToggle,
                )
            }
            if (debate.moderatorEnabled) {
                Text("Pick a model", style = MaterialTheme.typography.bodySmall)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(installed, key = { "moderator-${it.id}" }) { model ->
                        AssistChip(
                            onClick = { onPickModel(model) },
                            label = {
                                val prefix = if (model.id == debate.moderatorModelId) "✓ " else ""
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
                    label = { Text("Moderator system prompt") },
                    minLines = 2,
                    maxLines = 4,
                )
            } else {
                Text(
                    "Without a moderator, participants' answers are concatenated as the final result.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
private fun friendlyStepLabel(run: StepRun, fallbackIndex: Int, isMultiRound: Boolean): String {
    val id = run.stepId
    return when {
        id == "router" -> "Router"
        id == "routed" -> "Routed"
        id == "moderator" -> "Moderator"
        id.startsWith("p-") -> {
            val rest = id.removePrefix("p-")
            val roundSep = rest.indexOf("-r")
            if (roundSep < 0) {
                val n = rest.toIntOrNull() ?: return "Step ${fallbackIndex + 1}"
                if (isMultiRound) "Round 1 — Participant ${n + 1}" else "Participant ${n + 1}"
            } else {
                val n = rest.substring(0, roundSep).toIntOrNull()
                val r = rest.substring(roundSep + 2).toIntOrNull()
                if (n == null || r == null) "Step ${fallbackIndex + 1}"
                else "Round $r — Participant ${n + 1}"
            }
        }
        else -> "Step ${fallbackIndex + 1}"
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
                    if (run.isComplete) "done" else "…",
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
                Text(if (run.output.isEmpty() && !run.isComplete) "…" else run.output)
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
                call.isError -> " · error"
                call.resultJson == null -> " · running"
                else -> ""
            }
            Text(
                "🔧 " + call.toolName + statusSuffix,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
            Text(
                "args: ${call.argumentsJson}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            call.resultJson?.let {
                Text(
                    "result: $it",
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
            "Running…",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
        RunStatus.Done -> Text(
            "Pipeline complete.",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
        is RunStatus.Failed -> Text(
            "Failed: ${status.message}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
