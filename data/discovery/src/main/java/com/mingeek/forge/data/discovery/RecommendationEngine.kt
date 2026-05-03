package com.mingeek.forge.data.discovery

import com.mingeek.forge.data.storage.InstalledModel
import com.mingeek.forge.data.storage.effectiveLastUsedEpochSec
import com.mingeek.forge.domain.DiscoveredModel
import kotlin.math.exp
import kotlin.math.ln

/**
 * Scores [DiscoveredModel] candidates against the user's installed library
 * to produce a "For you" feed. Pluggable so a future embedding-similarity
 * scorer can replace heuristic matching without changing the call site.
 */
interface RecommendationEngine {
    val sourceId: String
    suspend fun recommend(
        candidates: List<DiscoveredModel>,
        installed: List<InstalledModel>,
        limit: Int = 12,
    ): List<RecommendedModel>
}

data class RecommendedModel(
    val candidate: DiscoveredModel,
    val score: Float,
    val reasons: List<String>,
)

/**
 * Heuristic recommender — scores candidates by vendor / family-name /
 * size-class similarity to recently-used installed models, weighted by
 * recency. Cold-start (no installed models) returns empty.
 *
 * Signals:
 * - vendor match (0.45) — exact vendor string match (case-insensitive)
 * - family-name token overlap (0.30) — Jaccard similarity over tokenized
 *   family names; "Qwen2.5-Coder-3B" vs "Qwen2.5-3B" → some overlap
 * - size class match (0.25) — both in {tiny, small, medium, large}
 *
 * Recency: each installed model contributes its similarity weighted by
 * `exp(-days_since_used / 30)` so models the user used today dominate
 * over ones they haven't touched in months.
 */
class UsagePatternRecommender : RecommendationEngine {

    override val sourceId: String = "usage-pattern"

    override suspend fun recommend(
        candidates: List<DiscoveredModel>,
        installed: List<InstalledModel>,
        limit: Int,
    ): List<RecommendedModel> {
        if (installed.isEmpty()) return emptyList()
        val now = System.currentTimeMillis() / 1000
        return candidates
            .map { candidate -> scoreCandidate(candidate, installed, now) }
            .filter { it.score > 0.05f }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun scoreCandidate(
        candidate: DiscoveredModel,
        installed: List<InstalledModel>,
        nowSec: Long,
    ): RecommendedModel {
        var total = 0f
        val reasons = mutableSetOf<String>()
        for (used in installed) {
            val recencyWeight = recencyDecay(used.effectiveLastUsedEpochSec(), nowSec)
            val (sim, why) = similarity(candidate, used)
            if (sim > 0f) {
                total += sim * recencyWeight
                why?.let { reasons += it }
            }
        }
        return RecommendedModel(
            candidate = candidate,
            score = total,
            reasons = reasons.toList().take(3),
        )
    }

    private fun similarity(candidate: DiscoveredModel, used: InstalledModel): Pair<Float, String?> {
        val vendor = candidate.card.family.vendor?.lowercase()
        val usedVendor = used.id.substringBefore('/', missingDelimiterValue = "").lowercase()
        var score = 0f
        var why: String? = null
        if (!vendor.isNullOrBlank() && vendor == usedVendor) {
            score += 0.45f
            why = "Same vendor as $usedVendor"
        }
        val candTokens = tokenize(candidate.card.family.name)
        val usedTokens = tokenize(used.displayName + " " + used.fileName)
        val jaccard = jaccard(candTokens, usedTokens)
        if (jaccard > 0f) {
            score += 0.30f * jaccard
            if (why == null) why = "Name overlap with ${used.displayName}"
        }
        if (sizeClass(candidate) == sizeClass(used)) {
            score += 0.25f
            if (why == null) why = "Same size class as ${used.displayName}"
        }
        return score to why
    }

    private fun recencyDecay(lastUsedSec: Long, nowSec: Long): Float {
        val days = ((nowSec - lastUsedSec).coerceAtLeast(0)) / 86_400.0
        return exp(-days / 30.0).toFloat() // half-life ~3 weeks
    }

    private fun tokenize(text: String): Set<String> = text.lowercase()
        .split(Regex("[^a-z0-9.]+"))
        .filter { it.length >= 2 }
        .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return inter.toFloat() / union.toFloat()
    }

    private enum class SizeClass { UNKNOWN, TINY, SMALL, MEDIUM, LARGE }

    /** Heuristic — we don't have the parameter count for HF Trending cards,
     *  so fall back to the model id substring. */
    private fun sizeClass(candidate: DiscoveredModel): SizeClass =
        sizeClassFromText(candidate.card.displayName + " " + candidate.card.id)

    private fun sizeClass(used: InstalledModel): SizeClass =
        sizeClassFromText(used.displayName + " " + used.fileName)

    private fun sizeClassFromText(text: String): SizeClass {
        val lc = text.lowercase()
        // Look for `<n>b` patterns. log scale buckets keep the math simple.
        val match = Regex("(\\d+(?:\\.\\d+)?)\\s*b\\b").find(lc) ?: return SizeClass.UNKNOWN
        val billions = match.groupValues[1].toDoubleOrNull() ?: return SizeClass.UNKNOWN
        val l = ln(billions.coerceAtLeast(0.1))
        return when {
            l < ln(1.0) -> SizeClass.TINY     // <1B
            l < ln(4.0) -> SizeClass.SMALL    // 1-4B
            l < ln(13.0) -> SizeClass.MEDIUM  // 4-13B
            else -> SizeClass.LARGE
        }
    }
}
