package com.mingeek.forge.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingeek.forge.data.discovery.Collection
import com.mingeek.forge.data.discovery.DiscoveryRepository
import com.mingeek.forge.data.discovery.RecommendedModel
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.domain.Curation
import com.mingeek.forge.domain.DiscoveredModel

@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onOpenInCatalog: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Discover", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (state.isRefreshing || state.isCurating) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            TextButton(onClick = viewModel::refresh) { Text("Refresh") }
        }

        state.error?.let {
            Text(
                "Error: $it",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        CuratorBar(
            installed = installed,
            curatorModelId = state.curatorModelId,
            isCurating = state.isCurating,
            curatorError = state.curatorError,
            curatedCount = state.curatedCount,
            onSelectCurator = viewModel::selectCurator,
            onRunCurator = viewModel::runCurator,
            onClearCurations = viewModel::clearCurations,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            if (state.recommendations.isNotEmpty()) {
                item {
                    Text(
                        "For you",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.recommendations, key = { "rec-${it.candidate.card.id}" }) { rec ->
                            RecommendationCard(rec = rec, onClick = { onOpenInCatalog(rec.candidate.card.id) })
                        }
                    }
                }
            }
            if (state.collections.isNotEmpty()) {
                item {
                    Text(
                        "Curated collections",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                    )
                }
                items(state.collections, key = { "col-${it.id}" }) { collection ->
                    CollectionSection(collection = collection, onOpenInCatalog = onOpenInCatalog)
                }
            }
            items(state.feeds, key = { it.sourceId }) { feed ->
                FeedSection(
                    feed = feed,
                    curations = state.curations,
                    onOpenInCatalog = onOpenInCatalog,
                )
            }
        }
    }
}

@Composable
private fun CuratorBar(
    installed: List<InstalledModel>,
    curatorModelId: String?,
    isCurating: Boolean,
    curatorError: String?,
    curatedCount: Int,
    onSelectCurator: (String?) -> Unit,
    onRunCurator: () -> Unit,
    onClearCurations: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AI Curator",
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onRunCurator,
                    enabled = curatorModelId != null && !isCurating,
                ) { Text(if (isCurating) "Curating ($curatedCount)…" else "Run") }
                TextButton(onClick = onClearCurations, enabled = !isCurating) { Text("Clear") }
            }
            if (installed.isEmpty()) {
                Text(
                    "Install at least one model to enable curation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Pick which installed model rates the discovered cards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(installed, key = { "curator-${it.id}" }) { model ->
                        AssistChip(
                            onClick = { onSelectCurator(model.id) },
                            label = {
                                val prefix = if (model.id == curatorModelId) "✓ " else ""
                                Text("$prefix${model.displayName.takeLast(28)}")
                            },
                        )
                    }
                }
            }
            curatorError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RecommendationCard(rec: RecommendedModel, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(260.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                rec.candidate.card.displayName,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            Text(
                rec.candidate.card.family.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Match: ${"%.0f%%".format((rec.score * 100f).coerceAtMost(100f))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
            for (reason in rec.reasons) {
                Text(
                    "• $reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CollectionSection(collection: Collection, onOpenInCatalog: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(collection.title, fontWeight = FontWeight.Medium)
        Text(
            collection.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(collection.modelIds, key = { "${collection.id}-$it" }) { modelId ->
                AssistChip(
                    onClick = { onOpenInCatalog(modelId) },
                    label = { Text(modelId.takeLast(36)) },
                )
            }
        }
    }
}

@Composable
private fun FeedSection(
    feed: DiscoveryRepository.Feed,
    curations: Map<String, Curation>,
    onOpenInCatalog: (String) -> Unit,
) {
    Column {
        Text(
            feed.displayName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        if (feed.error != null) {
            Text(
                "Failed: ${feed.error}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp),
            )
            return@Column
        }
        if (feed.items.isEmpty()) {
            Text(
                "No items.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
            return@Column
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(feed.items, key = { it.card.id }) { item ->
                DiscoverCard(
                    item = item,
                    curation = curations[item.card.id],
                    onClick = { onOpenInCatalog(item.card.id) },
                )
            }
        }
    }
}

@Composable
private fun DiscoverCard(
    item: DiscoveredModel,
    curation: Curation?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.width(260.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            item.signals.trendingRank?.let {
                Text(
                    "#$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(item.card.displayName, fontWeight = FontWeight.Medium, maxLines = 2)
            Text(
                item.card.family.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item.signals.downloadCount?.let {
                    Text("⬇ ${formatCount(it)}", style = MaterialTheme.typography.labelSmall)
                }
                item.signals.likes?.let {
                    Text("❤ ${formatCount(it)}", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (curation != null) {
                HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 6.dp))
                val scoreLabel = curation.score?.let { "★ ${"%.0f".format(it)}/5" } ?: "★ ?"
                Text(
                    scoreLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if ((curation.score ?: 0f) >= 4) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    curation.summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                )
            }
        }
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
