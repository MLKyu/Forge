package com.mingeek.forge.agent.curator

import com.mingeek.forge.domain.Curation
import com.mingeek.forge.domain.DiscoveredModel

/**
 * Evaluates a discovered model and produces a [Curation] summary. Multiple
 * implementations are intended to coexist:
 *
 * - [LlmCurator] — asks an installed LLM to rate the model; works fully
 *   offline once a model is downloaded
 * - Future: rule-based scorers (license / size / family heuristics) for
 *   the cold-start case before any model is installed
 *
 * Implementations should be cheap and idempotent so the discover screen
 * can call evaluateAll on every refresh without building up state.
 */
interface ModelEvaluator {

    /** Stable id for the [Curation.curatorId] field, also used as a memory tag. */
    val curatorId: String

    suspend fun evaluate(model: DiscoveredModel): Curation

    /** Default fan-out — implementations can override for batched LLM calls. */
    suspend fun evaluateAll(models: List<DiscoveredModel>): Map<String, Curation> {
        return models.associate { it.card.id to evaluate(it) }
    }
}
