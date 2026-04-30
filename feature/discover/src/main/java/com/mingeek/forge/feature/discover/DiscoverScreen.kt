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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import com.mingeek.forge.data.discovery.DiscoveryRepository
import com.mingeek.forge.domain.DiscoveredModel

@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onOpenInCatalog: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Discover", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (state.isRefreshing) {
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            items(state.feeds, key = { it.sourceId }) { feed ->
                FeedSection(feed = feed, onOpenInCatalog = onOpenInCatalog)
            }
        }
    }
}

@Composable
private fun FeedSection(feed: DiscoveryRepository.Feed, onOpenInCatalog: (String) -> Unit) {
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
                DiscoverCard(item = item, onClick = { onOpenInCatalog(item.card.id) })
            }
        }
    }
}

@Composable
private fun DiscoverCard(item: DiscoveredModel, onClick: () -> Unit) {
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
        }
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
