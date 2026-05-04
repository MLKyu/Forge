package com.mingeek.forge.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingeek.forge.core.ui.components.DeviceFitBadge
import com.mingeek.forge.core.ui.components.LicenseChip
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.domain.License
import com.mingeek.forge.feature.library.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val cleanupReport by viewModel.lastCleanupReport.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<InstalledModel?>(null) }

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importFromUri(uri)
    }

    val filtered = remember(rows, query, sort) {
        val q = if (query.isBlank()) rows
        else rows.filter { row ->
            row.model.displayName.contains(query, ignoreCase = true) ||
                row.model.fileName.contains(query, ignoreCase = true) ||
                row.model.quantization.name.contains(query, ignoreCase = true)
        }
        val secondary = when (sort) {
            LibrarySort.NAME -> q.sortedBy { it.model.displayName.lowercase() }
            LibrarySort.SIZE_DESC -> q.sortedByDescending { it.model.sizeBytes }
            LibrarySort.INSTALLED_DESC -> q.sortedByDescending { it.model.installedAtEpochSec }
            LibrarySort.SPEED_DESC -> q.sortedByDescending { it.benchmark?.tokensPerSecond ?: -1f }
        }
        // Pinned rows always float to the top; within each group the chosen
        // sort applies. Stable sort preserves the secondary order.
        secondary.sortedByDescending { it.pinned }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.library_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            }) { Text(stringResource(R.string.library_import)) }
        }
        Text(
            when {
                rows.isEmpty() -> stringResource(R.string.library_no_models_installed)
                query.isBlank() -> stringResource(R.string.library_models_installed, rows.size)
                else -> stringResource(R.string.library_filter_match_count, filtered.size, rows.size)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        importStatus?.let { status ->
            Text(
                importStatusMessage(status),
                style = MaterialTheme.typography.bodySmall,
                color = if (status.isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        cleanupReport?.let { report ->
            Text(
                stringResource(
                    R.string.library_auto_cleanup_report,
                    report.deletedCount,
                    report.budgetGb,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.library_empty_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                label = { Text(stringResource(R.string.library_search_label)) },
                singleLine = true,
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(LibrarySort.entries.toList(), key = { it.name }) { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { viewModel.setSort(option) },
                        label = { Text(stringResource(option.labelRes)) },
                    )
                }
            }

            if (filtered.isEmpty()) {
                Text(
                    stringResource(R.string.library_no_matches),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.model.id }) { row ->
                        LibraryItemCard(
                            row = row,
                            onDelete = { pendingDelete = row.model },
                            onBenchmark = { viewModel.runBenchmark(row.model) },
                            onTogglePin = { viewModel.togglePin(row.model) },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.library_delete_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.library_delete_dialog_text,
                        model.displayName,
                        formatSize(model.sizeBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(model)
                    pendingDelete = null
                }) { Text(stringResource(R.string.library_item_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.library_dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun importStatusMessage(status: ImportStatus): String = when (status) {
    ImportStatus.InProgress -> stringResource(R.string.library_import_in_progress)
    is ImportStatus.Unsupported -> stringResource(R.string.library_import_unsupported, status.fileName)
    is ImportStatus.Succeeded -> stringResource(R.string.library_import_succeeded, status.fileName)
    is ImportStatus.Failed -> {
        val detail = status.detail.ifBlank { stringResource(R.string.library_import_error_unknown) }
        stringResource(R.string.library_import_failed, detail)
    }
}

@Composable
private fun LibraryItemCard(
    row: LibraryRow,
    onDelete: () -> Unit,
    onBenchmark: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val model = row.model
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    val title = if (row.pinned) "📌 ${model.displayName}" else model.displayName
                    Text(title, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(
                            R.string.library_item_meta,
                            model.fileName,
                            model.quantization.name,
                            formatSize(model.sizeBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.library_item_runtime, model.recommendedRuntime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onTogglePin) {
                    Text(
                        if (row.pinned) stringResource(R.string.library_item_unpin)
                        else stringResource(R.string.library_item_pin),
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.library_item_delete))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeviceFitBadge(score = row.fit)
                LicenseChip(
                    license = License(
                        spdxId = model.licenseSpdxId,
                        displayName = model.licenseSpdxId,
                        commercialUseAllowed = model.commercialUseAllowed,
                    ),
                )
                row.benchmark?.let { bench ->
                    Text(
                        stringResource(
                            R.string.library_item_benchmark_summary,
                            bench.tokensPerSecond,
                            bench.firstTokenLatencyMs,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            row.benchmark?.let { bench ->
                Text(
                    stringResource(
                        R.string.library_item_benchmark_measured,
                        formatTime(bench.measuredAtEpochSec),
                        bench.deviceModel,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (val s = row.benchmarkState) {
                    BenchmarkState.Running -> {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.library_benchmark_running),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is BenchmarkState.Failed -> {
                        val detail = s.detail?.takeIf { it.isNotBlank() } ?: stringResource(s.messageRes)
                        Text(
                            stringResource(R.string.library_benchmark_failed, detail),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = onBenchmark) {
                            Text(stringResource(R.string.library_benchmark_retry))
                        }
                    }
                    BenchmarkState.Idle -> {
                        OutlinedButton(onClick = onBenchmark) {
                            Text(
                                if (row.benchmark != null) stringResource(R.string.library_benchmark_rerun)
                                else stringResource(R.string.library_benchmark_start),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "?"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return "%.1f %s".format(v, units[i])
}

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatTime(epochSec: Long): String =
    TIME_FORMATTER.format(Instant.ofEpochSecond(epochSec))
