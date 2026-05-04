package com.mingeek.forge.data.discovery

/**
 * Bundled, hand-picked collections shipped with the app.
 *
 * Hard rule: every entry must be downloadable **without an HF token and
 * without signing up**. The token in Settings is a power-user upgrade,
 * not a gate to using the app — so the curated default experience can't
 * include anything that needs auth. Llama is included via Bartowski's
 * community GGUF mirrors (un-gated) rather than Meta/unsloth's gated
 * official builds. Gemma is omitted — even community mirrors can inherit
 * Google's gating in ways that aren't obvious from the repo page.
 *
 * Updates ship as a code change so we can review against the rest of
 * the catalog stack at the same time. To extend: add a new [Collection]
 * entry below, or replace this source entirely with a remote-feed
 * implementation that satisfies [CollectionSource].
 *
 * `approxSizeBytes` is the rough Q4_K_M file size, used by the discover
 * screen to pre-score each entry against device fit before the user
 * commits to a detail fetch.
 */
class StaticCollectionsSource : CollectionSource {

    override val sourceId: String = "static-collections"

    override suspend fun fetch(): List<Collection> = SEED_COLLECTIONS

    private companion object {
        private const val MB = 1024L * 1024L

        // Q4_K_M ballparks. Real file sizes come back from HF detail; these
        // are only good enough for the pre-fit traffic-light on the chip.
        private const val SIZE_0_5B = 400L * MB
        private const val SIZE_LLAMA_1B = 770L * MB
        private const val SIZE_1_5B = 1100L * MB
        private const val SIZE_1_7B = 1100L * MB
        private const val SIZE_LLAMA_3_3B = 1900L * MB
        private const val SIZE_3B = 1900L * MB
        private const val SIZE_PHI_3_5_MINI = 2400L * MB

        val SEED_COLLECTIONS = listOf(
            Collection(
                id = "starter-tiny",
                title = "Starter — tiny",
                description = "Quantized 0.5B–1.7B models that run on almost any phone. " +
                    "Good first download for trying the app.",
                entries = listOf(
                    CollectionEntry("unsloth/Qwen2.5-0.5B-Instruct-GGUF", approxSizeBytes = SIZE_0_5B),
                    CollectionEntry("unsloth/Qwen2.5-1.5B-Instruct-GGUF", approxSizeBytes = SIZE_1_5B),
                    CollectionEntry("unsloth/SmolLM2-1.7B-Instruct-GGUF", approxSizeBytes = SIZE_1_7B),
                    CollectionEntry("bartowski/Llama-3.2-1B-Instruct-GGUF", approxSizeBytes = SIZE_LLAMA_1B),
                ),
                tags = setOf("starter", "tiny"),
            ),
            Collection(
                id = "coding",
                title = "Coding assistants",
                description = "Models tuned for writing and reviewing source code.",
                entries = listOf(
                    CollectionEntry("unsloth/Qwen2.5-Coder-1.5B-Instruct-GGUF", approxSizeBytes = SIZE_1_5B),
                    CollectionEntry("unsloth/Qwen2.5-Coder-3B-Instruct-GGUF", approxSizeBytes = SIZE_3B),
                ),
                tags = setOf("coding"),
            ),
            Collection(
                id = "general-3b",
                title = "General-purpose, 3B class",
                description = "Solid all-rounders that fit comfortably in 8GB phones.",
                entries = listOf(
                    CollectionEntry("unsloth/Qwen2.5-3B-Instruct-GGUF", approxSizeBytes = SIZE_3B),
                    CollectionEntry("unsloth/Phi-3.5-mini-instruct-GGUF", approxSizeBytes = SIZE_PHI_3_5_MINI),
                    CollectionEntry("bartowski/Llama-3.2-3B-Instruct-GGUF", approxSizeBytes = SIZE_LLAMA_3_3B),
                ),
                tags = setOf("general"),
            ),
            Collection(
                id = "creative",
                title = "Creative writing",
                description = "Smaller models known for flexible prose at low memory.",
                entries = listOf(
                    CollectionEntry("unsloth/SmolLM2-1.7B-Instruct-GGUF", approxSizeBytes = SIZE_1_7B),
                    CollectionEntry("unsloth/Qwen2.5-3B-Instruct-GGUF", approxSizeBytes = SIZE_3B),
                ),
                tags = setOf("creative"),
            ),
            Collection(
                id = "math",
                title = "Math + reasoning",
                description = "Models with strong chain-of-thought training.",
                entries = listOf(
                    CollectionEntry("unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF", approxSizeBytes = SIZE_1_5B),
                    CollectionEntry("unsloth/Qwen2.5-Math-1.5B-Instruct-GGUF", approxSizeBytes = SIZE_1_5B),
                ),
                tags = setOf("math", "reasoning"),
            ),
        )
    }
}
