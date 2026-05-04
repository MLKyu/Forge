package com.mingeek.forge.feature.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Row
import com.mingeek.forge.core.ui.components.DeviceFitBadge
import com.mingeek.forge.core.ui.components.FormatBadge
import com.mingeek.forge.core.ui.components.LicenseChip
import com.mingeek.forge.data.catalog.ModelCardDetail
import com.mingeek.forge.data.catalog.RemoteFile
import com.mingeek.forge.data.catalog.SearchQuery
import com.mingeek.forge.data.download.DownloadState
import com.mingeek.forge.domain.DeviceFitScore
import com.mingeek.forge.domain.ModelCard
import com.mingeek.forge.domain.ModelFormat

private val SORT_OPTIONS: List<Pair<SearchQuery.Sort, Int>> = listOf(
    SearchQuery.Sort.RELEVANCE to R.string.catalog_sort_relevance,
    SearchQuery.Sort.DOWNLOADS to R.string.catalog_sort_downloads,
    SearchQuery.Sort.RECENT to R.string.catalog_sort_recent,
)

// "GGUF" / "MediaPipe" are technical IDs and not translated, so they stay
// as raw strings. "All" is user-visible copy and resolves via @StringRes.
private val FORMAT_OPTIONS: List<Pair<ModelFormat?, Any>> = listOf(
    null to R.string.catalog_format_all,
    ModelFormat.GGUF to "GGUF",
    ModelFormat.MEDIAPIPE_TASK to "MediaPipe",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.catalog_title), style = MaterialTheme.typography.headlineSmall)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.catalog_search_label)) },
                singleLine = true,
            )
            Button(onClick = viewModel::search, enabled = !state.isSearching) {
                Text(
                    if (state.isSearching) {
                        stringResource(R.string.catalog_search_button_loading)
                    } else {
                        stringResource(R.string.catalog_search_button)
                    }
                )
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SORT_OPTIONS) { (sort, labelRes) ->
                FilterChip(
                    selected = state.sort == sort,
                    onClick = { viewModel.setSort(sort) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(FORMAT_OPTIONS) { (format, label) ->
                FilterChip(
                    selected = state.formatFilter == format,
                    onClick = { viewModel.setFormatFilter(format) },
                    label = {
                        Text(
                            when (label) {
                                is Int -> stringResource(label)
                                is String -> label
                                else -> ""
                            }
                        )
                    },
                )
            }
        }

        state.error?.let { err ->
            val message = when (err) {
                is CatalogError.Message -> err.text
                is CatalogError.Res -> stringResource(err.resId)
            }
            Text(
                stringResource(R.string.catalog_error_prefix, message),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val displayed = state.displayedResults
            items(displayed, key = { it.id }) { card ->
                ResultCard(card = card, onClick = { viewModel.openDetails(card) })
            }
            if (displayed.isEmpty() && !state.isSearching) {
                item {
                    Text(
                        when {
                            state.results.isEmpty() ->
                                stringResource(R.string.catalog_empty_no_results)
                            else ->
                                stringResource(R.string.catalog_empty_filter)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.isSearching && displayed.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.catalog_loading_trending),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val detail = state.selectedDetail
    if (state.isLoadingDetail) {
        ModalBottomSheet(
            onDismissRequest = viewModel::closeDetails,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    } else if (detail != null) {
        ModalBottomSheet(
            onDismissRequest = viewModel::closeDetails,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            DetailSheet(
                detail = detail,
                downloads = downloads,
                variantRuntimeFits = state.variantRuntimeFits,
                onDownload = { card, file -> viewModel.downloadVariant(card, file) },
                onPause = viewModel::pauseDownload,
                onResume = viewModel::resumeDownload,
                onCancel = viewModel::cancelDownload,
            )
        }
    }
}

@Composable
private fun ResultCard(card: ModelCard, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                FormatBadge(format = card.format)
            }
            val familyDetail = "${card.family.name}${card.family.parameterBillions?.let { " · ${it}B" } ?: ""}"
            Text(
                stringResource(R.string.catalog_family_label, familyDetail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.catalog_view_variants))
            }
        }
    }
}

@Composable
private fun DetailSheet(
    detail: ModelCardDetail,
    downloads: Map<String, DownloadState>,
    variantRuntimeFits: Map<String, Map<com.mingeek.forge.domain.RuntimeId, DeviceFitScore>>,
    onDownload: (ModelCard, RemoteFile) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(detail.card.displayName, style = MaterialTheme.typography.titleLarge)
        if (detail.description.isNotBlank()) {
            Text(
                detail.description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.catalog_variants_header),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.titleSmall,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(detail.variants.zip(detail.files)) { (variant, file) ->
                VariantRow(
                    variant = variant,
                    file = file,
                    download = downloads[file.url],
                    runtimeFits = variantRuntimeFits[file.url].orEmpty(),
                    onDownload = { onDownload(variant, file) },
                    onPause = { onPause(file.url) },
                    onResume = { onResume(file.url) },
                    onCancel = { onCancel(file.url) },
                )
            }
            if (detail.variants.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.catalog_no_gguf_variants),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VariantRow(
    variant: ModelCard,
    file: RemoteFile,
    download: DownloadState?,
    runtimeFits: Map<com.mingeek.forge.domain.RuntimeId, DeviceFitScore>,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val primaryFit = variant.recommendedRuntimes.firstNotNullOfOrNull { runtimeFits[it] }
        ?: runtimeFits.values.firstOrNull()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "${variant.quantization.name} · ${formatSize(file.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                if (primaryFit != null) DeviceFitBadge(score = primaryFit)
                LicenseChip(license = variant.license)
            }
            if (runtimeFits.size > 1 || (runtimeFits.size == 1 && primaryFit != null)) {
                Text(
                    stringResource(R.string.catalog_runtime_options),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                for ((runtimeId, fit) in runtimeFits) {
                    Text(
                        "• ${runtimeId.name.replace('_', ' ').lowercase()} · " +
                            "${fit.tier.name}" +
                            (fit.estimatedTokensPerSecond?.let { " · ~${it.toInt()} tok/s" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (runtimeFits.isEmpty()) {
                Text(
                    stringResource(R.string.catalog_no_runtime_supports, variant.format.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (primaryFit != null) {
                primaryFit.reasons.firstOrNull()?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (!variant.license.commercialUseAllowed && !variant.license.spdxId.equals("unknown", ignoreCase = true)) {
                Text(
                    stringResource(R.string.catalog_non_commercial_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            DownloadControls(
                state = download,
                onDownload = onDownload,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun DownloadControls(
    state: DownloadState?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        null -> {
            Button(onClick = onDownload, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.catalog_download))
            }
        }
        is DownloadState.Failed -> {
            Text(
                stringResource(R.string.catalog_failed_prefix, state.message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onDownload) {
                    Text(stringResource(R.string.catalog_retry))
                }
                androidx.compose.material3.OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.catalog_dismiss))
                }
            }
        }
        is DownloadState.Queued -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(
                stringResource(R.string.catalog_queued),
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.material3.OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(stringResource(R.string.catalog_cancel)) }
        }
        is DownloadState.Running -> {
            val frac = state.fraction
            if (frac != null) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Text(
                stringResource(
                    R.string.catalog_progress_size,
                    formatSize(state.bytesDownloaded),
                    state.totalBytes?.let(::formatSize) ?: "?",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.OutlinedButton(onClick = onPause) {
                    Text(stringResource(R.string.catalog_pause))
                }
                androidx.compose.material3.OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.catalog_cancel))
                }
            }
        }
        is DownloadState.Verifying -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(
                stringResource(R.string.catalog_verifying),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is DownloadState.Paused -> {
            val frac = state.fraction
            if (frac != null) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Text(
                stringResource(
                    R.string.catalog_paused_progress,
                    formatSize(state.bytesDownloaded),
                    state.totalBytes?.let(::formatSize) ?: "?",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onResume) {
                    Text(stringResource(R.string.catalog_resume))
                }
                androidx.compose.material3.OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.catalog_cancel))
                }
            }
        }
        is DownloadState.Completed -> {
            Text(
                stringResource(R.string.catalog_installed),
                color = MaterialTheme.colorScheme.primary,
            )
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
