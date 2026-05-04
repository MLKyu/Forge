package com.mingeek.forge.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingeek.forge.core.ui.components.DeviceFitBadge
import com.mingeek.forge.data.discovery.DiscoveryRepository
import com.mingeek.forge.data.discovery.RecommendedModel
import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.domain.Curation
import com.mingeek.forge.domain.DeviceFitScore
import com.mingeek.forge.domain.DiscoveredModel
import com.mingeek.forge.feature.discover.R

@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onOpenInCatalog: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val hasHfAuth by viewModel.hasHfAuth.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.discover_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (state.isRefreshing || state.isCurating) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            TextButton(onClick = viewModel::refresh) {
                Text(stringResource(R.string.discover_refresh))
            }
        }

        state.error?.let { msg ->
            Text(
                stringResource(R.string.discover_error_prefix, msg.resolve()),
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
                        stringResource(R.string.discover_section_for_you),
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
                    Column(modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)) {
                        Text(
                            stringResource(R.string.discover_section_curated_collections),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.discover_section_curated_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.collections, key = { "col-${it.collection.id}" }) { view ->
                    CollectionSection(
                        view = view,
                        hasHfAuth = hasHfAuth,
                        onOpenInCatalog = onOpenInCatalog,
                        onOpenSettings = onOpenSettings,
                    )
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

/**
 * Resolves a [DiscoverUiMessage] to a final localized string. When [detail]
 * is present, it is appended via [R.string.discover_error_with_detail] so
 * exception text from the runtime is still surfaced to the user.
 */
@Composable
private fun DiscoverUiMessage.resolve(): String {
    val base = stringResource(messageRes)
    return if (detail.isNullOrBlank()) base
    else stringResource(R.string.discover_error_with_detail, base, detail)
}

@Composable
private fun CuratorBar(
    installed: List<InstalledModel>,
    curatorModelId: String?,
    isCurating: Boolean,
    curatorError: DiscoverUiMessage?,
    curatedCount: Int,
    onSelectCurator: (String?) -> Unit,
    onRunCurator: () -> Unit,
    onClearCurations: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.discover_curator_title),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onRunCurator,
                    enabled = curatorModelId != null && !isCurating,
                ) {
                    Text(
                        if (isCurating) stringResource(R.string.discover_curator_running, curatedCount)
                        else stringResource(R.string.discover_curator_run)
                    )
                }
                TextButton(onClick = onClearCurations, enabled = !isCurating) {
                    Text(stringResource(R.string.discover_curator_clear))
                }
            }
            if (installed.isEmpty()) {
                Text(
                    stringResource(R.string.discover_curator_no_models),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.discover_curator_pick_hint),
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
                Text(
                    it.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
                stringResource(
                    R.string.discover_match_percent,
                    "%.0f".format((rec.score * 100f).coerceAtMost(100f)),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
            for (reason in rec.reasons) {
                Text(
                    stringResource(R.string.discover_reason_bullet, reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CollectionSection(
    view: CollectionView,
    hasHfAuth: Boolean,
    onOpenInCatalog: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val collection = view.collection
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(collection.title, fontWeight = FontWeight.Medium)
        Text(
            collection.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(view.entries, key = { "${collection.id}-${it.entry.modelId}" }) { entryView ->
                CollectionEntryCard(
                    entryView = entryView,
                    hasHfAuth = hasHfAuth,
                    onClick = { onOpenInCatalog(entryView.entry.modelId) },
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun CollectionEntryCard(
    entryView: CollectionEntryView,
    hasHfAuth: Boolean,
    onClick: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val entry = entryView.entry
    Card(
        modifier = Modifier.width(220.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                entry.modelId.takeLast(36),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            Text(
                formatApproxSize(entry.approxSizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entryView.fit != null) {
                    DeviceFitBadge(score = entryView.fit)
                }
                if (entry.gated) {
                    AuthBadge(
                        hasAuth = hasHfAuth,
                        onTapWhenMissing = onOpenSettings,
                    )
                }
            }
            val fitLabelRes = fitLabelRes(entryView.fit)
            Text(
                stringResource(fitLabelRes),
                style = MaterialTheme.typography.labelSmall,
                color = if (entryView.fit?.tier == DeviceFitScore.Tier.RED ||
                    entryView.fit?.tier == DeviceFitScore.Tier.UNSUPPORTED
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Compact auth-status pill on cards whose entry is gated. Sized to
 * match [com.mingeek.forge.core.ui.components.DeviceFitBadge] so the
 * row of badges reads as one visual block. Missing-state uses a saturated
 * red fill with white text — deliberately "must do this" rather than the
 * softer Material tertiary tone, since the user reads the card as
 * already-decided and the badge needs to interrupt that. Authenticated
 * flips to a muted green so it sits quietly. The actual download can
 * still 401 if the user hasn't accepted the model's license on
 * huggingface.co — that case is handled by
 * [com.mingeek.forge.data.catalog.CatalogException.Gated] in the detail
 * sheet.
 */
@Composable
private fun AuthBadge(hasAuth: Boolean, onTapWhenMissing: () -> Unit) {
    val (bg, fg, labelRes) = if (hasAuth) {
        Triple(
            Color(0xFF1B5E20),  // dark green — quiet confirmation
            Color.White,
            R.string.discover_collection_authed,
        )
    } else {
        Triple(
            Color(0xFFB71C1C),  // saturated red — "must do this"
            Color.White,
            R.string.discover_collection_gated,
        )
    }
    val baseModifier = Modifier
        .clip(RoundedCornerShape(6.dp))
        .background(bg)
    val modifier = if (hasAuth) baseModifier else baseModifier.clickable(onClick = onTapWhenMissing)
    Text(
        text = stringResource(labelRes),
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun formatApproxSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return stringResource(R.string.discover_collection_size_unknown)
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1.0) return stringResource(R.string.discover_collection_size_gb, gb)
    val mb = bytes / 1_048_576.0
    return stringResource(R.string.discover_collection_size_mb, mb)
}

private fun fitLabelRes(fit: DeviceFitScore?): Int = when (fit?.tier) {
    DeviceFitScore.Tier.GREEN -> R.string.discover_collection_fit_green
    DeviceFitScore.Tier.YELLOW -> R.string.discover_collection_fit_yellow
    DeviceFitScore.Tier.RED -> R.string.discover_collection_fit_red
    DeviceFitScore.Tier.UNSUPPORTED -> R.string.discover_collection_fit_red
    null -> R.string.discover_collection_fit_unknown
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
                stringResource(R.string.discover_feed_failed, feed.error!!),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp),
            )
            return@Column
        }
        if (feed.items.isEmpty()) {
            Text(
                stringResource(R.string.discover_feed_empty),
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
                val scoreLabel = curation.score?.let {
                    stringResource(R.string.discover_curation_score, "%.0f".format(it))
                } ?: stringResource(R.string.discover_curation_score_unknown)
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
