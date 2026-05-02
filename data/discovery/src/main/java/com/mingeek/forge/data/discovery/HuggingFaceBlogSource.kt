package com.mingeek.forge.data.discovery

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
import okhttp3.OkHttpClient
import java.time.Instant

/**
 * Discovery source backed by the public HuggingFace Blog RSS feed. Most
 * blog posts aren't model-release announcements — to keep noise down we
 * only surface posts whose title or description mentions a known
 * model-family keyword. Misses are fine; a separate Catalog browse
 * still finds anything not announced via the blog.
 *
 * Known limitation (PLANNING.md §6): "모델 출시 vs 일반 글 구분 부정확".
 * Heuristic over-filters in favour of precision.
 */
class HuggingFaceBlogSource(
    httpClient: OkHttpClient,
) : RssDiscoverySource(httpClient, FEED_URL) {

    override val sourceId: String = "hf-blog"
    override val displayName: String = "HuggingFace Blog"

    override fun transform(item: RssItem): DiscoveredModel? {
        val haystack = (item.title + " " + item.description).lowercase()
        if (MODEL_KEYWORDS.none { haystack.contains(it) }) return null
        if (item.title.isBlank() || item.link.isBlank()) return null
        return DiscoveredModel(
            card = ModelCard(
                // Synthetic id derived from the blog post URL. Catalog can't
                // download this directly — surfacing the announcement gives
                // the user something to click and investigate.
                id = "hf-blog:${item.link}",
                displayName = item.title,
                family = ModelFamily(name = item.title.take(40)),
                sizeBytes = 0L,
                quantization = Quant.UNKNOWN,
                format = ModelFormat.GGUF,
                contextLength = 0,
                capabilities = setOf(Capability.CHAT),
                license = License(
                    spdxId = "unknown",
                    displayName = "unknown",
                    commercialUseAllowed = false,
                ),
                source = Source.CustomUrl(item.link),
                recommendedRuntimes = listOf(RuntimeId.LLAMA_CPP),
                benchmarks = null,
            ),
            discoveredAt = Instant.now(),
            sourceId = sourceId,
            signals = DiscoverySignals(
                publishedAt = item.publishedAt,
                updatedAt = item.publishedAt,
            ),
            curation = null,
            deviceFit = DeviceFitScore(
                tier = DeviceFitScore.Tier.GREEN,
                estimatedTokensPerSecond = null,
                estimatedMemoryBytes = 0L,
                reasons = listOf("Announcement post — open the link to find a downloadable variant."),
            ),
            userRelevance = 0.4f,
        )
    }

    private companion object {
        const val FEED_URL = "https://huggingface.co/blog/feed.xml"

        // Keep short; we want recall on common model-family announcements
        // without dragging in datasets / tooling posts. Lowercase compare.
        val MODEL_KEYWORDS = setOf(
            "llama", "qwen", "mistral", "mixtral", "phi", "gemma", "deepseek",
            "yi-", "command-r", "smollm", "olmo", "stablelm", "starcoder",
            "codellama", "falcon", "gguf", "release", "announcing",
        )
    }
}
