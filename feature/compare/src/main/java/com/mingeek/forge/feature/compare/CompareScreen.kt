package com.mingeek.forge.feature.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingeek.forge.feature.compare.R

@Composable
fun CompareScreen(
    viewModel: CompareViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri != null) viewModel.export(uri, resolver)
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (state.installed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.compare_no_models_installed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Text(
            stringResource(R.string.compare_select_models_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.installed, key = { it.id }) { model ->
                FilterChip(
                    selected = model.id in state.selectedIds,
                    onClick = { viewModel.toggleSelection(model.id) },
                    label = { Text(model.displayName.takeLast(28)) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = viewModel::onDraftChanged,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                placeholder = { Text(stringResource(R.string.compare_prompt_placeholder)) },
                enabled = !state.isRunning,
            )
            if (state.isRunning) {
                OutlinedButton(onClick = viewModel::cancel) { Text(stringResource(R.string.compare_action_stop)) }
            } else {
                Button(
                    onClick = viewModel::run,
                    enabled = state.draft.isNotBlank() && state.selectedIds.size >= 1,
                ) { Text(stringResource(R.string.compare_action_run)) }
            }
            OutlinedButton(
                onClick = { exportLauncher.launch("compare-results.md") },
                enabled = state.panes.any { it.output.isNotEmpty() } && !state.isRunning,
            ) { Text(stringResource(R.string.compare_action_export)) }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.panes, key = { it.model.id }) { pane -> PaneCard(pane) }
            if (state.panes.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.compare_empty_panes_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PaneCard(pane: ComparePane) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pane.model.displayName, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        "${pane.model.quantization.name} · ${pane.model.recommendedRuntime}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.TextButton(
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(pane.output))
                    },
                    enabled = pane.output.isNotEmpty(),
                ) { Text(stringResource(R.string.compare_action_copy)) }
                StatusBadge(pane.status)
            }

            val metrics = buildString {
                pane.firstTokenLatencyMs?.let { append("TTFT ${it}ms") }
                pane.tokensPerSecond?.let {
                    if (isNotEmpty()) append(" · ")
                    append("%.1f tok/s".format(it))
                }
            }
            if (metrics.isNotEmpty()) {
                Text(
                    metrics,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            when (val s = pane.status) {
                is PaneStatus.Failed -> {
                    val resolved = s.messageRes?.let { res ->
                        if (s.formatArg != null) stringResource(res, s.formatArg) else stringResource(res)
                    } ?: s.message
                    Text(
                        stringResource(R.string.compare_error_prefix, resolved),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        if (pane.output.isEmpty() && s != PaneStatus.Done) "…" else pane.output,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: PaneStatus) {
    when (status) {
        PaneStatus.Idle -> Text(stringResource(R.string.compare_status_idle), style = MaterialTheme.typography.labelSmall)
        PaneStatus.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.compare_status_loading), style = MaterialTheme.typography.labelSmall)
        }
        PaneStatus.Generating -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.compare_status_generating), style = MaterialTheme.typography.labelSmall)
        }
        PaneStatus.Done -> Text(stringResource(R.string.compare_status_done), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        is PaneStatus.Failed -> Text(stringResource(R.string.compare_status_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
    }
}
