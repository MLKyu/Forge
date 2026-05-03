package com.mingeek.forge.agent.curator

import com.mingeek.forge.agent.core.Agent
import com.mingeek.forge.agent.core.AgentContext
import com.mingeek.forge.agent.core.AgentEvent
import com.mingeek.forge.agent.core.AgentInput
import com.mingeek.forge.domain.Curation
import com.mingeek.forge.domain.DiscoveredModel
import kotlinx.coroutines.flow.Flow

/**
 * [ModelEvaluator] backed by an arbitrary [Agent] — typically an [LlmAgent]
 * pointed at one of the user's installed models. The agent is asked to
 * read the discovered model's metadata and emit a single line that
 * starts with `SCORE: <n>` (1–5) followed by a one-sentence summary.
 *
 * Free-form responses are also tolerated: any digit 1–5 we find becomes
 * the score, and the rest of the text becomes the summary. We trade
 * structured output for robustness on small models.
 */
class LlmCurator(
    private val agent: Agent,
    override val curatorId: String = "llm-curator",
) : ModelEvaluator {

    override suspend fun evaluate(model: DiscoveredModel): Curation {
        val prompt = buildPrompt(model)
        val raw = agent
            .run(AgentInput(text = prompt), AgentContext(agentId = curatorId))
            .collectFinalText()
        return parseCuration(raw, model)
    }

    private fun buildPrompt(model: DiscoveredModel): String = buildString {
        append("You are evaluating a newly-discovered language model for an on-device chat app. ")
        append("Read the metadata and respond with one line:\n")
        append("SCORE: <1-5> (5 = top pick) — <one short sentence why>\n\n")
        append("Model: ").append(model.card.displayName).append("\n")
        model.card.family.vendor?.let { append("Vendor: ").append(it).append("\n") }
        model.card.family.parameterBillions?.let { append("Size: ").append(it).append("B params\n") }
        append("Format: ").append(model.card.format.name).append("\n")
        append("Quantization: ").append(model.card.quantization.name).append("\n")
        append("License: ").append(model.card.license.spdxId).append("\n")
        append("Source: ").append(model.sourceId).append("\n")
        if (model.card.sizeBytes > 0) {
            append("Size on disk: ").append(model.card.sizeBytes / (1024L * 1024L)).append(" MB\n")
        }
        append("\nReply with exactly one SCORE line.")
    }

    private fun parseCuration(raw: String, model: DiscoveredModel): Curation {
        // Score: first 1-5 in the response.
        val scoreMatch = Regex("[1-5]").find(raw)
        val score = scoreMatch?.value?.toFloatOrNull()
        // Summary: trim leading "SCORE: n —" if present, else first sentence.
        val summary = raw
            .replace(Regex("(?i)score\\s*:?\\s*[1-5]\\s*[—-]\\s*"), "")
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(200)
            ?: "(no summary)"
        return Curation(
            curatorId = curatorId,
            summary = summary,
            score = score,
            tags = buildSet {
                add("auto")
                model.card.family.vendor?.let { add("vendor:$it") }
                if (score != null && score >= 4) add("recommended")
            },
        )
    }

    private suspend fun Flow<AgentEvent>.collectFinalText(): String {
        val sb = StringBuilder()
        var explicitFinal: String? = null
        var failed: String? = null
        collect { ev ->
            when (ev) {
                is AgentEvent.Token -> sb.append(ev.piece)
                is AgentEvent.Final -> explicitFinal = ev.output
                is AgentEvent.Failed -> failed = ev.message
                else -> { /* ignore tool events; curator prompt doesn't use tools */ }
            }
        }
        failed?.let { return "ERROR: $it" }
        return explicitFinal ?: sb.toString().trim()
    }
}
