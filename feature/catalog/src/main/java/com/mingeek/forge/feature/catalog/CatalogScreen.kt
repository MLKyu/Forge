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
import com.mingeek.forge.data.download.DownloadProgress
import com.mingeek.forge.domain.DeviceFitScore
import com.mingeek.forge.domain.ModelCard
import com.mingeek.forge.domain.ModelFormat

private val SORT_OPTIONS: List<Pair<SearchQuery.Sort, String>> = listOf(
    SearchQuery.Sort.RELEVANCE to "Relevance",
    SearchQuery.Sort.DOWNLOADS to "Downloads",
    SearchQuery.Sort.RECENT to "Recent",
)

private val FORMAT_OPTIONS: List<Pair<ModelFormat?, String>> = listOf(
    null to "All",
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

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Catalog — HuggingFace", style = MaterialTheme.typography.headlineSmall)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier.weight(1f),
                label = { Text("Search GGUF / MediaPipe models") },
                singleLine = true,
            )
            Button(onClick = viewModel::search, enabled = !state.isSearching) {
                Text(if (state.isSearching) "..." else "Go")
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SORT_OPTIONS) { (sort, label) ->
                FilterChip(
                    selected = state.sort == sort,
                    onClick = { viewModel.setSort(sort) },
                    label = { Text(label) },
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
                    label = { Text(label) },
                )
            }
        }

        state.error?.let {
            Text(
                "Error: $it",
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
                                "No results. Try a different query or sort."
                            else ->
                                "No results match the current filter."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.isSearching && displayed.isEmpty()) {
                item {
                    Text(
                        "Loading trending models…",
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
                downloads = state.downloads,
                variantFits = state.variantFits,
                onDownload = { card, file -> viewModel.downloadVariant(card, file) },
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
            Text(
                "Family: ${card.family.name}${card.family.parameterBillions?.let { " · ${it}B" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) {
                Text("View variants")
            }
        }
    }
}

@Composable
private fun DetailSheet(
    detail: ModelCardDetail,
    downloads: Map<String, DownloadProgress>,
    variantFits: Map<String, DeviceFitScore>,
    onDownload: (ModelCard, RemoteFile) -> Unit,
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
        Text("Variants", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(detail.variants.zip(detail.files)) { (variant, file) ->
                VariantRow(
                    variant = variant,
                    file = file,
                    progress = downloads[file.url],
                    fit = variantFits[file.url],
                    onDownload = { onDownload(variant, file) },
                )
            }
            if (detail.variants.isEmpty()) {
                item {
                    Text("No GGUF variants found in this repo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun VariantRow(
    variant: ModelCard,
    file: RemoteFile,
    progress: DownloadProgress?,
    fit: DeviceFitScore?,
    onDownload: () -> Unit,
) {
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
                if (fit != null) DeviceFitBadge(score = fit)
                LicenseChip(license = variant.license)
            }
            if (fit != null) {
                fit.reasons.firstOrNull()?.let { reason ->
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
                    "Non-commercial license — check terms before deploying",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            when (progress) {
                null, is DownloadProgress.Failed -> {
                    if (progress is DownloadProgress.Failed) {
                        Text(
                            "Failed: ${progress.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = onDownload, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (progress is DownloadProgress.Failed) "Retry" else "Download")
                    }
                }
                is DownloadProgress.Started, is DownloadProgress.Verifying -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                is DownloadProgress.Progress -> {
                    val frac = progress.fraction
                    if (frac != null) {
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    Text(
                        "${formatSize(progress.bytesDownloaded)} / ${progress.totalBytes?.let(::formatSize) ?: "?"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is DownloadProgress.Completed -> {
                    Text("Installed", color = MaterialTheme.colorScheme.primary)
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
