package com.mingeek.forge.data.discovery

import com.mingeek.forge.data.catalog.huggingface.HfModelSummary
import com.mingeek.forge.data.catalog.huggingface.HuggingFaceApi
import com.mingeek.forge.domain.Capability
import com.mingeek.forge.domain.DeviceFitScore
import com.mingeek.forge.domain.DiscoveredModel
import com.mingeek.forge.domain.DiscoverySignals
import com.mingeek.forge.domain.License
import com.mingeek.forge.domain.ModelCard
import com.mingeek.forge.domain.ModelFamily
import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.Quant
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.domain.Source
import java.time.Instant
import java.time.format.DateTimeParseException

class HuggingFaceTrendingSource(
    private val api: HuggingFaceApi,
    private val limit: Int = 20,
) : DiscoverySource {
    override val sourceId = "hf-trending"
    override val displayName = "Trending on HuggingFace"

    override suspend fun fetchSignals(): List<DiscoveredModel> =
        api.searchModels(search = null, filter = "gguf", sort = "downloads", limit = limit)
            .toDiscovered(sourceId, withTrendingRank = true)
}

class HuggingFaceRecentSource(
    private val api: HuggingFaceApi,
    private val limit: Int = 20,
) : DiscoverySource {
    override val sourceId = "hf-recent"
    override val displayName = "Recently updated"

    override suspend fun fetchSignals(): List<DiscoveredModel> =
        api.searchModels(search = null, filter = "gguf", sort = "lastModified", limit = limit)
            .toDiscovered(sourceId, withTrendingRank = false)
}

class HuggingFaceLikedSource(
    private val api: HuggingFaceApi,
    private val limit: Int = 20,
) : DiscoverySource {
    override val sourceId = "hf-liked"
    override val displayName = "Most liked"

    override suspend fun fetchSignals(): List<DiscoveredModel> =
        api.searchModels(search = null, filter = "gguf", sort = "likes", limit = limit)
            .toDiscovered(sourceId, withTrendingRank = false)
}

private fun List<HfModelSummary>.toDiscovered(
    sourceId: String,
    withTrendingRank: Boolean,
): List<DiscoveredModel> {
    val now = Instant.now()
    return mapIndexed { index, summary ->
        DiscoveredModel(
            card = summary.toCard(),
            discoveredAt = now,
            sourceId = sourceId,
            signals = DiscoverySignals(
                downloadCount = summary.downloads,
                likes = summary.likes,
                publishedAt = null,
                updatedAt = summary.lastModified.parseInstantOrNull(),
                trendingRank = if (withTrendingRank) index + 1 else null,
            ),
            curation = null,
            deviceFit = DeviceFitScore(
                tier = DeviceFitScore.Tier.GREEN,
                estimatedTokensPerSecond = null,
                estimatedMemoryBytes = 0L,
                reasons = emptyList(),
            ),
            userRelevance = 0.5f,
        )
    }
}

private fun HfModelSummary.toCard(): ModelCard = ModelCard(
    id = id,
    displayName = id,
    family = ModelFamily(
        name = id.substringAfter('/'),
        vendor = id.substringBefore('/').takeIf { '/' in id },
    ),
    sizeBytes = 0L,
    quantization = Quant.UNKNOWN,
    format = ModelFormat.GGUF,
    contextLength = 4096,
    capabilities = setOf(Capability.CHAT),
    license = License(
        spdxId = tags.firstOrNull { it.startsWith("license:") }?.substringAfter("license:") ?: "unknown",
        displayName = tags.firstOrNull { it.startsWith("license:") }?.substringAfter("license:") ?: "Unknown",
        commercialUseAllowed = false,
    ),
    source = Source.HuggingFace(id),
    recommendedRuntimes = listOf(RuntimeId.LLAMA_CPP),
)

private fun String?.parseInstantOrNull(): Instant? {
    if (this == null) return null
    return try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }
}
