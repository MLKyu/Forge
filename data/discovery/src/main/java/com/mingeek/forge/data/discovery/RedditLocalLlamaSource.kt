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
 * Discovery source backed by r/LocalLLaMA's RSS feed.
 *
 * Reddit posts are noisy. We use a two-tier filter:
 * 1. Prefer posts whose body contains a `huggingface.co/owner/repo` link —
 *    if we can recover a HF repo id, surface the post with that id and
 *    Source.HuggingFace so Catalog can attempt details + download
 * 2. Otherwise, fall back to keyword matching on the title (release /
 *    model family names) and surface as a Source.CustomUrl pointing at
 *    the Reddit thread itself
 *
 * Known limitation per PLANNING §6: heuristic-only. Misses any
 * announcement that links via a redirector or doesn't mention the
 * model family in the title.
 */
class RedditLocalLlamaSource(
    httpClient: OkHttpClient,
) : RssDiscoverySource(httpClient, FEED_URL) {

    override val sourceId: String = "reddit-localllama"
    override val displayName: String = "r/LocalLLaMA"

    override fun transform(item: RssItem): DiscoveredModel? {
        if (item.title.isBlank() || item.link.isBlank()) return null

        val haystack = (item.title + " " + item.description).lowercase()
        val hfRepoId = HF_REPO_REGEX.find(item.description)?.groupValues?.get(1)
        val titleMentionsModel = MODEL_KEYWORDS.any { haystack.contains(it) }
        if (hfRepoId == null && !titleMentionsModel) return null

        val (id, source, displayName) = if (hfRepoId != null) {
            Triple(hfRepoId, Source.HuggingFace(hfRepoId), hfRepoId)
        } else {
            Triple("reddit:${item.link}", Source.CustomUrl(item.link), item.title.take(80))
        }

        return DiscoveredModel(
            card = ModelCard(
                id = id,
                displayName = displayName,
                family = ModelFamily(
                    name = displayName.substringAfter('/').take(40),
                    vendor = displayName.substringBefore('/').takeIf { '/' in displayName },
                ),
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
                source = source,
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
                reasons = if (hfRepoId != null) {
                    listOf("HuggingFace repo extracted from r/LocalLLaMA post.")
                } else {
                    listOf("Reddit announcement — open the link to find a downloadable variant.")
                },
            ),
            userRelevance = if (hfRepoId != null) 0.6f else 0.4f,
        )
    }

    private companion object {
        const val FEED_URL = "https://www.reddit.com/r/LocalLLaMA/.rss"

        // Captures `owner/repo` from a huggingface.co URL. Owner / repo
        // segments allow alphanumerics, dashes, underscores, and dots.
        // Tolerates trailing slashes, query strings, anchors.
        val HF_REPO_REGEX = Regex(
            "huggingface\\.co/([\\w.\\-]+/[\\w.\\-]+?)(?:[/?#)\\s\"'>]|$)",
            RegexOption.IGNORE_CASE,
        )

        val MODEL_KEYWORDS = setOf(
            "llama", "qwen", "mistral", "mixtral", "phi", "gemma", "deepseek",
            "yi-", "command-r", "smollm", "olmo", "stablelm", "starcoder",
            "codellama", "falcon", "released", "release", "announcing",
        )
    }
}
